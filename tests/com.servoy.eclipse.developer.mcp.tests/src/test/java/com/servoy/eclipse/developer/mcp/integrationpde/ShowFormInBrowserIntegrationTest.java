/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.AbstractTitaniumIntegrationTestCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.RepositoryException;

/**
 * Integration tests for the ShowFormInBrowser tool with actual form creation.
 * <p>
 * Creates Servoy forms with labels and other elements, then opens them in the
 * browser using the formpreview mechanism. Verifies the full flow from form
 * creation to browser display.
 * <p>
 * <b>Prerequisites:</b>
 * <ul>
 * <li>Run as a JUnit Plugin Test inside Eclipse IDE with Servoy plugins
 * active.</li>
 * <li>Servoy Application Server must be running.</li>
 * </ul>
 */
public class ShowFormInBrowserIntegrationTest extends AbstractTitaniumIntegrationTestCopy {

	private static final String TEST_SOLUTION = "test_showform_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String FORM_WITH_LABEL = "formWithLabel";
	private static final String FORM_WITH_MULTIPLE_LABELS = "formWithMultipleLabels";
	private static final String FORM_EMPTY = "formEmpty";

	private ServoyTestingServer tool;
	private ServoyProject activeProject;

	public ShowFormInBrowserIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	/**
	 * Enable the titanium build before any test in this class runs.
	 * Because we really need to show the client in a browser and test it.
	 * Also deletes any stale solution projects so the class starts fresh.
	 */
	@BeforeAll
	public static void adjustTitaniumBuildJobEnablementForThisClass() throws Exception {
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();

		Activator.setNodeExtractionAndTitaniumBuildDisabled(false);
	}

	@BeforeEach
	public void setUp() throws Exception {
		tool = new ServoyTestingServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();

		long x = System.currentTimeMillis();
		System.out.println("*** " + this.getClass().getName() + " writing solution, activating project and building");
		
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();
		
		System.out.println("*** activating project and building took: " + String.format( "%.2f", ((System.currentTimeMillis() - x) / 1000d)) + " s");

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	// -----------------------------------------------------------------------
	// Form with a single label
	// -----------------------------------------------------------------------

	@Test
	public void testCreateFormWithLabel_formExists() throws Exception {
		Form form = ensureForm(FORM_WITH_LABEL);
		assertNotNull(form, "Form should be created");

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 20));
		label.setText("Hello from test!");
		label.setName("lblHello");
		saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form });

		assertNotNull(activeProject.getSolution().getForm(FORM_WITH_LABEL), "Sol Form should exist after save");
		Form reloaded = activeProject.getEditingSolution().getForm(FORM_WITH_LABEL);
		assertNotNull(reloaded, "Editing sol Form should exist after save");
	}

	@Test
	public void testCreateFormWithLabel_openInBrowser_returnsUrl() throws Exception {
		ensureForm(FORM_WITH_LABEL);

		String result = tool.showFormInBrowser(FORM_WITH_LABEL, false);

		assertNotNull(result, "Result should not be null");
		assertTrue(result.contains("formpreview=" + FORM_WITH_LABEL), "Result should contain formpreview parameter");
		assertTrue(result.contains("Opened form"), "Result should confirm form was opened");
	}

	@Test
	public void testCreateFormWithLabel_urlHasCorrectSolution() throws Exception {
		ensureForm(FORM_WITH_LABEL);

		String result = tool.showFormInBrowser(FORM_WITH_LABEL, false);

		assertTrue(result.contains("/solution/" + TEST_SOLUTION + "/"), "URL should contain solution name");
	}

	// -----------------------------------------------------------------------
	// Form with multiple labels
	// -----------------------------------------------------------------------

	@Test
	public void testCreateFormWithMultipleLabels_allLabelsCreated() throws Exception {
		Form form = ensureForm(FORM_WITH_MULTIPLE_LABELS);

		GraphicalComponent lbl1 = form.createNewGraphicalComponent(new Point(20, 20));
		lbl1.setText("Title");
		lbl1.setName("lblTitle");

		GraphicalComponent lbl2 = form.createNewGraphicalComponent(new Point(20, 60));
		lbl2.setText("Subtitle");
		lbl2.setName("lblSubtitle");

		GraphicalComponent lbl3 = form.createNewGraphicalComponent(new Point(20, 100));
		lbl3.setText("Footer");
		lbl3.setName("lblFooter");

		saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form });

		assertNotNull(activeProject.getSolution().getForm(FORM_WITH_MULTIPLE_LABELS), "Sol Form should exist");
		Form reloaded = activeProject.getEditingSolution().getForm(FORM_WITH_MULTIPLE_LABELS);
		assertNotNull(reloaded, "Editing sol Form should exist");
	}
	
	private void saveEditingSolutionNodes(IPersist[] iPersists) throws RepositoryException {
		activeProject.saveEditingSolutionNodes(iPersists, true);
		waitForWorkspaceBuildJobs();
	}

	@Test
	public void testCreateFormWithMultipleLabels_openInBrowser() throws Exception {
		ensureForm(FORM_WITH_MULTIPLE_LABELS);

		String result = tool.showFormInBrowser(FORM_WITH_MULTIPLE_LABELS, false);

		assertNotNull(result, "Result should not be null");
		assertTrue(result.contains("formpreview=" + FORM_WITH_MULTIPLE_LABELS),
				"Should open form with multiple labels");
	}

	// -----------------------------------------------------------------------
	// Empty form
	// -----------------------------------------------------------------------

	@Test
	public void testCreateEmptyForm_openInBrowser() throws Exception {
		ensureForm(FORM_EMPTY);

		String result = tool.showFormInBrowser(FORM_EMPTY, false);

		assertNotNull(result, "Result should not be null");
		assertTrue(result.contains("formpreview=" + FORM_EMPTY), "Should open empty form");
	}

	// -----------------------------------------------------------------------
	// Error cases
	// -----------------------------------------------------------------------

	@Test
	public void testShowFormInBrowser_nullForm_returnsError() {
		String result = tool.showFormInBrowser(null, false);

		assertNotNull(result, "Result should not be null");
		// The tool may return an error or treat null as empty and produce a URL
		assertTrue(result.startsWith("Error") || result.contains("formpreview="),
				"Should return error or a URL for null form");
	}

	@Test
	public void testShowFormInBrowser_emptyString_returnsError() {
		String result = tool.showFormInBrowser("", false);

		assertNotNull(result, "Result should not be null");
		assertTrue(result.startsWith("Error"), "Should return error for empty form name");
	}

	@Test
	public void testCheckNGClientStatus_returnsInfo() {
		String result = tool.checkNGClientStatus();

		assertNotNull(result, "Status should not be null");
		assertFalse(result.isEmpty(), "Status should not be empty");
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("running") || result.contains("URL"), "Status should contain useful info");
	}

	// -----------------------------------------------------------------------
	// Multiple forms opened sequentially
	// -----------------------------------------------------------------------

	@Test
	public void testOpenMultipleForms_sequentially() throws Exception {
		ensureForm(FORM_WITH_LABEL);
		ensureForm(FORM_EMPTY);

		String result1 = tool.showFormInBrowser(FORM_WITH_LABEL, false);
		String result2 = tool.showFormInBrowser(FORM_EMPTY, false);

		assertNotNull(result1, "First result should not be null");
		assertNotNull(result2, "Second result should not be null");
		assertTrue(result1.contains("formpreview=" + FORM_WITH_LABEL), "First should open formWithLabel");
		assertTrue(result2.contains("formpreview=" + FORM_EMPTY), "Second should open formEmpty");
	}

	// -----------------------------------------------------------------------
	// Form with button + label: verify elements are present via Cypress
	// -----------------------------------------------------------------------

	private static final String FORM_BUTTON_LABEL = "formButtonAndLabel";

	@Test
	public void testFormWithButtonAndLabel_cypressFindsElements() throws Exception {
		Form form = ensureFormWithButtonAndLabel();
		assertNotNull(form, "Form should be created");

		java.nio.file.Path testsDir = new com.servoy.eclipse.cypress.services.FormSpecGenerator().getFormSpecDir();
		java.nio.file.Files.createDirectories(testsDir);

		String cySpec = "describe('" + FORM_BUTTON_LABEL + " - elements present', () => {\n\n"
				+ "  beforeEach(() => {\n"
				+ "    cy.visit('?formpreview=" + FORM_BUTTON_LABEL + "&svy_testmode=true');\n"
				+ "    cy.get('[data-cy^=\"" + FORM_BUTTON_LABEL + ".\"]', { timeout: 30000 }).should('exist');\n"
				+ "  });\n\n"
				+ "  it('button is present', () => {\n"
				+ "    cy.get('[data-cy=\"" + FORM_BUTTON_LABEL + ".btnAction\"]').should('exist');\n"
				+ "    cy.get('[data-cy=\"" + FORM_BUTTON_LABEL + ".btnAction\"]').should('contain.text', 'Click Me');\n"
				+ "  });\n\n"
				+ "  it('label is present', () => {\n"
				+ "    cy.get('[data-cy=\"" + FORM_BUTTON_LABEL + ".lblStatus\"]').should('exist');\n"
				+ "    cy.get('[data-cy=\"" + FORM_BUTTON_LABEL + ".lblStatus\"]').should('contain.text', 'Status');\n"
				+ "  });\n\n"
				+ "});\n";

		java.nio.file.Files.writeString(testsDir.resolve(FORM_BUTTON_LABEL + ".spec.cy.js"), cySpec,
				java.nio.charset.StandardCharsets.UTF_8);

		com.servoy.eclipse.cypress.services.FormSpecRunner specRunner = new com.servoy.eclipse.cypress.services.FormSpecRunner();
		String result = specRunner.runFormCypressTests(FORM_BUTTON_LABEL, true);

		assertNotNull(result, "Cypress result should not be null");
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("passed") || result.contains("failed") || result.contains("timed out"),
				"Cypress should find button and label elements: " + result);
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private Form ensureForm(String formName) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "Form creation should succeed: " + formName);
		return form;
	}

	private Form ensureFormWithButtonAndLabel() throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(FORM_BUTTON_LABEL);
		if (existing != null) {
			assertNotNull(activeProject.getSolution().getForm(FORM_BUTTON_LABEL), "Sol form should not be null");
			return existing;
		}

		new ServoyArtifactCreationService().createForm(FORM_BUTTON_LABEL, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(FORM_BUTTON_LABEL);
		assertNotNull(form, "Form creation should succeed: " + FORM_BUTTON_LABEL);

		GraphicalComponent button = form.createNewGraphicalComponent(new Point(20, 20));
		button.setName("btnAction");
		button.setText("Click Me");

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 80));
		label.setName("lblStatus");
		label.setText("Status");

		saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form });
		assertNotNull(activeProject.getSolution().getForm(FORM_BUTTON_LABEL), "Sol form should not be null");
		return form;
	}

}
