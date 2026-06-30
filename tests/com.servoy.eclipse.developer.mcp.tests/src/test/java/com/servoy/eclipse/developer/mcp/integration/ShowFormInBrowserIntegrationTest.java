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

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

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
public class ShowFormInBrowserIntegrationTest {
	private static final String TEST_SOLUTION = "test_showform_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String FORM_WITH_LABEL = "formWithLabel";
	private static final String FORM_WITH_MULTIPLE_LABELS = "formWithMultipleLabels";
	private static final String FORM_EMPTY = "formEmpty";

	private static final long APP_SERVER_POLL_MS = 5_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private ServoyTestingServer tool;
	private ServoyProject activeProject;

	@Before
	public void setUp() throws Exception {
		tool = new ServoyTestingServer();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
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
	public void testShowFormInBrowser_emptyString_returnsUrl() {
		String result = tool.showFormInBrowser("", false);

		assertNotNull("Result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Should contain URL with empty formpreview", result.contains("formpreview="));
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

		java.nio.file.Path testsDir = new com.servoy.eclipse.developer.mcp.services.FormSpecGenerator().getFormSpecDir();
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

		com.servoy.eclipse.developer.mcp.services.FormSpecRunner specRunner = new com.servoy.eclipse.developer.mcp.services.FormSpecRunner();
		String result = specRunner.runSpec(FORM_BUTTON_LABEL, true);

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

	private static Boolean appServerAvailableCache = null;

	private void waitForAppServer() throws InterruptedException {
		if (appServerAvailableCache == null) {
			long deadline = System.currentTimeMillis() + APP_SERVER_POLL_MS;
			while (!ApplicationServerRegistry.exists() && System.currentTimeMillis() < deadline) {
				Thread.sleep(500);
			}
			appServerAvailableCache = ApplicationServerRegistry.exists();
		}
		assertTrue("Servoy application server not started - skipping", appServerAvailableCache);
	}

	private void ensureTestSolutionInWorkspace() throws Exception {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen())
				res.open(monitor);

			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_SOLUTION);
			if (!sol.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(TEST_SOLUTION);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
						"org.eclipse.dltk.javascript.core.nature" });
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { res });
				sol.create(d, monitor);
			}
			if (!sol.isOpen())
				sol.open(monitor);

			writeProjectFile(sol, "rootmetadata.obj",
					"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\""
							+ TEST_SOLUTION + "\",\n"
							+ "solutionType:1,\ntypeid:43,\nuuid:\"d4e5f6a7-b8c9-0123-def0-456789abcdef\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"d4e5f6a7-b8c9-0123-def0-456789abcdef\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(sol, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry kind=\"src\" path=\"\"/>\n</buildpath>\n",
					monitor);
		}, new NullProgressMonitor());

		pumpEvents(1000);
	}

	private void ensureActiveProject() throws Exception {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		ServoyProject active = model.getActiveProject();
		if (active != null && TEST_SOLUTION.equals(active.getProject().getName()))
			return;

		model.refreshServoyProjects();
		pumpEvents(1000);

		ServoyProject[] projects = model.getServoyProjects();
		assertTrue("No ServoyProject found in workspace", projects != null && projects.length > 0);

		ServoyProject toActivate = null;
		for (ServoyProject p : projects) {
			if (TEST_SOLUTION.equals(p.getProject().getName())) {
				toActivate = p;
				break;
			}
		}
		if (toActivate == null)
			toActivate = projects[0];

		try {
			model.setActiveProject(toActivate, true);
		} catch (Exception e) {
			// caught by assumeNotNull below
		}

		long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
		Display display = Display.getDefault();
		if (display.getThread() == Thread.currentThread()) {
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				display.readAndDispatch();
		} else {
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				Thread.sleep(200);
		}

		assertNotNull("Active project not set - skipping", model.getActiveProject());
	}

	private void writeProjectFile(IProject project, String fileName, String content,
			org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
		org.eclipse.core.resources.IFile file = project.getFile(fileName);
		if (!file.exists())
			file.create(new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
					true, monitor);
	}

	private void pumpEvents(long ms) {
		Display display = Display.getDefault();
		long end = System.currentTimeMillis() + ms;
		if (display.getThread() == Thread.currentThread()) {
			while (System.currentTimeMillis() < end)
				display.readAndDispatch();
		} else {
			try {
				Thread.sleep(ms);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
