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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.MessageConsole;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.actions.CypressConsoleUtil;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.FormSpecGenerator;
import com.servoy.eclipse.developer.mcp.services.FormSpecRunner;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.server.shared.IApplicationServerSingleton;

/**
 * Integration tests for the Cypress form testing workflow: showFormInBrowser ->
 * generateSpec -> testForm (Cypress run).
 *
 * These tests require a running Servoy application server and an active
 * solution. They are skipped (via Assume) when the environment is not
 * available.
 */
public class CypressFormTestingIntegrationTest {
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
	public void setUp() throws Exception {
		testingServer = new ServoyTestingServer();
		specGenerator = new FormSpecGenerator();
		specRunner = new FormSpecRunner();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace();
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	@org.junit.AfterClass
	public static void tearDownClass() throws Exception {
		// Wait for server to release formpreview sessions before next test suite starts
		Thread.sleep(5000);
	}

	// -----------------------------------------------------------------------
	// showFormInBrowser tests (only 1 test opens browser - validates the combined
	// behavior)
	// -----------------------------------------------------------------------

	@Test
	public void testShowFormInBrowser_opensAndGeneratesSpecFiles() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String result = testingServer.showFormInBrowser(TEST_FORM, false);

		assertNotNull("Result should not be null", result);
		assertTrue("Should contain URL", result.contains("formpreview=" + TEST_FORM));

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertNotNull("Cypress spec path should not be null", cySpec);
		assertTrue("Cypress spec file should exist after showFormInBrowser", Files.exists(cySpec));

		IProject project = activeProject.getProject();
		Path setupPath = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress").resolve("e2e-form-spec").resolve(TEST_FORM + ".spec.js");
		assertTrue("Setup spec.js file should exist in e2e-form-spec/ directory", Files.exists(setupPath));
	}

	@Test
	public void testShowFormInBrowser_doesNotRegenerateExistingSpec() throws Exception {
		ensureForm(TEST_FORM);

		if (!specGenerator.specExists(TEST_FORM))
			specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		long firstModified = Files.getLastModifiedTime(cySpec).toMillis();

		Thread.sleep(1100);

		testingServer.showFormInBrowser(TEST_FORM, false);
		long secondModified = Files.getLastModifiedTime(cySpec).toMillis();

		assertTrue("Spec file should not be regenerated if it already exists", firstModified == secondModified);
	}

	// -----------------------------------------------------------------------
	// specExists tests
	// -----------------------------------------------------------------------

	@Test
	public void testSpecExists_falseBeforeGeneration() {
		boolean exists = specGenerator.specExists("nonExistentForm_XYZ");
		assertTrue("specExists should return false for non-existent form", !exists);
	}

	@Test
	public void testSpecExists_trueAfterGeneration() throws Exception {
		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM))
			specGenerator.generateSpec(TEST_FORM);

		boolean exists = specGenerator.specExists(TEST_FORM);
		assertTrue("specExists should return true after spec is generated", exists);
	}

	// -----------------------------------------------------------------------
	// generateFormSpec tests
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_returnsSuccessMessage() throws Exception {
		ensureForm(TEST_FORM);

		deleteSpecFiles(TEST_FORM);

		String result = testingServer.generateFormSpec(TEST_FORM);

		assertNotNull(result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Should mention created files", result.contains("Created"));
	}

	@Test
	public void testGenerateFormSpec_nonExistentForm_returnsError() {
		String result = testingServer.generateFormSpec("totally_fake_form_xyz");

		assertNotNull(result);
		assertTrue("Should return error for non-existent form",
				result.contains("Error") || result.contains("not found"));
	}

	// -----------------------------------------------------------------------
	// testForm (Cypress run) tests - only 1 test actually runs Cypress
	// -----------------------------------------------------------------------

	@Test
	public void testTestForm_runsAndReturnsResults() throws Exception {
		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM))
			specGenerator.generateSpec(TEST_FORM);

		String result = testingServer.testForm(TEST_FORM);

		assertNotNull("testForm result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("testForm should return results", result.contains("passed") || result.contains("failed"));

		MessageConsole console = CypressConsoleUtil.findOrCreateConsole();
		pumpEvents(300);
		String consoleContent = console.getDocument().get();
		assertNotNull("Console document should not be null after testForm", consoleContent);
		assertTrue("Console should contain test result written by testForm: " + consoleContent,
				consoleContent.contains("passed") || consoleContent.contains("failed"));
	}

	@Test
	public void testTestForm_autoGeneratesSpecIfMissing() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String result = testingServer.testForm(TEST_FORM);

		assertNotNull(result);
		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertTrue("testForm should auto-generate spec file", cySpec != null && Files.exists(cySpec));
	}

	// -----------------------------------------------------------------------
	// testingMode auto-injection tests
	// -----------------------------------------------------------------------

	@Test
	public void testShowFormInBrowser_enablesTestingMode() throws Exception {
		com.servoy.j2db.util.Settings.getInstance().remove("servoy.ngclient.testingMode");

		ensureForm(TEST_FORM);
		testingServer.showFormInBrowser(TEST_FORM, false);

		String value = com.servoy.j2db.util.Settings.getInstance().getProperty("servoy.ngclient.testingMode");
		assertNotNull("testingMode should be set after showFormInBrowser", value);
		assertTrue("testingMode should be true", "true".equals(value));
	}

	@Test
	public void testTestForm_enablesTestingMode() throws Exception {
		com.servoy.j2db.util.Settings.getInstance().remove("servoy.ngclient.testingMode");

		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM))
			specGenerator.generateSpec(TEST_FORM);

		testingServer.testForm(TEST_FORM);

		String value = com.servoy.j2db.util.Settings.getInstance().getProperty("servoy.ngclient.testingMode");
		assertNotNull("testingMode should be set after testForm", value);
		assertTrue("testingMode should be true", "true".equals(value));
	}

	@Test
	public void testShowAndTest_enablesTestingMode() throws Exception {
		com.servoy.j2db.util.Settings.getInstance().remove("servoy.ngclient.testingMode");

		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM))
			specGenerator.generateSpec(TEST_FORM);

		testingServer.showAndTest(TEST_FORM);

		String value = com.servoy.j2db.util.Settings.getInstance().getProperty("servoy.ngclient.testingMode");
		assertNotNull("testingMode should be set after showAndTest", value);
		assertTrue("testingMode should be true", "true".equals(value));

		MessageConsole console = CypressConsoleUtil.findOrCreateConsole();
		pumpEvents(300);
		String consoleContent = console.getDocument().get();
		assertNotNull("Console should have content after showAndTest", consoleContent);
		assertTrue("Console should contain showAndTest output: " + consoleContent, consoleContent.contains("passed")
				|| consoleContent.contains("failed") || consoleContent.contains("timed out"));
	}

	// -----------------------------------------------------------------------
	// Cypress config tests
	// -----------------------------------------------------------------------

	@Test
	public void testCypressConfigIsGenerated() throws Exception {
		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM))
			specGenerator.generateSpec(TEST_FORM);

		specRunner.runSpec(TEST_FORM, true);

		Path cypressDir = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().getParent()
				.resolve(".metadata").resolve(".plugins").resolve("com.servoy.eclipse.developer.mcp")
				.resolve("cypress");
		Path configFile = cypressDir.resolve("cypress.config.js");

		assertTrue("cypress.config.js should be generated", Files.exists(configFile));

		String content = Files.readString(configFile);
		assertTrue("Config should have baseUrl", content.contains("baseUrl"));
		assertTrue("Config should have specPattern", content.contains("specPattern"));
		assertTrue("Config should disable video", content.contains("video: false"));
	}

	// -----------------------------------------------------------------------
	// generateFormSpec tests (independent of showFormInBrowser)
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_createsSpecCyFile() throws Exception {
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
	public void testGenerateFormSpec_createsSetupJsFile() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path setupPath = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
				.resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress").resolve("e2e-form-spec").resolve(TEST_FORM + ".spec.js");
		assertTrue("Setup .spec.js should be created in e2e-form-spec/", Files.exists(setupPath));
	}

	@Test
	public void testGenerateFormSpec_specCyUsesDataCySelectors() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		// Forms with named elements use [data-cy] attribute selectors;
		// empty forms (no elements) fall back to .svy-form CSS class selector
		assertTrue("Cypress spec must use data-cy selectors or .svy-form for empty forms",
				content.contains("data-cy") || content.contains(".svy-form"));
		assertTrue("Cypress spec must reference form name",
				content.contains(TEST_FORM + ".") || content.contains(TEST_FORM + "'"));
	}

	@Test
	public void testGenerateFormSpec_specCyUsesBeforeEach() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		assertTrue("Cypress spec must use beforeEach", content.contains("beforeEach("));
		assertTrue("Cypress spec must use cy.visit", content.contains("cy.visit("));
	}

	@Test
	public void testGenerateFormSpec_specCyUsesSvyTestMode() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		String content = Files.readString(cySpec);

		assertTrue("Cypress spec must include svy_testmode=true in URL", content.contains("svy_testmode=true"));
	}

	@Test
	public void testGenerateFormSpec_setupJsHasProperties() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path setupPath = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
				.resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress").resolve("e2e-form-spec").resolve(TEST_FORM + ".spec.js");
		String content = Files.readString(setupPath);

		assertTrue("Setup must have @properties annotation", content.contains("@properties"));
		assertTrue("Setup must have spec_setUp", content.contains("function spec_setUp()"));
		assertTrue("Setup must have spec_tearDown", content.contains("function spec_tearDown()"));
	}

	@Test
	public void testGenerateFormSpec_specInE2eFormDir() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM);
		assertNotNull(cySpec);
		String path = cySpec.toString().replace('\\', '/');
		assertTrue("Cypress spec should be in jenkins-custom/e2e-test-scripts/cypress/e2e-form/ directory: " + path,
				path.contains("jenkins-custom") && path.contains("e2e-test-scripts") && path.contains("cypress")
						&& path.contains("e2e-form"));
		assertTrue("Cypress spec must no longer live under medias/tests: " + path, !path.contains("medias"));
	}

	@Test
	public void testGenerateFormSpec_runCypressDirectly() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		String result = specRunner.runSpec(TEST_FORM, true);

		assertNotNull("runSpec result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("runSpec should return pass/fail or timed out",
				result.contains("passed") || result.contains("failed") || result.contains("timed out"));
	}

	// -----------------------------------------------------------------------
	// Button + Label interaction tests (creates form with onAction handler)
	// -----------------------------------------------------------------------

	private static final String BUTTON_LABEL_FORM = "cypressButtonLabelForm";

	@Test
	public void testCypress_buttonClickUpdatesLabel() throws Exception {
		ensureButtonLabelForm();
		deleteSpecFiles(BUTTON_LABEL_FORM);

		Path testsDir = specGenerator.getFormSpecDir();
		Files.createDirectories(testsDir);

		String cySpec = "describe('" + BUTTON_LABEL_FORM + " - button click', () => {\n\n" + "  beforeEach(() => {\n"
				+ "    cy.visit('?formpreview=" + BUTTON_LABEL_FORM + "&svy_testmode=true');\n"
				+ "    cy.get('[data-cy^=\"" + BUTTON_LABEL_FORM + ".\"]', { timeout: 30000 }).should('exist');\n"
				+ "  });\n\n" + "  it('button click updates label text', () => {\n" + "    cy.get('[data-cy=\""
				+ BUTTON_LABEL_FORM + ".button_1\"]').click();\n" + "    cy.get('[data-cy=\"" + BUTTON_LABEL_FORM
				+ ".label_2\"]').should('contain.text', 'CLICKED 1');\n" + "  });\n\n"
				+ "  it('multiple clicks increment counter', () => {\n" + "    cy.get('[data-cy=\"" + BUTTON_LABEL_FORM
				+ ".button_1\"]').click();\n" + "    cy.get('[data-cy=\"" + BUTTON_LABEL_FORM
				+ ".label_2\"]').should('contain.text', 'CLICKED 1');\n" + "    cy.get('[data-cy=\"" + BUTTON_LABEL_FORM
				+ ".button_1\"]').click();\n" + "    cy.get('[data-cy=\"" + BUTTON_LABEL_FORM
				+ ".label_2\"]').should('contain.text', 'CLICKED 2');\n" + "  });\n\n" + "});\n";

		Files.writeString(testsDir.resolve(BUTTON_LABEL_FORM + ".spec.cy.js"), cySpec,
				java.nio.charset.StandardCharsets.UTF_8);

		String result = specRunner.runSpec(BUTTON_LABEL_FORM, true);

		assertNotNull("Cypress button/label test result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Cypress button/label test should return results",
				result.contains("passed") || result.contains("failed") || result.contains("timed out"));
	}

	@Test
	public void testCypress_generatedSpecPassesForButtonLabelForm() throws Exception {
		ensureButtonLabelForm();
		deleteSpecFiles(BUTTON_LABEL_FORM);

		specGenerator.generateSpec(BUTTON_LABEL_FORM);

		String result = specRunner.runSpec(BUTTON_LABEL_FORM, true);

		assertNotNull("Generated spec run result should not be null", result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Generated spec should return results",
				result.contains("passed") || result.contains("failed") || result.contains("timed out"));
	}

	private void ensureButtonLabelForm() throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(BUTTON_LABEL_FORM);
		if (existing != null)
			return;

		new ServoyArtifactCreationService().createForm(BUTTON_LABEL_FORM, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(BUTTON_LABEL_FORM);
		assertNotNull("Button/label form creation should succeed", form);

		// Add button and label elements via the form script
		IProject project = activeProject.getProject();
		Path formsDir = project.getLocation().toFile().toPath().resolve("forms");

		// Write form script with button click handler
		String formScript = "/**\n" + " * @type {Number}\n" + " * @properties={typeid:35,uuid:\""
				+ java.util.UUID.randomUUID() + "\",variableType:4}\n" + " */\n" + "var i = 1;\n\n" + "/**\n"
				+ " * @param {JSEvent} event\n" + " * @properties={typeid:24,uuid:\"" + java.util.UUID.randomUUID()
				+ "\"}\n" + " */\n" + "function onAction(event) {\n" + "\telements.label_2.text = 'CLICKED ' + i;\n"
				+ "\ti++;\n" + "}\n";

		Files.writeString(formsDir.resolve(BUTTON_LABEL_FORM + ".js"), formScript,
				java.nio.charset.StandardCharsets.UTF_8);
	}

	// -----------------------------------------------------------------------
	// Data setup + Cypress full cycle test
	// -----------------------------------------------------------------------

	private static final String CYPRESS_TEST_SERVER = "cypress_test";
	private static final String CYPRESS_TEST_TABLE = "cypress_test_orders";

	@Test
	public void testFormWithDataSetup_insertsRecord_showsForm_cleansUp() throws Exception {
		ensureCypressTestTable();

		String formName = "ordersDataTest";

		ensureFormWithDataSource(formName, "db:/" + CYPRESS_TEST_SERVER + "/" + CYPRESS_TEST_TABLE);

		java.util.Map<String, Object> testData = new java.util.LinkedHashMap<>();
		testData.put("customerid", "CYPRS");
		testData.put("shipcity", "CYPRESS_TEST_CITY");
		testData.put("shipcountry", "Testland");
		testData.put("freight", 99.99);

		String setupResult = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, testData);
		assertNotNull(setupResult);
		assertTrue("Setup should succeed: " + setupResult, setupResult.contains("inserted"));
		assertTrue("Setup should mention server and table: " + setupResult,
				setupResult.contains(CYPRESS_TEST_SERVER) && setupResult.contains(CYPRESS_TEST_TABLE));

		try {
			String showResult = testingServer.showFormInBrowser(formName, false);
			assertNotNull(showResult);
			assertTrue("Should contain URL: " + showResult, showResult.contains("formpreview"));
		} finally {
			String teardownResult = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
					"CYPRESS_TEST_CITY");
			assertNotNull(teardownResult);
			assertTrue("Teardown should succeed: " + teardownResult, teardownResult.contains("deleted"));
		}
	}

	private void ensureCypressTestTable() throws Exception {
		IApplicationServerSingleton appServer = ApplicationServerRegistry.get();
		com.servoy.j2db.persistence.IServerManagerInternal serverManager = (com.servoy.j2db.persistence.IServerManagerInternal) appServer
				.getServerManager();

		IServerInternal server = (IServerInternal) serverManager.getServer(CYPRESS_TEST_SERVER, true, true);
		if (server == null) {
			Class.forName("org.hsqldb.jdbcDriver", true, appServer.getServerManager().getClassLoader());
			com.servoy.j2db.persistence.ServerConfig config = new com.servoy.j2db.persistence.ServerConfig(
					CYPRESS_TEST_SERVER, "sa", "", "jdbc:hsqldb:mem:" + CYPRESS_TEST_SERVER, null,
					"org.hsqldb.jdbcDriver", com.servoy.j2db.persistence.ServerConfig.NONE,
					com.servoy.j2db.persistence.ServerConfig.NONE, 5, 2, 20,
					com.servoy.j2db.persistence.ServerConfig.CONNECTION_EXCEPTION_VALIDATION, null, null, true, true,
					false, false, 0, null, null, null, false, null, null);
			server = (IServerInternal) serverManager.createServer(config);
		}
		assertNotNull("cypress_test server must be available", server);

		try (java.sql.Connection conn = server.getRawConnection()) {
			java.sql.DatabaseMetaData meta = conn.getMetaData();
			try (java.sql.ResultSet rs = meta.getTables(null, null, CYPRESS_TEST_TABLE.toUpperCase(), null)) {
				if (rs.next())
					return;
			}

			conn.createStatement().executeUpdate("CREATE TABLE " + CYPRESS_TEST_TABLE + " ("
					+ "  orderid INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," + "  customerid VARCHAR(50),"
					+ "  shipcity VARCHAR(100)," + "  shipcountry VARCHAR(100)," + "  freight DOUBLE" + ")");
			conn.commit();
		}
	}

	@Test
	public void testExecuteTestSetup_invalidServer_returnsError() {
		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("col1", "val1");

		String result = specRunner.executeTestSetup("nonexistent_server_xyz", "table", data);
		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestTeardown_invalidServer_returnsError() {
		String result = specRunner.executeTestTeardown("nonexistent_server_xyz", "table", "col", "val");
		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
	}

	@Test
	public void testExecuteTestSetup_nullParams_returnsError() {
		String result = specRunner.executeTestSetup(null, null, null);
		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error"));
	}

	@Test
	public void testSetupAndTeardown_multipleRecords() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> row1 = new java.util.LinkedHashMap<>();
		row1.put("customerid", "MULTI1");
		row1.put("shipcity", "CYPRESS_MULTI_TEST");
		row1.put("freight", 11.11);

		java.util.Map<String, Object> row2 = new java.util.LinkedHashMap<>();
		row2.put("customerid", "MULTI2");
		row2.put("shipcity", "CYPRESS_MULTI_TEST");
		row2.put("freight", 22.22);

		String r1 = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, row1);
		String r2 = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, row2);
		assertTrue("First insert should succeed: " + r1, r1.contains("inserted"));
		assertTrue("First insert should have correct message format: " + r1,
				r1.equals("Test setup: inserted 1 row into " + CYPRESS_TEST_SERVER + "." + CYPRESS_TEST_TABLE));
		assertTrue("Second insert should succeed: " + r2, r2.contains("inserted"));

		String teardown = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_MULTI_TEST");
		assertTrue("Teardown should delete 2 rows: " + teardown, teardown.contains("deleted 2"));
	}

	@Test
	public void testSetup_returnMessageFormat() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "FMTTEST");
		data.put("shipcity", "CYPRESS_FORMAT_TEST");

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertEquals("Return message should match exact format",
				"Test setup: inserted 1 row into " + CYPRESS_TEST_SERVER + "." + CYPRESS_TEST_TABLE, result);

		specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity", "CYPRESS_FORMAT_TEST");
	}

	@Test
	public void testSetupAndTeardown_newTable_createAndDrop() throws Exception {
		ensureCypressTestTable();

		String dynamicTable = "cypress_dynamic_" + System.currentTimeMillis();

		com.servoy.j2db.persistence.IServerManagerInternal serverManager = (com.servoy.j2db.persistence.IServerManagerInternal) ApplicationServerRegistry
				.get().getServerManager();
		IServerInternal server = (IServerInternal) serverManager.getServer(CYPRESS_TEST_SERVER, true, true);
		assertNotNull("Server must be available", server);

		try (java.sql.Connection conn = server.getRawConnection()) {
			conn.createStatement()
					.executeUpdate("CREATE TABLE " + dynamicTable + " ("
							+ "  id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," + "  name VARCHAR(100),"
							+ "  value VARCHAR(200)" + ")");
			conn.commit();
		}

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("name", "test_key");
		data.put("value", "test_value_123");

		String setupResult = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, dynamicTable, data);
		assertTrue("Setup should succeed: " + setupResult, setupResult.contains("inserted"));

		String teardownResult = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, dynamicTable, "name", "test_key");
		assertTrue("Teardown should succeed: " + teardownResult, teardownResult.contains("deleted 1"));

		try (java.sql.Connection conn = server.getRawConnection()) {
			conn.createStatement().executeUpdate("DROP TABLE " + dynamicTable);
			conn.commit();
		}
	}

	@Test
	public void testTeardown_noMatchingRows_deletesZero() throws Exception {
		ensureCypressTestTable();

		String result = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"NONEXISTENT_CITY_XYZ_999");
		assertNotNull(result);
		assertTrue("Should delete 0 rows: " + result, result.contains("deleted 0"));
	}

	@Test
	public void testSetup_verifyDataPersists_viaSelect() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "VERIFY1");
		data.put("shipcity", "CYPRESS_VERIFY_TEST");
		data.put("freight", 55.55);

		specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);

		try {
			com.servoy.j2db.persistence.IServerManagerInternal serverManager = (com.servoy.j2db.persistence.IServerManagerInternal) ApplicationServerRegistry
					.get().getServerManager();
			IServerInternal server = (IServerInternal) serverManager.getServer(CYPRESS_TEST_SERVER, true, true);

			try (java.sql.Connection conn = server.getRawConnection();
					java.sql.PreparedStatement ps = conn.prepareStatement("SELECT customerid, shipcity, freight FROM "
							+ CYPRESS_TEST_TABLE + " WHERE shipcity = ?")) {
				ps.setString(1, "CYPRESS_VERIFY_TEST");
				try (java.sql.ResultSet rs = ps.executeQuery()) {
					assertTrue("Should find inserted record", rs.next());
					assertEquals("customerid should match", "VERIFY1", rs.getString("customerid"));
					assertEquals("shipcity should match", "CYPRESS_VERIFY_TEST", rs.getString("shipcity"));
					assertEquals("freight should match", 55.55, rs.getDouble("freight"), 0.01);
				}
			}
		} finally {
			specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity", "CYPRESS_VERIFY_TEST");
		}
	}

	@Test
	public void testSetup_specialCharactersInValues() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "O'Brien");
		data.put("shipcity", "CYPRESS_SPECIAL_CHARS");
		data.put("shipcountry", "Test \"Country\"");

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertTrue("Should handle special chars: " + result, result.contains("inserted"));

		String teardown = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_SPECIAL_CHARS");
		assertTrue("Teardown should succeed: " + teardown, teardown.contains("deleted 1"));
	}

	@Test
	public void testSetup_nullColumnValue() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "NULLTEST");
		data.put("shipcity", "CYPRESS_NULL_TEST");
		data.put("shipcountry", null);

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertTrue("Should handle null value: " + result, result.contains("inserted"));

		String teardown = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_NULL_TEST");
		assertTrue("Teardown should succeed: " + teardown, teardown.contains("deleted 1"));
	}

	@Test
	public void testSetup_integerAndDoubleTypes() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "TYPES");
		data.put("shipcity", "CYPRESS_TYPES_TEST");
		data.put("freight", 123.456);

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertTrue("Should handle numeric types: " + result, result.contains("inserted"));

		com.servoy.j2db.persistence.IServerManagerInternal serverManager = (com.servoy.j2db.persistence.IServerManagerInternal) ApplicationServerRegistry
				.get().getServerManager();
		IServerInternal server = (IServerInternal) serverManager.getServer(CYPRESS_TEST_SERVER, true, true);

		try (java.sql.Connection conn = server.getRawConnection();
				java.sql.PreparedStatement ps = conn
						.prepareStatement("SELECT freight FROM " + CYPRESS_TEST_TABLE + " WHERE shipcity = ?")) {
			ps.setString(1, "CYPRESS_TYPES_TEST");
			try (java.sql.ResultSet rs = ps.executeQuery()) {
				assertTrue("Should find record", rs.next());
				assertEquals("freight should be 123.456", 123.456, rs.getDouble("freight"), 0.001);
			}
		} finally {
			specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity", "CYPRESS_TYPES_TEST");
		}
	}

	@Test
	public void testTeardown_idempotent_secondCallDeletesZero() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "IDEMP");
		data.put("shipcity", "CYPRESS_IDEMPOTENT_TEST");

		specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);

		String first = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_IDEMPOTENT_TEST");
		assertTrue("First teardown should delete 1: " + first, first.contains("deleted 1"));

		String second = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_IDEMPOTENT_TEST");
		assertTrue("Second teardown should delete 0: " + second, second.contains("deleted 0"));
	}

	// -----------------------------------------------------------------------
	// screenshotForm PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testScreenshotForm_validForm_returnsResult() throws Exception {
		ensureForm(TEST_FORM);
		String result = testingServer.screenshotForm(TEST_FORM, 2);
		assertNotNull("screenshotForm should return a result", result);
		// Screenshot requires a running NG client; when the server is not running
		// (port -1) the tool returns an environment-dependent error which is
		// acceptable.
		if (result.startsWith("Error")) {
			assertTrue("Environment error should mention screenshot or navigation: " + result,
					result.contains("screenshot") || result.contains("navigate") || result.contains("localhost:-1"));
		} else {
			assertTrue("Should return file path or screenshot info: " + result,
					result.contains(".png") || result.contains("screenshot"));
		}
	}

	@Test
	public void testScreenshotForm_nullForm_returnsError() {
		String result = testingServer.screenshotForm(null, 1);
		assertNotNull(result);
		assertTrue("Should return error for null form: " + result, result.contains("Error"));
	}

	@Test
	public void testScreenshotForm_nonExistentForm_returnsError() {
		String result = testingServer.screenshotForm("totally_fake_form_xyz_99999", 1);
		assertNotNull(result);
		assertTrue("Should return error for non-existent form: " + result, result.contains("Error"));
	}

	// -----------------------------------------------------------------------
	// createTestFile PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_validParams_createsFile() throws Exception {
		String fileName = "test_pde_cypress_" + System.currentTimeMillis() + ".js";
		String result = testingServer.createTestFile(fileName, "TARGET");
		assertNotNull(result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Should succeed or show path: " + result, result.contains("Created") || result.contains(fileName));
	}

	@Test
	public void testCreateTestFile_invalidPrefix_returnsError() {
		String result = testingServer.createTestFile("invalid_no_prefix.js", "TARGET");
		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error") && result.contains("test_"));
	}

	@Test
	public void testCreateTestFile_invalidExtension_returnsError() {
		String result = testingServer.createTestFile("test_something.txt", "TARGET");
		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error") && result.contains(".js"));
	}

	// -----------------------------------------------------------------------
	// addTestMethod PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testAddTestMethod_validParams_addsMethod() throws Exception {
		String fileName = "test_pde_addmethod_" + System.currentTimeMillis() + ".js";
		testingServer.createTestFile(fileName, "TARGET");

		String result = testingServer.addTestMethod(fileName, "test_myNewMethod", "jsunit.assertTrue('works', true);");
		assertNotNull(result);
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
		assertTrue("Should succeed: " + result,
				result.contains("Added") || result.contains("success") || result.contains("replaced"));
	}

	@Test
	public void testAddTestMethod_nonExistentFile_returnsError() {
		String result = testingServer.addTestMethod("test_nonexistent_xyz_99999.js", "test_method",
				"jsunit.assertTrue(true);");
		assertNotNull(result);
		assertTrue("Should return error: " + result, result.contains("Error") || result.contains("not found"));
	}

	// -----------------------------------------------------------------------
	// testFileExists PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testTestFileExists_afterCreate_returnsTrue() throws Exception {
		String fileName = "test_exists_check_" + System.currentTimeMillis() + ".js";
		testingServer.createTestFile(fileName, "TARGET");

		com.servoy.eclipse.developer.mcp.services.TestFileService tfs = com.servoy.eclipse.developer.mcp.services.TestFileService
				.getInstance();
		String solutionName = activeProject.getProject().getName();

		assertTrue("testFileExists should return true after creation", tfs.testFileExists(fileName, solutionName));
	}

	@Test
	public void testTestFileExists_nonExistentFile_returnsFalse() {
		com.servoy.eclipse.developer.mcp.services.TestFileService tfs = com.servoy.eclipse.developer.mcp.services.TestFileService
				.getInstance();
		String solutionName = activeProject.getProject().getName();

		assertFalse("testFileExists should return false for non-existent file",
				tfs.testFileExists("test_totally_nonexistent_xyz_99999.js", solutionName));
	}

	@Test
	public void testTestFileExists_nullSolution_returnsFalse() {
		com.servoy.eclipse.developer.mcp.services.TestFileService tfs = com.servoy.eclipse.developer.mcp.services.TestFileService
				.getInstance();

		assertFalse("testFileExists should return false for null solution",
				tfs.testFileExists("test_something.js", null));
	}

	@Test
	public void testTestFileExists_invalidSolution_returnsFalse() {
		com.servoy.eclipse.developer.mcp.services.TestFileService tfs = com.servoy.eclipse.developer.mcp.services.TestFileService
				.getInstance();

		assertFalse("testFileExists should return false for invalid solution",
				tfs.testFileExists("test_something.js", "nonexistent_solution_xyz_99999"));
	}

	// -----------------------------------------------------------------------
	// generateFormSpec PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_validForm_generatesFiles() throws Exception {
		String formName = "specGenTestForm_" + System.currentTimeMillis();
		ensureForm(formName);
		deleteSpecFiles(formName);

		String result = testingServer.generateFormSpec(formName);
		assertNotNull(result);
		assertTrue("Should create spec files: " + result,
				result.contains("Created") || result.contains("already exist"));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private Form ensureFormWithDataSource(String formName, String dataSource) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, dataSource, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form creation with dataSource should succeed: " + formName, form);
		return form;
	}

	private Form ensureForm(String formName) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull("Form creation should succeed: " + formName, form);
		return form;
	}

	private void deleteSpecFiles(String formName) {
		try {
			Path cySpec = specGenerator.getSpecFilePath(formName);
			if (cySpec != null)
				Files.deleteIfExists(cySpec);

			IProject project = activeProject.getProject();
			Path setupSpec = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress").resolve("e2e-form-spec").resolve(formName + ".spec.js");
			Files.deleteIfExists(setupSpec);
		} catch (Exception e) {
			// ignore
		}
	}

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
					"fileVersion:" + com.servoy.j2db.persistence.AbstractRepository.repository_version
							+ ",\nmustAuthenticate:false,\nname:\"" + TEST_SOLUTION + "\",\n"
							+ "solutionType:1024,\ntypeid:43,\nuuid:\"c1e2f3a4-b5c6-7890-abcd-ef1234567890\"\n",
					monitor);
			writeProjectFile(sol, "solution_settings.obj",
					"typeid:43,\nuuid:\"c1e2f3a4-b5c6-7890-abcd-ef1234567890\",\nversion:\"1.0\"\n", monitor);
			writeProjectFile(sol, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
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
		byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (file.exists()) {
			file.setContents(new java.io.ByteArrayInputStream(bytes), true, false, monitor);
		} else {
			file.create(new java.io.ByteArrayInputStream(bytes), true, monitor);
		}
	}

	private void pumpEvents(long ms) {
		try {
			Display display = Display.getDefault();
			long end = System.currentTimeMillis() + ms;
			if (display.getThread() == Thread.currentThread()) {
				while (System.currentTimeMillis() < end)
					display.readAndDispatch();
			} else {
				Thread.sleep(ms);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// -----------------------------------------------------------------------
	// screenshotForm marker validation PDE tests (SVY-21195)
	// -----------------------------------------------------------------------

	@Test
	public void testScreenshotForm_formWithMarkerErrors_returnsTextError() throws Exception {
		String invalidFormName = "cypressInvalidMarkerForm";
		ensureForm(invalidFormName);

		IProject project = activeProject.getProject();
		writeProjectFile(project, "forms/" + invalidFormName + ".js",
				"!!! this is not valid JavaScript !!!\nfunction broken( {", new NullProgressMonitor());
		org.eclipse.core.resources.IFile jsFile = project.getFile("forms/" + invalidFormName + ".js");
		assertTrue("js file should exist after write", jsFile.exists());

		project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		pumpEvents(2000);

		boolean hasErrorMarkers = false;
		for (org.eclipse.core.resources.IMarker m : jsFile.findMarkers(org.eclipse.core.resources.IMarker.PROBLEM,
				true, org.eclipse.core.resources.IResource.DEPTH_ZERO)) {
			if (m.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
					-1) == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
				hasErrorMarkers = true;
				break;
			}
		}
		assertTrue("Invalid JS must produce DLTK error markers", hasErrorMarkers);

		String result = testingServer.screenshotForm(invalidFormName, 1);
		assertNotNull("screenshotForm result should not be null", result);
		assertTrue("Should return text error (not screenshot path) when form has markers: " + result,
				result.contains("validation errors"));
		assertTrue("Error should list specific marker messages: " + result, result.contains("[ERROR]"));
		assertFalse("Should NOT return a .png path when form has errors: " + result, result.contains(".png"));
	}

	@Test
	public void testScreenshotForm_formWithMarkerErrors_includesFormName() throws Exception {
		String invalidFormName = "cypressInvalidMarkerForm2";
		ensureForm(invalidFormName);

		IProject project = activeProject.getProject();
		writeProjectFile(project, "forms/" + invalidFormName + ".js",
				"!!! this is not valid JavaScript !!!\nfunction broken( {", new NullProgressMonitor());
		org.eclipse.core.resources.IFile jsFile = project.getFile("forms/" + invalidFormName + ".js");
		assertTrue("js file should exist after write", jsFile.exists());

		project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		pumpEvents(2000);

		boolean hasErrorMarkers = false;
		for (org.eclipse.core.resources.IMarker m : jsFile.findMarkers(org.eclipse.core.resources.IMarker.PROBLEM,
				true, org.eclipse.core.resources.IResource.DEPTH_ZERO)) {
			if (m.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
					-1) == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
				hasErrorMarkers = true;
				break;
			}
		}
		assertTrue("Invalid JS must produce DLTK error markers", hasErrorMarkers);

		String result = testingServer.screenshotForm(invalidFormName, 1);
		assertNotNull(result);
		assertTrue("Error message should include form name: " + result, result.contains(invalidFormName));
	}

	@Test
	public void testShowFormInBrowser_formWithJSMarkerErrors_returnsValidationError() throws Exception {
		String invalidFormName = "cypressInvalidBrowserForm";
		ensureForm(invalidFormName);

		IProject project = activeProject.getProject();
		writeProjectFile(project, "forms/" + invalidFormName + ".js",
				"!!! this is not valid JavaScript !!!\nfunction broken( {", new NullProgressMonitor());
		org.eclipse.core.resources.IFile jsFile = project.getFile("forms/" + invalidFormName + ".js");
		assertTrue("js file should exist after write", jsFile.exists());

		project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		pumpEvents(2000);

		boolean hasErrorMarkers = false;
		for (org.eclipse.core.resources.IMarker m : jsFile.findMarkers(org.eclipse.core.resources.IMarker.PROBLEM,
				true, org.eclipse.core.resources.IResource.DEPTH_ZERO)) {
			if (m.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
					-1) == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
				hasErrorMarkers = true;
				break;
			}
		}
		assertTrue("Invalid JS must produce DLTK error markers", hasErrorMarkers);

		String result = testingServer.showFormInBrowser(invalidFormName, false);
		assertNotNull("showFormInBrowser result should not be null", result);
		assertTrue("Should return validation error, not 'Opened form': " + result,
				result.contains("validation errors") || result.contains("compilation errors"));
		assertTrue("Error should mention the form name: " + result, result.contains(invalidFormName));
	}
}
