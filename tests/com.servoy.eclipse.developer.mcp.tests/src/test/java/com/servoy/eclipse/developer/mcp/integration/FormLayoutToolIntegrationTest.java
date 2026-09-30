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
package com.servoy.eclipse.developer.mcp.integration;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyFormServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.RepositoryException;

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
 * {@link AbstractIntegrationTest} base (dialog guard, titanium build, helpers).
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
public class FormLayoutToolIntegrationTest extends AbstractIntegrationTest {

	private static final String TEST_SOLUTION = "test_formlayout_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final String FORM_CSS = "layoutCssForm";
	private static final String FORM_OTHER = "layoutOtherForm";
	private static final String FORM_MISSING = "noSuchFormAtAll";

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
				tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS)));

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
				tool.getFormLayout(FORM_CSS, selector, "false", Long.toString(RENDER_TIMEOUT_SECONDS)));

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

		JsonNode first = envelopeOf(tool.getFormLayout(FORM_CSS, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS)));
		JsonNode second = envelopeOf(
				tool.getFormLayout(FORM_OTHER, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS)));

		assertEquals(FORM_CSS, first.path("form").asText(), "first call returns the first form");
		assertEquals(FORM_OTHER, second.path("form").asText(),
				"second call returns the SECOND form, not the stale first one");

		String secondHtml = second.path("html").asText();
		assertTrue(secondHtml.contains("lblOnlyHere"), "the second form's own element is present in its HTML");
		assertFalse(secondHtml.contains("btnAction"),
				"the previous form's button must not leak into the second form's DOM");
	}

	// -----------------------------------------------------------------------
	// Not-found / named-message paths (valid even without the render prerequisite)
	// -----------------------------------------------------------------------

	@Test
	@org.junit.jupiter.api.DisplayName("form not found returns the named actionable message")
	public void formNotFound_returnsNamedMessage() {
		Object result = tool.getFormLayout(FORM_MISSING, null, "false", Long.toString(RENDER_TIMEOUT_SECONDS));

		assertInstanceOf(String.class, result, "a named message is returned as a plain String");
		String message = (String) result;
		assertTrue(message.contains("was not found") && message.contains(FORM_MISSING),
				"missing form yields the named 'was not found' message: " + result);
	}

	@Test
	@org.junit.jupiter.api.DisplayName("blank form name returns an error, never a hang")
	public void blankFormName_returnsError() {
		Object result = tool.getFormLayout("   ", null, null, null);

		assertInstanceOf(String.class, result, "an error is returned as a plain String");
		assertTrue(((String) result).startsWith("Error"), "a blank form name yields an Error message: " + result);
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
		assertFalse(
				toolResult.startsWith("Error") || toolResult.startsWith("Form '") || toolResult.startsWith("No active")
						|| toolResult.startsWith("Selector '") || toolResult.startsWith("The Servoy web server"),
				"tool must return a render envelope, not a named message: " + toolResult);
		JsonNode envelope = mapper.readTree(toolResult);
		JsonNode resultFile = envelope.path("resultFile");
		if (!resultFile.isMissingNode() && !resultFile.isNull()) {
			// large form: the full html + appearance was spilled - read it back
			Path file = Paths.get(resultFile.asText());
			assertTrue(Files.exists(file), "the spilled resultFile must exist on disk: " + file);
			return mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
		}
		return envelope;
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
