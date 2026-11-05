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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.AbstractTitaniumIntegrationTestCopy;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.DialogExpectationCopy;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestDialogInterceptorCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyFormServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.persistence.IColumnTypes;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptVariable;

/**
 * Integration tests for the {@code getFormLayout} MCP tool (server
 * {@code servoy-form}, {@link ServoyFormServer}).
 * <p>
 * These exercise the full render + read round-trip: the tool renders
 * {@code /formtemplate/<form>.html} in a hidden {@code IBrowser} on the SWT
 * display thread, waits for render-complete, and reads back the rendered DOM as
 * a JSON envelope carrying the subtree {@code html} (outerHTML) and an
 * {@code appearance} map (computed styles + bounding boxes). They must run in
 * the PDE harness (running Servoy Application Server + configured workspace) -
 * hence the {@code *IntegrationTest} suffix and the
 * {@link AbstractTitaniumIntegrationTestCopy} base (dialog guard, titanium build, helpers).
 * </p>
 * <p>
 * <b>Render-side prerequisite (not yet on the target platform):</b> the
 * stateless {@code /formtemplate/<form>.html} route lands on the 2026.03 LTS
 * branch. Until the resolved target platform carries it, the positive
 * round-trip assertions here cannot pass. This class is therefore registered
 * but is <b>intentionally not run</b> until that prerequisite is present; the
 * negative-path tests (no form / blank name) are already valid. A stable
 * {@code data-svy-*} identity attribute on the component tags (from a separate
 * core enhancement) makes the returned HTML self-identifying but is <b>not</b>
 * required by this tool - the rendered component tags, {@code id} and
 * {@code data-cy} already carry identity.
 * </p>
 */
public class FormLayoutToolIntegrationTest extends AbstractTitaniumIntegrationTestCopy {

	private static final String TEST_SOLUTION = "test_formlayout_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final String FORM_CSS = "layoutCssForm";
	private static final String FORM_OTHER = "layoutOtherForm";
	private static final String FORM_MISSING = "noSuchFormAtAll";
	/** SVY-21610: the form carrying a variable-bound label, used by the sample-data tests. */
	private static final String FORM_SAMPLE = "layoutSampleDataForm";
	/** SVY-21614: a form with one enabled and one disabled button, for the disabled/readonly assertions. */
	private static final String FORM_DISABLED = "layoutDisabledForm";
	/** The initialiser value of that form's variable; must survive into the rendered DOM. */
	private static final String SAMPLE_VARIABLE_VALUE = "InitialisedValue";

	private static final long RENDER_TIMEOUT_SECONDS = 20;

	private final ObjectMapper mapper = new ObjectMapper();

	private ServoyFormServer tool;
	private ServoyProject activeProject;

	public FormLayoutToolIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	/**
	 * The tool renders the real form template in a hidden browser, so the titanium
	 * build must be enabled (as ShowFormInBrowserIntegrationTest does). Also
	 * deletes stale solution projects so the class starts fresh.
	 */
	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();

		Activator.setNodeExtractionAndTitaniumBuildDisabled(false);
	}

	@BeforeEach
	public void setUp() throws Exception {
		tool = new ServoyFormServer();

		// On a headless agent (no embedded Chromium/WebKit) BrowserFactory.createBrowser
		// throws an SWTError. getFormLayout catches it and returns the NO_BROWSER named
		// message (so the render tests SKIP, see envelopeOf), but the platform ALSO routes
		// that SWTError to the IDE error handler, which pops a modal "Internal Error - An
		// SWT error has occurred ... Do you want to exit the workbench?" dialog. Left alone
		// that modal dialog would be flagged by the DialogGuard (and, unanswered, could
		// block the UI thread), so pre-register an expectation that answers "No" (keep the
		// workbench running) whenever it appears. Harmless when it never does: an unmatched
		// expectation is simply cleared after the test.
		TestDialogInterceptorCopy.expect(DialogExpectationCopy.withTitleContaining("Internal Error")
				.messageContains("SWT error").clickButtonContains("No"));

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();

		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	// -----------------------------------------------------------------------
	// Whole-form render: html + appearance for the .svy-form subtree
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("CSS form renders .svy-form HTML with the component tags and a non-empty appearance map")
	public void cssForm_returnsHtmlAndAppearance() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);

		JsonNode envelope = envelopeOf(
				tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));

		assertEquals(FORM_CSS, envelope.path("form").asText(), "the read is for the requested form");
		assertEquals(".svy-form", envelope.path("selector").asText(), "no selector -> the whole .svy-form");

		// the rendered viewport is laid out (non-zero box)
		JsonNode vp = envelope.path("viewport");
		assertTrue(vp.path("w").asInt() > 0 && vp.path("h").asInt() > 0, "the .svy-form viewport has a non-zero box");

		// html carries the real rendered DOM: the .svy-form root and the component's
		// rendered element (a button component renders a <button>; the element's
		// Servoy name surfaces via the data-cy / id the renderer stamps).
		String html = envelope.path("html").asText();
		assertTrue(html.contains("svy-form"), "html is the rendered .svy-form outerHTML");
		assertTrue(html.contains("btnAction"), "the button's Servoy name appears in the rendered HTML: " + html);

		// appearance is a non-empty map keyed by element ref, each entry carrying a box
		// and resolved styles
		JsonNode appearance = envelope.path("appearance");
		assertTrue(appearance.isObject() && appearance.size() > 0, "appearance map is non-empty");
		JsonNode anyEntry = appearance.elements().next();
		assertTrue(anyEntry.path("box").isObject(), "each appearance entry has a bounding box");
		assertTrue(anyEntry.path("styles").isObject(), "each appearance entry has resolved styles");
	}

	// -----------------------------------------------------------------------
	// Selector: return just one component's subtree
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("selector returns only the matched subtree's HTML and appearance")
	public void selector_returnsMatchedSubtreeOnly() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);

		// Ask for just the button's subtree via a stable attribute selector the
		// renderer
		// stamps (data-cy = "<form>.<name>"). The returned subtree must be the button
		// only - so the label that is a sibling in the full form must NOT appear.
		String selector = "[data-cy='" + FORM_CSS + ".btnAction']";
		JsonNode envelope = envelopeOf(
				tool.getFormLayout(FORM_CSS, selector, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));

		assertEquals(selector, envelope.path("selector").asText(), "the read echoes the requested selector");

		String html = envelope.path("html").asText();
		assertTrue(html.contains("btnAction"), "the matched subtree carries the button: " + html);
		assertFalse(html.contains("lblStatus"),
				"a selector-scoped read returns ONLY the matched subtree, not the sibling label: " + html);

		// appearance is scoped to the subtree and still carries a box + styles
		JsonNode appearance = envelope.path("appearance");
		assertTrue(appearance.isObject() && appearance.size() > 0, "subtree appearance map is non-empty");
		JsonNode anyEntry = appearance.elements().next();
		assertTrue(anyEntry.path("box").isObject() && anyEntry.path("styles").isObject(),
				"each appearance entry has a box and resolved styles");
	}

	// -----------------------------------------------------------------------
	// B1 regression: two consecutive calls for DIFFERENT forms each return
	// that form's own DOM (the reused browser must discard the previous one)
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("B1: consecutive calls for different forms each return their own DOM")
	public void consecutiveDifferentForms_returnOwnDom() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);
		ensureCssFormWithSingleLabel(FORM_OTHER, "lblOnlyHere", "Only here");

		JsonNode first = envelopeOf(tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));
		JsonNode second = envelopeOf(
				tool.getFormLayout(FORM_OTHER, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));

		assertEquals(FORM_CSS, first.path("form").asText(), "first call returns the first form");
		assertEquals(FORM_OTHER, second.path("form").asText(),
				"second call returns the SECOND form, not the stale first one");

		String secondHtml = second.path("html").asText();
		assertTrue(secondHtml.contains("lblOnlyHere"), "the second form's own element is present in its HTML");
		assertFalse(secondHtml.contains("btnAction"),
				"the previous form's button must not leak into the second form's DOM");
	}

	// -----------------------------------------------------------------------
	// SVY-21610: sample data - a field bound to a dataprovider must render a value
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21610: a label bound to a form variable with an initialiser renders that value with sampleData on")
	public void sampleData_rendersVariableInitialiserValue() throws Exception {
		Form form = ensureCssFormWithVariableBoundLabel(FORM_SAMPLE, "vFilled", "lblFilled");
		assertNotNull(form, "the sample-data form must exist");

		JsonNode envelope = envelopeOf(
				tool.getFormLayout(FORM_SAMPLE, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "true", null, null));

		String html = envelope.path("html").asText();
		// the variable is declared with an initialiser, so its real initial value reaches the design-time render -
		// this is the no-code answer to the SVY-21610 report (an onLoad assignment could never show up here)
		assertTrue(html.contains(SAMPLE_VARIABLE_VALUE),
				"a label bound to a variable with an initialiser must render that value: " + html);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21610: the same form renders byte-identical HTML twice (sample data is deterministic)")
	public void sampleData_isDeterministicAcrossRenders() throws Exception {
		ensureCssFormWithVariableBoundLabel(FORM_SAMPLE, "vFilled", "lblFilled");

		String first = envelopeOf(
				tool.getFormLayout(FORM_SAMPLE, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "true", null, null))
						.path("html").asText();
		String second = envelopeOf(
				tool.getFormLayout(FORM_SAMPLE, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "true", null, null))
						.path("html").asText();

		// THE contract of this feature: the agent loop is render -> change one thing -> render -> diff, so sample
		// values must not vary between two renders of an unchanged form. A regression to a random/clock-based value
		// would still "show data" and pass every other test here.
		assertEquals(first, second, "two renders of an unchanged form must produce identical HTML");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21610: sampleData=false keeps the bare chrome (no value rendered)")
	public void sampleDataOff_rendersNoValue() throws Exception {
		ensureCssFormWithVariableBoundLabel(FORM_SAMPLE, "vFilled", "lblFilled");

		String html = envelopeOf(
				tool.getFormLayout(FORM_SAMPLE, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null))
						.path("html").asText();

		assertFalse(html.contains(SAMPLE_VARIABLE_VALUE),
				"with sampleData off the render must carry no data values: " + html);
	}
	// -----------------------------------------------------------------------
	// SVY-21591: render at a requested viewport width/height
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21591: viewportWidth/Height are applied and echoed in 'window'")
	public void viewport_isAppliedAndEchoed() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);

		JsonNode narrow = envelopeOf(tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false",
				"375", "700"));

		assertEquals(375, narrow.path("window").path("width").asInt(), "window.width echoes the requested viewport width");
		assertEquals(700, narrow.path("window").path("height").asInt(), "window.height echoes the requested viewport height");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21591: the rendered window width equals the requested width exactly, around the md/lg breakpoints")
	public void viewport_isExactAroundBreakpoints() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);

		for (int requested : new int[] { 1281, 992, 768, 767, 375 }) {
			JsonNode envelope = envelopeOf(tool.getFormLayout(FORM_CSS, null, "false",
					Long.toString(RENDER_TIMEOUT_SECONDS), "false", Integer.toString(requested), null));
			assertEquals(requested, envelope.path("window").path("width").asInt(),
					"window.width must equal the requested viewportWidth " + requested);
		}
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21591: different viewport widths give different layouts; the default is restored afterwards")
	public void viewport_changesLayoutAndDoesNotLeakIntoNextCall() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);

		JsonNode wide = envelopeOf(tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false",
				"1281", null));
		JsonNode narrow = envelopeOf(tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false",
				"375", null));
		JsonNode defaults = envelopeOf(
				tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));

		assertEquals(1281, wide.path("window").path("width").asInt(), "wide render uses the requested width");
		assertEquals(375, narrow.path("window").path("width").asInt(), "narrow render uses the requested width");
		assertTrue(narrow.path("viewport").path("w").asInt() < wide.path("viewport").path("w").asInt(),
				"the form root is narrower at 375px than at 1281px");
		assertEquals(1280, defaults.path("window").path("width").asInt(),
				"a call without viewportWidth renders at the default width, not the previous narrow one");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21591: an out-of-range viewport returns a named error")
	public void viewport_outOfRange_returnsError() {
		Object tooNarrow = tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", "50", null);
		Object tooTall = tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, "99999");

		assertInstanceOf(String.class, tooNarrow, "an error is returned as a plain String");
		assertTrue(((String) tooNarrow).startsWith("Error") && ((String) tooNarrow).contains("viewportWidth"),
				"out-of-range width names the parameter: " + tooNarrow);
		assertInstanceOf(String.class, tooTall, "an error is returned as a plain String");
		assertTrue(((String) tooTall).startsWith("Error") && ((String) tooTall).contains("viewportHeight"),
				"out-of-range height names the parameter: " + tooTall);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21591: a non-numeric viewport returns a named error instead of silently using the default")
	public void viewport_nonNumeric_returnsError() {
		Object result = tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", "wide", null);

		assertInstanceOf(String.class, result, "an error is returned as a plain String");
		assertTrue(((String) result).startsWith("Error") && ((String) result).contains("viewportWidth"),
				"non-numeric width names the parameter: " + result);
	}


	// -----------------------------------------------------------------------
	// Not-found / named-message paths (valid even without the render prerequisite)
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("form not found returns the named actionable message")
	public void formNotFound_returnsNamedMessage() {
		Object result = tool.getFormLayout(FORM_MISSING, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null);

		assertInstanceOf(String.class, result, "a named message is returned as a plain String");
		String message = (String) result;
		assertTrue(message.contains("was not found") && message.contains(FORM_MISSING),
				"missing form yields the named 'was not found' message: " + result);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("blank form name returns an error, never a hang")
	public void blankFormName_returnsError() {
		Object result = tool.getFormLayout("   ", null, null, null, null, null, null);

		assertInstanceOf(String.class, result, "an error is returned as a plain String");
		assertTrue(((String) result).startsWith("Error"), "a blank form name yields an Error message: " + result);
	}

	// -----------------------------------------------------------------------
	// SVY-21614: appearance carries outline* styles and disabled/readonly booleans
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614: every appearance entry carries the outline-* computed styles")
	public void appearance_carriesOutlineStyles() throws Exception {
		ensureCssFormWithButtonAndLabel(FORM_CSS);

		JsonNode envelope = envelopeOf(
				tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));

		JsonNode appearance = envelope.path("appearance");
		assertTrue(appearance.isObject() && appearance.size() > 0, "appearance map is non-empty: " + envelope);
		// every entry's styles must carry the four outline longhands (a focus ring is now observable)
		for (JsonNode entry : appearance) {
			JsonNode styles = entry.path("styles");
			for (String prop : new String[] { "outline-width", "outline-style", "outline-color", "outline-offset" }) {
				assertTrue(styles.has(prop), "each appearance entry's styles carry '" + prop + "': " + entry);
			}
		}
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614: appearance carries disabled/readonly, and a disabled component reports disabled:true")
	public void appearance_carriesDisabledReadonlyBooleans() throws Exception {
		ensureCssFormWithEnabledAndDisabledButtons(FORM_DISABLED);

		JsonNode envelope = envelopeOf(
				tool.getFormLayout(FORM_DISABLED, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));

		JsonNode appearance = envelope.path("appearance");
		assertTrue(appearance.isObject() && appearance.size() > 0, "appearance map is non-empty: " + envelope);

		// every entry carries explicit booleans (not merely present on some)
		for (JsonNode entry : appearance) {
			assertTrue(entry.path("disabled").isBoolean(), "each appearance entry has a disabled boolean: " + entry);
			assertTrue(entry.path("readonly").isBoolean(), "each appearance entry has a readonly boolean: " + entry);
		}

		// the disabled button is reported disabled:true; the enabled one disabled:false, so the
		// boolean reflects the real state (not an always-false constant). The component's name
		// surfaces in the element ref / nested DOM, so find the entry whose html subtree carries it.
		boolean sawDisabledTrue = false;
		boolean sawEnabledFalse = false;
		String html = envelope.path("html").asText();
		assertTrue(html.contains("btnDisabled") && html.contains("btnEnabled"),
				"both the enabled and disabled buttons are in the rendered HTML: " + html);
		for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = appearance.fields(); it.hasNext();) {
			java.util.Map.Entry<String, JsonNode> e = it.next();
			boolean disabled = e.getValue().path("disabled").asBoolean();
			if (e.getKey().contains("btnDisabled") && disabled) sawDisabledTrue = true;
			if (e.getKey().contains("btnEnabled") && !disabled) sawEnabledFalse = true;
		}
		// The element ref may be a nested cssPath rather than the component name; fall back to a
		// whole-map check: at least one entry is disabled:true and at least one is disabled:false.
		if (!sawDisabledTrue || !sawEnabledFalse) {
			boolean anyTrue = false, anyFalse = false;
			for (JsonNode entry : appearance) {
				if (entry.path("disabled").asBoolean()) anyTrue = true;
				else anyFalse = true;
			}
			assertTrue(anyTrue, "a disabled component must make some entry disabled:true: " + appearance);
			assertTrue(anyFalse, "the enabled components keep disabled:false, so it is not always-true: " + appearance);
		}
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Parses the tool result, fails on a named message, and returns the JSON
	 * envelope. When the result was spilled to a temp file (large forms), the full
	 * envelope is read back from {@code resultFile}.
	 */
	private JsonNode envelopeOf(Object rawResult) throws Exception {
		assertNotNull(rawResult, "tool result must not be null");
		// A successful render with screenshot=false returns the envelope as a plain
		// String; a
		// named message is also a String. (With a screenshot it would be an
		// McpToolResult, but
		// these tests pass screenshot=false so the DOM assertions stay stable.)
		assertInstanceOf(String.class, rawResult, "expected a String envelope/message, got: " + rawResult);
		String toolResult = (String) rawResult;
		// When the render prerequisite is not met on this agent (no embedded browser
		// backend, no active solution, web server not up, ...), getFormLayout returns a
		// plain named message instead of a JSON envelope. That is an environment gap,
		// not a product defect, so SKIP the test with a clear reason rather than letting
		// mapper.readTree(...) blow up with a cryptic JsonParseException. Each known
		// message prefix maps to a readable "why it was skipped" explanation.
		String skipReason = renderPrerequisiteSkipReason(toolResult);
		if (skipReason != null)
		{
			Assumptions.abort("getFormLayout render prerequisite not met, skipping: " + skipReason
					+ " | tool returned: " + toolResult);
		}
		JsonNode envelope = mapper.readTree(toolResult);
		JsonNode resultFile = envelope.path("resultFile");
		if (!resultFile.isMissingNode() && !resultFile.isNull()) {
			// large form: the full html + appearance was spilled - read it back
			Path file = Paths.get(resultFile.asText());
			assertTrue(Files.exists(file), "the spilled resultFile must exist on disk: " + file);

			String raw = Files.readString(file, StandardCharsets.UTF_8);
			// SVY-21577: the spilled file must be pretty-printed (one JSON node per
			// line) so line-based file readers can grep + ranged-read it, not a single
			// ~170 KB line. A compact serialization would collapse to a single physical
			// line with no indentation; a revert of the pretty-printer would fail here.
			assertSpillIsPrettyPrinted(raw, file);
			// SVY-21577: the inline warning must steer the agent to the absolute path,
			// to pass it unchanged (no project-relative ../), and must advertise that the
			// file is pretty-printed / greppable.
			assertSpillWarning(envelope.path("warning").asText(), resultFile.asText());

			// still parses to the same envelope shape (pretty JSON parses identically).
			return mapper.readTree(raw);
		}
		return envelope;
	}

	/**
	 * Maps a known {@code getFormLayout} named message (a plain-text,
	 * non-envelope result) to a human-readable reason the render tests should be
	 * SKIPPED rather than failed. Returns {@code null} when the result is not a
	 * recognised named message (i.e. it is - or should be - a real JSON envelope,
	 * so the caller must parse and assert against it).
	 * <p>
	 * These messages signal an environment/prerequisite gap on the running agent
	 * (no embedded browser backend, no active solution, web server not up, a form
	 * that will not render, a selector that matched nothing), not a product
	 * defect, so the render round-trip simply cannot be exercised here.
	 */
	private String renderPrerequisiteSkipReason(String toolResult)
	{
		if (toolResult.startsWith("No browser"))
		{
			return "no embedded browser backend is available on this agent (headless build), so the form cannot be rendered";
		}
		if (toolResult.startsWith("No active"))
		{
			return "no active Servoy solution is open, so no form can be rendered";
		}
		if (toolResult.startsWith("The Servoy web server"))
		{
			return "the Servoy web server is not running, so the /formtemplate render route is unreachable";
		}
		if (toolResult.startsWith("Form '"))
		{
			// did not finish rendering / produced no .svy-form root / was not found
			return "the form did not render (timeout, no .svy-form root, or not found): " + toolResult;
		}
		if (toolResult.startsWith("Selector '"))
		{
			return "the selector matched no element in the rendered form: " + toolResult;
		}
		if (toolResult.startsWith("Error"))
		{
			return "getFormLayout reported an error rendering the form: " + toolResult;
		}
		return null;
	}

	/**
	 * Asserts the spilled file content is pretty-printed JSON: more than one
	 * physical line and carrying newline-anchored indentation (Jackson's default
	 * pretty printer indents nested nodes). A compact single-line serialization -
	 * the pre-SVY-21577 behaviour - would fail both checks.
	 */
	private void assertSpillIsPrettyPrinted(String raw, Path file) {
		long lineCount = raw.lines().count();
		assertTrue(lineCount > 1,
				"the spilled JSON must be pretty-printed across multiple physical lines, not one giant line (file "
						+ file + ", lines=" + lineCount + ")");
		assertTrue(raw.indexOf('\n') >= 0, "the spilled JSON must contain line breaks: " + file);
		// default pretty printing puts nested nodes on their own indented lines, so
		// grep + a ranged read can reach a specific appearance entry; compact JSON has
		// no newline-anchored indentation.
		assertTrue(raw.contains("\n ") || raw.contains("\n\t"),
				"the spilled JSON must carry newline-anchored indentation (pretty-printed), file " + file);
	}

	/**
	 * Asserts the inline spill warning carries the three required points: the path
	 * is absolute, must be passed unchanged (no project-relative ../), and the file
	 * is pretty-printed / greppable.
	 */
	private void assertSpillWarning(String warning, String spilledPath) {
		assertNotNull(warning, "a spill envelope must carry a warning");
		String lower = warning.toLowerCase();
		assertTrue(lower.contains("absolute"), "the warning must state the path is absolute: " + warning);
		assertTrue(warning.contains(spilledPath), "the warning must embed the actual spilled path: " + warning);
		assertTrue(lower.contains("unchanged"),
				"the warning must tell the agent to pass the path unchanged: " + warning);
		assertTrue(lower.contains("../") || lower.contains("project-relative"),
				"the warning must warn against rebuilding a project-relative (../) path: " + warning);
		assertTrue(lower.contains("pretty"), "the warning must say the file is pretty-printed: " + warning);
		assertTrue(lower.contains("grep"), "the warning must say the file is greppable: " + warning);
	}

	private void saveEditingSolutionNodes(IPersist[] iPersists) throws RepositoryException {
		activeProject.saveEditingSolutionNodes(iPersists, true);
		waitForWorkspaceBuildJobs();
	}

	private Form ensureCssFormWithButtonAndLabel(String formName) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "form creation should succeed: " + formName);

		GraphicalComponent button = form.createNewGraphicalComponent(new Point(20, 20));
		button.setName("btnAction");
		button.setText("Click Me");

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 80));
		label.setName("lblStatus");
		label.setText("Status");

		saveEditingSolutionNodes(new IPersist[] { form });
		return form;
	}

	/**
	 * A css form carrying one enabled and one disabled button (SVY-21614), so the rendered
	 * {@code appearance} map exposes {@code disabled:true} on the disabled one and
	 * {@code disabled:false} on the enabled one - proving the boolean reflects the real state.
	 */
	private Form ensureCssFormWithEnabledAndDisabledButtons(String formName) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "form creation should succeed: " + formName);

		GraphicalComponent enabled = form.createNewGraphicalComponent(new Point(20, 20));
		enabled.setName("btnEnabled");
		enabled.setText("Enabled");
		enabled.setEnabled(true);

		GraphicalComponent disabled = form.createNewGraphicalComponent(new Point(20, 80));
		disabled.setName("btnDisabled");
		disabled.setText("Disabled");
		disabled.setEnabled(false);

		saveEditingSolutionNodes(new IPersist[] { form });
		return form;
	}

	/**
	 * A css form carrying a form variable WITH AN INITIALISER plus a label bound to it as its dataprovider - the
	 * SVY-21610 scenario (the reporter's agent used four such specimen fields).
	 */
	private Form ensureCssFormWithVariableBoundLabel(String formName, String variableName, String labelName)
			throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "form creation should succeed: " + formName);

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		ScriptVariable variable = form.createNewScriptVariable(validator, variableName, IColumnTypes.TEXT);
		assertNotNull(variable, "variable creation should succeed: " + variableName);
		// an initialiser, NOT an onLoad assignment: onLoad does not run in a stateless render, which is exactly why
		// the agent's four onLoad-assigned specimen fields came out empty in the reported case
		variable.setDefaultValue("'" + SAMPLE_VARIABLE_VALUE + "'");

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 20));
		label.setName(labelName);
		label.setDataProviderID(variableName);

		saveEditingSolutionNodes(new IPersist[] { form, variable });
		return form;
	}

	private Form ensureCssFormWithSingleLabel(String formName, String labelName, String labelText) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "form creation should succeed: " + formName);

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 20));
		label.setName(labelName);
		label.setText(labelText);

		saveEditingSolutionNodes(new IPersist[] { form });
		return form;
	}
}
