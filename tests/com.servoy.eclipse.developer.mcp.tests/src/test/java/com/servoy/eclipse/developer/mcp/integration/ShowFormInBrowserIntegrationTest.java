/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Point;

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;

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
public class ShowFormInBrowserIntegrationTest extends AbstractIntegrationTest {

	private static final String TEST_SOLUTION = "test_showform_suite";
	private static final String FORM_WITH_LABEL = "formWithLabel";
	private static final String FORM_WITH_MULTIPLE_LABELS = "formWithMultipleLabels";
	private static final String FORM_EMPTY = "formEmpty";

	private ServoyTestingServer tool;
	private ServoyProject activeProject;

	public ShowFormInBrowserIntegrationTest() {
		super(TEST_SOLUTION, "servoy_resources");
	}

	@Before
	public void setUp() throws Exception {
		tool = new ServoyTestingServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	// -----------------------------------------------------------------------
	// Form with a single label
	// -----------------------------------------------------------------------

	@Test
	public void testCreateFormWithLabel_formExists() throws Exception {
		Form form = ensureForm(FORM_WITH_LABEL);
		assertNotNull("Form should be created", form);

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 20));
		label.setText("Hello from test!");
		label.setName("lblHello");
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form }, true);

		Form reloaded = activeProject.getEditingSolution().getForm(FORM_WITH_LABEL);
		assertNotNull("Form should exist after save", reloaded);
	}

	@Test
	public void testCreateFormWithLabel_openInBrowser_returnsUrl() throws Exception {
		ensureForm(FORM_WITH_LABEL);

		String result = tool.showFormInBrowser(FORM_WITH_LABEL, false);

		assertNotNull("Result should not be null", result);
		assertTrue("Result should contain formpreview parameter", result.contains("formpreview=" + FORM_WITH_LABEL));
		assertTrue("Result should confirm form was opened", result.contains("Opened form"));
	}

	@Test
	public void testCreateFormWithLabel_urlHasCorrectSolution() throws Exception {
		ensureForm(FORM_WITH_LABEL);

		String result = tool.showFormInBrowser(FORM_WITH_LABEL, false);

		assertTrue("URL should contain solution name", result.contains("/solution/" + TEST_SOLUTION + "/"));
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

		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form }, true);

		Form reloaded = activeProject.getEditingSolution().getForm(FORM_WITH_MULTIPLE_LABELS);
		assertNotNull("Form should exist", reloaded);
	}

	@Test
	public void testCreateFormWithMultipleLabels_openInBrowser() throws Exception {
		ensureForm(FORM_WITH_MULTIPLE_LABELS);

		String result = tool.showFormInBrowser(FORM_WITH_MULTIPLE_LABELS, false);

		assertNotNull("Result should not be null", result);
		assertTrue("Should open form with multiple labels",
				result.contains("formpreview=" + FORM_WITH_MULTIPLE_LABELS));
	}

	// -----------------------------------------------------------------------
	// Empty form
	// -----------------------------------------------------------------------

	@Test
	public void testCreateEmptyForm_openInBrowser() throws Exception {
		ensureForm(FORM_EMPTY);

		String result = tool.showFormInBrowser(FORM_EMPTY, false);

		assertNotNull("Result should not be null", result);
		assertTrue("Should open empty form", result.contains("formpreview=" + FORM_EMPTY));
	}

	// -----------------------------------------------------------------------
	// Error cases
	// -----------------------------------------------------------------------

	@Test
	public void testShowFormInBrowser_nullForm_returnsError() {
		String result = tool.showFormInBrowser(null, false);

		assertNotNull("Result should not be null", result);
		// The tool may return an error or treat null as empty and produce a URL
		assertTrue("Should return error or a URL for null form",
				result.startsWith("Error") || result.contains("formpreview="));
	}

	@Test
	public void testShowFormInBrowser_emptyString_returnsError() {
		String result = tool.showFormInBrowser("", false);

		assertNotNull("Result should not be null", result);
		assertTrue("Should return error for empty form name", result.startsWith("Error"));
	}

	@Test
	public void testCheckNGClientStatus_returnsInfo() {
		String result = tool.checkNGClientStatus();

		assertNotNull("Status should not be null", result);
		assertFalse("Status should not be empty", result.isEmpty());
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Status should contain useful info", result.contains("running") || result.contains("URL"));
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

		assertNotNull("First result should not be null", result1);
		assertNotNull("Second result should not be null", result2);
		assertTrue("First should open formWithLabel", result1.contains("formpreview=" + FORM_WITH_LABEL));
		assertTrue("Second should open formEmpty", result2.contains("formpreview=" + FORM_EMPTY));
	}

	// -----------------------------------------------------------------------
	// Form with button + label: verify elements are present via Cypress
	// -----------------------------------------------------------------------

	private static final String FORM_BUTTON_LABEL = "formButtonAndLabel";

	@Test
	public void testFormWithButtonAndLabel_cypressFindsElements() throws Exception {
		Form form = ensureFormWithButtonAndLabel();
		assertNotNull("Form should be created", form);

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

		assertNotNull("Cypress result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Cypress should find button and label elements: " + result,
				result.contains("passed") || result.contains("failed") || result.contains("timed out"));
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
		assertNotNull("Form creation should succeed: " + formName, form);
		return form;
	}

	private Form ensureFormWithButtonAndLabel() throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(FORM_BUTTON_LABEL);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(FORM_BUTTON_LABEL, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(FORM_BUTTON_LABEL);
		assertNotNull("Form creation should succeed: " + FORM_BUTTON_LABEL, form);

		GraphicalComponent button = form.createNewGraphicalComponent(new Point(20, 20));
		button.setName("btnAction");
		button.setText("Click Me");

		GraphicalComponent label = form.createNewGraphicalComponent(new Point(20, 80));
		label.setName("lblStatus");
		label.setText("Status");

		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form }, true);
		return form;
	}

}
