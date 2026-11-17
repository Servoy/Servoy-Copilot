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

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.MessageConsole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.cypress.actions.CypressConsoleUtil;
import com.servoy.eclipse.cypress.services.FormSpecGenerator;
import com.servoy.eclipse.cypress.services.FormSpecRunner;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.AbstractTitaniumIntegrationTestCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.developer.mcp.services.TestFileService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.server.shared.IApplicationServerSingleton;

/**
 * Integration tests for the Cypress form testing workflow: showFormInBrowser ->
 * generateSpec -> testForm (Cypress run).
 *
 * These tests require a running Servoy application server and an active
 * solution. They fail fast when the environment is not
 * available.
 */
public class CypressFormTestingIntegrationTest extends AbstractTitaniumIntegrationTestCopy {

	private static final String TEST_SOLUTION = "test_cypress_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String TEST_FORM = "cypressTestForm";

	private ServoyTestingServer testingServer;
	private FormSpecGenerator specGenerator;
	private FormSpecRunner specRunner;
	private ServoyProject activeProject;

	public CypressFormTestingIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@org.junit.jupiter.api.BeforeAll
	public static void adjustTitaniumBuildJobEnablementForThisClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();

		// Cypress tests need the node install extracted and the titanium client built — re-enable the copy/npm cycle.
		Activator.setNodeExtractionAndTitaniumBuildDisabled(false);
	}

	@BeforeEach
	public void setUp() throws Exception {
		testingServer = new ServoyTestingServer();
		specGenerator = new FormSpecGenerator();
		specRunner = new FormSpecRunner();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();

		long x = System.currentTimeMillis();
		System.out.println("*** " + this.getClass().getName() + " writing solution, activating project and building");

		ensureTestSolutionInWorkspace(null, null);

		ensureActiveProject();
		
		System.out.println("*** activating project and building took: " + String.format( "%.2f", ((System.currentTimeMillis() - x) / 1000d)) + " s");

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");

		ensureNgClientBuilt();
	}

	/**
	 * Cypress drives the real NG client, so the Titanium NG client of the test solution must actually be built. In a
	 * fresh workspace (a new Jenkins workspace, or a deleted test workspace) the solution activation copies the
	 * client sources but does not produce a build, so the app serves its "TiNG resources are not generated correctly
	 * yet" page and every Cypress run reloads it until Cypress gives up. The base class only builds when the solution
	 * was already active, so build it here once when the output is missing. This is cheap when it is already there.
	 */
	private void ensureNgClientBuilt() throws Exception {
		java.io.File targetFolder = Activator.getInstance().getMainTargetFolder();
		if (targetFolder == null) {
			return;
		}
		java.io.File builtIndex = new java.io.File(targetFolder, TEST_SOLUTION + "/dist/app/browser/index.html");
		if (!builtIndex.exists()) {
			System.out.println("*** " + TEST_SOLUTION + " has no built NG client (" + builtIndex + "), building it");
			buildTitaniumIfNeeded();
			System.out.println("*** NG client built: " + builtIndex.exists());
		}
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

		assertNotNull(result, "Result should not be null");
		assertTrue(result.contains("formpreview=" + TEST_FORM), "Should contain URL");

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		assertNotNull(cySpec, "Cypress spec path should not be null");
		assertTrue(Files.exists(cySpec), "Cypress spec file should exist after showFormInBrowser");

		Path setupPath = specGenerator.getSetupFilePath(TEST_FORM, TEST_SOLUTION);
		assertTrue(Files.exists(setupPath), "Setup spec.js file should exist in cy-form-spec/ directory");
	}

	@Test
	public void testShowFormInBrowser_doesNotRegenerateExistingSpec() throws Exception {
		ensureForm(TEST_FORM);

		if (!specGenerator.specExists(TEST_FORM, TEST_SOLUTION))
			specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		long firstModified = Files.getLastModifiedTime(cySpec).toMillis();


		testingServer.showFormInBrowser(TEST_FORM, false);
		Thread.sleep(1100);
		long secondModified = Files.getLastModifiedTime(cySpec).toMillis();

		assertTrue(firstModified == secondModified, "Spec file should not be regenerated if it already exists");
	}

	// -----------------------------------------------------------------------
	// specExists tests
	// -----------------------------------------------------------------------

	@Test
	public void testSpecExists_falseBeforeGeneration() {
		boolean exists = specGenerator.specExists("nonExistentForm_XYZ", TEST_SOLUTION);
		assertTrue(!exists, "specExists should return false for non-existent form");
	}

	@Test
	public void testSpecExists_trueAfterGeneration() throws Exception {
		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM, TEST_SOLUTION))
			specGenerator.generateSpec(TEST_FORM);

		boolean exists = specGenerator.specExists(TEST_FORM, TEST_SOLUTION);
		assertTrue(exists, "specExists should return true after spec is generated");
	}

	// -----------------------------------------------------------------------
	// generateFormSpec tests
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_returnsSuccessMessage() throws Exception {
		ensureForm(TEST_FORM);

		deleteSpecFiles(TEST_FORM);

		String result = testingServer.generateFormSpec(TEST_FORM, null);

		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("Created"), "Should mention created files");
	}

	@Test
	public void testGenerateFormSpec_nonExistentForm_returnsError() {
		String result = testingServer.generateFormSpec("totally_fake_form_xyz", null);

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"),
				"Should return error for non-existent form");
	}

	// -----------------------------------------------------------------------
	// testForm (Cypress run) tests - only 1 test actually runs Cypress
	// -----------------------------------------------------------------------

	@Test
	public void testTestForm_runsAndReturnsResults() throws Exception {
		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM, TEST_SOLUTION))
			specGenerator.generateSpec(TEST_FORM);

		String result = testingServer.testForm(TEST_FORM);

		assertNotNull(result, "testForm result should not be null");
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("passed") || result.contains("failed"), "testForm should return results");

		MessageConsole console = CypressConsoleUtil.findOrCreateConsole();
		pumpEventsUntil(300, () -> {
			String consoleContent = console.getDocument().get();
			assertNotNull(consoleContent, "Console document should not be null after testForm");
			assertTrue(consoleContent.contains("passed") || consoleContent.contains("failed"),
					"Console should contain test result written by testForm: " + consoleContent);
		});
	}

	@Test
	public void testTestForm_autoGeneratesSpecIfMissing() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String result = testingServer.testForm(TEST_FORM);

		assertNotNull(result);
		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		assertTrue(cySpec != null && Files.exists(cySpec), "testForm should auto-generate spec file");
	}

	// -----------------------------------------------------------------------
	// Cypress config tests
	// -----------------------------------------------------------------------

	@Test
	public void testCypressConfigIsGenerated() throws Exception {
		ensureForm(TEST_FORM);
		if (!specGenerator.specExists(TEST_FORM, TEST_SOLUTION))
			specGenerator.generateSpec(TEST_FORM);

		specRunner.runFormCypressTests(TEST_FORM, true);

		Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		Path scriptsRoot = workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts");
		Path configFile = scriptsRoot.resolve("cypress.config.js");

		assertTrue(Files.exists(configFile), "cypress.config.js should be generated");

		String content = Files.readString(configFile);
		assertTrue(content.contains("baseUrl"), "Config should have baseUrl");
		assertTrue(content.contains("specPattern"), "Config should have specPattern");
		assertTrue(content.contains("video: true"), "Config should have video setting");
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
		assertTrue(result.contains("Created"), "Should report creation");

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		assertNotNull(cySpec);
		assertTrue(Files.exists(cySpec), "Cypress spec file should be created");
	}

	@Test
	public void testGenerateFormSpec_createsSetupJsFile() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path setupPath = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile()
				.toPath().resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress")
				.resolve("cy-form-spec").resolve(TEST_SOLUTION + "." + TEST_FORM + ".spec.js");
		assertTrue(Files.exists(setupPath), "Setup .spec.js should be created in cy-form-spec/");
	}

	@Test
	public void testGenerateFormSpec_specCyUsesDataCySelectors() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		String content = Files.readString(cySpec);

		// Forms with named elements use [data-cy] attribute selectors;
		// empty forms (no elements) fall back to .svy-form CSS class selector
		assertTrue(content.contains("data-cy") || content.contains(".svy-form"),
				"Cypress spec must use data-cy selectors or .svy-form for empty forms");
		assertTrue(content.contains(TEST_FORM + ".") || content.contains(TEST_FORM + "'"),
				"Cypress spec must reference form name");
	}

	@Test
	public void testGenerateFormSpec_specCyUsesBeforeEach() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		String content = Files.readString(cySpec);

		assertTrue(content.contains("beforeEach("), "Cypress spec must use beforeEach");
		assertTrue(content.contains("cy.visit("), "Cypress spec must use cy.visit");
	}

	@Test
	public void testGenerateFormSpec_specCyUsesSvyTestMode() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		String content = Files.readString(cySpec);

		assertTrue(content.contains("svy_testmode=true"), "Cypress spec must include svy_testmode=true in URL");
	}

	@Test
	public void testGenerateFormSpec_setupJsHasProperties() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path setupPath = specGenerator.getSetupFilePath(TEST_FORM, TEST_SOLUTION);
		String content = Files.readString(setupPath);

		assertTrue(content.contains("@properties"), "Setup must have @properties annotation");
		assertTrue(content.contains("function spec_setUp()"), "Setup must have spec_setUp");
		assertTrue(content.contains("function spec_tearDown()"), "Setup must have spec_tearDown");
	}

	@Test
	public void testGenerateFormSpec_specInE2eFormDir() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		assertNotNull(cySpec);
		String path = cySpec.toString().replace('\\', '/');
		assertTrue(path.contains("jenkins-custom") && path.contains("e2e-test-scripts") && path.contains("cypress")
						&& path.contains("cy-form"),
				"Cypress spec should be in jenkins-custom/e2e-test-scripts/cypress/cy-form/ directory: " + path);
		assertTrue(!path.contains("medias"), "Cypress spec must no longer live under medias/tests: " + path);
	}

	@Test
	public void testGenerateFormSpec_runCypressDirectly() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		specGenerator.generateSpec(TEST_FORM);

		String result = specRunner.runFormCypressTests(TEST_FORM, true);

		assertNotNull(result, "runFormCypressTests result should not be null");
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("passed") || result.contains("failed") || result.contains("timed out"),
				"runFormCypressTests should return pass/fail or timed out");
	}

	// -----------------------------------------------------------------------
	// SVY-21619: always-on viewport-measurement reporting, end to end (N1)
	// -----------------------------------------------------------------------

	/**
	 * Proves the anti-false-negative core of SVY-21619 against the real NG client: a form spec generated WITH
	 * {@code viewportWidths="375,1024"} and run through the real Cypress runner must surface the measured
	 * {@code window.innerWidth} and the rendered container width in the returned output. This is the end-to-end
	 * proof that the spec's {@code cy.task('svyMeasure', 'SVY_MEASURE ...')} reaches Cypress stdout (via the task's
	 * Node handler {@code console.log}, which {@code cypress run} prints and the Java runner captures - unlike
	 * {@code cy.log}) and that the runner's {@code hoistMeasurementMarkers} lifts it into the returned
	 * "Measured layout" summary.
	 * <p>
	 * Skips only on a genuine environment gap (no NG client / no browser / Cypress not installed), following the
	 * same discipline as the sibling Cypress integration tests - never to dodge an assertion. No {@code Thread.sleep}.
	 */
	@Test
	public void testGeneratedSpecWithViewportWidths_surfacesMeasuredLayout() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		// Remove any stale cypress.config.js from an earlier Developer session: an old config may still carry
		// supportFile: false or lack the svyMeasure setupNodeEvents registration (pre-SVY-21619), which would drop
		// the SVY_MEASURE markers from stdout and make this an environment-staleness failure rather than a real
		// task->Node->stdout check. The runner regenerates the correct config (supportFile: 'cypress/support/e2e.js'
		// plus the svyMeasure task) when it is missing or stale, but delete it here to exercise the fresh-write path.
		Path scriptsRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
				.resolve("jenkins-custom").resolve("e2e-test-scripts");
		Files.deleteIfExists(scriptsRoot.resolve("cypress.config.js"));
		Files.deleteIfExists(scriptsRoot.resolve("cypress.config.ts"));

		// Generate the spec WITH responsive widths so it emits both the always-on measureContainer and the
		// per-width measureAtViewport blocks that log SVY_MEASURE lines.
		String genResult = specGenerator.generateSpec(TEST_FORM, null, java.util.List.of(375, 1024));
		assertNotNull(genResult, "generateSpec(form, null, widths) result should not be null");
		assertFalse(genResult.startsWith("Error"), "spec generation should not error: " + genResult);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		assertNotNull(cySpec);
		assertTrue(Files.exists(cySpec), "the generated spec file must exist");
		String specContent = Files.readString(cySpec);
		assertTrue(specContent.contains("cy.measureContainer('.svy-form');"),
				"the generated spec must carry the always-on measureContainer call: " + specContent);
		assertTrue(specContent.contains("cy.measureAtViewport('.svy-form', 375, 1080);"),
				"the generated spec must carry the 375px responsive block");
		assertTrue(specContent.contains("cy.measureAtViewport('.svy-form', 1024, 1080);"),
				"the generated spec must carry the 1024px responsive block");

		String result = specRunner.runFormCypressTests(TEST_FORM, true);
		assertNotNull(result, "runFormCypressTests result should not be null");

		// Genuine environment gap -> skip via Assumptions.abort (never to dodge the assertion below), the same
		// render-prerequisite skip discipline the sibling getFormLayout integration tests use. Cypress/NG
		// client/browser not being available on this agent is an acceptable reason to not prove N1 here.
		abortIfNgClientNotBuilt(result);
		boolean ran = result.contains("passed") || result.contains("failed");
		if (!ran || result.contains("timed out")) {
			org.junit.jupiter.api.Assumptions.abort("Cypress render prerequisite not met (NG client / browser / Cypress "
					+ "not available on this agent), skipping: " + result.substring(0, Math.min(result.length(), 400)));
		}

		// N1: the returned output must surface the measured innerWidth and container width (the hoisted
		// "Measured layout" summary built from the SVY_MEASURE lines the svyMeasure task console.logs to stdout).
		assertTrue(result.contains("Measured layout") || result.contains("SVY_MEASURE") || result.contains("innerWidth="),
				"runner output must surface the hoisted measurement summary (cy.task svyMeasure -> Node console.log -> stdout -> hoistMeasurementMarkers): " + result);
		assertTrue(result.contains("innerWidth=") && result.contains("containerWidth="),
				"runner output must report both the measured window.innerWidth and the container width: " + result);
	}

	// -----------------------------------------------------------------------
	// SVY-21619: viewportWidths through the real MCP tool methods
	// -----------------------------------------------------------------------

	private Path e2eScriptsRoot() {
		return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve("jenkins-custom")
				.resolve("e2e-test-scripts");
	}

	/**
	 * Skips (never fails) a test whose Cypress run could not load the form at all. When the Titanium NG client has not
	 * been built in the test workspace, the app serves a "TiNG resources are not generated correctly yet" page that
	 * reloads itself, and Cypress gives up with "The application redirected to ... more than 20 times" on the very first
	 * {@code cy.visit}. The viewport/measurement code never runs in that case, so the assertions about it cannot be
	 * evaluated: that is an environment gap, the same kind {@code renderPrerequisiteSkipReason} skips on elsewhere.
	 * The older Cypress tests in this class accept "failed" as a valid outcome and so do not notice it.
	 */
	private static void abortIfNgClientNotBuilt(String result) {
		if (result != null && result.contains("redirected to") && result.contains("more than 20 times")) {
			org.junit.jupiter.api.Assumptions.abort("Cypress could not load the form: the Titanium NG client is not built in this "
					+ "test workspace (the app keeps reloading its 'TiNG resources are not generated' page), so the "
					+ "measurement assertions cannot be evaluated here");
		}
	}

	@Test
	public void testGenerateFormSpec_withViewportWidths_emitsOneBlockPerWidthAndScaffoldsCommands() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		// padded input with a trailing comma and a duplicate: the tool must normalise it, not reject it
		String result = testingServer.generateFormSpec(TEST_FORM, " 375 , 1024 ,375");

		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "valid widths must not produce an error: " + result);
		assertTrue(result.contains("Created"), "the spec must be created: " + result);

		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		String spec = Files.readString(cySpec);
		assertTrue(spec.contains("cy.measureContainer('.svy-form');"), "the always-on measurement must be emitted: " + spec);
		int at375 = spec.indexOf("cy.measureAtViewport('.svy-form', 375, 1080);");
		int at1024 = spec.indexOf("cy.measureAtViewport('.svy-form', 1024, 1080);");
		assertTrue(at375 >= 0 && at1024 > at375, "one block per width, in the order given: " + spec);
		assertEquals(at375, spec.lastIndexOf("cy.measureAtViewport('.svy-form', 375, 1080);"), "the duplicate 375 must be dropped: " + spec);

		// the tool path must also have scaffolded the shared commands the spec calls
		String commands = Files.readString(e2eScriptsRoot().resolve("cypress").resolve("support").resolve("commands.js"));
		assertTrue(commands.contains("Cypress.Commands.add('measureAtViewport'"), commands);
		assertTrue(commands.contains("Cypress.Commands.add('measureContainer'"), commands);
	}

	@Test
	public void testGenerateFormSpec_existingSpec_isNeverOverwrittenSoNewWidthsAreNotApplied() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String first = testingServer.generateFormSpec(TEST_FORM, null);
		assertTrue(first.contains("Created"), first);
		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		String before = Files.readString(cySpec);

		String second = testingServer.generateFormSpec(TEST_FORM, "375,1024");

		assertTrue(second.startsWith("Spec files already exist"), "an existing spec must be reported, not rewritten: " + second);
		assertEquals(before, Files.readString(cySpec), "the existing spec must be left byte-for-byte unchanged");
		assertFalse(Files.readString(cySpec).contains("cy.measureAtViewport"),
				"widths supplied for an existing spec are not applied: the caller has to delete the spec first");
	}

	@Test
	public void testGenerateFormSpec_invalidViewportWidths_returnsErrorAndWritesNothing() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		String result = testingServer.generateFormSpec(TEST_FORM, "375,abc");

		assertTrue(result.startsWith("Error:") && result.contains("abc"), "the offending token must be named: " + result);
		Path cySpec = specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION);
		assertTrue(cySpec == null || !Files.exists(cySpec), "an invalid width must not leave a spec behind");
	}

	@Test
	public void testGenerateFormSpec_withViewportWidths_unknownForm_returnsFormNotFound() {
		String result = testingServer.generateFormSpec("totally_fake_form_xyz", "375");

		assertTrue(result.startsWith("Error: Form not found"), "the form lookup must still run with widths supplied: " + result);
	}

	@Test
	public void testGenerateCypressE2ETest_withViewportWidths_writesSpecWithBlocksAndFreshConfig() throws Exception {
		ensureForm(TEST_FORM);
		String solutionName = activeProject.getProject().getName();
		Path scriptsRoot = e2eScriptsRoot();
		Path spec = scriptsRoot.resolve("cypress").resolve("e2e").resolve(solutionName).resolve("svy21619_widths.cy.ts");
		Path config = scriptsRoot.resolve("cypress.config.js");
		Files.createDirectories(scriptsRoot);
		// a STALE config (no svyMeasure task) must be rewritten so the markers can reach stdout
		Files.writeString(config, "module.exports = { e2e: { baseUrl: 'http://stale' } };\n");
		try {
			String result = testingServer.generateCypressE2ETest(TEST_FORM, "svy-21619 widths", null, "svy21619_widths",
					"http://localhost:8183/", null, null, null, null, "375,1024");

			assertFalse(result.startsWith("Error"), "valid widths must not produce an error: " + result);
			assertTrue(result.startsWith("Created:") && result.contains("svy21619_widths.cy.ts"), "the file name must be normalised to .cy.ts: " + result);
			assertTrue(Files.exists(spec), "the E2E spec must be written under e2e/<solution>/");

			String content = Files.readString(spec);
			assertTrue(content.contains("cy.measureContainer('.svy-form');"), "the always-on measurement must be emitted: " + content);
			int at375 = content.indexOf("cy.measureAtViewport('.svy-form', 375, 1080);");
			int at1024 = content.indexOf("cy.measureAtViewport('.svy-form', 1024, 1080);");
			assertTrue(at375 >= 0 && at1024 > at375, "one block per width, in the order given: " + content);

			String configContent = Files.readString(config);
			assertTrue(configContent.contains("svyMeasure"), "the stale config must be rewritten with the svyMeasure task: " + configContent);
			assertTrue(configContent.contains("baseUrl: 'http://localhost:8183'"), "the trailing slash of the base URL must be trimmed: " + configContent);

			String commands = Files.readString(scriptsRoot.resolve("cypress").resolve("support").resolve("commands.js"));
			assertTrue(commands.contains("Cypress.Commands.add('measureAtViewport'"), commands);
		} finally {
			Files.deleteIfExists(spec);
		}
	}

	@Test
	public void testGenerateSpec_modelOverloadWithoutWidths_stillEmitsTheAlwaysOnMeasurement() throws Exception {
		ensureForm(TEST_FORM);
		deleteSpecFiles(TEST_FORM);

		// the two-argument overload (form, model) predates viewportWidths and must keep delegating to the widths-aware one
		String result = specGenerator.generateSpec(TEST_FORM, (com.servoy.eclipse.cypress.services.FormSpecModel) null);

		assertFalse(result.startsWith("Error"), result);
		String spec = Files.readString(specGenerator.getSpecFilePath(TEST_FORM, TEST_SOLUTION));
		assertTrue(spec.contains("cy.measureContainer('.svy-form');"), "reporting is always on, even through the old overload: " + spec);
		assertFalse(spec.contains("cy.measureAtViewport"), "no responsive block without widths: " + spec);
	}

	@Test
	public void testGenerateCypressE2ETest_unreadableConfig_isTreatedAsStaleAndTheFailureIsReported() throws Exception {
		ensureForm(TEST_FORM);
		String solutionName = activeProject.getProject().getName();
		Path scriptsRoot = e2eScriptsRoot();
		Path spec = scriptsRoot.resolve("cypress").resolve("e2e").resolve(solutionName).resolve("svy21619_unreadable.cy.ts");
		Path config = scriptsRoot.resolve("cypress.config.js");
		Files.deleteIfExists(config);
		// a directory where the config file should be: it exists but cannot be read as a file, so it is "stale" and
		// cannot be rewritten either - the tool must report an error instead of claiming success
		Files.createDirectories(config);
		try {
			String result = testingServer.generateCypressE2ETest(TEST_FORM, "svy-21619 unreadable", null, "svy21619_unreadable", null, null,
					null, null, null, "375");

			assertTrue(result.startsWith("Error:"), "an unwritable config must surface as an error, not a silent success: " + result);
		} finally {
			Files.deleteIfExists(config);
			Files.deleteIfExists(spec);
		}
	}

	// -----------------------------------------------------------------------
	// SVY-21619: the E2E runner (testE2E -> FormSpecRunner.runE2ECypressTests), pass AND fail branch, on the real NG client
	// -----------------------------------------------------------------------

	private Path seedE2eSpec(String specName, String body) throws Exception {
		String solutionName = activeProject.getProject().getName();
		Path dir = e2eScriptsRoot().resolve("cypress").resolve("e2e").resolve(solutionName);
		Files.createDirectories(dir);
		Path spec = dir.resolve(specName + ".cy.js");
		String url = "solution/" + TEST_SOLUTION + "/index.html?formpreview=" + TEST_FORM + "&svy_testmode=true";
		Files.writeString(spec, "describe('" + specName + "', () => {\n  it('" + specName + "', () => {\n    cy.visit('" + url + "');\n"
				+ "    cy.get('.svy-form', { timeout: 30000 }).should('exist');\n" + body + "  });\n});\n");
		return spec;
	}

	@Test
	public void testTestE2E_passingSpec_hoistsTheMeasuredLayoutAndRewritesAMissingConfig() throws Exception {
		ensureForm(TEST_FORM);
		Path config = e2eScriptsRoot().resolve("cypress.config.js");
		Files.deleteIfExists(config);
		Files.deleteIfExists(e2eScriptsRoot().resolve("cypress.config.ts"));
		Path spec = seedE2eSpec("svy21619_e2e_pass",
				"    cy.measureContainer('.svy-form');\n    cy.measureAtViewport('.svy-form', 375, 1080);\n");
		try {
			String result = testingServer.testE2E("svy21619_e2e_pass");
			abortIfNgClientNotBuilt(result);

			assertTrue(result.startsWith("**E2E Spec Results: svy21619_e2e_pass**"), "the run must produce a results envelope: " + result);
			assertTrue(result.contains("All tests passed!"), "the seeded spec must pass: " + result);
			assertTrue(result.contains("**Measured layout:**"), "the measurement summary must be hoisted on a PASS: " + result);
			assertTrue(result.contains("requested=375") && result.contains("innerWidth=375"),
					"the per-width measurement must report the applied width: " + result);
			assertTrue(Files.exists(config) && Files.readString(config).contains("svyMeasure"),
					"a missing cypress.config.js must be written with the svyMeasure task before the run");
		} finally {
			Files.deleteIfExists(spec);
		}
	}

	@Test
	public void testTestE2E_failingSpec_stillHoistsTheMeasuredLayoutAndRewritesAStaleConfig() throws Exception {
		ensureForm(TEST_FORM);
		Path config = e2eScriptsRoot().resolve("cypress.config.js");
		Files.deleteIfExists(e2eScriptsRoot().resolve("cypress.config.ts"));
		Files.createDirectories(config.getParent());
		// supportFile:false and no svyMeasure task: exactly the stale shape that would swallow the markers
		Files.writeString(config, "module.exports = { e2e: { supportFile: false, specPattern: '**/*.cy.{js,ts}' } };\n");
		Path spec = seedE2eSpec("svy21619_e2e_fail",
				"    cy.measureContainer('.svy-form');\n    cy.then(() => { throw new Error('deliberate SVY-21619 failure'); });\n");
		try {
			String result = testingServer.testE2E("svy21619_e2e_fail");
			abortIfNgClientNotBuilt(result);

			assertTrue(result.startsWith("**E2E Spec Results: svy21619_e2e_fail**"), "the run must produce a results envelope: " + result);
			assertTrue(result.contains("Some tests failed:"), "the seeded spec must fail: " + result);
			assertTrue(result.contains("**Measured layout:**") && result.contains("innerWidth="),
					"the measurement summary must be hoisted on a FAIL too, that is the whole point: " + result);
			assertTrue(Files.readString(config).contains("svyMeasure") && !Files.readString(config).contains("supportFile: false"),
					"a stale cypress.config.js must be rewritten before the run");
		} finally {
			Files.deleteIfExists(spec);
		}
	}

	@Test
	public void testTestE2E_unknownSpec_returnsNotFound() throws Exception {
		ensureForm(TEST_FORM);

		String result = testingServer.testE2E("svy21619_does_not_exist");

		assertTrue(result.startsWith("Error: E2E spec file not found for 'svy21619_does_not_exist'"), result);
	}

	@Test
	public void testGenerateCypressE2ETest_withoutViewportWidths_stillReportsAtTheDefaultViewport() throws Exception {
		ensureForm(TEST_FORM);
		String solutionName = activeProject.getProject().getName();
		Path spec = e2eScriptsRoot().resolve("cypress").resolve("e2e").resolve(solutionName).resolve("svy21619_default.cy.ts");
		try {
			String result = testingServer.generateCypressE2ETest(TEST_FORM, "svy-21619 default", null, "svy21619_default.cy.ts", null,
					null, null, null, null, null);

			assertFalse(result.startsWith("Error"), result);
			String content = Files.readString(spec);
			assertTrue(content.contains("cy.measureContainer('.svy-form');"), "reporting is always on: " + content);
			assertFalse(content.contains("cy.measureAtViewport"), "no responsive block without widths: " + content);
		} finally {
			Files.deleteIfExists(spec);
		}
	}

	@Test
	public void testGenerateCypressE2ETest_invalidViewportWidths_returnsErrorAndWritesNothing() throws Exception {
		ensureForm(TEST_FORM);
		String solutionName = activeProject.getProject().getName();
		Path spec = e2eScriptsRoot().resolve("cypress").resolve("e2e").resolve(solutionName).resolve("svy21619_invalid.cy.ts");
		Files.deleteIfExists(spec);

		String result = testingServer.generateCypressE2ETest(TEST_FORM, "svy-21619 invalid", null, "svy21619_invalid", null, null, null,
				null, null, "0");

		assertTrue(result.startsWith("Error:") && result.contains("'0'"), "the offending token must be named: " + result);
		assertFalse(Files.exists(spec), "an invalid width must not leave a spec behind");
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

		String result = specRunner.runFormCypressTests(BUTTON_LABEL_FORM, true);

		assertNotNull(result, "Cypress button/label test result should not be null");
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("passed") || result.contains("failed") || result.contains("timed out"),
				"Cypress button/label test should return results");
	}

	@Test
	public void testCypress_generatedSpecPassesForButtonLabelForm() throws Exception {
		ensureButtonLabelForm();
		deleteSpecFiles(BUTTON_LABEL_FORM);

		specGenerator.generateSpec(BUTTON_LABEL_FORM);

		String result = specRunner.runFormCypressTests(BUTTON_LABEL_FORM, true);

		assertNotNull(result, "Generated spec run result should not be null");
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("passed") || result.contains("failed") || result.contains("timed out"),
				"Generated spec should return results");
	}

	private void ensureButtonLabelForm() throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(BUTTON_LABEL_FORM);
		if (existing != null)
			return;

		new ServoyArtifactCreationService().createForm(BUTTON_LABEL_FORM, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(BUTTON_LABEL_FORM);
		assertNotNull(form, "Button/label form creation should succeed");

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
		assertTrue(setupResult.contains("inserted"), "Setup should succeed: " + setupResult);
		assertTrue(setupResult.contains(CYPRESS_TEST_SERVER) && setupResult.contains(CYPRESS_TEST_TABLE),
				"Setup should mention server and table: " + setupResult);

		try {
			String showResult = testingServer.showFormInBrowser(formName, false);
			assertNotNull(showResult);
			assertTrue(showResult.contains("formpreview"), "Should contain URL: " + showResult);
		} finally {
			String teardownResult = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
					"CYPRESS_TEST_CITY");
			assertNotNull(teardownResult);
			assertTrue(teardownResult.contains("deleted"), "Teardown should succeed: " + teardownResult);
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
		assertNotNull(server, "cypress_test server must be available");

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
		assertTrue(result.contains("Error"), "Should return error: " + result);
	}

	@Test
	public void testExecuteTestTeardown_invalidServer_returnsError() {
		String result = specRunner.executeTestTeardown("nonexistent_server_xyz", "table", "col", "val");
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
	}

	@Test
	public void testExecuteTestSetup_nullParams_returnsError() {
		String result = specRunner.executeTestSetup(null, null, null);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error: " + result);
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
		assertTrue(r1.contains("inserted"), "First insert should succeed: " + r1);
		assertTrue(r1.equals("Test setup: inserted 1 row into " + CYPRESS_TEST_SERVER + "." + CYPRESS_TEST_TABLE),
				"First insert should have correct message format: " + r1);
		assertTrue(r2.contains("inserted"), "Second insert should succeed: " + r2);

		String teardown = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_MULTI_TEST");
		assertTrue(teardown.contains("deleted 2"), "Teardown should delete 2 rows: " + teardown);
	}

	@Test
	public void testSetup_returnMessageFormat() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "FMTTEST");
		data.put("shipcity", "CYPRESS_FORMAT_TEST");

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertEquals("Test setup: inserted 1 row into " + CYPRESS_TEST_SERVER + "." + CYPRESS_TEST_TABLE, result,
				"Return message should match exact format");

		specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity", "CYPRESS_FORMAT_TEST");
	}

	@Test
	public void testSetupAndTeardown_newTable_createAndDrop() throws Exception {
		ensureCypressTestTable();

		String dynamicTable = "cypress_dynamic_" + System.currentTimeMillis();

		com.servoy.j2db.persistence.IServerManagerInternal serverManager = (com.servoy.j2db.persistence.IServerManagerInternal) ApplicationServerRegistry
				.get().getServerManager();
		IServerInternal server = (IServerInternal) serverManager.getServer(CYPRESS_TEST_SERVER, true, true);
		assertNotNull(server, "Server must be available");

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
		assertTrue(setupResult.contains("inserted"), "Setup should succeed: " + setupResult);

		String teardownResult = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, dynamicTable, "name", "test_key");
		assertTrue(teardownResult.contains("deleted 1"), "Teardown should succeed: " + teardownResult);

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
		assertTrue(result.contains("deleted 0"), "Should delete 0 rows: " + result);
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
					assertTrue(rs.next(), "Should find inserted record");
					assertEquals("VERIFY1", rs.getString("customerid"), "customerid should match");
					assertEquals("CYPRESS_VERIFY_TEST", rs.getString("shipcity"), "shipcity should match");
					assertEquals(55.55, rs.getDouble("freight"), 0.01, "freight should match");
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
		assertTrue(result.contains("inserted"), "Should handle special chars: " + result);

		String teardown = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_SPECIAL_CHARS");
		assertTrue(teardown.contains("deleted 1"), "Teardown should succeed: " + teardown);
	}

	@Test
	public void testSetup_nullColumnValue() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "NULLTEST");
		data.put("shipcity", "CYPRESS_NULL_TEST");
		data.put("shipcountry", null);

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertTrue(result.contains("inserted"), "Should handle null value: " + result);

		String teardown = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_NULL_TEST");
		assertTrue(teardown.contains("deleted 1"), "Teardown should succeed: " + teardown);
	}

	@Test
	public void testSetup_integerAndDoubleTypes() throws Exception {
		ensureCypressTestTable();

		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("customerid", "TYPES");
		data.put("shipcity", "CYPRESS_TYPES_TEST");
		data.put("freight", 123.456);

		String result = specRunner.executeTestSetup(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, data);
		assertTrue(result.contains("inserted"), "Should handle numeric types: " + result);

		com.servoy.j2db.persistence.IServerManagerInternal serverManager = (com.servoy.j2db.persistence.IServerManagerInternal) ApplicationServerRegistry
				.get().getServerManager();
		IServerInternal server = (IServerInternal) serverManager.getServer(CYPRESS_TEST_SERVER, true, true);

		try (java.sql.Connection conn = server.getRawConnection();
				java.sql.PreparedStatement ps = conn
						.prepareStatement("SELECT freight FROM " + CYPRESS_TEST_TABLE + " WHERE shipcity = ?")) {
			ps.setString(1, "CYPRESS_TYPES_TEST");
			try (java.sql.ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next(), "Should find record");
				assertEquals(123.456, rs.getDouble("freight"), 0.001, "freight should be 123.456");
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
		assertTrue(first.contains("deleted 1"), "First teardown should delete 1: " + first);

		String second = specRunner.executeTestTeardown(CYPRESS_TEST_SERVER, CYPRESS_TEST_TABLE, "shipcity",
				"CYPRESS_IDEMPOTENT_TEST");
		assertTrue(second.contains("deleted 0"), "Second teardown should delete 0: " + second);
	}

	// -----------------------------------------------------------------------
	// screenshotForm PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testScreenshotForm_validForm_returnsResult() throws Exception {
		ensureForm(TEST_FORM);
		String result = testingServer.screenshotForm(TEST_FORM, 2);
		assertNotNull(result, "screenshotForm should return a result");
		// Screenshot requires a running NG client; when the server is not running
		// (port -1) the tool returns an environment-dependent error which is
		// acceptable.
		if (result.startsWith("Error")) {
			assertTrue(result.contains("screenshot") || result.contains("Cypress") || result.contains("Tomcat")
							|| result.contains("localhost:-1") || result.contains("Node.js"),
					"Environment error should mention Tomcat, Cypress, or localhost:-1: " + result);
		} else {
			assertTrue(result.contains(".png") || result.contains("screenshot"),
					"Should return file path or screenshot info: " + result);
		}
	}

	@Test
	public void testScreenshotForm_nullForm_returnsError() {
		String result = testingServer.screenshotForm(null, 1);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for null form: " + result);
	}

	@Test
	public void testScreenshotForm_nonExistentForm_returnsError() {
		String result = testingServer.screenshotForm("totally_fake_form_xyz_99999", 1);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Should return error for non-existent form: " + result);
	}

	// -----------------------------------------------------------------------
	// createTestFile PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_validParams_createsFile() throws Exception {
		String fileName = "test_pde_cypress_" + System.currentTimeMillis() + ".js";
		String result = testingServer.createTestFile(fileName, "TARGET");
		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("Created") || result.contains(fileName), "Should succeed or show path: " + result);
	}

	@Test
	public void testCreateTestFile_invalidPrefix_returnsError() {
		String result = testingServer.createTestFile("invalid_no_prefix.js", "TARGET");
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("test_"), "Should return error: " + result);
	}

	@Test
	public void testCreateTestFile_invalidExtension_returnsError() {
		String result = testingServer.createTestFile("test_something.txt", "TARGET");
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains(".js"), "Should return error: " + result);
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
		assertFalse(result.startsWith("Error"), "Should not start with Error: " + result);
		assertTrue(result.contains("Added") || result.contains("success") || result.contains("replaced"),
				"Should succeed: " + result);
	}

	@Test
	public void testAddTestMethod_nonExistentFile_returnsError() {
		String result = testingServer.addTestMethod("test_nonexistent_xyz_99999.js", "test_method",
				"jsunit.assertTrue(true);");
		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("not found"), "Should return error: " + result);
	}

	// -----------------------------------------------------------------------
	// testFileExists PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testTestFileExists_afterCreate_returnsTrue() throws Exception {
		String fileName = "test_exists_check_" + System.currentTimeMillis() + ".js";
		testingServer.createTestFile(fileName, "TARGET");

		TestFileService tfs = TestFileService
				.getInstance();
		String solutionName = activeProject.getProject().getName();

		assertTrue(tfs.testFileExists(fileName, solutionName), "testFileExists should return true after creation");
	}

	@Test
	public void testTestFileExists_nonExistentFile_returnsFalse() {
		TestFileService tfs = TestFileService
				.getInstance();
		String solutionName = activeProject.getProject().getName();

		assertFalse(tfs.testFileExists("test_totally_nonexistent_xyz_99999.js", solutionName),
				"testFileExists should return false for non-existent file");
	}

	@Test
	public void testTestFileExists_nullSolution_returnsFalse() {
		TestFileService tfs = TestFileService
				.getInstance();

		assertFalse(tfs.testFileExists("test_something.js", null),
				"testFileExists should return false for null solution");
	}

	@Test
	public void testTestFileExists_invalidSolution_returnsFalse() {
		TestFileService tfs = TestFileService
				.getInstance();

		assertFalse(tfs.testFileExists("test_something.js", "nonexistent_solution_xyz_99999"),
				"testFileExists should return false for invalid solution");
	}

	// -----------------------------------------------------------------------
	// generateFormSpec PDE tests
	// -----------------------------------------------------------------------

	@Test
	public void testGenerateFormSpec_validForm_generatesFiles() throws Exception {
		String formName = "specGenTestForm_" + System.currentTimeMillis();
		ensureForm(formName);
		deleteSpecFiles(formName);

		String result = testingServer.generateFormSpec(formName, null);
		assertNotNull(result);
		assertTrue(result.contains("Created") || result.contains("already exist"),
				"Should create spec files: " + result);
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
		assertNotNull(form, "Form creation with dataSource should succeed: " + formName);
		return form;
	}

	private Form ensureForm(String formName) throws Exception {
		Form existing = activeProject.getEditingSolution().getForm(formName);
		if (existing != null)
			return existing;

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form = activeProject.getEditingSolution().getForm(formName);
		assertNotNull(form, "Form creation should succeed: " + formName);
		return form;
	}

	private void deleteSpecFiles(String formName) {
		try {
			Path cySpec = specGenerator.getSpecFilePath(formName, TEST_SOLUTION);
			if (cySpec != null)
				Files.deleteIfExists(cySpec);

			Path setupSpec = specGenerator.getSetupFilePath(formName, TEST_SOLUTION);
			Files.deleteIfExists(setupSpec);
		} catch (Exception e) {
			// ignore
		}
	}

	// -----------------------------------------------------------------------
	// screenshotForm marker validation PDE tests (SVY-21195)
	// -----------------------------------------------------------------------

	/**
	 * Problem markers on the .js file must not gate the screenshot. Invalid JavaScript
	 * produces DLTK ERROR markers but does not stop the form from rendering, so
	 * screenshotForm must proceed rather than return a marker-gate error.
	 */
	@Test
	public void testScreenshotForm_jsFileErrors_doNotBlock() throws Exception {
		String invalidFormName = "cypressInvalidJsOnlyForm";
		ensureForm(invalidFormName);

		IProject project = activeProject.getProject();
		writeProjectFile(project, "forms/" + invalidFormName + ".js",
				"!!! this is not valid JavaScript !!!\nfunction broken( {", new NullProgressMonitor());
		org.eclipse.core.resources.IFile jsFile = project.getFile("forms/" + invalidFormName + ".js");
		assertTrue(jsFile.exists(), "js file should exist after write");

		project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());

		pumpEventsUntil(2000, () -> {
			assertTrue(hasErrorMarkers(jsFile), "Invalid JS must produce DLTK error markers on the .js file");
		});

		String result = testingServer.screenshotForm(invalidFormName, 1);
		assertNotNull(result, "screenshotForm result should not be null");
		assertFalse(result.contains("has problem markers") && result.startsWith("Error:"),
				"Problem markers must not gate the screenshot: " + result);
	}

	/**
	 * Problem markers on the .frm file must not gate the screenshot either. Marker presence
	 * is a poor proxy for render failure - an unresolved event method sits on the .frm file
	 * yet the form renders fine. Only property type mismatches block (see
	 * validateFormProperties).
	 */
	@Test
	public void testScreenshotForm_frmFileMarkers_doNotBlock() throws Exception {
		String invalidFormName = "cypressInvalidFrmForm";
		ensureForm(invalidFormName);

		IProject project = activeProject.getProject();
		writeProjectFile(project, "forms/" + invalidFormName + ".frm",
				"!!! this is not valid frm JSON !!!", new NullProgressMonitor());
		org.eclipse.core.resources.IFile frmFile = project.getFile("forms/" + invalidFormName + ".frm");
		assertTrue(frmFile.exists(), "frm file should exist after write");

		project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());

		pumpEventsUntil(2000, () -> {
			assertTrue(hasErrorMarkers(frmFile), "Invalid .frm JSON must produce ERROR markers on the .frm file");
		});

		String result = testingServer.screenshotForm(invalidFormName, 1);
		assertNotNull(result, "screenshotForm result should not be null");
		// Markers are reported as a Warning alongside the result, never as a blocking gate.
		assertFalse(result.startsWith("Error: Form '" + invalidFormName + "' has problem markers"),
				"Problem markers on .frm must not gate the screenshot: " + result);
	}

	/** Returns true when the given file carries at least one ERROR-severity problem marker. */
	private static boolean hasErrorMarkers(org.eclipse.core.resources.IFile file) {
		try {
			for (org.eclipse.core.resources.IMarker m : file.findMarkers(org.eclipse.core.resources.IMarker.PROBLEM, true,
					org.eclipse.core.resources.IResource.DEPTH_ZERO)) {
				if (m.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
						-1) == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
					return true;
				}
			}
		} catch (CoreException e) {
			fail("Cannot check for error markers: " + e.getMessage());
		}
		return false;
	}

	// AC1: showFormInBrowser never blocks — JS errors appear as Warning: text only
	@Test
	public void testShowFormInBrowser_formWithJSMarkerErrors_opensSuccessfully() throws Exception {
		String invalidFormName = "cypressInvalidBrowserForm";
		ensureForm(invalidFormName);

		IProject project = activeProject.getProject();
		writeProjectFile(project, "forms/" + invalidFormName + ".js",
				"!!! this is not valid JavaScript !!!\nfunction broken( {", new NullProgressMonitor());
		org.eclipse.core.resources.IFile jsFile = project.getFile("forms/" + invalidFormName + ".js");
		assertTrue(jsFile.exists(), "js file should exist after write");

		project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());

		pumpEventsUntil(2000, () -> {
			assertTrue(hasErrorMarkers(jsFile), "Invalid JS must produce DLTK error markers");
		});

		String result = testingServer.showFormInBrowser(invalidFormName, false);
		assertNotNull(result, "showFormInBrowser result should not be null");
		// AC1: showFormInBrowser must never block — must not return a bare "Error:" response
		assertFalse(result.startsWith("Error:"),
				"showFormInBrowser must never return a blocking Error: response for JS-only markers (AC1): " + result);
		// The result should report the form was opened, possibly with a Warning: about the JS errors
		assertTrue(result.contains("Opened form") || result.contains("Warning:"),
				"showFormInBrowser should report form opened (or Warning:) even when .js has errors: " + result);
		assertTrue(result.contains(invalidFormName), "Result should mention the form name: " + result);
	}
}
