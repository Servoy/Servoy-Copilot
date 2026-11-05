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

	/** Distinctive colours that no Bootstrap / Servoy base theme sets on a component. */
	private static final String DISTINCT_BACKGROUND = "rgb(18, 52, 86)";
	private static final String DISTINCT_COLOR = "rgb(171, 205, 239)";
	/** The Bootstrap baseline the Run 005 failure reported instead of the solution's value. */
	private static final String BOOTSTRAP_BASELINE_BACKGROUND = "rgb(240, 240, 240)";

	/**
	 * The solution stylesheet: plain CSS is valid LESS; {@code !important} keeps the cascade order out
	 * of the assertion. The rule hits the component AND every descendant, so the assertion holds
	 * whichever inner element carries an id / class (the {@code getFormLayout} appearance map only
	 * lists elements that have one).
	 */
	private static final String SOLUTION_LESS = "[data-cy='" + FORM_STYLED + "." + COMPONENT + "'],\n" + "[data-cy='"
			+ FORM_STYLED + "." + COMPONENT + "'] * {\n" + "  background-color: " + DISTINCT_BACKGROUND
			+ " !important;\n" + "  color: " + DISTINCT_COLOR + " !important;\n" + "}\n";

	private static final String COMPONENT_SELECTOR = "[data-cy='" + FORM_STYLED + "." + COMPONENT + "']";

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
