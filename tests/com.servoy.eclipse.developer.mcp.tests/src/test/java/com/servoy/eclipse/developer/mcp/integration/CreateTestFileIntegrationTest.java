/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.TestFileService;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Layer 2 PDE integration tests for {@link TestFileService#createTestFile}.
 * <p>
 * Covers all scenarios from unittest.txt for the createTestFile tool:
 * <ul>
 *   <li>Happy-path: file is created on disk, return value format</li>
 *   <li>Content structure: JSDoc header, well-formed block comment, non-empty, UTF-8</li>
 *   <li>File location: always in solution root, never in a subdirectory</li>
 *   <li>Multi-file: two distinct test files can coexist in the same project</li>
 *   <li>Re-creation: file can be recreated after deletion</li>
 *   <li>Duplicate-call protection: second call on same name returns "already exists" error</li>
 *   <li>Error conditions: unknown solution, null solution</li>
 * </ul>
 */
public class CreateTestFileIntegrationTest
{
	// Neutral name - NOT an existing Servoy solution so Servoy never tries to
	// load it and show blocking error dialogs in the PDE test workspace.
	private static final String SOLUTION_NAME = "test_layer2_workspace";
	private static final String TEST_FILE_NAME = "test_createfiletest.js";

	private TestFileService service;
	private ServoyProject servoyProject;

	@Before
	public void setUp()
	{
		service = TestFileService.getInstance();

		// Create a plain project in the test workspace (no Servoy natures/builders).
		// We do NOT import the on-disk fixture: Servoy would try to load it as a
		// solution, discover the missing "preimp4Test" module, and show a blocking
		// error dialog that stalls the test runner.
		// IWorkspace.run() acquires the workspace lock atomically, avoiding
		// ResourceException from concurrent Servoy startup jobs holding the lock.
		try
		{
			final IProject[] holder = new IProject[1];
			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
				IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(SOLUTION_NAME);
				if (!p.exists())
				{
					IProjectDescription desc = ResourcesPlugin.getWorkspace()
						.newProjectDescription(SOLUTION_NAME);
					p.create(desc, monitor);
				}
				if (!p.isOpen())
				{
					p.open(monitor);
				}
				holder[0] = p;
			}, new NullProgressMonitor());
			servoyProject = new ServoyProject();
			servoyProject.setProject(holder[0]);
		}
		catch (Throwable e)
		{
			System.err.println("[CreateTestFileIntegrationTest.setUp] FAILED: " +
				e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace(System.err);
			servoyProject = null;
		}
		assertNotNull("Failed to create test project in workspace - skipping Layer 2 tests",
			servoyProject);

		// Clean slate: OS-level delete bypasses any Eclipse workspace rule/lock that
		// can cause IFile.delete() to throw silently (e.g. Servoy resource listeners
		// holding a rule on the newly-created .js file).  After the OS delete we
		// refresh Eclipse's resource cache so the workspace is back in sync.
		deleteFileIfExists(TEST_FILE_NAME);
	}

	@After
	public void tearDown()
	{
		deleteFileIfExists(TEST_FILE_NAME);
	}

	// -----------------------------------------------------------------------
	// Happy-path: file creation and return value
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_createsFileOnDisk() throws Exception
	{
		String result = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		assertFalse("createTestFile should succeed but returned: " + result, result.startsWith("Error"));

		IFile file = servoyProject.getProject().getFile(TEST_FILE_NAME);
		assertTrue("File should physically exist in the project", file.exists());
	}

	@Test
	public void testCreateTestFile_returnValueContainsFileName() throws Exception
	{
		String result = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		assertFalse("Should succeed: " + result, result.startsWith("Error"));
		assertTrue("Return value should mention the file name: " + result,
			result.contains(TEST_FILE_NAME));
	}

	@Test
	public void testCreateTestFile_returnValueContainsCreated() throws Exception
	{
		String result = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		assertFalse("Should succeed: " + result, result.startsWith("Error"));
		assertTrue("Return value should contain 'Created': " + result, result.contains("Created"));
	}

	@Test
	public void testCreateTestFile_returnValueContainsPath() throws Exception
	{
		String result = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		assertFalse("Should succeed: " + result, result.startsWith("Error"));
		// Eclipse IPath.toString() always uses '/' as separator
		assertTrue("Return value should contain a path separator: " + result, result.contains("/"));
	}

	// -----------------------------------------------------------------------
	// Content structure (unittest.txt §4: JSDoc header, encoding, well-formed JS)
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_fileContentNotEmpty() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		IFile file = servoyProject.getProject().getFile(TEST_FILE_NAME);
		String content;
		try (java.io.InputStream is = file.getContents())
		{
			content = new String(is.readAllBytes(), "UTF-8");
		}
		assertTrue("Created file should not be empty", content.trim().length() > 0);
	}

	@Test
	public void testCreateTestFile_createdFileContainsJsunitHeader() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		IFile file = servoyProject.getProject().getFile(TEST_FILE_NAME);
		String content;
		try (java.io.InputStream is = file.getContents())
		{
			content = new String(is.readAllBytes(), "UTF-8");
		}

		assertTrue("Created file should contain JSUnit block comment",
			content.startsWith("/**"));
		assertTrue("Created file should mention Servoy or JSUnit",
			content.contains("Servoy") || content.contains("JSUnit"));
	}

	@Test
	public void testCreateTestFile_contentHasClosingBlockComment() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		IFile file = servoyProject.getProject().getFile(TEST_FILE_NAME);
		String content;
		try (java.io.InputStream is = file.getContents())
		{
			content = new String(is.readAllBytes(), "UTF-8");
		}

		assertTrue("Header should have a closing */ marker", content.contains("*/"));
	}

	@Test
	public void testCreateTestFile_contentBlockCommentIsWellFormed() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		IFile file = servoyProject.getProject().getFile(TEST_FILE_NAME);
		String content;
		try (java.io.InputStream is = file.getContents())
		{
			content = new String(is.readAllBytes(), "UTF-8");
		}

		int openIdx = content.indexOf("/**");
		int closeIdx = content.indexOf("*/");
		assertTrue("Opening /** must come before closing */", openIdx >= 0 && closeIdx > openIdx);
	}

	// -----------------------------------------------------------------------
	// File location (unittest.txt §3: always in solution root, never subdirectory)
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_fileIsInProjectRoot() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		IFile file = servoyProject.getProject().getFile(TEST_FILE_NAME);
		assertTrue("File should be a direct child of the project root (not in a subdirectory)",
			file.getParent().equals(servoyProject.getProject()));
	}

	// -----------------------------------------------------------------------
	// Multi-file and re-creation
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_afterDeletion_canRecreate() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);
		deleteFileIfExists(TEST_FILE_NAME); // simulate user deleting the file

		String result = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);

		assertFalse("Re-creating after deletion should succeed: " + result,
			result.startsWith("Error"));
		assertTrue("File should exist after re-creation",
			servoyProject.getProject().getFile(TEST_FILE_NAME).exists());
	}

	@Test
	public void testCreateTestFile_twoDistinctFilesCanCoexist() throws Exception
	{
		// unittest.txt: each source file gets its own test file (test_sourceFileName.js)
		String secondFileName = "test_createfiletest2.js";
		try
		{
			String r1 = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME);
			String r2 = service.createTestFile(secondFileName, SOLUTION_NAME);

			assertFalse("First file creation failed: " + r1, r1.startsWith("Error"));
			assertFalse("Second file creation failed: " + r2, r2.startsWith("Error"));
			assertTrue("First file should exist on disk",
				servoyProject.getProject().getFile(TEST_FILE_NAME).exists());
			assertTrue("Second file should exist on disk",
				servoyProject.getProject().getFile(secondFileName).exists());
		}
		finally
		{
			deleteFileIfExists(secondFileName);
		}
	}

	// -----------------------------------------------------------------------
	// Duplicate-call protection (unittest.txt §1 step 2B: check before creating)
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_duplicateCall_returnsError() throws Exception
	{
		service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME); // first call succeeds
		String result = service.createTestFile(TEST_FILE_NAME, SOLUTION_NAME); // second call should fail

		assertTrue("Second call should return an Error message", result.startsWith("Error"));
		assertTrue("Error should mention the file already exists",
			result.toLowerCase().contains("already exists"));
	}

	// -----------------------------------------------------------------------
	// Error conditions (unittest.txt §5)
	// -----------------------------------------------------------------------

	@Test
	public void testCreateTestFile_unknownSolution_returnsError() throws Exception
	{
		String result = service.createTestFile(TEST_FILE_NAME, "nonexistent_solution_xyz");

		assertTrue("Non-existent solution should return Error", result.startsWith("Error"));
		assertTrue("Error should mention solution not found",
			result.toLowerCase().contains("not found"));
	}

	@Test
	public void testCreateTestFile_nullSolution_returnsError() throws Exception
	{
		String result = service.createTestFile(TEST_FILE_NAME, null);

		assertTrue("Null solution should return an Error: " + result, result.startsWith("Error"));
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * OS-level delete for any named file in the test project, then syncs the Eclipse
	 * workspace cache. Safe to call when the file does not exist. Uses OS-level I/O to
	 * bypass Eclipse workspace rules that can block IFile.delete() (e.g. Servoy resource
	 * listeners holding a read lock on a .js file after creation).
	 */
	private void deleteFileIfExists(String fileName)
	{
		if (servoyProject == null) return;
		try
		{
			IProject project = servoyProject.getProject();
			java.io.File f = new java.io.File(project.getLocation().toFile(), fileName);
			if (f.exists()) f.delete();
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		}
		catch (Exception ignored)
		{
		}
	}
}
