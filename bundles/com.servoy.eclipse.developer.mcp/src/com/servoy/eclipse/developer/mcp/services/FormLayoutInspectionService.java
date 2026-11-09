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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
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
 * <b>Stale-form safety.</b> Every call navigates to a URL carrying a unique query parameter, so a new
 * document is always created, and the readiness predicate additionally requires the document's
 * {@code performance.timeOrigin} to be later than the start of the navigation. Both the readiness predicate
 * and the DOM read are <em>form-specific</em> (they require {@code window.formtemplateName} to equal the
 * requested form name). Script evaluations are never issued while the new document is still committing, and
 * each one runs with a hard time cap, because Equo's {@code evaluate} can otherwise block forever.
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
	 * Indentation width (spaces) used when pretty-printing the rendered HTML written to the
	 * {@code htmlFile}. One space per nesting level keeps a deeply nested form readable without
	 * the file growing much, while still putting one element per physical line so the file can be
	 * grepped and ranged-read.
	 */
	private static final int HTML_INDENT_SPACES = 1;

	/** Default offscreen viewport for the utility browser (used when no viewport is requested). */
	private static final int VIEWPORT_WIDTH = 1280;
	private static final int VIEWPORT_HEIGHT = 1024;

	/** Accepted range (px) for a requested viewport width/height (SVY-21591). */
	static final int MIN_VIEWPORT_SIZE = 200;
	static final int MAX_VIEWPORT_SIZE = 4000;

	/** The pseudo-state actions {@code getFormLayout} can apply to a target before reading (SVY-21614). */
	public static final String ACTION_FOCUS = "focus";
	public static final String ACTION_HOVER = "hover";
	public static final String ACTION_ACTIVE = "active";

	/** Cap on the number of matched {@code :hover}/{@code :active} rules collected for the CSS-rewrite. */
	static final int MAX_PSEUDO_RULES = 50;

	/** Tolerance (px) when checking the rendered {@code window.innerWidth} against a requested width. */
	private static final int VIEWPORT_WIDTH_TOLERANCE = 2;

	/** Poll interval while awaiting render-complete. */
	private static final long POLL_INTERVAL_MS = 150;

	/**
	 * Hard cap on one script evaluation. Equo's {@code Chromium.evaluate} waits on a future with no timeout, so an
	 * evaluation that races a navigation never returns; every evaluation therefore runs with its own cap.
	 */
	private static final long POLL_EVAL_TIMEOUT_MS = 5_000;
	private static final long READ_EVAL_TIMEOUT_MS = 15_000;
	private static final long DIAGNOSTIC_EVAL_TIMEOUT_MS = 2_000;

	/** Without a location-changed event for the new navigation, start evaluating anyway after this long. */
	private static final long COMMIT_WAIT_MS = 3_000;

	/** How long to wait for the browser to report a new width after the shell was resized to correct it. */
	private static final long ALIGN_WAIT_MS = 2_000;

	/** Allowed clock difference between this JVM and the page when comparing {@code performance.timeOrigin}. */
	private static final long NAV_CLOCK_SLACK_MS = 200;

	/** Detail appended to a TIMEOUT when the browser stopped answering script evaluations. */
	private static final String STUCK_DETAIL =
		" {\"scriptEvaluation\":\"the browser did not answer a script evaluation (page navigating or Chromium busy)\"}";

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
		"      'display','position','visibility','opacity','z-index','margin','padding',\n" +
		"      'outline-width','outline-style','outline-color','outline-offset'];\n" +
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
		"      var disabled = !!(el.disabled || el.getAttribute('disabled') !== null ||\n" +
		"        (el.classList && el.classList.contains('disabled')) || el.getAttribute('aria-disabled') === 'true');\n" +
		"      var readonly = !!(el.readOnly || el.getAttribute('readonly') !== null || el.getAttribute('aria-readonly') === 'true');\n" +
		"      var key = cssPath(el);\n" +
		"      appearance[key] = { box: box(el), styles: styles, disabled: disabled, readonly: readonly }; }\n" +
		"    return JSON.stringify({ form: (window.formtemplateName || null), selector: (sel || '.svy-form'),\n" +
		"      viewport: box(root), window: { width: window.innerWidth, height: window.innerHeight },\n" +
		"      html: root.outerHTML, appearance: appearance });\n" +
		"  } catch (err) { return JSON.stringify({ error: String(err) }); }\n" +
		"})();";

	/**
	 * Pseudo-state template (SVY-21614). Puts one target element into a CSS interaction state before
	 * the appearance read, so the computed styles and the screenshot reflect it. Placeholders:
	 * {@code %1$s} = JSON-encoded target selector, {@code %2$s} = JSON-encoded action
	 * ({@code focus}/{@code hover}/{@code active}), {@code %3$s} = {@link #MAX_PSEUDO_RULES}.
	 * <p>
	 * {@code focus} is REAL: {@code element.focus()} is a DOM API, so {@code :focus}/{@code :focus-visible}
	 * genuinely apply. {@code hover}/{@code active} are APPROXIMATE: the browser exposes no way to force
	 * {@code :hover} from page JS (that needs CDP {@code CSS.forcePseudoState}, which the embedded Equo
	 * Chromium does not expose), so instead every style rule whose selector carries {@code :hover}
	 * (resp. {@code :active}) and whose non-pseudo part matches the target has its declarations applied to
	 * the target as <em>inline {@code !important}</em> declarations ({@code el.style.setProperty(p, v,
	 * 'important')}). An inline {@code !important} declaration beats any stylesheet rule regardless of
	 * specificity, so the pseudo-state styling reliably wins the cascade - even over an {@code !important}
	 * rest rule or a more specific pseudo-rule. It is still APPROXIMATE (descendant-{@code :hover} and
	 * combined states are not modelled, and the inline value may itself exceed what the real cascade would
	 * apply), so the result is labelled {@code approximate:true}.
	 * </p>
	 * Returns a JSON object describing the outcome ({@code matched}, {@code approximate}, and for
	 * hover/active {@code matchedRules}/{@code appliedProps}/{@code skippedSheets}/{@code truncated});
	 * never throws.
	 */
	private static final String PSEUDO_JS_TEMPLATE = "" +
		"return (function(){\n" +
		"  try {\n" +
		"    var sel = %1$s; var action = %2$s; var CAP = %3$s;\n" +
		"    var el = document.querySelector(sel);\n" +
		"    if (!el) { return JSON.stringify({ matched: false, action: action }); }\n" +
		"    if (action === 'focus') { try { el.focus(); } catch (e) {} \n" +
		"      return JSON.stringify({ matched: true, action: action, approximate: false }); }\n" +
		"    // hover / active: copy the matching pseudo-rule declarations onto the target (approximate)\n" +
		"    var pseudo = (action === 'active') ? ':active' : ':hover';\n" +
		"    var matchedRules = 0; var appliedProps = 0; var skippedSheets = 0; var truncated = false;\n" +
		"    var sheets = document.styleSheets;\n" +
		"    for (var s=0; s<sheets.length; s++){\n" +
		"      var rules; try { rules = sheets[s].cssRules; } catch (e) { skippedSheets++; continue; }\n" +
		"      if (!rules) continue;\n" +
		"      for (var r=0; r<rules.length; r++){ var rule = rules[r];\n" +
		"        if (rule.type !== 1 || !rule.selectorText) continue;\n" +
		"        var parts = rule.selectorText.split(',');\n" +
		"        for (var p=0; p<parts.length; p++){ var selText = parts[p];\n" +
		"          if (selText.indexOf(pseudo) === -1) continue;\n" +
		"          var base = selText.split(pseudo).join('').trim();\n" +
		"          if (!base) continue;\n" +
		"          var isMatch = false; try { isMatch = el.matches(base); } catch (e) { continue; }\n" +
		"          if (!isMatch) continue;\n" +
		"          if (matchedRules >= CAP) { truncated = true; break; }\n" +
		"          // apply each property of the matched pseudo-rule as an INLINE !important declaration on\n" +
		"          // the target. An inline !important declaration beats ANY stylesheet rule regardless of\n" +
		"          // its specificity (including an !important rest rule or a more specific pseudo-rule), so\n" +
		"          // the pseudo-state styling reliably wins the cascade - no marker/specificity heuristic.\n" +
		"          var rs = rule.style;\n" +
		"          for (var d=0; d<rs.length; d++){ var prop = rs[d];\n" +
		"            var val = rs.getPropertyValue(prop); if (!val) continue;\n" +
		"            try { el.style.setProperty(prop, val, 'important'); appliedProps++; } catch (e) {} }\n" +
		"          matchedRules++;\n" +
		"        }\n" +
		"        if (truncated) break;\n" +
		"      }\n" +
		"      if (truncated) break;\n" +
		"    }\n" +
		"    return JSON.stringify({ matched: true, action: action, approximate: true,\n" +
		"      matchedRules: matchedRules, appliedProps: appliedProps, skippedSheets: skippedSheets, truncated: truncated });\n" +
		"  } catch (err) { return JSON.stringify({ matched: false, error: String(err) }); }\n" +
		"})();";

	/**
	 * Readiness probe template. {@code return}s {@code true} only when
	 * {@code window.formtemplateName} equals the requested form (so a stale previous form
	 * cannot trip it) <em>and</em> the requested root exists with at least one laid-out node
	 * (non-zero bounding box). Placeholders: {@code %1$s} = JSON-encoded form name,
	 * {@code %2$s} = JSON-encoded selector or {@code null}, {@code %3$s} = earliest acceptable
	 * {@code performance.timeOrigin} (epoch ms), so a document that started loading before this navigation
	 * (a stale previous render) can never satisfy it. The viewport width is deliberately NOT
	 * part of readiness: it is verified after the read (see {@code getFormLayout}), so a browser that
	 * ignores the resize yields a fast error naming the actual width instead of a render timeout.
	 * <p>
	 * SVY-21614: readiness additionally requires every solution stylesheet {@code <link>} to have
	 * applied. {@code FormTemplateHttpEndpoint} injects one
	 * {@code <link rel="stylesheet" svy-stylesheet="...">} per solution/module sheet, loaded
	 * <em>asynchronously</em>; a laid-out box can appear before those sheets parse, so reading
	 * {@code getComputedStyle} too early returns the Bootstrap defaults instead of the solution
	 * cascade (the Run 005 bug). A {@code <link>}'s {@code .sheet} is {@code null} until the sheet
	 * has loaded and parsed, and non-null once it is live in the cascade; the sheets are same-origin
	 * so {@code .sheet} is populated for them. With no solution sheet the set is empty and the check
	 * is a no-op. The check runs only after the form-name, fresh-nav and laid-out-node checks pass.
	 */
	private static final String READINESS_JS_TEMPLATE = "" +
		"return (function(){\n" +
		"  var expected = %1$s;\n" +
		"  if (String(window.formtemplateName) !== String(expected)) return false;\n" +
		"  if (performance.timeOrigin < %3$s) return false;\n" +
		"  var sel = %2$s;\n" +
		"  var root = sel ? document.querySelector(sel) : document.querySelector('.svy-form');\n" +
		"  if (!root) return false;\n" +
		"  var laidOut = false;\n" +
		"  var rb = root.getBoundingClientRect(); if (rb.width > 0 && rb.height > 0) { laidOut = true; }\n" +
		"  else { var kids = root.querySelectorAll('*');\n" +
		"    for (var i=0;i<kids.length;i++){ var r = kids[i].getBoundingClientRect(); if (r.width > 0 && r.height > 0) { laidOut = true; break; } } }\n" +
		"  if (!laidOut) return false;\n" +
		"  var sheets = document.querySelectorAll('link[rel=\"stylesheet\"][svy-stylesheet]');\n" +
		"  for (var s=0;s<sheets.length;s++){ if (!sheets[s].sheet) return false; }\n" +
		"  return true;\n" +
		"})();";

	/**
	 * Diagnostic probe evaluated only after a render timeout: the page that is actually loaded, whether it
	 * is the requested form, whether {@code .svy-form} exists and has a box, and the real viewport size.
	 */
	private static final String DIAGNOSTIC_JS = "" +
		"return (function(){\n" +
		"  try {\n" +
		"    var r = document.querySelector('.svy-form'); var b = r ? r.getBoundingClientRect() : null;\n" +
		"    return JSON.stringify({ href: String(location.href), readyState: document.readyState,\n" +
		"      formtemplateName: String(window.formtemplateName), svyForm: !!r,\n" +
		"      svyFormBox: b ? { w: Math.round(b.width), h: Math.round(b.height) } : null,\n" +
		"      innerWidth: window.innerWidth, innerHeight: window.innerHeight,\n" +
		"      bodyChildren: document.body ? document.body.children.length : -1 });\n" +
		"  } catch (err) { return JSON.stringify({ error: String(err) }); }\n" +
		"})();";

	/**
	 * Serializes calls: one render/read in flight at a time against the shared browser.
	 */
	private final ReentrantLock renderLock = new ReentrantLock();

	/** The single reused hidden browser + its offscreen shell (pool-ready handle). */
	private volatile BrowserHandle handle;

	/**
	 * Set once creating the browser has failed (no Chromium / WebKit backend, e.g. a headless build agent).
	 * Static because each tool-server instance owns its own service, while the missing backend is a property
	 * of the whole JVM. Every failed creation also makes the platform pop a modal "Internal Error - SWT error"
	 * dialog, so retrying on every call would only spawn more of them for a result that cannot change.
	 */
	private static volatile boolean browserUnavailable;

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Writer for the spilled {@code appearanceFile}: pretty-printed with a single space per
	 * nesting level and a bare {@code \n} line separator, so it matches the HTML file's
	 * {@value #HTML_INDENT_SPACES}-space indentation and line endings (Jackson's default pretty
	 * printer otherwise uses two spaces and the platform line separator, which is {@code \r\n} on
	 * Windows). Width and line-ending are cosmetic for the grep/ranged-read the agent does, but
	 * keeping both spilled files identical in style avoids a needless diff between them.
	 */
	private final ObjectWriter appearanceWriter = new ObjectMapper().writer(
		new DefaultPrettyPrinter()
			.withObjectIndenter(new DefaultIndenter(" ".repeat(HTML_INDENT_SPACES), "\n"))
			.withArrayIndenter(new DefaultIndenter(" ".repeat(HTML_INDENT_SPACES), "\n")));

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
		return getFormLayout(formName, selector, screenshot, timeoutSeconds, true);
	}

	/**
	 * As {@link #getFormLayout(String, String, boolean, int)}, with explicit control over sample data (SVY-21610).
	 *
	 * @param sampleData when true (the default) the server fills the render with synthetic, deterministic sample
	 *            values - a value per dataprovider, 3 rows per foundset - so fields and grids show data instead of
	 *            empty chrome. Pass false to inspect the bare component chrome.
	 */
	public FormLayoutResult getFormLayout(String formName, String selector, boolean screenshot, int timeoutSeconds, boolean sampleData)
	{
		return getFormLayout(formName, selector, screenshot, timeoutSeconds, sampleData, null, null);
	}

	/**
	 * As {@link #getFormLayout(String, String, boolean, int, boolean)}, rendering at a requested viewport (SVY-21591)
	 * so responsive (media-query) layouts can be verified at e.g. 1281 / 768 / 375 px.
	 *
	 * @param viewportWidth requested viewport width in px ({@value #MIN_VIEWPORT_SIZE}-{@value #MAX_VIEWPORT_SIZE}),
	 *            or {@code null} for the default
	 * @param viewportHeight requested viewport height in px (same range), or {@code null} for the default
	 */
	public FormLayoutResult getFormLayout(String formName, String selector, boolean screenshot, int timeoutSeconds, boolean sampleData,
		Integer viewportWidth, Integer viewportHeight)
	{
		return getFormLayout(formName, selector, screenshot, timeoutSeconds, sampleData, viewportWidth, viewportHeight, null, null);
	}

	/**
	 * As {@link #getFormLayout(String, String, boolean, int, boolean, Integer, Integer)}, additionally putting one
	 * target element into a CSS pseudo-state before the read (SVY-21614), so the computed styles and the screenshot
	 * reflect {@code :focus} / {@code :hover} / {@code :active}.
	 *
	 * @param targetSelector the single element to put into the state (first {@code querySelector} match), or
	 *            {@code null} for none
	 * @param action {@link #ACTION_FOCUS} (real), {@link #ACTION_HOVER} or {@link #ACTION_ACTIVE} (approximated), or
	 *            {@code null} for the resting render. The caller validates the pair.
	 */
	public FormLayoutResult getFormLayout(String formName, String selector, boolean screenshot, int timeoutSeconds, boolean sampleData,
		Integer viewportWidth, Integer viewportHeight, String targetSelector, String action)
	{
		if (formName == null || formName.isBlank())
		{
			return FormLayoutResult.error("Error: Form name must not be null or empty.");
		}
		String viewportError = validateViewport("viewportWidth", viewportWidth);
		if (viewportError == null) viewportError = validateViewport("viewportHeight", viewportHeight);
		if (viewportError != null)
		{
			return FormLayoutResult.error(viewportError);
		}
		int width = viewportWidth != null ? viewportWidth.intValue() : VIEWPORT_WIDTH;
		int height = viewportHeight != null ? viewportHeight.intValue() : VIEWPORT_HEIGHT;

		// active-solution + form resolution (named, actionable messages - never a hang).
		// Resolve against the editing FLATTENED solution, not the active solution's own editing
		// solution: a form lives in any of the active solution's modules, and the /formtemplate
		// route itself renders from the active editing flattened solution - so form lookup must
		// match it, or forms that are present (in a module) are wrongly reported "not found".
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null)
		{
			return FormLayoutResult.error(
				"No active Servoy solution. Open a solution in Servoy Developer, then try again.");
		}
		String solutionName = activeProject.getSolution().getName();
		com.servoy.j2db.FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
		if (fs == null || fs.getForm(formName) == null)
		{
			return FormLayoutResult.error("Form '" + formName +
				"' was not found in the active solution '" + solutionName + "' or its modules.");
		}

		int port = ApplicationServerRegistry.get() != null ? ApplicationServerRegistry.get().getWebServerPort() : -1;
		if (port <= 0)
		{
			return FormLayoutResult.error(
				"The Servoy web server is not running; start Servoy Developer and open a solution, then try again.");
		}

		// sample data is the endpoint's default; only the opt-out needs to be requested explicitly
		String url = "http://127.0.0.1:" + port + "/formtemplate/" + formName + ".html" + (sampleData ? "" : "?sampleData=false");
		long timeoutMs = Math.max(1, timeoutSeconds) * 1000L;
		String cssSelector = (selector != null && !selector.isBlank()) ? selector.trim() : null;

		// A render that never returns (e.g. a Chromium evaluate that hangs) must not make every later call
		// queue behind it and time out one by one: wait for the lock only as long as this call may take.
		boolean locked;
		try
		{
			locked = renderLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ie)
		{
			Thread.currentThread().interrupt();
			return FormLayoutResult.error("Error: interrupted while waiting for another getFormLayout render to finish.");
		}
		if (!locked)
		{
			return FormLayoutResult.error("Another getFormLayout render is still in progress (or stuck) after " + timeoutSeconds +
				"s, so this call did not start. Wait for it to finish, or restart Servoy Developer if it never does.");
		}
		try
		{
			AtomicReference<String> failure = new AtomicReference<>();
			AtomicReference<String> interaction = new AtomicReference<>();
			String rawRead = renderAndRead(formName, cssSelector, url, timeoutMs, width, height, targetSelector, action, interaction, failure);
			if (failure.get() != null)
			{
				String reason = failure.get();
				if (reason.startsWith("TIMEOUT"))
				{
					// SVY-21670: a form that raised an exception while the server built it is answered with an HTTP
					// error and never renders, which looks exactly like a slow render. Ask the server once, so the
					// caller gets the failure (and its cause) instead of a timeout.
					String serverError = probeServerError(url);
					if (serverError != null)
					{
						ServoyLog.logWarning("FormLayoutInspectionService: form '" + formName + "' failed to load: " + serverError, null);
						return FormLayoutResult.error("Form '" + formName + "' failed to load - the server could not generate it (this is not a timeout): " +
							serverError + ". The full stack trace is in the Servoy Developer log.");
					}
					String detail = reason.substring("TIMEOUT".length());
					ServoyLog.logWarning("FormLayoutInspectionService: form '" + formName + "' did not finish rendering at " + width + "x" +
						height + " within " + timeoutSeconds + "s" + detail, null);
					return FormLayoutResult.error("Form '" + formName + "' did not finish rendering within " + timeoutSeconds + "s" +
						(detail.isEmpty() ? "." : " (viewport " + width + "x" + height + "). Browser state at timeout:" + detail));
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

			// SVY-21591: an explicitly requested width must really have been applied; otherwise the layout
			// the caller reads would silently be the default one.
			if (viewportWidth != null)
			{
				int actualWidth = mapper.readTree(rawRead).path("window").path("width").asInt(-1);
				if (Math.abs(actualWidth - width) > VIEWPORT_WIDTH_TOLERANCE)
				{
					return FormLayoutResult.error("Error: requested viewportWidth " + width + " but the browser rendered at " +
						actualWidth + " px, so the layout would not reflect the requested width. The viewport could not be applied.");
				}
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
			return assembleResult(formName, rawRead, shot, action, targetSelector, interaction.get());
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
	 * browser + offscreen shell on first use), apply the viewport, navigate to the form (a fresh document
	 * every time), poll - with every script evaluation time-capped - until the <em>form-specific</em>
	 * readiness predicate holds or the timeout elapses, then evaluate the <em>form-specific</em> DOM read.
	 * Returns the raw read JSON string, or leaves a reason in {@code failure}.
	 */
	private String renderAndRead(String formName, String cssSelector, String url, long timeoutMs, int width, int height,
		String targetSelector, String action, AtomicReference<String> interactionResult, AtomicReference<String> failure)
	{
		Display display = Display.getDefault();
		AtomicReference<String> readResult = new AtomicReference<>();

		String formNameLiteral = jsStringLiteral(formName);
		String selectorLiteral = cssSelector == null ? "null" : jsStringLiteral(cssSelector);
		String readJs = String.format(READ_JS_TEMPLATE, formNameLiteral, selectorLiteral);

		// create (first call) + clear the previous document so no stale form/marker survives.
		// Catch Throwable, not just Exception: when there is no browser backend (e.g. a headless
		// environment with no Equo Chromium and no WebKit-GTK), BrowserFactory/SWT throws an
		// SWTError (an Error, not an Exception). Translate any creation failure into the NO_BROWSER
		// reason so the caller emits a clean named message instead of leaking a raw SWT stack.
		if (browserUnavailable)
		{
			failure.set("NO_BROWSER");
			return null;
		}
		display.syncExec(() -> {
			boolean creating = true;
			try
			{
				BrowserHandle h = ensureHandle(display);
				creating = false;
				// always (re)apply the requested viewport - the browser is reused, so a previous narrow
				// render must not leak into this call (SVY-21591)
				applyViewport(h, width, height);
			}
			catch (Throwable t)
			{
				// only a failed creation means "no backend"; a later viewport problem must not disable rendering for good
				browserUnavailable = creating;
				failure.set("NO_BROWSER");
				ServoyLog.logWarning("FormLayoutInspectionService: could not create a browser to render forms " +
					"(no Chromium/WebKit backend available in this environment)", t);
			}
		});
		if (failure.get() != null)
		{
			return null;
		}

		// Navigate to the form. A unique query parameter forces a real navigation to a NEW document even when the
		// previous call rendered the same form, and the readiness probe rejects any document that started loading
		// before this moment, so a stale previous render can never be read. This replaces the old about:blank round
		// trip, whose script evaluation could hang forever inside Chromium.
		long navStart = System.currentTimeMillis();
		String navUrl = url + (url.indexOf('?') >= 0 ? "&" : "?") + "svyRender=" + navStart;
		String readinessJs = String.format(READINESS_JS_TEMPLATE, formNameLiteral, selectorLiteral, Long.toString(navStart - NAV_CLOCK_SLACK_MS));
		display.syncExec(() -> {
			try
			{
				BrowserHandle h = handle;
				if (h == null || h.browser.isDisposed())
				{
					failure.set("browser disposed");
					return;
				}
				h.browser.setUrl(navUrl);
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

		// bounded await-ready: never evaluate before the new document has committed (or COMMIT_WAIT_MS passed),
		// and give every evaluation a hard cap so one that never returns cannot wedge the render lock
		AtomicBoolean evalStuck = new AtomicBoolean(false);
		long deadline = System.currentTimeMillis() + timeoutMs;
		boolean ready = false;
		while (System.currentTimeMillis() < deadline)
		{
			BrowserHandle current = handle;
			boolean committed = current != null && (current.committedAtMs >= navStart || System.currentTimeMillis() - navStart > COMMIT_WAIT_MS);
			if (committed)
			{
				Object r = evaluateBounded(display, readinessJs, POLL_EVAL_TIMEOUT_MS, evalStuck);
				if (evalStuck.get())
				{
					break;
				}
				if (Boolean.TRUE.equals(r))
				{
					ready = true;
					break;
				}
			}
			pumpOrWait(display, deadline);
		}

		if (!ready)
		{
			failure.set("TIMEOUT" + (evalStuck.get() ? STUCK_DETAIL : describeBrowserState(display)));
			return null;
		}

		// make the browser's real width equal the requested one (it reports a little more than the shell size)
		alignViewportWidth(display, width, height, evalStuck);
		if (evalStuck.get())
		{
			failure.set("TIMEOUT" + STUCK_DETAIL);
			return null;
		}

		// SVY-21614: put the target element into the requested pseudo-state AFTER the form is ready but
		// BEFORE the read, so the appearance map and the screenshot both reflect it. focus() is real;
		// hover/active are approximated by copying the matching pseudo-rule declarations onto the target.
		if (action != null)
		{
			String pseudoJs = String.format(PSEUDO_JS_TEMPLATE, jsStringLiteral(targetSelector), jsStringLiteral(action),
				Integer.toString(MAX_PSEUDO_RULES));
			Object pseudoValue = evaluateBounded(display, pseudoJs, READ_EVAL_TIMEOUT_MS, evalStuck);
			if (evalStuck.get())
			{
				failure.set("TIMEOUT" + STUCK_DETAIL);
				return null;
			}
			interactionResult.set(pseudoValue == null ? null : pseudoValue.toString());
		}

		// read the rendered subtree HTML + appearance map
		Object readValue = evaluateBounded(display, readJs, READ_EVAL_TIMEOUT_MS, evalStuck);
		if (evalStuck.get())
		{
			failure.set("TIMEOUT" + STUCK_DETAIL);
			return null;
		}
		readResult.set(readValue == null ? null : readValue.toString());

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
	 * Resizes the shell until the browser's {@code window.innerWidth} equals {@code width}. The embedded
	 * browser reports a width that differs slightly from the size its shell was given (observed: +1 px), which
	 * would make a request for 768 render at 769 - above the {@code md} breakpoint. The correction is remembered
	 * on the handle so later calls start at the right size. Gives up quietly when the browser does not react to
	 * a resize; {@code getFormLayout} then reports the real width instead of a wrong layout (it checks the
	 * reported width after the read).
	 */
	private void alignViewportWidth(Display display, int width, int height, AtomicBoolean stuck)
	{
		int measured = measureInnerWidth(display, stuck);
		for (int attempt = 0; attempt < 3 && measured >= 0 && measured != width; attempt++)
		{
			int before = measured;
			int delta = width - before;
			display.syncExec(() -> {
				BrowserHandle h = handle;
				if (h != null && !h.browser.isDisposed())
				{
					h.widthDelta += delta;
					applyViewport(h, width, height);
				}
			});
			// the resize reaches the page asynchronously: wait until it reports a different width
			long until = System.currentTimeMillis() + ALIGN_WAIT_MS;
			while (System.currentTimeMillis() < until)
			{
				pumpOrWait(display, until);
				measured = measureInnerWidth(display, stuck);
				if (measured < 0 || measured != before)
				{
					break;
				}
			}
			if (measured == before)
			{
				return;
			}
		}
	}

	/** @return the browser's current {@code window.innerWidth}, or -1 when it cannot be read */
	private int measureInnerWidth(Display display, AtomicBoolean stuck)
	{
		Object r = evaluateBounded(display, "return window.innerWidth;", POLL_EVAL_TIMEOUT_MS, stuck);
		return !stuck.get() && r instanceof Number ? ((Number)r).intValue() : -1;
	}

	/** Reads at most 16 KB of the error response body (the error page is small); empty when there is none. */
	private static String readBody(HttpURLConnection connection)
	{
		try (InputStream in = connection.getErrorStream())
		{
			if (in == null)
			{
				return "";
			}
			return new String(in.readNBytes(16 * 1024), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			return "";
		}
	}

	/**
	 * Pulls the message out of a servlet-container error page. Tomcat renders {@code sendError(status, message)} as
	 * {@code <p><b>Message</b> text</p>}; the text is HTML-escaped, so common entities are decoded again. Returns an empty
	 * string when the body is not such a page.
	 */
	public static String extractErrorMessage(String body)
	{
		if (body == null)
		{
			return "";
		}
		Matcher m = ERROR_PAGE_MESSAGE.matcher(body);
		if (!m.find())
		{
			return "";
		}
		String text = m.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
		return text.replaceAll("\\s+", " ").trim();
	}

	/** {@code <b>Message</b> ...</p>} as written by Tomcat's default error report. */
	private static final Pattern ERROR_PAGE_MESSAGE = Pattern.compile("<b>\\s*Message\\s*</b>\\s*(.*?)</p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	/**
	 * Asks the {@code /formtemplate} route directly why a form did not render. The route answers a form it could not
	 * generate with an HTTP error status, and the status message carries the cause (SVY-21670). Runs after a timeout
	 * only, on the calling (non-UI) thread, with short timeouts so it can never delay the answer much.
	 *
	 * @return {@code "HTTP <status> <message>"} when the route reports a failure, or {@code null} when it answers
	 *         normally or cannot be reached (then the caller keeps the plain timeout message)
	 */
	public static String probeServerError(String url)
	{
		HttpURLConnection connection = null;
		try
		{
			connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
			connection.setConnectTimeout(2_000);
			connection.setReadTimeout(5_000);
			connection.setRequestMethod("GET");
			int status = connection.getResponseCode();
			if (status < 400)
			{
				return null;
			}
			// Tomcat keeps the sendError message out of the status line (it is the standard reason phrase) and
			// writes it into the error page body, so read the body and keep its "Message" text
			String statusText = "HTTP " + status;
			String reason = connection.getResponseMessage();
			if (reason != null && !reason.isBlank())
			{
				statusText += " " + reason.trim();
			}
			String detail = extractErrorMessage(readBody(connection));
			return detail.isEmpty() ? statusText : statusText + ": " + detail;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
		finally
		{
			if (connection != null)
			{
				connection.disconnect();
			}
		}
	}

	/**
	 * Best-effort snapshot of what the hidden browser looks like, appended to a render timeout so the
	 * caller (and the log) can tell a slow render from a page that never became the requested form.
	 *
	 * @return {@code " "} followed by a JSON object, or an empty string when the browser cannot be read
	 */
	private String describeBrowserState(Display display)
	{
		Object r = evaluateBounded(display, DIAGNOSTIC_JS, DIAGNOSTIC_EVAL_TIMEOUT_MS, new AtomicBoolean(false));
		return r == null ? "" : " " + r;
	}

	/**
	 * Evaluates {@code js} in the hidden browser on the display thread but never waits for it longer than
	 * {@code maxWaitMs}. Equo's {@code Chromium.evaluate} blocks on a future that is never completed when the page
	 * navigates mid-evaluation; the display thread then keeps pumping events inside that call, so later display work
	 * still runs, but the calling thread must not wait for it. When the cap passes, {@code stuck} is set and
	 * {@code null} returned; the abandoned evaluation stays parked on the display thread.
	 */
	private Object evaluateBounded(Display display, String js, long maxWaitMs, AtomicBoolean stuck)
	{
		AtomicReference<Object> result = new AtomicReference<>();
		Runnable evaluation = () -> {
			try
			{
				BrowserHandle h = handle;
				if (h != null && !h.browser.isDisposed())
				{
					result.set(h.browser.evaluate(js));
				}
			}
			catch (Exception e)
			{
				// transient during navigation; the caller polls again
			}
		};
		if (display.getThread() == Thread.currentThread())
		{
			evaluation.run();
			return result.get();
		}
		CountDownLatch done = new CountDownLatch(1);
		display.asyncExec(() -> {
			try
			{
				evaluation.run();
			}
			finally
			{
				done.countDown();
			}
		});
		try
		{
			if (!done.await(maxWaitMs, TimeUnit.MILLISECONDS))
			{
				stuck.set(true);
				return null;
			}
		}
		catch (InterruptedException ie)
		{
			Thread.currentThread().interrupt();
			stuck.set(true);
			return null;
		}
		return result.get();
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
	 * Validates an optional requested viewport dimension.
	 *
	 * @return a named error message, or {@code null} when the value is absent or within range
	 */
	static String validateViewport(String paramName, Integer value)
	{
		if (value != null && (value.intValue() < MIN_VIEWPORT_SIZE || value.intValue() > MAX_VIEWPORT_SIZE))
		{
			return "Error: " + paramName + " must be between " + MIN_VIEWPORT_SIZE + " and " + MAX_VIEWPORT_SIZE + " px, got " + value + ".";
		}
		return null;
	}

	/**
	 * Resizes the offscreen shell and its browser to the requested viewport. Must run on the display thread.
	 * The shell is resized (not just the browser) so the {@link FillLayout} propagates the new size.
	 */
	private static void applyViewport(BrowserHandle h, int width, int height)
	{
		// widthDelta is the correction learned by alignViewportWidth: the browser reports innerWidth
		// slightly different from the size the shell was given (observed: +1 px)
		int shellWidth = Math.max(1, width + h.widthDelta);
		h.shell.setSize(shellWidth, height);
		h.browser.setSize(shellWidth, height);
		h.shell.layout(true, true);
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
			final BrowserHandle created = h;
			browser.addLocationListener(new LocationAdapter()
			{
				@Override
				public void changed(LocationEvent event)
				{
					if (event.top)
					{
						created.committedAtMs = System.currentTimeMillis();
					}
				}
			});
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
	 * Builds the final result envelope from the raw DOM-read JSON. The two heavy parts of the
	 * read - the rendered {@code html} and the computed-appearance {@code map} - are
	 * <em>always</em> written to their own temp files and are <em>never</em> inlined, so one
	 * {@code getFormLayout} call costs only the small metadata envelope in the model's context no
	 * matter how large the form is (the previous inline-under-a-cap path could still spend ~10K
	 * characters on a modest form; SVY-21577 follow-up). The caller reads only the file(s) it
	 * needs and can grep each for a specific element.
	 * <p>
	 * The envelope inlines just the cheap, always-useful metadata (form, selector, the root box,
	 * the real viewport, the element count) plus the two absolute file paths and a guidance
	 * string. The {@code htmlFile} is written pretty-printed (one element per indented line, see
	 * {@link #prettyPrintHtml(String)}) and the {@code appearanceFile} is pretty-printed JSON
	 * (one node per line), so a line-based file reader can grep either for an element ref, id,
	 * class or style and read a small range around the match.
	 */
	private FormLayoutResult assembleResult(String formName, String rawRead, CapturedScreenshot shot) throws Exception
	{
		return assembleResult(formName, rawRead, shot, null, null, null);
	}

	/**
	 * As {@link #assembleResult(String, String, CapturedScreenshot)}, additionally attaching the SVY-21614
	 * {@code interaction} summary when a pseudo-state action was requested. {@code interactionRaw} is the JSON the
	 * injected {@link #PSEUDO_JS_TEMPLATE} returned (or {@code null} if it did not run); it is enriched with the
	 * requested {@code action} / {@code targetSelector} and, for {@code hover}/{@code active}, an honesty
	 * {@code note}. The key is placed on the read envelope so it survives the inline-vs-spill decision.
	 */
	private FormLayoutResult assembleResult(String formName, String rawRead, CapturedScreenshot shot, String action,
		String targetSelector, String interactionRaw) throws Exception
	{
		// rawRead is a JSON object string produced by the injected read; validate + normalize.
		com.fasterxml.jackson.databind.JsonNode readNode = mapper.readTree(rawRead);
		ObjectNode read = readNode.isObject() ? (ObjectNode)readNode : mapper.createObjectNode();

		if (action != null)
		{
			ObjectNode interaction = mapper.createObjectNode();
			interaction.put("action", action);
			if (targetSelector != null) interaction.put("targetSelector", targetSelector);
			com.fasterxml.jackson.databind.JsonNode raw = interactionRaw != null ? mapper.readTree(interactionRaw) : null;
			boolean matched = raw != null && raw.path("matched").asBoolean(false);
			interaction.put("matched", matched);
			if (!matched)
			{
				interaction.put("note", "no element matched targetSelector '" + targetSelector +
					"', so no pseudo-state was applied; the appearance below is the resting render.");
			}
			else if (ACTION_FOCUS.equals(action))
			{
				interaction.put("approximate", false);
			}
			else
			{
				interaction.put("approximate", true);
				if (raw.has("matchedRules")) interaction.set("matchedRules", raw.get("matchedRules"));
				if (raw.has("skippedSheets")) interaction.set("skippedSheets", raw.get("skippedSheets"));
				if (raw.path("truncated").asBoolean(false)) interaction.put("truncated", true);
				interaction.put("note", "'" + action + "' is APPROXIMATED: the matching " + (ACTION_ACTIVE.equals(action) ? ":active" : ":hover") +
					" rule declarations were applied to the target; this is not a true pseudo-state match (descendant/combined states and full cascade specificity are not modelled). 'focus' is the only exact state.");
			}
			read.set("interaction", interaction);
		}

		String screenshotFile = shot != null ? shot.file : null;
		String imageBase64 = shot != null ? shot.base64 : null;

		// Always externalise the two heavy parts. The html is pretty-printed so the file is
		// greppable and line-addressable (not one giant line); the appearance map is written as
		// pretty JSON (one node per line) for the same reason.
		String rawHtml = read.path("html").asText("");
		String htmlFile = spill(formName, "html", prettyPrintHtml(rawHtml));

		com.fasterxml.jackson.databind.JsonNode appearance = read.has("appearance") ? read.get("appearance")
			: mapper.createObjectNode();
		String appearanceFile = spill(formName, "json", appearanceWriter.writeValueAsString(appearance));
		int elementCount = appearance.isObject() ? appearance.size() : 0;

		ObjectNode envelope = mapper.createObjectNode();
		if (read.has("form")) envelope.set("form", read.get("form"));
		if (read.has("selector")) envelope.set("selector", read.get("selector"));
		if (read.has("viewport")) envelope.set("viewport", read.get("viewport"));
		if (read.has("window")) envelope.set("window", read.get("window"));
		envelope.put("elementCount", elementCount);
		// SVY-21614: the interaction summary is small and high-signal, so keep it inline in the envelope
		if (read.has("interaction")) envelope.set("interaction", read.get("interaction"));
		envelope.put("htmlFile", htmlFile);
		envelope.put("appearanceFile", appearanceFile);
		if (screenshotFile != null) envelope.put("screenshotFile", screenshotFile);
		envelope.put("guidance",
			"The rendered HTML and the computed-appearance map were each written to a file (never inlined, to keep this " +
				"call cheap). Both paths are ABSOLUTE - pass each one UNCHANGED to a file-reading tool; do not make it " +
				"project-relative (no ../). 'htmlFile' is pretty-printed (one element per indented line) - read it for the " +
				"rendered structure, the real component tags and their id / data-svy-name / class identity. 'appearanceFile' " +
				"is pretty-printed JSON (one node per line) keyed by element ref - read it for the resolved colours, border, " +
				"font, the on-screen box and the disabled / readonly booleans. Both files are greppable: search for an " +
				"element ref, id, class or style and read a small line range around the match instead of the whole file.");

		String json = mapper.writeValueAsString(envelope);
		return new FormLayoutResult(json, htmlFile, screenshotFile, imageBase64, null);
	}

	/**
	 * Pretty-prints the rendered subtree HTML so the spilled {@code htmlFile} is one element per
	 * indented physical line (greppable and line-addressable) rather than the single very long
	 * line the browser's {@code outerHTML} produces. Parsed with jsoup's lenient HTML parser and
	 * re-serialised with pretty-printing on and {@link #HTML_INDENT_SPACES}-space indentation.
	 * Entity escaping is left as-is ({@link Entities.EscapeMode#xhtml}, the minimal set) so the
	 * identity attributes and text the caller greps for are not re-encoded. On any parse failure
	 * the raw HTML is returned unchanged - a readable-but-unformatted file beats no file.
	 */
	static String prettyPrintHtml(String html)
	{
		if (html == null || html.isBlank())
		{
			return html == null ? "" : html;
		}
		try
		{
			// Parse as a body fragment: the read returns a single root element's outerHTML (the
			// whole .svy-form or the selector match), so the fragment's body holds exactly that
			// subtree. body().html() serialises its children, i.e. that one root, indented.
			Document doc = Jsoup.parse(html, "", Parser.htmlParser());
			doc.outputSettings()
				.prettyPrint(true)
				.indentAmount(HTML_INDENT_SPACES)
				.outline(true)
				.escapeMode(Entities.EscapeMode.xhtml);
			return doc.body().html();
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("FormLayoutInspectionService: HTML pretty-print failed; writing the raw HTML instead", e);
			return html;
		}
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

		/** When the top frame last committed a location change (epoch ms); lets the render wait for the new document. */
		volatile long committedAtMs;

		/** Pixels added to a requested width so the browser's innerWidth matches it; learned on first use. */
		volatile int widthDelta;

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
	 * Small result holder returned to the tool method. {@code json} is the metadata envelope
	 * (form / selector / viewport / window / elementCount plus the {@code htmlFile} and
	 * {@code appearanceFile} paths and a guidance string), or {@code null} on failure. The heavy
	 * {@code html} and {@code appearance} are always in those files, never in the envelope.
	 * {@code resultFile} is the {@code htmlFile} path (the primary spilled file).
	 * {@code screenshotFile} is the PNG path when a screenshot was captured; {@code imageBase64}
	 * is the base64-encoded PNG for attaching as MCP image content. {@code error} carries a named
	 * actionable message on failure.
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
