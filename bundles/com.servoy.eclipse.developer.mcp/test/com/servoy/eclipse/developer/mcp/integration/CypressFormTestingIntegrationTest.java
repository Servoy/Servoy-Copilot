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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.services.FormSpecRunner;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for the Cypress form testing workflow:
 * showFormInBrowser -> generateSpec -> testForm (Cypress run).
 *
 * These tests require a running Servoy application server and an active solution.
 * They are skipped (via Assume) when the environment is not available.
 */
public class CypressFormTestingIntegrationTest
{
	private static final String TEST_SOLUTION = "test_cypress_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String TEST_FORM = "cypressTestForm";

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private ServoyTestingServer testingServer;
	private FormSpecGenerator specGenerator;
	private FormSpecRunner specRunner;
	private ServoyProject activeProject;

	private static Boolean appServerAvailableCache;

	@Before
	public void setUp() throws Exception
	{
		testingServer = new ServoyTestingServer();
		specGenerator = new FormSpecGenerator();
		specRunner = new FormSpecRunner();

		assumeNotNull("No Display available - test requires a running Eclipse UI",
			Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assumeNotNull("Active project required", activeProject);
	}

	// -----------------------------------------------------------------------
	// showFormInBrowser tests
	// -----------------------------------------------------------------------

	@Test
	public void testShowFormInBrowser_generatesSpecFiles() throws Exception
	{
		ensureForm(TEST_FORM);

		String result = testingServer.showFormInBrowser(TEST_FORM);

		assertNotNull("Result should not be null", result);
		assertTrue("Should contain URL", result.contains("formpreview=" + TEST_FORM));

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertNotNull("Cypress spec path should not be null", cySpec);
		assertTrue("Cypress spec file should exist after showFormInBrowser",
			Files.exists(cySpec));
	}

	@Test
	public void testShowFormInBrowser_generatesSetupFile() throws Exception
	{
		ensureForm(TEST_FORM);

		testingServer.showFormInBrowser(TEST_FORM);

		IProject project = activeProject.getProject();
		Path setupPath = project.getLocation().toFile().toPath()
			.resolve("forms").resolve(TEST_FORM + ".spec.js");
		assertTrue("Setup spec.js file should exist in forms/ directory",
			Files.exists(setupPath));
	}

	@Test
	public void testShowFormInBrowser_specFileHasCorrectContent() throws Exception
	{
		ensureForm(TEST_FORM);

		testingServer.showFormInBrowser(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		assertTrue("Spec should use cy.visit", content.contains("cy.visit("));
		assertTrue("Spec should use data-cy selectors", content.contains("data-cy"));
		assertTrue("Spec should reference form name", content.contains(TEST_FORM));
		assertTrue("Spec should use describe()", content.contains("describe("));
		assertTrue("Spec should use beforeEach()", content.contains("beforeEach("));
		assertTrue("Spec should check for errors", content.contains(".svy-error"));
	}

	@Test
	public void testShowFormInBrowser_setupFileHasServoyAnnotations() throws Exception
	{
		ensureForm(TEST_FORM);

		testingServer.showFormInBrowser(TEST_FORM);

		IProject project = activeProject.getProject();
		Path setupPath = project.getLocation().toFile().toPath()
			.resolve("forms").resolve(TEST_FORM + ".spec.js");
		String content = Files.readString(setupPath);

		assertTrue("Setup should have @properties annotation", content.contains("@properties"));
		assertTrue("Setup should have spec_setUp function", content.contains("function spec_setUp()"));
		assertTrue("Setup should have spec_tearDown function", content.contains("function spec_tearDown()"));
		assertTrue("Setup should mention Cypress", content.contains("Cypress"));
	}

	@Test
	public void testShowFormInBrowser_doesNotRegenerateExistingSpec() throws Exception
	{
		ensureForm(TEST_FORM);

		testingServer.showFormInBrowser(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		long firstModified = Files.getLastModifiedTime(cySpec).toMillis();

		Thread.sleep(1100);

		testingServer.showFormInBrowser(TEST_FORM);
		long secondModified = Files.getLastModifiedTime(cySpec).toMillis();

		assertTrue("Spec file should not be regenerated if it already exists",
			firstModified == secondModified);
	}

	// -----------------------------------------------------------------------
	// specExists tests
	// -----------------------------------------------------------------------

	@Test
	public void testSpecExists_falseBeforeGeneration()
	{
		boolean exists = specGenerator.specExists("nonExistentForm_XYZ");
		assertTrue("specExists should return false for non-existent form", !exists);
	}

	@Test
	public void testSpecExists_trueAfterGeneration() throws Exception
	{
		ensureForm(TEST_FORM);
		testingServer.showFormInBrowser(TEST_FORM);

		boolean exists = specGenerator.specExists(TEST_FORM);
		assertTrue("specExists should return true after spec is generated", exists);
	}

	// -----------------------------------------------------------------------
	// generateFormSpec tests
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_returnsSuccessMessage() throws Exception
	{
		ensureForm(TEST_FORM);

		deleteSpecFiles(TEST_FORM);

		String result = testingServer.generateFormSpec(TEST_FORM);

		assertNotNull(result);
		assertTrue("Should mention created files",
			result.contains("Created") || result.contains("already exist"));
	}

	@Test
	public void testGenerateFormSpec_nonExistentForm_returnsError()
	{
		String result = testingServer.generateFormSpec("totally_fake_form_xyz");

		assertNotNull(result);
		assertTrue("Should return error for non-existent form",
			result.contains("Error") || result.contains("not found"));
	}

	// -----------------------------------------------------------------------
	// testForm (Cypress run) tests
	// -----------------------------------------------------------------------

	@Test
	public void testTestForm_runsAndReturnsResults() throws Exception
	{
		ensureForm(TEST_FORM);
		testingServer.showFormInBrowser(TEST_FORM);

		String result = testingServer.testForm(TEST_FORM);

		assertNotNull("testForm result should not be null", result);
		assertTrue("testForm should return results",
			result.contains("passed") || result.contains("failed") || result.contains("Error"));
	}

	@Test
	public void testTestForm_autoGeneratesSpecIfMissing() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String result = testingServer.testForm(TEST_FORM);

		assertNotNull(result);
		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertTrue("testForm should auto-generate spec file",
			cySpec != null && Files.exists(cySpec));
	}

	// -----------------------------------------------------------------------
	// Cypress config tests
	// -----------------------------------------------------------------------

	@Test
	public void testCypressConfigIsGenerated() throws Exception
	{
		ensureForm(TEST_FORM);
		testingServer.showFormInBrowser(TEST_FORM);

		testingServer.testForm(TEST_FORM);

		Path cypressDir = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
			.getParent().resolve(".metadata").resolve(".plugins")
			.resolve("com.servoy.eclipse.developer.mcp").resolve("cypress");
		Path configFile = cypressDir.resolve("cypress.config.js");

		assertTrue("cypress.config.js should be generated",
			Files.exists(configFile));

		String content = Files.readString(configFile);
		assertTrue("Config should have baseUrl", content.contains("baseUrl"));
		assertTrue("Config should have specPattern", content.contains("specPattern"));
		assertTrue("Config should disable video", content.contains("video: false"));
	}

	// -----------------------------------------------------------------------
	// generateFormSpec tests (independent of showFormInBrowser)
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_createsSpecCyFile() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String result = specGenerator.generateSpec(TEST_FORM);

		assertNotNull(result);
		assertTrue("Should report creation", result.contains("Created"));

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertNotNull(cySpec);
		assertTrue("Cypress spec file should be created", Files.exists(cySpec));
	}

	@Test
	public void testGenerateFormSpec_createsSetupJsFile() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path setupPath = activeProject.getProject().getLocation().toFile().toPath()
			.resolve("forms").resolve(TEST_FORM + ".spec.js");
		assertTrue("Setup .spec.js should be created in forms/", Files.exists(setupPath));
	}

	@Test
	public void testGenerateFormSpec_specCyUsesDataCySelectors() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		assertTrue("Cypress spec must use data-cy selectors", content.contains("data-cy"));
		assertTrue("Cypress spec must reference form name in selectors", content.contains(TEST_FORM + "."));
	}

	@Test
	public void testGenerateFormSpec_specCyUsesBeforeEach() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		assertTrue("Cypress spec must use beforeEach", content.contains("beforeEach("));
		assertTrue("Cypress spec must use cy.visit", content.contains("cy.visit("));
	}

	@Test
	public void testGenerateFormSpec_specCyUsesSvyTestMode() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		assertTrue("Cypress spec must include svy_testmode=true in URL",
			content.contains("svy_testmode=true"));
	}

	@Test
	public void testGenerateFormSpec_setupJsHasProperties() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path setupPath = activeProject.getProject().getLocation().toFile().toPath()
			.resolve("forms").resolve(TEST_FORM + ".spec.js");
		String content = Files.readString(setupPath);

		assertTrue("Setup must have @properties annotation", content.contains("@properties"));
		assertTrue("Setup must have spec_setUp", content.contains("function spec_setUp()"));
		assertTrue("Setup must have spec_tearDown", content.contains("function spec_tearDown()"));
	}

	@Test
	public void testGenerateFormSpec_specInMediasTests() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertNotNull(cySpec);
		assertTrue("Cypress spec should be in medias/tests/ directory",
			cySpec.toString().contains("medias") && cySpec.toString().contains("tests"));
	}

	@Test
	public void testGenerateFormSpec_runCypressDirectly() throws Exception
	{
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		String result = specRunner.runSpec(TEST_FORM, true);

		assertNotNull("runSpec result should not be null", result);
		assertTrue("runSpec should return pass/fail or error",
			result.contains("passed") || result.contains("failed") || result.contains("Error") || result.contains("timed out"));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private Form ensureForm(String formName) throws Exception
	{
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null) return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form creation should succeed: " + formName, form);
		return form;
	}

	private void deleteSpecFiles(String formName)
	{
		try
		{
			Path cySpec = specGenerator.getSpecFilePath(formName);
			if (cySpec != null) Files.deleteIfExists(cySpec);

			IProject project = activeProject.getProject();
			Path setupSpec = project.getLocation().toFile().toPath()
				.resolve("forms").resolve(formName + ".spec.js");
			Files.deleteIfExists(setupSpec);
		}
		catch (Exception e)
		{
			// ignore
		}
	}

	private void waitForAppServer() throws InterruptedException
	{
		if (appServerAvailableCache == null)
		{
			long deadline = System.currentTimeMillis() + APP_SERVER_POLL_MS;
			while (!ApplicationServerRegistry.exists() && System.currentTimeMillis() < deadline)
			{
				Thread.sleep(500);
			}
			appServerAvailableCache = ApplicationServerRegistry.exists();
		}
		assumeTrue("Servoy application server not started - skipping",
			appServerAvailableCache);
	}

	private void ensureTestSolutionInWorkspace() throws Exception
	{
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(SERVOY_RESOURCES);
			if (!res.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(SERVOY_RESOURCES);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen()) res.open(monitor);

			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(TEST_SOLUTION);
			if (!sol.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(TEST_SOLUTION);
				d.setNatureIds(new String[] {
					"com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature"
				});
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { res });
				sol.create(d, monitor);
			}
			if (!sol.isOpen()) sol.open(monitor);

			writeProjectFile(sol, "rootmetadata.obj",
				"fileVersion:" + com.servoy.j2db.persistence.AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\"" + TEST_SOLUTION + "\",\n" +
				"solutionType:1024,\ntypeid:43,\nuuid:\"c1e2f3a4-b5c6-7890-abcd-ef1234567890\"\n",
				monitor);
			writeProjectFile(sol, "solution_settings.obj",
				"typeid:43,\nuuid:\"c1e2f3a4-b5c6-7890-abcd-ef1234567890\",\nversion:\"1.0\"\n",
				monitor);
			writeProjectFile(sol, ".buildpath",
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/|**/*.spec.cy.js\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
				monitor);
		}, new NullProgressMonitor());

		pumpEvents(1000);
	}

	private void ensureActiveProject() throws Exception
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		ServoyProject active = model.getActiveProject();
		if (active != null && TEST_SOLUTION.equals(active.getProject().getName())) return;

		model.refreshServoyProjects();
		pumpEvents(1000);

		ServoyProject[] projects = model.getServoyProjects();
		assumeTrue("No ServoyProject found in workspace",
			projects != null && projects.length > 0);

		ServoyProject toActivate = null;
		for (ServoyProject p : projects)
		{
			if (TEST_SOLUTION.equals(p.getProject().getName()))
			{
				toActivate = p;
				break;
			}
		}
		if (toActivate == null) toActivate = projects[0];

		try
		{
			model.setActiveProject(toActivate, true);
		}
		catch (Exception e)
		{
			// caught by assumeNotNull below
		}

		long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
		Display display = Display.getDefault();
		if (display.getThread() == Thread.currentThread())
		{
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				display.readAndDispatch();
		}
		else
		{
			while (model.getActiveProject() == null && System.currentTimeMillis() < deadline)
				Thread.sleep(200);
		}

		assumeNotNull("Active project not set - skipping",
			model.getActiveProject());
	}

	private void writeProjectFile(IProject project, String fileName, String content, org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException
	{
		org.eclipse.core.resources.IFile file = project.getFile(fileName);
		byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (file.exists())
		{
			file.setContents(new java.io.ByteArrayInputStream(bytes), true, false, monitor);
		}
		else
		{
			file.create(new java.io.ByteArrayInputStream(bytes), true, monitor);
		}
	}


	private void pumpEvents(long ms)
	{
		try
		{
			Display display = Display.getDefault();
			long end = System.currentTimeMillis() + ms;
			if (display.getThread() == Thread.currentThread())
			{
				while (System.currentTimeMillis() < end)
					display.readAndDispatch();
			}
			else
			{
				Thread.sleep(ms);
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
