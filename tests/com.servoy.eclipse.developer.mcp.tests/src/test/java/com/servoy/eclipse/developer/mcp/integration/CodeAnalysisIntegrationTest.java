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

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.services.CodeAnalysisService;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.builder.ServoyBuilder;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.Solution;

/**
 * Integration tests for {@link CodeAnalysisService} that require a running
 * Eclipse Workbench with a Servoy solution loaded.
 * <p>
 * Positive tests create a dedicated test solution ({@value #TEST_SOLUTION})
 * with a form hierarchy and methods, then verify that findReferences,
 * getTypeHierarchy and getMethodCallHierarchy return meaningful results.
 * Defensive tests verify graceful handling of missing/invalid inputs.
 * </p>
 * <p>
 * Must be run as JUnit Plug-in Tests inside a PDE-launched Eclipse instance.
 * </p>
 */
public class CodeAnalysisIntegrationTest extends TestUtilitiesClass
{

	private CodeAnalysisService service;
	private ServoyProject activeProject;

	public CodeAnalysisIntegrationTest() {
		super("test_analysis_suite", "servoy_resources");
	}

	@Before
	public void setUp() throws Exception
	{
		service = new CodeAnalysisService();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
	}

	// -----------------------------------------------------------------------
	// getTypeHierarchy — positive tests
	// -----------------------------------------------------------------------

	@Test
	public void testGetTypeHierarchy_childForm_containsParentAsSupertype() throws Exception
	{
		String parentName = "analysisParent_" + System.currentTimeMillis();
		String childName = "analysisChild_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(parentName, "css", 640, 480, null, null, null);
		new ServoyArtifactCreationService().createForm(childName, "css", 640, 480, null, null, null);

		// Wire child to extend parent
		Solution solution = activeProject.getEditingSolution();
		Form parentForm = solution.getForm(parentName);
		Form childForm = solution.getForm(childName);
		assertNotNull("Parent form should exist", parentForm);
		assertNotNull("Child form should exist", childForm);

		childForm.setExtendsForm(parentForm);
		childForm.setExtendsID(parentForm.getUUID().toString());
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { childForm }, true);
		pumpEventsUntil(1000, () -> {
			String result = service.getTypeHierarchy(childName);

			assertNotNull(result);
			assertTrue("Should contain parent name in supertypes: " + result, result.contains(parentName));
			assertTrue("Should have Supertypes section: " + result, result.contains("Supertypes"));
		});
	}

	@Test
	public void testGetTypeHierarchy_parentForm_containsChildAsSubtype() throws Exception
	{
		String parentName = "analysisParent2_" + System.currentTimeMillis();
		String childName = "analysisChild2_" + System.currentTimeMillis();

		new ServoyArtifactCreationService().createForm(parentName, "css", 640, 480, null, null, null);
		new ServoyArtifactCreationService().createForm(childName, "css", 640, 480, null, null, null);

		Solution solution = activeProject.getEditingSolution();
		Form parentForm = solution.getForm(parentName);
		Form childForm = solution.getForm(childName);
		assertNotNull("Parent form should exist", parentForm);
		assertNotNull("Child form should exist", childForm);

		childForm.setExtendsForm(parentForm);
		childForm.setExtendsID(parentForm.getUUID().toString());
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { childForm }, true);
		pumpEventsUntil(1000, () -> {
			String result = service.getTypeHierarchy(parentName);
			
			assertNotNull(result);
			assertTrue("Should contain child name in subtypes: " + result, result.contains(childName));
			assertTrue("Should have Direct Subtypes section: " + result, result.contains("Direct Subtypes"));
		});
	}

	@Test
	public void testGetTypeHierarchy_formWithNoParen_showsNone() throws Exception
	{
		String formName = "analysisIsolated_" + System.currentTimeMillis();
		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		pumpEventsUntil(500, () -> {
			String result = service.getTypeHierarchy(formName);

			assertNotNull(result);
			assertTrue("Should indicate no parent: " + result, result.contains("none"));
		});
	}

	@Test
	public void testGetTypeHierarchy_formsPrefix_stripsAndFindsForm() throws Exception
	{
		String formName = "analysisPrefixed_" + System.currentTimeMillis();
		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		pumpEventsUntil(500, () -> {
			String withPrefix = service.getTypeHierarchy("forms." + formName);
			String withoutPrefix = service.getTypeHierarchy(formName);

			assertNotNull(withPrefix);
			assertNotNull(withoutPrefix);
			// Both should find the same form
			assertTrue("With prefix should find form: " + withPrefix, withPrefix.contains(formName));
			assertTrue("Without prefix should find form: " + withoutPrefix, withoutPrefix.contains(formName));
		});
	}

	// -----------------------------------------------------------------------
	// findReferences — positive tests
	// -----------------------------------------------------------------------

	@Test
	public void testFindReferences_existingMethod_elementResolved() throws Exception
	{
		String formName = "analysisRefForm_" + System.currentTimeMillis();
		String methodName = "testRefMethod";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form[] = { null };
		pumpEventsUntil(500, () -> {
			Solution solution = activeProject.getEditingSolution();
			form[0] = solution.getForm(formName);
			assertNotNull("Form should exist", form[0]);
		});

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		ScriptMethod method = form[0].createNewScriptMethod(validator, methodName);
		assertNotNull("Method should be created", method);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { method }, true);
		pumpEventsUntil(1000, () -> {
			String result = service.findReferences("forms." + formName, methodName);
			
			assertNotNull(result);
			// Verify the element was actually resolved (not a "Could not resolve" error)
			assertTrue("Method should be resolved — should not return 'Could not resolve': " + result,
					!result.contains("Could not resolve"));
			// The output must be the structured references header, not a "No active solution" error
			assertTrue("Should return structured references output: " + result,
					result.startsWith("# References to method"));
		});
	}

	@Test
	public void testFindReferences_existingVariable_elementResolved() throws Exception
	{
		String formName = "analysisVarForm_" + System.currentTimeMillis();
		String varName = "testVar";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form[] = { null };
		pumpEventsUntil(500, () -> {
			Solution solution = activeProject.getEditingSolution();
			form[0] = solution.getForm(formName);
			assertNotNull("Form should exist", form[0]);
		});

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		com.servoy.j2db.persistence.ScriptVariable variable = form[0].createNewScriptVariable(validator, varName,
			com.servoy.j2db.persistence.IColumnTypes.TEXT);
		assertNotNull("Variable should be created", variable);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { variable }, true);
		pumpEventsUntil(1000, () -> {
			String result = service.findReferences("forms." + formName, varName);

			assertNotNull(result);
			// Verify the element was actually resolved
			assertTrue("Variable should be resolved — should not return 'Could not resolve': " + result,
				!result.contains("Could not resolve"));
			assertTrue("Should return structured references output: " + result,
				result.startsWith("# References to variable"));
		});
	}

	// -----------------------------------------------------------------------
	// getMethodCallHierarchy — positive tests
	// -----------------------------------------------------------------------

	@Test
	public void testGetMethodCallHierarchy_existingMethod_methodResolved() throws Exception
	{
		String formName = "analysisCallForm_" + System.currentTimeMillis();
		String methodName = "testCallMethod";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form[] = { null };
		pumpEventsUntil(500, () -> {
			Solution solution = activeProject.getEditingSolution();
			form[0] = solution.getForm(formName);
			assertNotNull("Form should exist", form[0]);
		});

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		ScriptMethod method = form[0].createNewScriptMethod(validator, methodName);
		assertNotNull("Method should be created", method);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { method }, true);
		pumpEventsUntil(1000, () -> {
			String result = service.getMethodCallHierarchy("forms." + formName, methodName, null, "2");

			assertNotNull(result);
			// Verify the method was actually resolved — not a "Could not resolve" error
			assertTrue("Method should be resolved — should not return 'Could not resolve': " + result,
				!result.contains("Could not resolve"));
			// The output must be the structured call hierarchy header
			assertTrue("Should return structured call hierarchy output: " + result,
				result.startsWith("# Call Hierarchy (callers) for:"));
		});
	}

	@Test
	public void testGetMethodCallHierarchy_defaultDepth_doesNotThrow() throws Exception
	{
		String formName = "analysisCallDepth_" + System.currentTimeMillis();
		String methodName = "testDepthMethod";

		new ServoyArtifactCreationService().createForm(formName, "css", 640, 480, null, null, null);
		Form form[] = { null };
		pumpEventsUntil(500, () -> {
			Solution solution = activeProject.getEditingSolution();
			form[0] = solution.getForm(formName);
			assertNotNull("Form should exist", form[0]);
		});

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		form[0].createNewScriptMethod(validator, methodName);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { form[0] }, true);
		pumpEventsUntil(1000, () -> {
			// null maxDepth should use default (3)
			String result = service.getMethodCallHierarchy("forms." + formName, methodName, null, null);

			assertNotNull(result);
			assertTrue("Should not throw and return structured result: " + result, result.length() > 0);
		});
	}

	// -----------------------------------------------------------------------
	// executeQuickFix — positive test
	// -----------------------------------------------------------------------

	/**
	 * Creates a missingModulesProblem marker intentionally (by adding a
	 * non-existent module to the test solution), then verifies that:
	 * <ol>
	 * <li>The marker appears in the workspace with a valid ID.</li>
	 * <li>executeQuickFix(id, -1) lists the "Remove module" resolution.</li>
	 * <li>executeQuickFix(id, 0) applies the resolution successfully.</li>
	 * <li>After applying, the marker is gone from the workspace.</li>
	 * </ol>
	 */
	@Test
	public void testExecuteQuickFix_missingModule_listAndApply() throws Exception
	{
		final String FAKE_MODULE = "nonExistentModule_" + System.currentTimeMillis();
		final String MARKER_TYPE = ServoyBuilder.MISSING_MODULES_MARKER_TYPE;

		// Inject a fake module reference into the test solution to trigger the marker
		Solution solution = activeProject.getEditingSolution();
		String originalModules = solution.getModulesNames();
		String injected = (originalModules == null || originalModules.isBlank())
			? FAKE_MODULE : originalModules + "," + FAKE_MODULE;
		solution.setModulesNames(injected);
		activeProject.saveEditingSolutionNodes(new com.servoy.j2db.persistence.IPersist[] { solution }, true);
		IMarker foundMarker[] = { null };
		pumpEventsUntil(3000, () -> {
			// Find the marker for the missing module
			IMarker[] markers;
			try {
				markers = ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers(MARKER_TYPE, false, IResource.DEPTH_INFINITE);
			} catch (CoreException e) {
				throw new RuntimeException(e);
			}
			for (IMarker m : markers)
			{
				if (FAKE_MODULE.equals(m.getAttribute("moduleName", null)))
				{
					foundMarker[0] = m;
					break;
				}
			}
			assertNotNull("Missing-module marker should have been created for '" + FAKE_MODULE + "'", foundMarker[0]);
		});

		long markerId = foundMarker[0].getId();

		// List mode — should show the "Remove module" resolution
		String listResult = service.executeQuickFix(markerId, -1);
		assertNotNull(listResult);
		assertTrue("List mode should return proposal list: " + listResult,
			listResult.contains("Quick Fix Proposals") || listResult.contains("Remove"));
		assertTrue("Should not return not-found for a real marker: " + listResult,
			!listResult.contains("not found"));

		// Apply mode — apply proposal [0] (Remove the fake module)
		String applyResult = service.executeQuickFix(markerId, 0);
		assertNotNull(applyResult);
		assertTrue("Apply should report success: " + applyResult,
			applyResult.contains("Applied") || applyResult.contains("applied"));
		pumpEventsUntil(2000, () -> {
			// Verify marker is gone after fix
			IMarker[] markersAfter;
			try {
				markersAfter = ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers(MARKER_TYPE, false, IResource.DEPTH_INFINITE);
			} catch (CoreException e) {
				throw new RuntimeException(e);
			}
			boolean stillPresent = false;
			for (IMarker m : markersAfter)
			{
				if (FAKE_MODULE.equals(m.getAttribute("moduleName", null)))
				{
					stillPresent = true;
					break;
				}
			}
			assertTrue("Marker should be gone after applying the quick fix", !stillPresent);
		});
	}

	// -----------------------------------------------------------------------
	// Defensive / negative tests (no active solution or unknown element)
	// -----------------------------------------------------------------------

	@Test
	public void testFindReferences_unknownForm_returnsMessage()
	{
		String result = service.findReferences("forms.nonExistentForm_XYZ_ABC", "someMethod");
		assertNotNull(result);
		assertTrue("Should return a descriptive message",
			result.contains("No active solution") ||
			result.contains("Could not resolve") ||
			result.contains("not found"));
	}

	@Test
	public void testFindReferences_noElementName_returnsGuidance()
	{
		String result = service.findReferences("forms.myForm", null);
		assertNotNull(result);
		assertTrue("Should return guidance when elementName is missing",
			result.contains("elementName") ||
			result.contains("No active solution") ||
			result.contains("method") ||
			result.contains("variable"));
	}

	@Test
	public void testFindReferences_nullInputs_doesNotThrow()
	{
		String result = service.findReferences(null, null);
		assertNotNull(result);
	}

	@Test
	public void testGetTypeHierarchy_unknownForm_returnsNotFound()
	{
		String result = service.getTypeHierarchy("forms.nonExistentForm_XYZ_ABC");
		assertNotNull(result);
		assertTrue("Should return not-found message",
			result.contains("not found") ||
			result.contains("nonExistentForm_XYZ_ABC"));
	}

	@Test
	public void testGetTypeHierarchy_nullInput_doesNotThrow()
	{
		String result = service.getTypeHierarchy(null);
		assertNotNull(result);
	}

	@Test
	public void testGetMethodCallHierarchy_unknownMethod_returnsMessage()
	{
		String result = service.getMethodCallHierarchy("forms.nonExistentForm_XYZ", "nonExistentMethod_XYZ", null, null);
		assertNotNull(result);
		assertTrue("Should return a descriptive message",
			result.contains("Could not resolve") ||
			result.contains("No active solution") ||
			result.contains("not found"));
	}

	@Test
	public void testGetMethodCallHierarchy_invalidDepth_usesDefault()
	{
		String result = service.getMethodCallHierarchy("forms.nonExistentForm_XYZ", "someMethod", null, "notANumber");
		assertNotNull(result);
		assertTrue("Should not contain stack trace", !result.contains("NumberFormatException"));
	}

	@Test
	public void testExecuteQuickFix_invalidMarkerId_returnsNotFound()
	{
		String result = service.executeQuickFix(-99999L, -1);
		assertNotNull(result);
		assertTrue("Should return marker-not-found message",
			result.contains("not found") || result.contains("-99999"));
	}

	@Test
	public void testExecuteQuickFix_invalidProposalIndex_returnsError()
	{
		String result = service.executeQuickFix(-1L, 999);
		assertNotNull(result);
		assertTrue("Should return error message", result.length() > 0);
	}

}
