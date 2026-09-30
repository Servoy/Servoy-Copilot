/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.Activator;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.browser.BrowserFactory;
import com.servoy.eclipse.ui.browser.IBrowser;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service behind the {@code getFormLayout} MCP tool. Renders a Servoy form's real runtime
 * DOM by loading the stateless {@code GET /formtemplate/<form>.html} route in a <em>hidden</em>,
 * service-owned utility browser, waits for render-complete, then reads the rendered DOM back
 * with {@link IBrowser#evaluate(String)}.
 *
 * <p>
 * <b>What it returns.</b> The full rendered {@code outerHTML} of the requested subtree (the
 * whole {@code .svy-form}, or the element matched by an optional CSS {@code selector}) &mdash;
 * so the AI sees the real component tags (e.g. {@code <bootstrapcomponents-textbox>}), their
 * {@code id}/{@code data-*} identity attributes, applied classes and nested structure exactly
 * as rendered. Alongside the HTML it returns a compact <em>computed-appearance map</em> keyed
 * by a stable element ref: the resolved {@code color}/{@code background}/{@code border}/font,
 * {@code display}/{@code position}/{@code visibility} and the on-screen bounding box, so the AI
 * knows how the form actually looks (the solution stylesheet + theme are already applied in the
 * live browser) &mdash; the piece raw HTML alone cannot convey. This lets the AI judge and
 * change colours or positioning and verify the result on the next call.
 * </p>
 *
 * <p>
 * <b>Threading / lifecycle.</b> The MCP tool runs on a background (servlet) thread;
 * {@link IBrowser} / SWT must be touched only on the SWT display thread, so every browser
 * interaction is marshalled via {@link Display#syncExec(Runnable)}. The embedded Chromium is
 * shared, single-instance state, so this service keeps <em>one</em> reused hidden browser
 * (held behind a {@link BrowserHandle} so a bounded pool could replace it later without
 * reshaping callers) on an offscreen {@link Shell}, and serializes {@code getFormLayout} calls
 * through {@link #renderLock}. Navigating the reused browser to a new form URL discards the
 * previously rendered form &mdash; the desired behaviour. The browser and its shell are
 * disposed when the {@link Display} is torn down at workbench shutdown (via
 * {@link Display#disposeExec(Runnable)}), not per call.
 * </p>
 *
 * <p>
 * <b>Stale-form safety.</b> On reuse the reused browser is first navigated to {@code about:blank}
 * and confirmed cleared (the previous {@code .svy-form} / {@code window.formtemplateName} marker
 * is gone) before the new form URL is loaded. Both the readiness predicate and the DOM read are
 * <em>form-specific</em>: they require {@code window.formtemplateName} to equal the requested
 * form name, so a still-live previous form cannot satisfy readiness or be read during the brief
 * window before the new page commits.
 * </p>
 *
 * <p>
 * The service is <b>read-only</b> and <b>design-time</b>: no data, no running client, no
 * login, no websocket. It never touches the visible chat view's browser.
 * </p>
 */
@Creatable
public class FormLayoutInspectionService
{
	/**
	 * Hard cap (chars) on the inline result JSON (rendered HTML + appearance map). Over-cap
	 * output spills to a temp file whose path is returned. Rendered HTML for a real form
	 * usually exceeds this, so a spill is the common case.
	 */
	private static final int RESULT_CAP = 24_000;

	/** Default offscreen viewport for the utility browser. */
	private static final int VIEWPORT_WIDTH = 1280;
	private static final int VIEWPORT_HEIGHT = 1024;

	/** Poll interval while awaiting render-complete. */
	private static final long POLL_INTERVAL_MS = 150;

	/** Max time spent confirming the previous document was cleared before navigating. */
	private static final long CLEAR_BUDGET_MS = 2000;

	/**
	 * Injected DOM-read JavaScript template. Reads the requested subtree ({@code .svy-form} or
	 * the element matched by the injected CSS selector) and {@code return}s a JSON <em>string</em>
	 * (so the SWT/Chromium value crossing is a single scalar) carrying the subtree's rendered
	 * {@code outerHTML} plus a compact computed-appearance map: for each element that carries an
	 * {@code id} or a {@code class} (i.e. anything a design-time property could target), the
	 * resolved visual styles and the on-screen bounding box, keyed by a CSS path so the AI can
	 * correlate an appearance entry back to a node in the HTML.
	 *
	 * <p>
	 * Placeholders (via {@link String#format}): {@code %1$s} = JSON-encoded requested form name
	 * (the read verifies {@code window.formtemplateName} matches and returns a {@code WRONG_FORM}
	 * sentinel otherwise), {@code %2$s} = JSON-encoded CSS selector or the JS literal
	 * {@code null} for the whole form.
	 * </p>
	 */
	private static final String READ_JS_TEMPLATE = "" +
		"return (function(){\n" +
		"  try {\n" +
		"    var expected = %1$s;\n" +
		"    if (String(window.formtemplateName) !== String(expected)) { return JSON.stringify({ error: 'WRONG_FORM' }); }\n" +
		"    var sel = %2$s;\n" +
		"    var root = sel ? document.querySelector(sel) : document.querySelector('.svy-form');\n" +
		"    if (!root) { return JSON.stringify({ error: sel ? 'NO_MATCH' : 'NO_SVY_FORM' }); }\n" +
		"    var VISUAL = ['color','background-color','border-top-width','border-top-style','border-top-color',\n" +
		"      'border-bottom-width','border-left-width','border-right-width','border-radius',\n" +
		"      'font-family','font-size','font-weight','font-style','text-align','line-height',\n" +
		"      'display','position','visibility','opacity','z-index','margin','padding'];\n" +
		"    function cssPath(el){ if (el.id) return '#' + el.id; var parts = [];\n" +
		"      while (el && el.nodeType === 1 && el !== root.parentNode){\n" +
		"        var p = el.nodeName.toLowerCase();\n" +
		"        if (el.id){ parts.unshift('#' + el.id); break; }\n" +
		"        var sib = el, nth = 1; while ((sib = sib.previousElementSibling)){ if (sib.nodeName === el.nodeName) nth++; }\n" +
		"        parts.unshift(p + ':nth-of-type(' + nth + ')'); el = el.parentElement; }\n" +
		"      return parts.join(' > '); }\n" +
		"    function box(el){ var r = el.getBoundingClientRect();\n" +
		"      return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; }\n" +
		"    var appearance = {};\n" +
		"    var all = [root].concat(Array.prototype.slice.call(root.querySelectorAll('*')));\n" +
		"    for (var i=0;i<all.length;i++){ var el = all[i];\n" +
		"      var hasId = !!el.id; var hasClass = el.classList && el.classList.length > 0;\n" +
		"      if (!hasId && !hasClass) continue;\n" +
		"      var cs = getComputedStyle(el); var styles = {};\n" +
		"      for (var k=0;k<VISUAL.length;k++){ var prop = VISUAL[k]; var v = cs.getPropertyValue(prop); if (v) styles[prop] = v; }\n" +
		"      var key = cssPath(el);\n" +
		"      appearance[key] = { box: box(el), styles: styles }; }\n" +
		"    return JSON.stringify({ form: (window.formtemplateName || null), selector: (sel || '.svy-form'),\n" +
		"      viewport: box(root), html: root.outerHTML, appearance: appearance });\n" +
		"  } catch (err) { return JSON.stringify({ error: String(err) }); }\n" +
		"})();";

	/**
	 * Readiness probe template. {@code return}s {@code true} only when
	 * {@code window.formtemplateName} equals the requested form (so a stale previous form
	 * cannot trip it) <em>and</em> the requested root exists with at least one laid-out node
	 * (non-zero bounding box). Placeholders: {@code %1$s} = JSON-encoded form name,
	 * {@code %2$s} = JSON-encoded selector or {@code null}.
	 */
	private static final String READINESS_JS_TEMPLATE = "" +
		"return (function(){\n" +
		"  var expected = %1$s;\n" +
		"  if (String(window.formtemplateName) !== String(expected)) return false;\n" +
		"  var sel = %2$s;\n" +
		"  var root = sel ? document.querySelector(sel) : document.querySelector('.svy-form');\n" +
		"  if (!root) return false;\n" +
		"  var rb = root.getBoundingClientRect(); if (rb.width > 0 && rb.height > 0) return true;\n" +
		"  var kids = root.querySelectorAll('*');\n" +
		"  for (var i=0;i<kids.length;i++){ var r = kids[i].getBoundingClientRect(); if (r.width > 0 && r.height > 0) return true; }\n" +
		"  return false;\n" +
		"})();";

	/**
	 * Cleared probe. {@code return}s {@code true} when the previous document is gone: no
	 * {@code .svy-form} in the DOM and no {@code window.formtemplateName} marker. Used after
	 * navigating to {@code about:blank} on reuse so a stale form cannot satisfy the readiness
	 * predicate during the brief window before the new page commits.
	 */
	private static final String CLEARED_JS = "" +
		"return (function(){\n" +
		"  if (document.querySelector('.svy-form')) return false;\n" +
		"  return (typeof window.formtemplateName === 'undefined') || !window.formtemplateName;\n" +
		"})();";

	/**
	 * Serializes calls: one render/read in flight at a time against the shared browser.
	 */
	private final ReentrantLock renderLock = new ReentrantLock();

	/** The single reused hidden browser + its offscreen shell (pool-ready handle). */
	private volatile BrowserHandle handle;

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Renders {@code /formtemplate/<formName>.html} in the hidden browser and returns the
	 * rendered subtree HTML + computed-appearance map as a JSON envelope (capped inline /
	 * spilled to a temp file), or a named actionable message (never a hang) when the form
	 * cannot be rendered.
	 *
	 * @param formName the form to inspect
	 * @param selector optional CSS selector of the subtree to return; when {@code null}/blank
	 *            the whole {@code .svy-form} is returned
	 * @param timeoutSeconds max seconds to wait for render-complete before reading
	 * @return the result holder (envelope JSON or a named error)
	 */
	public FormLayoutResult getFormLayout(String formName, String selector, boolean screenshot, int timeoutSeconds)
	{
		if (formName == null || formName.isBlank())
		{
			return FormLayoutResult.error("Error: Form name must not be null or empty.");
		}

		// active-solution + form resolution (named, actionable messages - never a hang)
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
		{
			return FormLayoutResult.error(
				"No active Servoy solution. Open a solution in Servoy Developer, then try again.");
		}
		String solutionName = activeProject.getSolution().getName();
		if (activeProject.getEditingSolution().getForm(formName) == null)
		{
			return FormLayoutResult
				.error("Form '" + formName + "' was not found in the active solution '" + solutionName + "'.");
		}

		int port = ApplicationServerRegistry.get() != null ? ApplicationServerRegistry.get().getWebServerPort() : -1;
		if (port <= 0)
		{
			return FormLayoutResult.error(
				"The Servoy web server is not running; start Servoy Developer and open a solution, then try again.");
		}

		String url = "http://127.0.0.1:" + port + "/formtemplate/" + formName + ".html";
		long timeoutMs = Math.max(1, timeoutSeconds) * 1000L;
		String cssSelector = (selector != null && !selector.isBlank()) ? selector.trim() : null;

		renderLock.lock();
		try
		{
			AtomicReference<String> failure = new AtomicReference<>();
			String rawRead = renderAndRead(formName, cssSelector, url, timeoutMs, failure);
			if (failure.get() != null)
			{
				String reason = failure.get();
				if ("TIMEOUT".equals(reason))
				{
					return FormLayoutResult
						.error("Form '" + formName + "' did not finish rendering within " + timeoutSeconds + "s.");
				}
				if ("NO_SVY_FORM".equals(reason))
				{
					return FormLayoutResult.error("Form '" + formName +
						"' loaded but produced no '.svy-form' root; it may not be renderable via the form template route.");
				}
				if ("NO_MATCH".equals(reason))
				{
					return FormLayoutResult.error(
						"Selector '" + cssSelector + "' matched no element in the rendered form '" + formName + "'.");
				}
				if ("NO_BROWSER".equals(reason))
				{
					return FormLayoutResult.error(
						"No browser backend is available to render the form in this environment " +
							"(the embedded Chromium / WebKit browser is not present). This tool needs a running " +
							"Servoy Developer with the embedded browser; it cannot render in a headless build.");
				}
				return FormLayoutResult.error("Error rendering form '" + formName + "': " + reason);
			}

			// Capture a screenshot of the whole rendered form when requested. Only for the whole
			// form (a screenshot of a single selected subtree adds little). This runs on the
			// current background (servlet) thread - NOT inside syncExec - because
			// IBrowser.captureScreenshot() must be called off the SWT display thread (the inverse
			// of evaluate()); the render lock is still held so the browser still shows this form.
			CapturedScreenshot shot = null;
			if (screenshot && cssSelector == null)
			{
				shot = captureScreenshot(formName);
			}
			return assembleResult(formName, rawRead, shot);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in getFormLayout for form '" + formName + "'", e);
			return FormLayoutResult.error("Error: " + e.getMessage());
		}
		finally
		{
			renderLock.unlock();
		}
	}

	/**
	 * Drives the hidden browser on the SWT display thread: acquire handle (creating the
	 * browser + offscreen shell on first use), clear the previous document ({@code about:blank}
	 * + confirm cleared), navigate to the form, poll until the <em>form-specific</em> readiness
	 * predicate holds or the timeout elapses, then evaluate the <em>form-specific</em> DOM read.
	 * Returns the raw read JSON string, or leaves a reason in {@code failure}.
	 */
	private String renderAndRead(String formName, String cssSelector, String url, long timeoutMs,
		AtomicReference<String> failure)
	{
		Display display = Display.getDefault();
		AtomicReference<String> readResult = new AtomicReference<>();

		String formNameLiteral = jsStringLiteral(formName);
		String selectorLiteral = cssSelector == null ? "null" : jsStringLiteral(cssSelector);
		String readinessJs = String.format(READINESS_JS_TEMPLATE, formNameLiteral, selectorLiteral);
		String readJs = String.format(READ_JS_TEMPLATE, formNameLiteral, selectorLiteral);

		// create (first call) + clear the previous document so no stale form/marker survives.
		// Catch Throwable, not just Exception: when there is no browser backend (e.g. a headless
		// environment with no Equo Chromium and no WebKit-GTK), BrowserFactory/SWT throws an
		// SWTError (an Error, not an Exception). Translate any creation failure into the NO_BROWSER
		// reason so the caller emits a clean named message instead of leaking a raw SWT stack.
		display.syncExec(() -> {
			try
			{
				BrowserHandle h = ensureHandle(display);
				h.browser.setUrl("about:blank");
			}
			catch (Throwable t)
			{
				failure.set("NO_BROWSER");
				ServoyLog.logWarning("FormLayoutInspectionService: could not create a browser to render forms " +
					"(no Chromium/WebKit backend available in this environment)", t);
			}
		});
		if (failure.get() != null)
		{
			return null;
		}

		// confirm the previous .svy-form / marker is gone (bounded, best-effort)
		long clearDeadline = System.currentTimeMillis() + CLEAR_BUDGET_MS;
		while (System.currentTimeMillis() < clearDeadline)
		{
			boolean[] clearedRef = new boolean[] { false };
			display.syncExec(() -> {
				try
				{
					BrowserHandle h = handle;
					if (h != null && !h.browser.isDisposed())
					{
						Object r = h.browser.evaluate(CLEARED_JS);
						clearedRef[0] = Boolean.TRUE.equals(r);
					}
				}
				catch (Exception e)
				{
					// transient during navigation; keep polling
				}
			});
			if (clearedRef[0])
			{
				break;
			}
			pumpOrWait(display, clearDeadline);
		}

		// navigate to the form
		display.syncExec(() -> {
			try
			{
				BrowserHandle h = handle;
				if (h == null || h.browser.isDisposed())
				{
					failure.set("browser disposed");
					return;
				}
				h.browser.setUrl(url);
			}
			catch (Exception e)
			{
				failure.set(e.getMessage() != null ? e.getMessage() : e.toString());
			}
		});
		if (failure.get() != null)
		{
			return null;
		}

		// bounded await-ready: poll on the display thread; never Thread.sleep on it
		long deadline = System.currentTimeMillis() + timeoutMs;
		boolean ready = false;
		while (System.currentTimeMillis() < deadline)
		{
			boolean[] readyRef = new boolean[] { false };
			display.syncExec(() -> {
				try
				{
					BrowserHandle h = handle;
					if (h != null && !h.browser.isDisposed())
					{
						Object r = h.browser.evaluate(readinessJs);
						readyRef[0] = Boolean.TRUE.equals(r);
					}
				}
				catch (Exception e)
				{
					// transient during navigation; keep polling
				}
			});
			if (readyRef[0])
			{
				ready = true;
				break;
			}
			pumpOrWait(display, deadline);
		}

		if (!ready)
		{
			failure.set("TIMEOUT");
			return null;
		}

		// read the rendered subtree HTML + appearance map
		display.syncExec(() -> {
			try
			{
				BrowserHandle h = handle;
				if (h == null || h.browser.isDisposed())
				{
					failure.set("browser disposed");
					return;
				}
				Object r = h.browser.evaluate(readJs);
				readResult.set(r == null ? null : r.toString());
			}
			catch (Exception e)
			{
				failure.set(e.getMessage() != null ? e.getMessage() : e.toString());
			}
		});
		if (failure.get() != null)
		{
			return null;
		}

		String read = readResult.get();
		if (read == null)
		{
			failure.set("The injected DOM read returned no value.");
			return null;
		}
		if (read.contains("\"error\":\"WRONG_FORM\""))
		{
			// the page did not (yet) settle on the requested form - treat as a render timeout
			// so the caller emits the named "did not finish rendering" message.
			failure.set("TIMEOUT");
			return null;
		}
		if (read.contains("\"error\":\"NO_SVY_FORM\""))
		{
			failure.set("NO_SVY_FORM");
			return null;
		}
		if (read.contains("\"error\":\"NO_MATCH\""))
		{
			failure.set("NO_MATCH");
			return null;
		}
		return read;
	}

	/**
	 * Advances the display's event loop (so the page keeps loading) or, off the display
	 * thread, waits a short interval &mdash; never {@link Thread#sleep(long)} on the display
	 * thread.
	 */
	private void pumpOrWait(Display display, long deadline)
	{
		if (display.getThread() == Thread.currentThread())
		{
			long slice = System.currentTimeMillis() + POLL_INTERVAL_MS;
			while (System.currentTimeMillis() < slice && System.currentTimeMillis() < deadline)
			{
				if (!display.readAndDispatch())
				{
					display.sleep();
				}
			}
		}
		else
		{
			try
			{
				Thread.sleep(POLL_INTERVAL_MS);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Creates the reused browser and its offscreen shell on first use. Must run on the display
	 * thread. If {@link BrowserFactory#createBrowser(org.eclipse.swt.widgets.Composite)} throws
	 * mid-creation the freshly-opened offscreen {@link Shell} is disposed before the exception
	 * is rethrown, so no shell is leaked.
	 */
	private BrowserHandle ensureHandle(Display display)
	{
		BrowserHandle h = handle;
		if (h != null && h.shell != null && !h.shell.isDisposed() && h.browser != null && !h.browser.isDisposed())
		{
			return h;
		}
		// (re)create - offscreen shell, never shown to the user
		Shell shell = new Shell(display, SWT.NO_TRIM);
		try
		{
			shell.setLayout(new FillLayout());
			shell.setSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
			shell.setLocation(-32000, -32000);
			IBrowser browser = BrowserFactory.createBrowser(shell);
			browser.setSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
			shell.setVisible(false);
			shell.open();
			shell.setVisible(false);
			h = new BrowserHandle(shell, browser);
			handle = h;
		}
		catch (RuntimeException | Error e)
		{
			// creation failed - dispose the orphaned shell before rethrowing so nothing leaks
			if (!shell.isDisposed())
			{
				shell.dispose();
			}
			throw e;
		}
		// Ensure the utility browser + shell are torn down when the display is disposed at
		// workbench shutdown, so nothing is leaked and nothing is disposed per call.
		final Shell toDispose = shell;
		display.disposeExec(() -> {
			if (toDispose != null && !toDispose.isDisposed())
			{
				toDispose.dispose();
			}
		});
		return h;
	}

	/**
	 * Builds the final result envelope from the raw DOM-read JSON, applying the cap-inline /
	 * spill-to-temp-file convention. The raw read is already the JSON envelope (form, selector,
	 * viewport, html, appearance); this only decides inline-vs-spill based on total size.
	 */
	private FormLayoutResult assembleResult(String formName, String rawRead, CapturedScreenshot shot) throws Exception
	{
		// rawRead is a JSON object string produced by the injected read; validate + normalize.
		com.fasterxml.jackson.databind.JsonNode read = mapper.readTree(rawRead);

		String screenshotFile = shot != null ? shot.file : null;
		String imageBase64 = shot != null ? shot.base64 : null;

		String json;
		String resultFile;
		if (mapper.writeValueAsString(read).length() > RESULT_CAP)
		{
			String file = spill(formName, "json", mapper.writeValueAsString(read));
			resultFile = file;
			ObjectNode envelope = mapper.createObjectNode();
			envelope.put("resultFile", file);
			envelope.put("truncated", true);
			envelope.put("warning",
				"The rendered form (HTML + appearance) exceeded the inline cap; the full result was written to " +
					file + " - read it with the file tools.");
			// include a light summary inline so the caller has immediate context
			if (read.has("form")) envelope.set("form", read.get("form"));
			if (read.has("selector")) envelope.set("selector", read.get("selector"));
			if (read.has("viewport")) envelope.set("viewport", read.get("viewport"));
			if (screenshotFile != null) envelope.put("screenshotFile", screenshotFile);
			json = mapper.writeValueAsString(envelope);
		}
		else if (screenshotFile != null)
		{
			// inline the read but add the screenshot path alongside it
			ObjectNode envelope = read.deepCopy();
			envelope.put("screenshotFile", screenshotFile);
			resultFile = null;
			json = mapper.writeValueAsString(envelope);
		}
		else
		{
			// small enough to inline verbatim, no screenshot
			resultFile = null;
			json = mapper.writeValueAsString(read);
		}

		return new FormLayoutResult(json, resultFile, screenshotFile, imageBase64, null);
	}

	/**
	 * Captures a PNG screenshot of the currently-rendered form via {@link IBrowser#captureScreenshot()}
	 * and writes it to the plugin temp dir. Must run on a background thread (not the SWT display
	 * thread) per the {@code IBrowser.captureScreenshot()} contract; {@link #getFormLayout} calls
	 * it from the servlet thread with the render lock held (so the browser still shows this form).
	 * Best-effort: any failure (unsupported backend, capture error) yields {@code null} and the
	 * form layout is still returned without an image.
	 *
	 * @return the captured screenshot (file path + base64), or {@code null} when capture failed
	 */
	private CapturedScreenshot captureScreenshot(String formName)
	{
		try
		{
			BrowserHandle h = handle;
			if (h == null || h.browser.isDisposed())
			{
				return null;
			}
			byte[] png = h.browser.captureScreenshot();
			if (png == null || png.length == 0)
			{
				return null;
			}
			String file = spillBytes(formName, "png", png);
			String base64 = java.util.Base64.getEncoder().encodeToString(png);
			return new CapturedScreenshot(file, base64);
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("FormLayoutInspectionService: screenshot capture failed for form '" + formName + "'",
				e);
			return null;
		}
	}

	/**
	 * JSON-encodes {@code value} into a quoted, escaped JavaScript string literal so it can be
	 * safely embedded into the injected scripts (a form name or selector containing a quote or
	 * backslash cannot break out of the literal or inject JS).
	 */
	private String jsStringLiteral(String value)
	{
		try
		{
			return mapper.writeValueAsString(value == null ? "" : value);
		}
		catch (Exception e)
		{
			// mapper never fails on a plain String; fall back to an empty literal defensively
			return "\"\"";
		}
	}

	/**
	 * Writes {@code content} to a uniquely-named file in the plugin's own temp directory and
	 * returns its absolute path. The directory is the plugin state area
	 * ({@code {workspace}/.metadata/.plugins/com.servoy.eclipse.developer.mcp/temp/}), obtained
	 * via {@link #tempDir()} - a stable, sanctioned, writable location <em>inside</em> Eclipse's
	 * plugin state area rather than the OS temp dir, so the Servoy orchestrator's path
	 * restrictions (which may forbid the OS temp dir) do not block reading the spilled file. The
	 * name is unique by construction ({@link Files#createTempFile(Path, String, String)}), so two
	 * spills in the same millisecond cannot collide.
	 */
	private static String spill(String formName, String ext, String content) throws Exception
	{
		return spillBytes(formName, ext, content.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes raw {@code bytes} to a uniquely-named file in the plugin's own temp directory (see
	 * {@link #tempDir()}) and returns its absolute path. Used for both the spilled JSON and the
	 * captured screenshot PNG.
	 */
	private static String spillBytes(String formName, String ext, byte[] bytes) throws Exception
	{
		String safeForm = formName.replaceAll("[^A-Za-z0-9_.-]", "_");
		String prefix = "svy-formlayout-" + safeForm + "-";
		Path dir = tempDir();
		Files.createDirectories(dir);
		Path file = Files.createTempFile(dir, prefix, "." + ext);
		Files.write(file, bytes);
		return file.toAbsolutePath().toString();
	}

	/**
	 * The plugin's own temp directory:
	 * {@code {workspace}/.metadata/.plugins/com.servoy.eclipse.developer.mcp/temp/}. Falls back
	 * to the OS temp dir only if the plugin state location is unavailable (e.g. the bundle
	 * Activator has not started, as in a plain unit test).
	 */
	private static Path tempDir()
	{
		try
		{
			Activator activator = Activator.getDefault();
			if (activator != null)
			{
				return activator.getStateLocation().append("temp").toFile().toPath();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("FormLayoutInspectionService: plugin state location unavailable, " +
				"falling back to the OS temp dir", e);
		}
		return Paths.get(System.getProperty("java.io.tmpdir"));
	}

	/**
	 * A pool-ready handle around one hidden browser + its offscreen shell. Kept as a distinct
	 * type (rather than two bare fields) so a bounded pool can later hold several without
	 * reshaping the acquire/navigate/await/evaluate/release flow.
	 */
	private static final class BrowserHandle
	{
		final Shell shell;
		final IBrowser browser;

		BrowserHandle(Shell shell, IBrowser browser)
		{
			this.shell = shell;
			this.browser = browser;
		}
	}

	/** A captured screenshot: the PNG file path and the base64-encoded PNG bytes. */
	private static final class CapturedScreenshot
	{
		final String file;
		final String base64;

		CapturedScreenshot(String file, String base64)
		{
			this.file = file;
			this.base64 = base64;
		}
	}

	/**
	 * Small result holder returned to the tool method. {@code json} is the result envelope
	 * (rendered HTML + appearance map, or a light summary + {@code resultFile} when spilled),
	 * or {@code null} on failure. {@code screenshotFile} is the PNG path when a screenshot was
	 * captured; {@code imageBase64} is the base64-encoded PNG for attaching as MCP image
	 * content. {@code error} carries a named actionable message on failure.
	 */
	public static final class FormLayoutResult
	{
		public final String json;
		public final String resultFile;
		public final String screenshotFile;
		public final String imageBase64;
		public final String error;

		public FormLayoutResult(String json, String resultFile, String screenshotFile, String imageBase64,
			String error)
		{
			this.json = json;
			this.resultFile = resultFile;
			this.screenshotFile = screenshotFile;
			this.imageBase64 = imageBase64;
			this.error = error;
		}

		static FormLayoutResult error(String message)
		{
			return new FormLayoutResult(null, null, null, null, message);
		}
	}
}
