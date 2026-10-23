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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.cypress.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.AbstractTitaniumIntegrationTestCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.Solution;

/**
 * Integration tests for the behaviour cases that {@code generateFormSpec} emits (SVY-21517): the model is built from the real form, so the
 * generated {@code .spec.cy.js} must contain the trigger of the bound handler, the recognised on-form assertion and the labelled off-form
 * comment. Needs the active solution, the form persists and the script methods, so it runs as a JUnit Plug-in test. The pure parts are covered by
 * {@code HandlerEffectRecognizerTest} and {@code FormSpecModelBuilderTest} (plainjunit).
 */
public class FormSpecBehaviourIntegrationTest extends AbstractTitaniumIntegrationTestCopy {

	private static final String TEST_SOLUTION = "test_specbehaviour_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String BEHAVIOUR_FORM = "specBehaviourForm";
	private static final String UNKNOWN_FORM = "specBehaviourUnknownForm_xyz";

	private static boolean seeded;

	private ServoyTestingServer testingServer;
	private FormSpecGenerator specGenerator;
	private ServoyProject activeProject;

	public FormSpecBehaviourIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
		seeded = false;
	}

	@BeforeEach
	public void setUp() throws Exception {
		testingServer = new ServoyTestingServer();
		specGenerator = new FormSpecGenerator();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");

		if (!seeded) {
			seedBehaviourForm();
			seeded = true;
		}
		deleteSpecFiles(BEHAVIOUR_FORM);
		deleteSpecFiles(UNKNOWN_FORM);
	}

	@SuppressWarnings("deprecation")
	private void seedBehaviourForm() throws Exception {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		Solution solution = activeProject.getEditingSolution();
		IValidateName validator = model.getNameValidator();

		new ServoyArtifactCreationService().createForm(BEHAVIOUR_FORM, "css", 640, 480, null, null, null);
		Form form = solution.getForm(BEHAVIOUR_FORM);
		assertNotNull(form, "Form creation should succeed: " + BEHAVIOUR_FORM);

		ScriptMethod hide = form.createNewScriptMethod(validator, "hide");
		hide.setDeclaration("function hide(event) {\n\telements.lbl.visible = false;\n\tscopes.x.y();\n}\n");

		GraphicalComponent button = form.createNewGraphicalComponent(new Point(10, 10));
		button.setName("btn");
		button.setText("btn");
		button.setOnActionMethodID(hide.getUUID().toString());

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(10, 60));
		label.setName("lbl");
		label.setText("lbl");

		GraphicalComponent secret = form.createNewGraphicalComponent(new Point(10, 110));
		secret.setName("secret");
		secret.setText("secret");
		secret.setVisible(false);
		secret.setOnActionMethodID(hide.getUUID().toString());

		activeProject.saveEditingSolutionNodes(new IPersist[] { form }, true);
		waitForWorkspaceBuildJobs();
		pumpEventsUntil(10_000, () -> assertNotNull(activeProject.getEditingFlattenedSolution().getForm(BEHAVIOUR_FORM),
				"Seeded form must resolve in the flattened solution"));
	}

	private String generateAndRead(String formName) throws Exception {
		String result = testingServer.generateFormSpec(formName);
		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		Path cySpec = specGenerator.getSpecFilePath(formName, TEST_SOLUTION);
		assertNotNull(cySpec, "Cypress spec path should not be null");
		assertTrue(Files.exists(cySpec), "Cypress spec file should exist: " + result);
		return Files.readString(cySpec, StandardCharsets.UTF_8);
	}

	private void deleteSpecFiles(String formName) {
		try {
			Path cySpec = specGenerator.getSpecFilePath(formName, TEST_SOLUTION);
			if (cySpec != null)
				Files.deleteIfExists(cySpec);
			Path setupSpec = specGenerator.getSetupFilePath(formName, TEST_SOLUTION);
			if (setupSpec != null)
				Files.deleteIfExists(setupSpec);
		} catch (Exception e) {
			fail("Cannot delete the spec files of " + formName + ": " + e.getMessage());
		}
	}

	@Test
	public void testGenerateFormSpec_emitsTriggerOnFormEffectAndOffFormComment() throws Exception {
		List<String> lines = generateAndRead(BEHAVIOUR_FORM).lines().toList();

		assertTrue(lines.contains("    cy.get('[data-cy=\"" + BEHAVIOUR_FORM + ".btn\"]').click();"), "Missing click on the button: " + lines);
		assertTrue(lines.contains("    cy.get('[data-cy=\"" + BEHAVIOUR_FORM + ".lbl\"]').should('not.be.visible');"),
				"Missing on-form visibility assertion: " + lines);
		assertTrue(lines.contains("    // OFF-FORM (not asserted): scopes.x.y()"), "Missing labelled off-form comment: " + lines);
	}

	@Test
	public void testGenerateFormSpec_caseIsOrderedTriggerEffectOffFormOverlay() throws Exception {
		List<String> lines = generateAndRead(BEHAVIOUR_FORM).lines().toList();

		int title = lines.indexOf("  it('btn: onActionMethodID (hide)', () => {");
		assertTrue(title >= 0, "Missing case title: " + lines);
		assertEquals(List.of("    cy.get('[data-cy=\"" + BEHAVIOUR_FORM + ".btn\"]').click();",
				"    cy.get('[data-cy=\"" + BEHAVIOUR_FORM + ".lbl\"]').should('not.be.visible');",
				"    // OFF-FORM (not asserted): scopes.x.y()", "    cy.get('.svy-error, .error-overlay').should('not.exist');", "  });"),
				lines.subList(title + 1, title + 6));
		assertEquals("  // btn.onActionMethodID -> forms." + BEHAVIOUR_FORM + ".hide", lines.get(title - 1));
	}

	@Test
	public void testGenerateFormSpec_resultReportsModelCounts() throws Exception {
		String result = testingServer.generateFormSpec(BEHAVIOUR_FORM);

		String firstLine = result.lines().findFirst().orElse("");
		assertTrue(firstLine.matches("Created: .*" + TEST_SOLUTION + "\\." + BEHAVIOUR_FORM + "\\.spec\\.cy\\.js \\(1 element visibility assertions, 2 handler cases\\)"),
				"Model path result must report visibility assertions and handler cases: " + result);
	}

	@Test
	public void testGenerateFormSpec_modelPathKeepsStructuralAssertions() throws Exception {
		String content = generateAndRead(BEHAVIOUR_FORM);
		Path setupPath = specGenerator.getSetupFilePath(BEHAVIOUR_FORM, TEST_SOLUTION);

		assertTrue(content.contains("data-cy"), "Cypress spec must use data-cy selectors");
		assertTrue(content.contains("beforeEach("), "Cypress spec must use beforeEach");
		assertTrue(content.contains("cy.visit("), "Cypress spec must use cy.visit");
		assertTrue(content.contains("svy_testmode=true"), "Cypress spec must include svy_testmode=true in URL");
		assertTrue(Files.readString(setupPath, StandardCharsets.UTF_8).contains("function spec_setUp()"), "Setup must have spec_setUp");
	}

	@Test
	public void testGenerateFormSpec_hiddenElementIsSkippedAndNeverSelected() throws Exception {
		String content = generateAndRead(BEHAVIOUR_FORM);
		List<String> lines = content.lines().toList();

		assertTrue(lines.contains("  it.skip('secret: onActionMethodID (element is hidden by design)', () => {});"),
				"A hidden element's handler must be a skipped case: " + lines);
		assertFalse(content.contains(BEHAVIOUR_FORM + ".secret\"]"), "A hidden element must not be selected at all: " + content);
	}

	@Test
	public void testGenerateFormSpec_visibleElementIsStillAssertedAndClicked() throws Exception {
		List<String> lines = generateAndRead(BEHAVIOUR_FORM).lines().toList();

		assertTrue(lines.contains("    cy.get('[data-cy=\"" + BEHAVIOUR_FORM + ".btn\"]').should('be.visible');"), "Missing baseline assertion: " + lines);
		assertTrue(lines.contains("    cy.get('[data-cy=\"" + BEHAVIOUR_FORM + ".btn\"]').click();"), "Missing click: " + lines);
	}

	@Test
	public void testGenerateFormSpec_neverEmitsStyleProperties() throws Exception {
		String content = generateAndRead(BEHAVIOUR_FORM);

		assertFalse(content.matches("(?s).*(bgcolor|fgcolor|font|border).*"), "Spec must not mention style properties: " + content);
	}

	@Test
	public void testGenerateFormSpec_secondCallDoesNotOverwrite() throws Exception {
		String first = generateAndRead(BEHAVIOUR_FORM);

		String second = testingServer.generateFormSpec(BEHAVIOUR_FORM);

		assertTrue(second.startsWith("Spec files already exist"), "Second call must not regenerate: " + second);
		assertEquals(first, Files.readString(specGenerator.getSpecFilePath(BEHAVIOUR_FORM, TEST_SOLUTION), StandardCharsets.UTF_8));
	}

	@Test
	public void testGenerateFormSpec_unknownFormFallsBackToLegacyPath() {
		String result = testingServer.generateFormSpec(UNKNOWN_FORM);

		assertEquals("Error: Form file not found: forms/" + UNKNOWN_FORM + ".frm", result);
	}
}
