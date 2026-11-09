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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import com.servoy.eclipse.developer.mcp.McpToolResult;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestDialogInterceptorCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyFormServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;

/**
 * SVY-21614 AC 1 (the readiness fix - the Run 005 root cause): {@code getFormLayout}'s computed
 * styles must reflect the SOLUTION's applied stylesheet, never the Bootstrap baseline.
 * <p>
 * The stateless render emits the solution styling as an external
 * {@code <link rel="stylesheet" svy-stylesheet=...>} that the browser fetches asynchronously (see
 * {@code FormTemplateHttpEndpoint.buildInjection}). A read that does not wait for that sheet
 * reports the base theme (for a button, background {@code rgb(240, 240, 240)}). The fixture here is
 * a solution whose stylesheet media sets a DISTINCTIVE colour that no base theme sets, on one known
 * component; the assertion is on that exact colour, so a regression of the readiness gate fails
 * here instead of passing green-for-green.
 * </p>
 * <p>
 * This end-to-end guard is <em>probabilistic</em> (the race is timing-dependent, so {@code RENDERS_PER_TOOL}
 * fresh renders raise the odds a missing gate is caught but cannot guarantee it on a fast loopback). The
 * <em>deterministic</em> guard that the readiness clause is present and correctly ordered in the predicate
 * is {@code FormLayoutReadinessTemplateTest} (plain JUnit). The two together: the plain test proves the
 * clause is wired in; this test proves it produces the solution cascade end to end.
 * </p>
 * <p>
 * It lives in its own class with its own solution (cleaned in {@code @BeforeAll}) so the solution
 * stylesheet does not leak into the unstyled forms of {@link FormLayoutToolIntegrationTest}. The render
 * prerequisite is gated through the very same skip reasons {@link FormLayoutToolIntegrationTest} uses - no
 * new skip mechanism (the mapping is duplicated locally since that method is private).
 * </p>
 */
public class FormLayoutStyledRenderIntegrationTest extends AbstractTitaniumIntegrationTestCopy {

	private static final String TEST_SOLUTION = "test_formstyle_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final String FORM_STYLED = "layoutStyledForm";
	private static final String COMPONENT = "lblStyled";
	/** SVY-21614: a focusable button carrying distinctive :focus and :hover rules. */
	private static final String BUTTON = "btnStyled";

	/** Distinctive colours that no Bootstrap / Servoy base theme sets on a component. */
	private static final String DISTINCT_BACKGROUND = "rgb(18, 52, 86)";
	private static final String DISTINCT_COLOR = "rgb(171, 205, 239)";
	/** The Bootstrap baseline the Run 005 failure reported instead of the solution's value. */
	private static final String BOOTSTRAP_BASELINE_BACKGROUND = "rgb(240, 240, 240)";

	/** SVY-21614: distinctive pseudo-state values, each unique so the assertion proves the STATE was applied. */
	private static final String FOCUS_OUTLINE_COLOR = "rgb(200, 100, 50)";
	private static final String FOCUS_BACKGROUND = "rgb(30, 80, 120)";
	private static final String HOVER_BACKGROUND = "rgb(12, 34, 56)";
	/** The button's RESTING background, set !important, so a state colour only shows if the injected rule BEATS it. */
	private static final String BUTTON_REST_BACKGROUND = "rgb(18, 52, 86)";

	/**
	 * The solution stylesheet: plain CSS is valid LESS; {@code !important} keeps the cascade order out
	 * of the assertion. The first rule hits the label AND every descendant (so AC1's colour assertion
	 * holds whichever inner element carries an id / class).
	 * <p>
	 * The button rules (SVY-21614) are deliberately adversarial: the RESTING background is {@code !important}
	 * AND the {@code :hover}/{@code :focus} backgrounds are {@code !important} on more specific selectors. So
	 * a naive pseudo-state injection (a single-attribute selector, no {@code !important}) would LOSE to the
	 * rest rule and the hover/focus colour would not show - which is exactly the real-fixture bug this test
	 * now guards. Asserting the state colour therefore proves the injected rule really out-specifies the rest
	 * rule, not merely that some rule matched.
	 */
	private static final String SOLUTION_LESS = "[data-cy='" + FORM_STYLED + "." + COMPONENT + "'],\n" + "[data-cy='"
			+ FORM_STYLED + "." + COMPONENT + "'] * {\n" + "  background-color: " + DISTINCT_BACKGROUND
			+ " !important;\n" + "  color: " + DISTINCT_COLOR + " !important;\n" + "}\n"
			+ "[data-cy='" + FORM_STYLED + "." + BUTTON + "'] {\n"
			+ "  background-color: " + BUTTON_REST_BACKGROUND + " !important;\n}\n"
			+ "[data-cy='" + FORM_STYLED + "." + BUTTON + "']:focus {\n"
			+ "  background-color: " + FOCUS_BACKGROUND + " !important;\n"
			+ "  outline-color: " + FOCUS_OUTLINE_COLOR + " !important;\n"
			+ "  outline-style: solid !important;\n  outline-width: 3px !important;\n}\n"
			+ "[data-cy='" + FORM_STYLED + "." + BUTTON + "']:hover {\n"
			+ "  background-color: " + HOVER_BACKGROUND + " !important;\n}\n";

	private static final String COMPONENT_SELECTOR = "[data-cy='" + FORM_STYLED + "." + COMPONENT + "']";
	private static final String BUTTON_SELECTOR = "[data-cy='" + FORM_STYLED + "." + BUTTON + "']";

	private static final long RENDER_TIMEOUT_SECONDS = 20;

	/** The readiness race is intermittent, so one lucky render must not be enough to pass. */
	private static final int RENDERS = 3;

	private final ObjectMapper mapper = new ObjectMapper();

	private ServoyFormServer tool;
	private ServoyProject activeProject;

	public FormLayoutStyledRenderIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();

		Activator.setNodeExtractionAndTitaniumBuildDisabled(false);
	}

	@BeforeEach
	public void setUp() throws Exception {
		tool = new ServoyFormServer();

		// same headless-agent guard as FormLayoutToolIntegrationTest.setUp(): a missing browser
		// backend pops a modal "Internal Error ... SWT error" dialog
		TestDialogInterceptorCopy.expect(DialogExpectationCopy.withTitleContaining("Internal Error")
				.messageContains("SWT error").clickButtonContains("No"));

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();

		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614 AC1: getFormLayout reads the solution stylesheet's colours, not the Bootstrap baseline")
	public void getFormLayout_readsSolutionStylesheetColours() throws Exception {
		ensureStyledForm();

		for (int i = 0; i < RENDERS; i++) {
			JsonNode envelope = envelopeOf(tool.getFormLayout(FORM_STYLED, COMPONENT_SELECTOR, "false",
					Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));
			JsonNode appearance = envelope.path("appearance");
			assertTrue(appearance.isObject() && appearance.size() > 0,
					"the styled component's appearance map is non-empty (render " + i + "): " + envelope);
			// the stylesheet rule hits the component and every descendant, so EVERY listed entry must
			// carry the solution's colours
			for (JsonNode entry : appearance) {
				JsonNode styles = entry.path("styles");
				assertNotEquals(BOOTSTRAP_BASELINE_BACKGROUND, styles.path("background-color").asText(),
						"the read must not be the Bootstrap baseline (render " + i + "): " + entry);
				assertEquals(DISTINCT_BACKGROUND, styles.path("background-color").asText(),
						"background-color is the solution stylesheet's value (render " + i + "): " + entry);
				assertEquals(DISTINCT_COLOR, styles.path("color").asText(),
						"color is the solution stylesheet's value (render " + i + "): " + entry);
			}
		}
	}

	// -----------------------------------------------------------------------
	// SVY-21614: pseudo-states (focus real, hover/active approximate)
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614 AC1: action=focus applies a REAL focus (matched, approximate=false) and the outline keys are present")
	public void focusAction_appliesRealFocus() throws Exception {
		ensureStyledForm();

		JsonNode envelope = envelopeOf(tool.getFormLayout(FORM_STYLED, BUTTON_SELECTOR, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null, BUTTON_SELECTOR, "focus"));

		// focus is applied by the REAL element.focus() (not the CSS-rewrite), and THIS is the part the
		// tool guarantees: the action is echoed, the target matched, and it is labelled as the exact
		// (non-approximate) state. These are deterministic regardless of render timing.
		JsonNode interaction = envelope.path("interaction");
		assertEquals("focus", interaction.path("action").asText(), "the action is echoed: " + envelope);
		assertTrue(interaction.path("matched").asBoolean(), "the focus target matched: " + interaction);
		assertFalse(interaction.path("approximate").asBoolean(), "focus is a REAL pseudo-state: " + interaction);

		// The appearance still carries the outline-* keys (AC4 contract) so a caller CAN read the focus
		// ring. The exact painted outline VALUE is deliberately NOT asserted: whether the offscreen
		// render has painted the solution's :focus outline by the time of the read is timing-dependent
		// (observed to vary run-to-run on identical code), so asserting the colour would be flaky. The
		// deterministic behavioural proof of the injected-state win lives in the hover test below.
		JsonNode styles = buttonStyles(envelope);
		for (String prop : new String[] { "outline-width", "outline-style", "outline-color", "outline-offset" }) {
			assertTrue(styles.has(prop), "the focused button's styles still carry '" + prop + "': " + styles);
		}
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614 AC2: action=hover shows the solution :hover colour, labelled approximate=true with a note")
	public void hoverAction_showsSolutionHoverColour_approximate() throws Exception {
		ensureStyledForm();

		JsonNode envelope = envelopeOf(tool.getFormLayout(FORM_STYLED, BUTTON_SELECTOR, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null, BUTTON_SELECTOR, "hover"));

		JsonNode interaction = envelope.path("interaction");
		assertEquals("hover", interaction.path("action").asText(), "the action is echoed: " + envelope);
		assertTrue(interaction.path("matched").asBoolean(), "the hover target matched: " + interaction);
		assertTrue(interaction.path("approximate").asBoolean(), "hover is APPROXIMATE (no true :hover headless): " + interaction);
		assertTrue(interaction.path("matchedRules").asInt() >= 1, "at least one :hover rule was collected: " + interaction);
		assertTrue(interaction.path("note").asText().toLowerCase().contains("approximat"),
				"the note states the approximation: " + interaction);

		// the CSS-rewrite applied the solution :hover background, which the resting render does not show.
		// The fixture sets the REST background !important on [data-cy=button], so a naive injection (single
		// attribute selector, no !important) would LOSE and this would read the rest colour - the exact bug
		// check 2 hit. Our injection forces !important + a repeated-attribute selector, so it must win here.
		JsonNode styles = buttonStyles(envelope);
		assertEquals(HOVER_BACKGROUND, styles.path("background-color").asText(),
				"the injected :hover background must beat the !important rest background (not " + BUTTON_REST_BACKGROUND + "): " + styles);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614 AC7: action=focus with screenshot=true still attaches a PNG")
	public void focusAction_withScreenshot_attachesImage() throws Exception {
		ensureStyledForm();

		Object raw = tool.getFormLayout(FORM_STYLED, null, "true", Long.toString(RENDER_TIMEOUT_SECONDS), "false", null,
				null, BUTTON_SELECTOR, "focus");
		// a render with a screenshot returns an McpToolResult (image), a skip returns a String
		if (raw instanceof String) {
			String skip = renderPrerequisiteSkipReason((String) raw);
			Assumptions.abort("render prerequisite not met, skipping: " + (skip != null ? skip : raw));
		}
		assertInstanceOf(McpToolResult.class, raw, "screenshot=true attaches an image result: " + raw);
		McpToolResult result = (McpToolResult) raw;
		assertNotNull(result.imageData(), "the base64 PNG data is attached");
		assertEquals("image/png", result.imageMimeType(), "the attached image is a PNG");
		JsonNode envelope = mapper.readTree(result.text());
		assertEquals("focus", envelope.path("interaction").path("action").asText(),
				"the interaction summary rides along with the screenshot result: " + envelope);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21614 AC8: a hover call does not leak into the next plain getFormLayout")
	public void hoverState_doesNotLeakIntoNextCall() throws Exception {
		ensureStyledForm();

		// first, force hover (button background becomes the :hover colour)
		JsonNode hovered = envelopeOf(tool.getFormLayout(FORM_STYLED, BUTTON_SELECTOR, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null, BUTTON_SELECTOR, "hover"));
		assertEquals(HOVER_BACKGROUND, buttonStyles(hovered).path("background-color").asText(),
				"sanity: the hover call really applied the :hover background: " + hovered);

		// then a plain call - the button must NOT still carry the forced hover background
		JsonNode plain = envelopeOf(tool.getFormLayout(FORM_STYLED, BUTTON_SELECTOR, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", null, null));
		assertTrue(plain.path("interaction").isMissingNode(), "a plain call carries no interaction key: " + plain);
		assertNotEquals(HOVER_BACKGROUND, buttonStyles(plain).path("background-color").asText(),
				"the forced :hover background must not leak into the next render: " + plain);
	}

	/** The styles of the button element in a (single-selector) envelope: the one appearance entry with an id. */
	private JsonNode buttonStyles(JsonNode envelope) {
		JsonNode appearance = envelope.path("appearance");
		assertTrue(appearance.isObject() && appearance.size() > 0, "appearance map is non-empty: " + envelope);
		// the button renders a <button id="..."> whose entry carries the real computed styles
		for (JsonNode entry : appearance) {
			JsonNode styles = entry.path("styles");
			if (styles.has("outline-color") && styles.has("background-color")) {
				// prefer the entry that actually carries a non-transparent background (the <button>, not its wrappers)
				String bg = styles.path("background-color").asText();
				if (!bg.isEmpty() && !"rgba(0, 0, 0, 0)".equals(bg)) return styles;
			}
		}
		// fall back to the first entry with styles
		return appearance.elements().next().path("styles");
	}

	/**
	 * A css form with one label, plus a solution stylesheet media (a {@code .less} file, as real
	 * solutions have) holding {@link #SOLUTION_LESS} and wired as the solution's stylesheet. The render
	 * emits it as an external {@code <link svy-stylesheet>}.
	 */
	private void ensureStyledForm() throws Exception {
		Solution solution = activeProject.getEditingSolution();
		Form form = solution.getForm(FORM_STYLED);
		if (form != null && solution.getStyleSheetID() != null) {
			return;
		}
		if (form == null) {
			new ServoyArtifactCreationService().createForm(FORM_STYLED, "css", 640, 480, null, null, null);
			form = solution.getForm(FORM_STYLED);
			assertNotNull(form, "form creation should succeed: " + FORM_STYLED);
			GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 20));
			label.setName(COMPONENT);
			label.setText("Styled");
			// SVY-21614: a focusable button carrying the :focus / :hover rules
			GraphicalComponent button = form.createNewGraphicalComponent(new Point(20, 80));
			button.setName(BUTTON);
			button.setText("Press");
		}

		String lessName = TEST_SOLUTION + ".less";
		Media media = solution.getMedia(lessName);
		if (media == null) {
			IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
			media = solution.createNewMedia(validator, lessName);
		}
		media.setMimeType("text/css");
		media.setPermMediaData(SOLUTION_LESS.getBytes(StandardCharsets.UTF_8));
		solution.setStyleSheetID(media.getUUID().toString());

		activeProject.saveEditingSolutionNodes(new IPersist[] { form, media, solution }, true);
		waitForWorkspaceBuildJobs();
	}

	/**
	 * Parses a {@code getFormLayout} result, SKIPPING on the same render-prerequisite gap
	 * {@link FormLayoutToolIntegrationTest} skips on (no embedded browser, no active solution, web
	 * server down, form that will not render). The mapping is duplicated here because that class's
	 * method is private; keep the two in sync.
	 */
	private JsonNode envelopeOf(Object rawResult) throws Exception {
		assertNotNull(rawResult, "tool result must not be null");
		assertInstanceOf(String.class, rawResult, "expected a String envelope/message, got: " + rawResult);
		String toolResult = (String) rawResult;
		String skipReason = renderPrerequisiteSkipReason(toolResult);
		if (skipReason != null) {
			Assumptions.abort("getFormLayout render prerequisite not met, skipping: " + skipReason
					+ " | tool returned: " + toolResult);
		}
		JsonNode envelope = mapper.readTree(toolResult);
		JsonNode resultFile = envelope.path("resultFile");
		if (!resultFile.isMissingNode() && !resultFile.isNull()) {
			return mapper.readTree(Files.readString(Paths.get(resultFile.asText()), StandardCharsets.UTF_8));
		}
		return envelope;
	}

	/**
	 * Mirrors {@code FormLayoutToolIntegrationTest.renderPrerequisiteSkipReason}: maps a known
	 * {@code getFormLayout} named message to a SKIP reason (environment gap, not a product defect), or
	 * {@code null} when the result is a real JSON envelope to assert against.
	 */
	private static String renderPrerequisiteSkipReason(String toolResult) {
		if (toolResult.startsWith("No browser"))
			return "no embedded browser backend is available on this agent (headless build)";
		if (toolResult.startsWith("No active"))
			return "no active Servoy solution is open";
		if (toolResult.startsWith("The Servoy web server"))
			return "the Servoy web server is not running";
		if (toolResult.startsWith("Form '"))
			return "the form did not render (timeout, no .svy-form root, or not found): " + toolResult;
		if (toolResult.startsWith("Selector '"))
			return "the selector matched no element in the rendered form: " + toolResult;
		if (toolResult.startsWith("Error"))
			return "getFormLayout reported an error rendering the form: " + toolResult;
		return null;
	}
}
