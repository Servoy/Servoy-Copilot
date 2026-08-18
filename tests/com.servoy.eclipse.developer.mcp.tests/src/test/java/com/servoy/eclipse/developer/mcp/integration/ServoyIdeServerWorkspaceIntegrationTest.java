package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer;
import com.servoy.eclipse.developer.mcp.services.IdeStateService;
import com.servoy.eclipse.developer.mcp.services.MarkdownService;
import com.servoy.eclipse.developer.mcp.services.ProjectService;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService;

public class ServoyIdeServerWorkspaceIntegrationTest {
	private static final String PROJECT_NAME = "test_ide_workspace_suite";

	private ServoyIdeServer server;
	private IProject project;

	@Before
	public void setUp() throws Exception {
		server = new ServoyIdeServer(new ProjectService(), new WorkspaceService(), new MarkdownService(),
				new IdeStateService());

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

		createTestFile("hello.txt", "Hello World\nSecond line\nThird line");
		createTestFile("src/Main.java", "public class Main {\n\tpublic static void main(String[] args) {}\n}");
		createTestFile("docs/README.md",
				"# Title\n\nIntro paragraph.\n\n## Section One\n\nContent of section one.\n\n## Section Two\n\nContent of section two.\n");
		createTestFile("data/config.xml", "<config><key>value</key></config>");
	}

	@After
	public void tearDown() throws Exception {
		if (project != null && project.exists()) {
			project.delete(true, true, new NullProgressMonitor());
		}
	}

	@Test
	public void testReadProjectResource_returnsFileContent() {
		String result = server.readProjectResource(PROJECT_NAME, "hello.txt", null, null, null);

		assertNotNull(result);
		assertTrue(result.contains("Hello World"));
		assertTrue(result.contains("Second line"));
	}

	@Test
	public void testReadProjectResource_withLineNumbers() {
		String result = server.readProjectResource(PROJECT_NAME, "hello.txt", "true", null, null);

		assertNotNull(result);
		assertTrue(result.contains("1"));
		assertTrue(result.contains("Hello World"));
	}

	@Test
	public void testReadProjectResource_withLineRange() {
		String result = server.readProjectResource(PROJECT_NAME, "hello.txt", null, "2", "2");

		assertNotNull(result);
		assertTrue(result.contains("Second line"));
		assertFalse(result.contains("Third line"));
	}

	@Test
	public void testGetProjectLayout_returnsStructure() {
		String result = server.getProjectLayout(PROJECT_NAME, null, null);

		assertNotNull(result);
		assertTrue(result.contains("hello.txt"));
		assertTrue(result.contains("src"));
		assertTrue(result.contains("docs"));
	}

	@Test
	public void testGetProjectLayout_withScopePath() {
		String result = server.getProjectLayout(PROJECT_NAME, "src", null);

		assertNotNull(result);
		assertTrue(result.contains("Main.java"));
		assertFalse(result.contains("hello.txt"));
	}

	@Test
	public void testGetProjectLayout_withMaxDepth() {
		String result = server.getProjectLayout(PROJECT_NAME, null, "1");

		assertNotNull(result);
		assertTrue(result.contains("src"));
	}

	@Test
	public void testGetProjectProperties_returnsInfo() {
		String result = server.getProjectProperties(PROJECT_NAME);

		assertNotNull(result);
		assertTrue(result.contains(PROJECT_NAME));
	}

	@Test
	public void testFindFiles_byPattern() {
		String result = server.findFiles("*.java", null);

		assertNotNull(result);
		assertTrue(result.contains("Main.java"));
	}

	@Test
	public void testFindFiles_xmlPattern() {
		String result = server.findFiles("*.xml", null);

		assertNotNull(result);
		assertTrue(result.contains("config.xml"));
	}

	@Test
	public void testFileSearch_findsTextInFile() {
		String result = server.fileSearch("Hello World", null);

		assertNotNull(result);
		assertTrue(result.contains("hello.txt"));
	}

	@Test
	public void testFileSearch_noMatch() {
		String result = server.fileSearch("nonexistent_string_xyz_12345", null);

		assertNotNull(result);
		assertTrue(result.contains("No") || result.isEmpty() || !result.contains("hello.txt"));
	}

	@Test
	public void testFileSearchRegExp_findsPattern() {
		String result = server.fileSearchRegExp("Hello\\s+World", null);

		assertNotNull(result);
		assertTrue(result.contains("hello.txt"));
	}

	@Test
	public void testFileSearchRegExp_withFilePattern() {
		String result = server.fileSearchRegExp("public", "*.java");

		assertNotNull(result);
		assertTrue(result.contains("Main.java"));
	}

	@Test
	public void testGetCompilationErrors_forProject() {
		String result = server.getCompilationErrors(PROJECT_NAME, null, null, null, "false");

		assertNotNull(result);
	}

	@Test
	public void testGetCompilationErrors_allProjects() {
		String result = server.getCompilationErrors(null, null, null, null, "false");

		assertNotNull(result);
	}

	@Test
	public void testGetCompilationErrors_waitsForBuild() throws Exception {
		createTestFile("globals.js", "function broken( { return; }");
		IFile file = project.getFile("globals.js");
		IMarker marker = file.createMarker(IMarker.PROBLEM);
		marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
		marker.setAttribute(IMarker.MESSAGE, "Syntax error: missing ) after formal parameters");
		marker.setAttribute(IMarker.LINE_NUMBER, 1);

		String result = server.getCompilationErrors(PROJECT_NAME, "ERROR", null, null, "true");

		assertNotNull(result);
		assertTrue(result.contains("ERROR"));
		assertTrue(result.contains("globals.js"));
		assertTrue(result.contains("Syntax error"));

		marker.delete();
	}

	@Test
	public void testGetMarkdownOutline_returnsHeadings() {
		String result = server.getMarkdownOutline(PROJECT_NAME, "docs/README.md");

		assertNotNull(result);
		assertTrue(result.contains("Title"));
		assertTrue(result.contains("Section One"));
		assertTrue(result.contains("Section Two"));
	}

	@Test
	public void testGetMarkdownSection_byHeadingText() {
		String result = server.getMarkdownSection(PROJECT_NAME, "docs/README.md", "Section One", null);

		assertNotNull(result);
		assertTrue(result.contains("Content of section one"));
	}

	@Test
	public void testGetMarkdownSection_excludeSubsections() {
		String result = server.getMarkdownSection(PROJECT_NAME, "docs/README.md", "Title", "false");

		assertNotNull(result);
		assertTrue(result.contains("Intro paragraph"));
		assertFalse(result.contains("Content of section one"));
	}

	@Test
	public void testGetFileInfo_existingFile() {
		String result = server.getFileInfo(PROJECT_NAME, "hello.txt");

		assertNotNull(result);
		assertTrue(result.contains("hello.txt"));
		assertTrue(result.contains("bytes"));
	}

	@Test
	public void testGetFileInfo_nonExistentFile() {
		String result = server.getFileInfo(PROJECT_NAME, "nonexistent.txt");

		assertNotNull(result);
		assertTrue(result.contains("Error"));
	}

	@Test
	public void testListProjects_containsTestProject() {
		String result = server.listProjects();

		assertNotNull(result);
		assertTrue(result.contains(PROJECT_NAME));
	}

	private void createTestFile(String path, String content) {
		try {
			ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
				IFile file = project.getFile(path);
				if (file.exists()) {
					file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, true,
							monitor);
				} else {
					org.eclipse.core.resources.IContainer parent = file.getParent();
					if (parent instanceof IFolder && !parent.exists()) {
						createFolderHierarchy((IFolder) parent, monitor);
					}
					file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, monitor);
				}
			}, new NullProgressMonitor());
		} catch (Exception e) {
			throw new RuntimeException("Failed to create test file: " + path, e);
		}
	}

	private void createFolderHierarchy(IFolder folder, IProgressMonitor monitor) throws CoreException {
		if (!folder.getParent().exists() && folder.getParent() instanceof IFolder) {
			createFolderHierarchy((IFolder) folder.getParent(), monitor);
		}
		if (!folder.exists()) {
			folder.create(true, true, monitor);
		}
	}
}
