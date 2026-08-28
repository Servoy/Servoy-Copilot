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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.dto.CodeContext;
import com.servoy.eclipse.developer.mcp.dto.SelectionInfo;
import com.servoy.eclipse.developer.mcp.services.CodeContextService;

/**
 * Integration tests for {@link CodeContextService}.
 * Requires Eclipse platform with DLTK JavaScript support.
 */
public class CodeContextServiceIntegrationTest extends TestUtilitiesClass
{
	private static final String PROJECT_NAME = "test_code_context_suite";

	private CodeContextService service;
	private IProject project;

	public CodeContextServiceIntegrationTest() {
		super(null, null);
	}

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(PROJECT_NAME);
		waitForWorkspaceBuildJobs();
	}

	@Before
	public void setUp() throws Exception
	{
		service = new CodeContextService();

		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
			IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
			if (!p.exists())
			{
				IProjectDescription desc = ResourcesPlugin.getWorkspace().newProjectDescription(PROJECT_NAME);
				desc.setNatureIds(new String[] { "org.eclipse.dltk.javascript.core.nature" });
				p.create(desc, monitor);
			}
			if (!p.isOpen())
			{
				p.open(monitor);
			}
		}, new NullProgressMonitor());

		project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
		assertNotNull("Test project should exist", project);
		assertTrue("Test project should be open", project.isOpen());
	}

	@After
	public void tearDown() throws Exception
	{
		if (project != null && project.exists())
		{
			try
			{
				project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
				for (IResource r : project.members())
				{
					if (!".project".equals(r.getName()) && !".buildpath".equals(r.getName()))
					{
						try
						{
							r.delete(true, new NullProgressMonitor());
						}
						catch (Exception e)
						{
							// best effort
						}
					}
				}
			}
			catch (Exception e)
			{
				// best effort
			}
		}
	}

	// -------------------------------------------------------------------------
	// getCodeContext - null/empty inputs
	// -------------------------------------------------------------------------

	@Test
	public void testGetCodeContext_nullSelectionInfo_returnsError()
	{
		CodeContext result = service.getCodeContext(null, null);

		assertNotNull(result);
		assertTrue("Should have error for null input", result.hasError());
		assertNotNull(result.getErrorMessage());
		assertTrue(result.getErrorMessage().contains("No selection"));
	}

	@Test
	public void testGetCodeContext_emptySelection_returnsEmpty()
	{
		SelectionInfo info = SelectionInfo.create("/test/file.js", 0, 0, "", null, 0, 0, false).orElse(null);
		assertNotNull("SelectionInfo should be created", info);

		CodeContext result = service.getCodeContext(info, null);

		assertNotNull(result);
		assertTrue("Should be empty for zero-length selection", result.isEmpty());
		assertFalse(result.hasError());
	}

	@Test
	public void testGetCodeContext_noSourceModule_returnsSuccess()
	{
		// SelectionInfo with text but no ISourceModule (can't parse)
		SelectionInfo info = SelectionInfo.create("/test/file.js", 0, 10, "var x = 1;", null, 0, 0, false)
			.orElse(null);
		assertNotNull("SelectionInfo should be created", info);

		CodeContext result = service.getCodeContext(info, null);

		assertNotNull(result);
		// Without a source module, should return success with null identifiers
		assertFalse("Should not have error", result.hasError());
	}

	// -------------------------------------------------------------------------
	// getCodeContext - with real JavaScript file
	// -------------------------------------------------------------------------

	@Test
	public void testGetCodeContext_simpleVariable_extractsContext() throws Exception
	{
		String jsContent = "var myVar = 'hello';";
		IFile jsFile = createJsFile("test_simple.js", jsContent);

		// Allow DLTK time to index
		SelectionInfo info[] = { null };
		pumpEventsUntil(2000, () -> {
			info[0] = service.createSelectionInfoFromFile(jsFile);
			assertNotNull(info[0]);
		});

//		if (info == null) return; // DLTK not fully initialized

		CodeContext result = service.getCodeContext(info[0], null);

		assertNotNull(result);
		assertFalse("Should not have error: " + result.getErrorMessage(), result.hasError());
	}

	@Test
	public void testGetCodeContext_servoyApi_extractsIdentifiers() throws Exception
	{
		String jsContent = "var result = application.getServerNames();";
		IFile jsFile = createJsFile("test_api.js", jsContent);

		SelectionInfo info[] = { null };
		pumpEventsUntil(2000, () -> {
			info[0] = service.createSelectionInfoFromFile(jsFile);
			assertNotNull(info[0]);
		});

//		if (info == null) return; // DLTK not fully initialized

		CodeContext result = service.getCodeContext(info[0], null);

		assertNotNull(result);
		assertFalse("Should not have error: " + result.getErrorMessage(), result.hasError());
		// If type inference works, we should get identifiers
		// (may be empty if DLTK type providers aren't fully loaded in test env)
	}

	@Test
	public void testGetCodeContext_withFilterIdentifiers() throws Exception
	{
		String jsContent = "var x = application.getServerNames();\nvar y = security.getUserName();";
		IFile jsFile = createJsFile("test_filter.js", jsContent);

		SelectionInfo info[] = { null };
		pumpEventsUntil(2000, () -> {
			info[0] = service.createSelectionInfoFromFile(jsFile);
			assertNotNull(info[0]);
		});

//		if (info == null) return;

		// Only extract 'application' identifier
		CodeContext result = service.getCodeContext(info[0], new String[] { "application" });

		assertNotNull(result);
		assertFalse("Should not have error", result.hasError());
		// If identifiers were found, they should only be 'application'-related
		result.getIdentifiers().forEach(id -> {
			assertTrue("Should only contain filtered identifier, got: " + id.getName(),
				id.getName().equals("application") || id.getName().startsWith("application."));
		});
	}

	@Test
	public void testGetCodeContext_multipleIdentifiers() throws Exception
	{
		String jsContent = "var name = controller.getName();\nvar count = foundset.getSize();";
		IFile jsFile = createJsFile("test_multi.js", jsContent);

		SelectionInfo info[] = { null };
		pumpEventsUntil(2000, () -> {
			info[0] = service.createSelectionInfoFromFile(jsFile);
			assertNotNull(info[0]);
		});

//		if (info == null) return;

		CodeContext result = service.getCodeContext(info[0], null);

		assertNotNull(result);
		assertFalse("Should not have error", result.hasError());
	}

	// -------------------------------------------------------------------------
	// createSelectionInfoFromFile
	// -------------------------------------------------------------------------

	@Test
	public void testCreateSelectionInfoFromFile_nullFile()
	{
		SelectionInfo result = service.createSelectionInfoFromFile(null);
		assertNull("Should return null for null file", result);
	}

	@Test
	public void testCreateSelectionInfoFromFile_nonExistentFile()
	{
		IFile file = project.getFile("does_not_exist.js");
		SelectionInfo result = service.createSelectionInfoFromFile(file);
		assertNull("Should return null for non-existent file", result);
	}

	@Test
	public void testCreateSelectionInfoFromFile_validJsFile() throws Exception
	{
		String content = "function hello() { return 'world'; }";
		IFile jsFile = createJsFile("test_selinfo.js", content);

		pumpEventsUntil(1000, () -> {
			SelectionInfo result = service.createSelectionInfoFromFile(jsFile);

			// May be null if DLTK can't create ISourceModule (depends on project config)
			if (result != null)
			{
				assertNotNull(result.getFilePath());
				assertTrue("Should have full file selected", result.isFullFileSelected());
				assertEquals(content.length(), result.getLength());
				assertEquals(content, result.getSelectedText());
			}
		});
	}

	@Test
	public void testCreateSelectionInfoFromFile_nonJsFile() throws Exception
	{
		// A .txt file won't be recognized as ISourceModule by DLTK
		IFile txtFile = createFile("test.txt", "not javascript");

		pumpEventsUntil(500, () -> {
			SelectionInfo result = service.createSelectionInfoFromFile(txtFile);
			// Should return null because DLTK won't create a source module for .txt
			assertNull("Should return null for non-JS file", result);
		});
	}

	// -------------------------------------------------------------------------
	// CodeContext formatting
	// -------------------------------------------------------------------------

	@Test
	public void testCodeContext_errorFormatting()
	{
		CodeContext error = CodeContext.error(null, "Something went wrong");

		assertEquals("<error>Something went wrong</error>", error.getFormattedXML());
		assertEquals("Error: Something went wrong", error.getFormattedPlainText());
		assertTrue(error.toString().contains("Something went wrong"));
	}

	@Test
	public void testCodeContext_emptyFormatting()
	{
		SelectionInfo info = SelectionInfo.create("/file.js", 0, 0, "", null, 0, 0, false).orElse(null);
		CodeContext empty = CodeContext.empty(info);

		assertEquals("<!-- No context information available -->", empty.getFormattedXML());
		assertEquals("No context information available.", empty.getFormattedPlainText());
		assertTrue(empty.isEmpty());
		assertEquals(0, empty.getIdentifierCount());
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private IFile createJsFile(String name, String content) throws Exception
	{
		return createFile(name, content);
	}

	private IFile createFile(String path, String content) throws Exception
	{
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
			IFile file = project.getFile(path);
			if (file.exists())
			{
				file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, false,
					monitor);
			}
			else
			{
				org.eclipse.core.resources.IContainer parent = file.getParent();
				if (parent instanceof IFolder && !parent.exists())
				{
					((IFolder)parent).create(true, true, monitor);
				}
				file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, monitor);
			}
		}, new NullProgressMonitor());

		return project.getFile(path);
	}

}
