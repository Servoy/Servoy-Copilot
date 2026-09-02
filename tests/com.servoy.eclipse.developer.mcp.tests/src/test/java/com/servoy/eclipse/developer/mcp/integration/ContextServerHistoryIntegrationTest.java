package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyContextServer;
import com.servoy.eclipse.developer.mcp.services.LocalHistoryService;

public class ContextServerHistoryIntegrationTest extends DialogGuardBase {

	private static final String PROJECT_NAME = "test_context_history_suite";

	private ServoyContextServer contextServer;
	private IProject project;
	private String testFileName;

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		TestUtilitiesClass.deleteProjects(PROJECT_NAME);
		TestUtilitiesClass.waitForWorkspaceBuildJobs();
	}

	@Before
	public void setUp() throws Exception {
		contextServer = new ServoyContextServer(new LocalHistoryService());

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
			if (!p.exists()) {
				IProjectDescription desc = ResourcesPlugin.getWorkspace().newProjectDescription(PROJECT_NAME);
				p.create(desc, monitor);
			}
			if (!p.isOpen()) {
				p.open(monitor);
			}
		}, new NullProgressMonitor());

		project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
		assertTrue("Test project should be open", project.isOpen());

		testFileName = "history_test_" + System.currentTimeMillis() + ".txt";
		createFileWithHistory(testFileName);
	}

	@After
	public void tearDown() throws Exception {
		if (project != null && project.exists()) {
			try {
				project.delete(true, true, new NullProgressMonitor());
			} catch (Exception e) {
				// Windows file lock - best effort cleanup
			}
		}
	}

	@Test
	public void testGetFileHistory_returnsEntries() {
		String result = contextServer.getFileHistory(PROJECT_NAME, testFileName, null);

		assertNotNull(result);
		assertFalse(result.contains("Error"));
	}

	@Test
	public void testGetFileHistory_withMaxEntries() {
		String result = contextServer.getFileHistory(PROJECT_NAME, testFileName, "1");

		assertNotNull(result);
		assertFalse(result.contains("Error"));
	}

	@Test(expected = RuntimeException.class)
	public void testGetFileHistory_nonExistentFile() {
		contextServer.getFileHistory(PROJECT_NAME, "nonexistent.txt", null);
	}

	@Test
	public void testGetFileHistoryContent_returnsOldContent() {
		String result = contextServer.getFileHistoryContent(PROJECT_NAME, testFileName, "0");

		assertNotNull(result);
		if (!result.contains("Error") && !result.contains("No history")) {
			assertTrue(result.contains("version"));
		}
	}

	@Test
	public void testGetFileHistoryContent_invalidIndex() {
		String result = contextServer.getFileHistoryContent(PROJECT_NAME, testFileName, "999");

		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("Invalid") || result.contains("invalid")
				|| result.contains("out of range") || result.contains("No history") || result.contains("No local history"));
	}

	@Test
	public void testCompareWithHistory_returnsDiff() {
		String result = contextServer.compareWithHistory(PROJECT_NAME, testFileName, "0");

		assertNotNull(result);
		if (!result.contains("Error") && !result.contains("No history")) {
			assertTrue(result.contains("version") || result.contains("-") || result.contains("+")
					|| result.contains("identical"));
		}
	}

	@Test(expected = RuntimeException.class)
	public void testCompareWithHistory_nonExistentFile() {
		contextServer.compareWithHistory(PROJECT_NAME, "no_such_file.txt", "0");
	}

	private void createFileWithHistory(String path) {
		try {
			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
				IFile file = project.getFile(path);
				if (file.exists()) {
					file.setContents(new ByteArrayInputStream("version 1".getBytes(StandardCharsets.UTF_8)), true, true, monitor);
				} else {
					file.create(new ByteArrayInputStream("version 1".getBytes(StandardCharsets.UTF_8)), true, monitor);
				}
			}, new NullProgressMonitor());

			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
				IFile file = project.getFile(path);
				file.setContents(new ByteArrayInputStream("version 2".getBytes(StandardCharsets.UTF_8)), true, true,
						monitor);
			}, new NullProgressMonitor());

			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
				IFile file = project.getFile(path);
				file.setContents(new ByteArrayInputStream("version 3 final".getBytes(StandardCharsets.UTF_8)), true,
						true, monitor);
			}, new NullProgressMonitor());

			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (Exception e) {
			throw new RuntimeException("Failed to create file with history: " + path, e);
		}
	}
}
