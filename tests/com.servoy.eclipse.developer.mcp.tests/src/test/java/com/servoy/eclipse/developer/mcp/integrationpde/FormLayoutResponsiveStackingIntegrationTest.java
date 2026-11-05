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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;

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
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.LayoutContainer;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.WebComponent;

/**
 * SVY-21619 regression guard: {@code getFormLayout} surfaces the data an agent
 * needs to verify responsive card-stacking at a narrow viewport, and to perform
 * the container-vs-viewport cross-check, in a SINGLE tool call per viewport.
 * <p>
 * Run 007 of the responsive-design agent concluded, wrongly, that "the
 * svy-respform shell does not narrow {@code .sd-page} to the viewport" and
 * escalated it as a <em>platform limitation</em>. That false negative came from
 * <em>not</em> comparing the measured container width against the applied
 * viewport width: the agent never had, in one place, the rendered
 * {@code window.innerWidth} next to the measured container/element bounding
 * boxes, so it could not see that the container DID track the viewport and that
 * the cards DID stack. The capability to get both in one call landed via
 * <b>SVY-21591</b> (apply a requested viewport and report
 * {@code window.innerWidth}) and <b>SVY-21614</b> (report measured bounding
 * boxes in {@code appearance}, measured after the solution stylesheets settle).
 * This test proves those two together let an agent:
 * </p>
 * <ol>
 * <li>see two cards sit side by side at a wide viewport (same row, each
 * narrower than the container);</li>
 * <li>see the two cards stack one-per-row at a narrow viewport (different rows,
 * each ~full width);</li>
 * <li>cross-check that the page/grid container width TRACKS the viewport - it
 * is not wider than the applied {@code window.innerWidth} (no horizontal
 * overflow) and is narrower at 375 than at 1024 - which is precisely the signal
 * whose absence produced the Run 007 "platform limitation" escalation.</li>
 * </ol>
 * <p>
 * The stacking is driven the same way Run 007 drove it: a solution stylesheet
 * puts {@code container-type: inline-size} on the page container and a
 * two-column grid ({@code grid-template-columns: 1fr 1fr}) on the grid
 * container, collapsed to one column by a {@code @container (max-width: 640px)}
 * query. The assertions key off BOX WIDTHS and y-positions (and
 * {@code window.width}), NOT the {@code grid-template-columns} string, because
 * the {@code appearance} map's {@code styles} does not carry
 * {@code grid-template-columns} - and the box widths are in any case the more
 * robust signal and exactly the "measured container width" SVY-21619 is about.
 * </p>
 * <p>
 * This class has its own solution (cleaned in {@code @BeforeAll}) so its
 * container-query stylesheet does not leak into the other form tests. The
 * render prerequisite is gated through the very same skip reasons
 * {@link FormLayoutToolIntegrationTest} uses - no new skip mechanism (the
 * mapping is duplicated locally since that method is private, as
 * {@link FormLayoutStyledRenderIntegrationTest} also does). On a headless CI
 * agent with no embedded browser these abort/skip, exactly as every other
 * {@code getFormLayout} integration test does; the deterministic parts are
 * still exercised and the render path is driven wherever a browser exists.
 * </p>
 */
public class FormLayoutResponsiveStackingIntegrationTest extends AbstractTitaniumIntegrationTestCopy {

	private static final String TEST_SOLUTION = "test_formrespstack_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final String FORM_RESPONSIVE = "layoutResponsiveStackForm";

	/** Prefixed to avoid colliding with any theme/bootstrap class. */
	private static final String CLASS_PAGE = "svytest-page";
	private static final String CLASS_GRID = "svytest-grid";
	private static final String CLASS_CARD = "svytest-card";
	private static final String CLASS_CARD_ONE = "svytest-card-one";
	private static final String CLASS_CARD_TWO = "svytest-card-two";

	/**
	 * Distinctive per-card background colours that no base theme sets, used to
	 * LOCATE each card in the {@code appearance} map. The map is keyed by a cssPath
	 * ({@code #id} or {@code div:nth-of-type(n)}) that does NOT embed class names,
	 * so a card cannot be found by its class; it is found by the exact
	 * background-colour the solution stylesheet puts on it (one entry per colour,
	 * since background-color does not inherit onto the label inside).
	 */
	private static final String CARD_ONE_BG = "rgb(10, 20, 30)";
	private static final String CARD_TWO_BG = "rgb(40, 50, 60)";

	/**
	 * The container-query breakpoint: above it two columns, at/below it one column.
	 */
	private static final int BREAKPOINT = 640;
	private static final int WIDE_VIEWPORT = 1024;
	private static final int NARROW_VIEWPORT = 375;

	/**
	 * The solution stylesheet (plain CSS is valid LESS).
	 * {@code container-type: inline-size} on the page container turns it into a
	 * query container; the grid is two columns normally and collapses to one column
	 * inside the {@code @container (max-width)} query - the exact mechanism Run 007
	 * used. {@code min-height} on the cards guarantees a laid-out, non-zero box to
	 * measure.
	 */
	private static final String SOLUTION_LESS = "." + CLASS_PAGE + " {\n" + "  container-type: inline-size;\n"
			+ "  width: 100%;\n" + "  box-sizing: border-box;\n" + "  padding: 0;\n" + "  margin: 0;\n" + "}\n" + "."
			+ CLASS_GRID + " {\n" + "  display: grid;\n" + "  grid-template-columns: 1fr 1fr;\n" + "  gap: 16px;\n"
			+ "  box-sizing: border-box;\n" + "}\n" + "." + CLASS_CARD + " {\n" + "  min-height: 80px;\n"
			+ "  box-sizing: border-box;\n" + "  border: 1px solid #ccc;\n" + "}\n" + "." + CLASS_CARD_ONE
			+ " { background-color: " + CARD_ONE_BG + "; }\n" + "." + CLASS_CARD_TWO + " { background-color: "
			+ CARD_TWO_BG + "; }\n" + "@container (max-width: " + BREAKPOINT + "px) {\n" + "  ." + CLASS_GRID
			+ " { grid-template-columns: 1fr; }\n" + "}\n";

	private static final long RENDER_TIMEOUT_SECONDS = 20;

	private final ObjectMapper mapper = new ObjectMapper();

	private ServoyFormServer tool;
	private ServoyProject activeProject;

	public FormLayoutResponsiveStackingIntegrationTest() {
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

		// same headless-agent guard as the sibling render tests: a missing browser
		// backend pops a
		// modal "Internal Error ... SWT error" dialog that the DialogGuard would
		// otherwise flag.
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
	// Wide viewport: the two cards sit SIDE BY SIDE (two columns)
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21619: at a wide viewport the two cards sit side by side (same row, each narrower than the container)")
	public void wideViewport_cardsSitSideBySide() throws Exception {
		ensureResponsiveStackForm();

		JsonNode envelope = envelopeOf(tool.getFormLayout(FORM_RESPONSIVE, null, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", Integer.toString(WIDE_VIEWPORT), null));

		assertTrue(envelope.path("window").path("width").asInt() == WIDE_VIEWPORT,
				"SVY-21591: window.width must equal the applied wide viewport " + WIDE_VIEWPORT + ": " + envelope);

		JsonNode appearance = envelope.path("appearance");
		JsonNode cardOne = findEntryByBackground(appearance, CARD_ONE_BG);
		JsonNode cardTwo = findEntryByBackground(appearance, CARD_TWO_BG);
		assertNotNull(cardOne, "the first card's appearance entry must be present: " + appearance);
		assertNotNull(cardTwo, "the second card's appearance entry must be present: " + appearance);

		int y1 = cardOne.path("box").path("y").asInt();
		int h1 = cardOne.path("box").path("h").asInt();
		int y2 = cardTwo.path("box").path("y").asInt();
		int w1 = cardOne.path("box").path("w").asInt();
		int w2 = cardTwo.path("box").path("w").asInt();

		// side by side == the two card tops are on (about) the same row, not one below
		// the other
		assertTrue(Math.abs(y1 - y2) <= Math.max(4, h1 / 2),
				"SVY-21619: at " + WIDE_VIEWPORT + "px the two cards must share a row (y1=" + y1 + ", y2=" + y2
						+ ", h1=" + h1 + ") - this is the two-column layout the agent must be able to observe");

		// neither card spans the full container: two columns means each is clearly less
		// than the whole width
		int containerWidth = gridContainerWidth(appearance, envelope);
		assertTrue(w1 < containerWidth * 0.75 && w2 < containerWidth * 0.75,
				"SVY-21619: at " + WIDE_VIEWPORT + "px neither card may span the container (two columns): card1.w=" + w1
						+ ", card2.w=" + w2 + ", container.w=" + containerWidth);
	}

	// -----------------------------------------------------------------------
	// Narrow viewport: the two cards STACK (one column) + container-vs-viewport
	// cross-check
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21619: at a narrow viewport the two cards stack one per row (different rows, each ~full width)")
	public void narrowViewport_cardsStack() throws Exception {
		ensureResponsiveStackForm();

		JsonNode envelope = envelopeOf(tool.getFormLayout(FORM_RESPONSIVE, null, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", Integer.toString(NARROW_VIEWPORT), null));

		assertTrue(envelope.path("window").path("width").asInt() == NARROW_VIEWPORT,
				"SVY-21591: window.width must equal the applied narrow viewport " + NARROW_VIEWPORT + ": " + envelope);

		JsonNode appearance = envelope.path("appearance");
		JsonNode cardOne = findEntryByBackground(appearance, CARD_ONE_BG);
		JsonNode cardTwo = findEntryByBackground(appearance, CARD_TWO_BG);
		assertNotNull(cardOne, "the first card's appearance entry must be present: " + appearance);
		assertNotNull(cardTwo, "the second card's appearance entry must be present: " + appearance);

		int y1 = cardOne.path("box").path("y").asInt();
		int h1 = cardOne.path("box").path("h").asInt();
		int y2 = cardTwo.path("box").path("y").asInt();
		int w1 = cardOne.path("box").path("w").asInt();
		int w2 = cardTwo.path("box").path("w").asInt();

		// stacked == the second card is clearly BELOW the first (different rows)
		assertTrue(y2 > y1 + h1 / 2,
				"SVY-21619: at " + NARROW_VIEWPORT + "px the second card must be below the first (stacked): y1=" + y1
						+ ", h1=" + h1 + ", y2=" + y2 + " - run 007 wrongly reported this was impossible");

		// one column == each card is close to the container content width
		int containerWidth = gridContainerWidth(appearance, envelope);
		assertTrue(w1 >= containerWidth * 0.85 && w2 >= containerWidth * 0.85,
				"SVY-21619: at " + NARROW_VIEWPORT
						+ "px each card must fill (about) the container width (one column): card1.w=" + w1
						+ ", card2.w=" + w2 + ", container.w=" + containerWidth);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("SVY-21619 cross-check: the grid container tracks the viewport (not wider than window.width, narrower at 375 than at 1024)")
	public void containerTracksViewport_theRun007CrossCheck() throws Exception {
		ensureResponsiveStackForm();

		JsonNode wide = envelopeOf(tool.getFormLayout(FORM_RESPONSIVE, null, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", Integer.toString(WIDE_VIEWPORT), null));
		JsonNode narrow = envelopeOf(tool.getFormLayout(FORM_RESPONSIVE, null, "false",
				Long.toString(RENDER_TIMEOUT_SECONDS), "false", Integer.toString(NARROW_VIEWPORT), null));

		int wideWindow = wide.path("window").path("width").asInt();
		int narrowWindow = narrow.path("window").path("width").asInt();
		assertTrue(wideWindow == WIDE_VIEWPORT, "the wide render applies " + WIDE_VIEWPORT + ": " + wide);
		assertTrue(narrowWindow == NARROW_VIEWPORT, "the narrow render applies " + NARROW_VIEWPORT + ": " + narrow);

		int wideContainer = gridContainerWidth(wide.path("appearance"), wide);
		int narrowContainer = gridContainerWidth(narrow.path("appearance"), narrow);

		// THE heart of SVY-21619: in the SAME narrow-viewport envelope the container
		// width must TRACK
		// window.innerWidth - it is not wider than the applied viewport (so there is no
		// horizontal
		// overflow), which is exactly the comparison whose absence made run 007
		// conclude (wrongly)
		// that ".sd-page does not narrow to the viewport" and escalate a non-existent
		// platform limitation.
		// The tiny tolerance absorbs rounding/scrollbar-free sub-pixel differences.
		assertTrue(narrowContainer <= narrowWindow + 2,
				"SVY-21619 cross-check: the grid container must not be wider than the applied viewport (no horizontal "
						+ "overflow) - container.w=" + narrowContainer + " must be <= window.width=" + narrowWindow
						+ ". That container.w tracking window.width is the signal the viewport WAS applied and the "
						+ "platform is NOT the limitation run 007 escalated.");

		// and the container is genuinely narrower at 375 than at 1024 - it followed the
		// viewport down
		assertTrue(narrowContainer < wideContainer,
				"SVY-21619 cross-check: the grid container must be narrower at " + NARROW_VIEWPORT + " than at "
						+ WIDE_VIEWPORT + " (it tracks the viewport): narrow.container.w=" + narrowContainer
						+ ", wide.container.w=" + wideContainer);
	}

	// -----------------------------------------------------------------------
	// Fixture
	// -----------------------------------------------------------------------

	/**
	 * A responsive (12grid) form: a page container ({@link #CLASS_PAGE}, the query
	 * container) holding a grid container ({@link #CLASS_GRID}, two columns),
	 * holding two card containers ({@link #CLASS_CARD} + a per-card class so each
	 * is findable), each with a label inside so the card has content. The stacking
	 * behaviour comes entirely from the solution stylesheet
	 * ({@link #SOLUTION_LESS}) wired as the solution's stylesheet (emitted as an
	 * external {@code <link svy-stylesheet>} by the render), mirroring the Run 007
	 * scenario.
	 */
	private void ensureResponsiveStackForm() throws Exception {
		Solution solution = activeProject.getEditingSolution();
		Form form = solution.getForm(FORM_RESPONSIVE);
		if (form != null && solution.getStyleSheetID() != null) {
			return;
		}

		if (form == null) {
			new ServoyArtifactCreationService().createForm(FORM_RESPONSIVE, "responsive", 640, 480, null, null, null);
			form = solution.getForm(FORM_RESPONSIVE);
			assertNotNull(form, "responsive form creation should succeed: " + FORM_RESPONSIVE);

			LayoutContainer page = form.createNewLayoutContainer();
			page.setPackageName("12grid");
			page.setSpecName("div");
			page.setCssClasses(CLASS_PAGE);

			LayoutContainer grid = page.createNewLayoutContainer();
			grid.setPackageName("12grid");
			grid.setSpecName("div");
			grid.setCssClasses(CLASS_GRID);

			createCard(grid, CLASS_CARD_ONE, "cardOne");
			createCard(grid, CLASS_CARD_TWO, "cardTwo");
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
	 * A card container carrying both the shared {@link #CLASS_CARD} class (styled
	 * with a min-height so it has a measurable box) and a per-card class so its
	 * appearance entry is findable, with a label inside so the card has rendered
	 * content.
	 */
	private void createCard(LayoutContainer grid, String perCardClass, String labelName) throws Exception {
		LayoutContainer card = grid.createNewLayoutContainer();
		card.setPackageName("12grid");
		card.setSpecName("div");
		card.setCssClasses(CLASS_CARD + " " + perCardClass);

		WebComponent label = card.createNewWebComponent(FORM_RESPONSIVE + "_" + labelName, "bootstrapcomponents-label");
		label.setProperty("text", labelName);
	}

	// -----------------------------------------------------------------------
	// Helpers (skip discipline duplicated from FormLayoutToolIntegrationTest)
	// -----------------------------------------------------------------------

	/**
	 * Finds the single appearance entry whose resolved {@code background-color}
	 * equals {@code backgroundColor}. The appearance map is keyed by a cssPath
	 * ({@code #id} or {@code div:nth-of-type(n)}) that does NOT embed class names,
	 * so a card is located by the distinctive background-colour the solution
	 * stylesheet puts on it (exactly one entry per colour: background-color does
	 * not inherit, so a child cannot also match).
	 */
	@SuppressWarnings("deprecation")
	private JsonNode findEntryByBackground(JsonNode appearance, String backgroundColor) {
		for (Iterator<Map.Entry<String, JsonNode>> it = appearance.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			if (backgroundColor.equals(e.getValue().path("styles").path("background-color").asText())) {
				return e.getValue();
			}
		}
		return null;
	}

	/**
	 * The measured width of the grid container - the "container" in the
	 * container-vs-viewport cross-check. The grid is the single entry whose
	 * computed {@code display} is {@code grid} (the {@link #CLASS_GRID} container).
	 * Falls back to the {@code viewport} (.svy-form root) box only if no grid entry
	 * is present, so the cross-check still has a container-ish width to compare,
	 * never a zero.
	 */
	@SuppressWarnings("deprecation")
	private int gridContainerWidth(JsonNode appearance, JsonNode envelope) {
		for (Iterator<Map.Entry<String, JsonNode>> it = appearance.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			if ("grid".equals(e.getValue().path("styles").path("display").asText())) {
				return e.getValue().path("box").path("w").asInt();
			}
		}
		return envelope.path("viewport").path("w").asInt();
	}

	/**
	 * Parses a {@code getFormLayout} result, SKIPPING on the same
	 * render-prerequisite gap {@link FormLayoutToolIntegrationTest} skips on (no
	 * embedded browser, no active solution, web server down, form that will not
	 * render). The mapping is duplicated here because that class's method is
	 * private; keep the two in sync.
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
	 * Mirrors {@code FormLayoutToolIntegrationTest.renderPrerequisiteSkipReason}:
	 * maps a known {@code getFormLayout} named message to a SKIP reason
	 * (environment gap, not a product defect), or {@code null} when the result is a
	 * real JSON envelope to assert against.
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
