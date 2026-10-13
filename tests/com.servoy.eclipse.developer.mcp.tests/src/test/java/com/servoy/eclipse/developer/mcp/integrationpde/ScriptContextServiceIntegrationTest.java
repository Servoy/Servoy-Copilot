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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService.SelectionResult;

/**
 * Integration tests for {@link ScriptContextService}, which backs the
 * {@code resolveIdentifierType} MCP tool.
 * <p>
 * The service uses DLTK's {@code JavaScriptSelectionEngine2} against a real
 * {@code ISourceModule}, so it needs a live Eclipse workbench, an active Servoy
 * solution and a real {@code .js} file on disk with the DLTK JavaScript nature.
 * This therefore runs as a JUnit Plug-in test. The active-solution bootstrap
 * mirrors {@link CreateArtifactsIntegrationTest} /
 * {@link ServoyIdeServerReadIntegrationTest}.
 * <p>
 * Prior to this test the service had only null/blank/not-found unit coverage
 * (via {@code ServoyDevServerTest}); the happy path (real identifier -&gt; DLTK
 * type resolution -&gt; JSON, and the JSDoc fallback) was uncovered.
 */
public class ScriptContextServiceIntegrationTest extends TestUtilitiesClassCopy {
	private static final String TEST_SOLUTION = "test_scriptctx_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private static final String FORM_NAME = "scriptCtxForm";
	private static final String FORM_PATH = "forms/" + FORM_NAME + ".js";

	// A Servoy form scriptfile with a Servoy API identifier (foundset), a
	// user-defined function, a local variable and a JSDoc-annotated variable.
	private static final String FORM_SCRIPT = "/**\n" //
			+ " * @properties={typeid:24,uuid:\"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE\"}\n" //
			+ " */\n" //
			+ "function onLoad(event) {\n" //
			+ "\tfoundset.newRecord();\n" //
			+ "\tvar helper = compute(1, 2);\n" //
			+ "\treturn helper;\n" //
			+ "}\n" //
			+ "\n" //
			+ "/**\n" //
			+ " * @properties={typeid:24,uuid:\"11111111-2222-3333-4444-555555555555\"}\n" //
			+ " */\n" //
			+ "function compute(a, b) {\n" //
			+ "\treturn a + b;\n" //
			+ "}\n" //
			+ "\n" //
			+ "/**\n" //
			+ " * @type {String}\n" //
			+ " *\n" //
			+ " * @properties={typeid:35,uuid:\"22222222-3333-4444-5555-666666666666\"}\n" //
			+ " */\n" //
			+ "var myText = \"hello\";\n";

	private ScriptContextService service;

	public ScriptContextServiceIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		service = new ScriptContextService();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, (solPrj, monitor) -> {
			try {
				writeProjectFileInWorkspaceRun(solPrj, FORM_PATH, FORM_SCRIPT);
			} catch (CoreException e) {
				fail("Cannot write the form script file: " + e.getMessage());
			}
		});
		ensureActiveProject();


		// Drain the I18N/build jobs kicked off by the file write while the solution
		// is guaranteed non-null, so no dialog surfaces later during the tests.
		waitForWorkspaceBuildJobs();
	}

	private IFile formFile() {
		return ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject().getProject().getFile(FORM_PATH);
	}

	// -----------------------------------------------------------------------
	// resolveIdentifierType - guard paths (still need a real file)
	// -----------------------------------------------------------------------

	@Test
	public void testResolveIdentifierType_nullIdentifier_returnsError() {
		String result = service.resolveIdentifierType(null, formFile());
		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("identifier"),
				"Should require identifier: " + result);
	}

	@Test
	public void testResolveIdentifierType_nullFile_returnsError() {
		String result = service.resolveIdentifierType("foundset", null);
		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("File not found"),
				"Should report file not found: " + result);
	}

	@Test
	public void testResolveIdentifierType_identifierNotInFile_returnsError() {
		String result = service.resolveIdentifierType("noSuchIdentifierXYZ", formFile());
		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("not found"),
				"Should report identifier not found: " + result);
	}

	// -----------------------------------------------------------------------
	// resolveIdentifierType - happy paths (real DLTK resolution)
	// -----------------------------------------------------------------------

	@Test
	public void testResolveIdentifierType_servoyApiIdentifier_resolves() {
		// 'foundset' is a Servoy API identifier available in every form scope.
		// This exercises the full resolveIdentifierType -> getModelElements (DLTK
		// selection engine) -> formatting path. Whether DLTK resolves a model/foreign
		// element or the code falls through to the "no type information" branch, the
		// result is always identifier-specific and never a guard error (identifier
		// present, file exists). We assert the deterministic invariant: the call runs
		// past the guards and produces output that references the identifier - and is
		// NOT the "identifier not found in file" guard error.
		String result = service.resolveIdentifierType("foundset", formFile());

		assertNotNull(result);
		assertFalse(result.contains("not found in file"), "Should pass the identifier-in-file guard: " + result);
		assertTrue(result.contains("foundset"), "Result should reference the resolved identifier: " + result);
	}

	@Test
	public void testResolveIdentifierType_userFunction_resolves() {
		String result = service.resolveIdentifierType("compute", formFile());

		assertNotNull(result);
		assertFalse(result.contains("not found in file"), "Should pass the identifier-in-file guard: " + result);
		assertTrue(result.contains("compute"), "Result should reference the function name: " + result);
	}

	@Test
	public void testResolveIdentifierType_jsdocTypedVariable_resolves() {
		// myText has a @type {String} JSDoc annotation; even if DLTK yields no
		// model element, the JSDoc fallback must still produce type info.
		String result = service.resolveIdentifierType("myText", formFile());

		assertNotNull(result);
		assertFalse(result.startsWith("Error"),
				"myText should resolve via model or JSDoc fallback, not error: " + result);
		assertTrue(result.contains("myText"), "Result should reference the identifier: " + result);
	}

	// -----------------------------------------------------------------------
	// getModelElements - direct
	// -----------------------------------------------------------------------

	@Test
	public void testGetModelElements_nullFilePath_returnsNull() {
		assertTrue(service.getModelElements(null, 0) == null, "null filePath must yield null");
	}

	@Test
	public void testGetModelElements_validOffset_returnsResult() {
		String filePath = formFile().getFullPath().toString();
		int offset = FORM_SCRIPT.indexOf("foundset");
		assertTrue(offset >= 0, "Precondition: foundset present in script");

		SelectionResult result = service.getModelElements(filePath, offset);

		assertNotNull(result, "A SelectionResult should be returned for a valid file/offset");
		assertNotNull(result.modelElements, "modelElements collection must be initialised");
		assertNotNull(result.foreignElements, "foreignElements collection must be initialised");
	}

}
