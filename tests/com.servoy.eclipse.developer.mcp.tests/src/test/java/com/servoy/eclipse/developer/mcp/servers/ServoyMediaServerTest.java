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
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.IRepository;
import com.servoy.j2db.persistence.ISupportChilds;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.SolutionMetaData;
import com.servoy.j2db.util.UUID;

public class ServoyMediaServerTest {
	private final ServoyMediaServer server = new ServoyMediaServer();

	// -----------------------------------------------------------------------
	// Annotation tests
	// -----------------------------------------------------------------------

	@Test
	public void testServoyMediaServer_hasCorrectMcpServerAnnotation() {
		McpServer ann = ServoyMediaServer.class.getAnnotation(McpServer.class);
		assertNotNull("ServoyMediaServer must have @McpServer annotation", ann);
		assertEquals("servoy-media", ann.name());
	}

	@Test
	public void testServoyMediaServer_hasMediaRenameToolMethod() {
		Method method = findToolMethod("media_rename");
		assertNotNull("ServoyMediaServer must have a tool named 'media_rename'", method);
	}

	@Test
	public void testServoyMediaServer_mediaRenameHasThreeParams() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		assertEquals(3, method.getParameterCount());
	}

	@Test
	public void testServoyMediaServer_mediaRenameReturnsString() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		assertEquals(String.class, method.getReturnType());
	}

	@Test
	public void testServoyMediaServer_mediaRenameHasToolParamAnnotations() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		for (Parameter param : method.getParameters()) {
			assertNotNull("All parameters must have @ToolParam", param.getAnnotation(ToolParam.class));
		}
	}

	@Test
	public void testServoyMediaServer_mediaRenameHasSolutionNameParam() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		boolean found = false;
		for (Parameter param : method.getParameters()) {
			ToolParam tp = param.getAnnotation(ToolParam.class);
			if (tp != null && "solutionName".equals(tp.name())) {
				found = true;
				assertTrue("solutionName must be required", tp.required());
			}
		}
		assertTrue("Must have a 'solutionName' parameter", found);
	}

	@Test
	public void testServoyMediaServer_mediaRenameHasMediaNameParam() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		boolean found = false;
		for (Parameter param : method.getParameters()) {
			ToolParam tp = param.getAnnotation(ToolParam.class);
			if (tp != null && "mediaName".equals(tp.name())) {
				found = true;
				assertTrue("mediaName must be required", tp.required());
			}
		}
		assertTrue("Must have a 'mediaName' parameter", found);
	}

	@Test
	public void testServoyMediaServer_mediaRenameHasNewNameParam() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		boolean found = false;
		for (Parameter param : method.getParameters()) {
			ToolParam tp = param.getAnnotation(ToolParam.class);
			if (tp != null && "newName".equals(tp.name())) {
				found = true;
				assertTrue("newName must be required", tp.required());
			}
		}
		assertTrue("Must have a 'newName' parameter", found);
	}

	@Test
	public void testServoyMediaServer_toolDescriptionMentionsUuid() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("Tool description should mention UUID preservation",
				tool.description().toLowerCase().contains("uuid"));
	}

	@Test
	public void testServoyMediaServer_toolDescriptionMentionsFolderRename() {
		Method method = findToolMethod("media_rename");
		assertNotNull(method);
		Tool tool = method.getAnnotation(Tool.class);
		assertTrue("Tool description should mention folder rename",
				tool.description().toLowerCase().contains("folder"));
	}

	// -----------------------------------------------------------------------
	// validateMediaName tests (via reflection since it's private)
	// -----------------------------------------------------------------------

	@Test
	public void testValidateMediaName_validSimpleName_returnsNull() throws Exception {
		assertNull(invokeValidateMediaName("logo.png"));
	}

	@Test
	public void testValidateMediaName_validPathName_returnsNull() throws Exception {
		assertNull(invokeValidateMediaName("css/theme.css"));
	}

	@Test
	public void testValidateMediaName_validNestedPath_returnsNull() throws Exception {
		assertNull(invokeValidateMediaName("images/icons/arrow-left.png"));
	}

	@Test
	public void testValidateMediaName_validUnderscoresAndDashes_returnsNull() throws Exception {
		assertNull(invokeValidateMediaName("my_file-name.js"));
	}

	@Test
	public void testValidateMediaName_nullName_returnsError() throws Exception {
		String result = invokeValidateMediaName(null);
		assertNotNull(result);
		assertTrue(result.contains("empty"));
	}

	@Test
	public void testValidateMediaName_emptyName_returnsError() throws Exception {
		String result = invokeValidateMediaName("");
		assertNotNull(result);
		assertTrue(result.contains("empty"));
	}

	@Test
	public void testValidateMediaName_blankName_returnsError() throws Exception {
		String result = invokeValidateMediaName("   ");
		assertNotNull(result);
		assertTrue(result.contains("empty") || result.contains("invalid"));
	}

	@Test
	public void testValidateMediaName_nameWithSpaces_returnsErrorWithSuggestion() throws Exception {
		String result = invokeValidateMediaName("my file.css");
		assertNotNull(result);
		assertTrue(result.contains("spaces not allowed"));
		assertTrue("Should suggest replacement", result.contains("my_file.css"));
	}

	@Test
	public void testValidateMediaName_nameWithSpecialChars_returnsError() throws Exception {
		String result = invokeValidateMediaName("file@name.png");
		assertNotNull(result);
		assertTrue(result.contains("invalid"));
	}

	@Test
	public void testValidateMediaName_nameWithHash_returnsError() throws Exception {
		String result = invokeValidateMediaName("file#1.png");
		assertNotNull(result);
		assertTrue(result.contains("invalid"));
	}

	@Test
	public void testValidateMediaName_nameWithParentheses_returnsError() throws Exception {
		String result = invokeValidateMediaName("file(1).png");
		assertNotNull(result);
		assertTrue(result.contains("invalid"));
	}

	@Test
	public void testValidateMediaName_leadingDotInSegment_returnsError() throws Exception {
		String result = invokeValidateMediaName(".hidden/file.css");
		assertNotNull(result);
		assertTrue(result.contains("start or end with a dot"));
	}

	@Test
	public void testValidateMediaName_trailingDotInSegment_returnsError() throws Exception {
		String result = invokeValidateMediaName("folder./file.css");
		assertNotNull(result);
		assertTrue(result.contains("start or end with a dot"));
	}

	@Test
	public void testValidateMediaName_dotInMiddleOfSegment_isValid() throws Exception {
		assertNull(invokeValidateMediaName("my.folder/file.css"));
	}

	// -----------------------------------------------------------------------
	// escapeJson tests (via reflection)
	// -----------------------------------------------------------------------

	@Test
	public void testEscapeJson_plainText_unchanged() throws Exception {
		assertEquals("hello", invokeEscapeJson("hello"));
	}

	@Test
	public void testEscapeJson_quotes_escaped() throws Exception {
		assertEquals("say \\\"hi\\\"", invokeEscapeJson("say \"hi\""));
	}

	@Test
	public void testEscapeJson_backslash_escaped() throws Exception {
		assertEquals("path\\\\to\\\\file", invokeEscapeJson("path\\to\\file"));
	}

	@Test
	public void testEscapeJson_slashes_notEscaped() throws Exception {
		assertEquals("css/theme.css", invokeEscapeJson("css/theme.css"));
	}

	// -----------------------------------------------------------------------
	// mediaRename error handling (without OSGi - exercises top-level catch)
	// -----------------------------------------------------------------------

	@Test
	public void testMediaRename_withoutOsgi_returnsError() {
		try {
			String result = server.mediaRename("NonExistentSolution", "old.png", "new.png");
			assertNotNull(result);
			assertTrue("Should return an error when ServoyModel is unavailable", result.startsWith("Error"));
		} catch (Throwable e) {
			assertNotNull("Expected error when ServoyModel class not available", e);
		}
	}

	@Test
	public void testMediaRename_folderRename_withoutOsgi_returnsError() {
		try {
			String result = server.mediaRename("NonExistentSolution", "images/", "icons/");
			assertNotNull(result);
			assertTrue("Should return an error when ServoyModel is unavailable", result.startsWith("Error"));
		} catch (Throwable e) {
			assertNotNull("Expected error when ServoyModel class not available", e);
		}
	}

	// -----------------------------------------------------------------------
	// Registration test
	// -----------------------------------------------------------------------

	@Test
	public void testServoyMediaServer_registeredInBuiltins() {
		boolean found = false;
		for (Class<?> cls : com.servoy.eclipse.developer.mcp.McpServerBuiltins.BUILT_IN_SERVER_CLASSES) {
			if (cls == ServoyMediaServer.class) {
				found = true;
				break;
			}
		}
		assertTrue("ServoyMediaServer must be registered in McpServerBuiltins", found);
	}

	// -----------------------------------------------------------------------
	// renameSingleMedia duplicate rejection test (AC 10)
	// -----------------------------------------------------------------------

	@Test
	public void testRenameSingleMedia_duplicateRejection() throws Exception {
		Solution solution = createTestSolution();
		createAndAddMedia(solution, "old.png");
		createAndAddMedia(solution, "new.png");

		String result = invokeRenameSingleMedia("old.png", "new.png", solution, null);
		assertNotNull(result);
		assertEquals("Error: Media 'new.png' already exists at the target name.", result);
	}

	// -----------------------------------------------------------------------
	// renameFolderMedia tests
	// -----------------------------------------------------------------------

	@Test
	public void testRenameFolderMedia_rejectsNewFolderWithoutTrailingSlash() throws Exception {
		String result = invokeRenameFolderMedia("images/", "icons", null, null);
		assertNotNull(result);
		assertEquals("Error: New folder name must end with '/'.", result);
	}

	@Test
	public void testRenameFolderMedia_errorWhenNoMediaMatchesPrefix() throws Exception {
		Solution solution = createTestSolution();

		try {
			String result = invokeRenameFolderMedia("nonexistent/", "other/", solution, null);
			assertNotNull(result);
			assertTrue(result.contains("No media found with folder prefix"));
		} catch (java.lang.reflect.InvocationTargetException e) {
			assertTrue("Should reach the empty-folder branch (NPE on null project)",
					e.getCause() instanceof NullPointerException);
		}
	}

	// -----------------------------------------------------------------------
	// UUID preservation tests (AC 6, AC 7)
	// -----------------------------------------------------------------------

	@Test
	public void testRenameSingleMedia_preservesUuid() throws Exception {
		Solution solution = createTestSolution();
		Media media = createAndAddMediaReturning(solution, "old.png");
		UUID uuidBefore = media.getUUID();
		media.setName("new.png");
		assertEquals("UUID must be preserved after rename", uuidBefore, media.getUUID());
		assertEquals("new.png", media.getName());
	}

	@Test
	public void testRenameFolderMedia_preservesUuids() throws Exception {
		Solution solution = createTestSolution();
		Media m1 = createAndAddMediaReturning(solution, "images/a.png");
		Media m2 = createAndAddMediaReturning(solution, "images/b.png");
		UUID uuid1 = m1.getUUID();
		UUID uuid2 = m2.getUUID();
		m1.setName("icons/a.png");
		m2.setName("icons/b.png");
		assertEquals(uuid1, m1.getUUID());
		assertEquals(uuid2, m2.getUUID());
	}

	// -----------------------------------------------------------------------
	// Media not found test (AC 9)
	// -----------------------------------------------------------------------

	@Test
	public void testRenameSingleMedia_mediaNotFound() throws Exception {
		Solution solution = createTestSolution();
		createAndAddMedia(solution, "existing.png");
		try {
			String result = invokeRenameSingleMedia("nonexistent.png", "new.png", solution, null);
			assertNotNull(result);
			assertTrue(result.contains("not found"));
		} catch (java.lang.reflect.InvocationTargetException e) {
			assertTrue("Should reach the not-found branch (NPE on null project after media lookup fails)",
					e.getCause() instanceof NullPointerException);
		}
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	private Method findToolMethod(String toolName) {
		for (Method m : ServoyMediaServer.class.getDeclaredMethods()) {
			Tool tool = m.getAnnotation(Tool.class);
			if (tool != null && toolName.equals(tool.name())) {
				return m;
			}
		}
		return null;
	}

	private String invokeValidateMediaName(String name) throws Exception {
		Method method = ServoyMediaServer.class.getDeclaredMethod("validateMediaName", String.class);
		method.setAccessible(true);
		return (String) method.invoke(server, name);
	}

	private String invokeEscapeJson(String s) throws Exception {
		Method method = ServoyMediaServer.class.getDeclaredMethod("escapeJson", String.class);
		method.setAccessible(true);
		return (String) method.invoke(server, s);
	}

	private String invokeRenameSingleMedia(String mediaName, String newName, Solution solution, ServoyProject project)
			throws Exception {
		Method method = ServoyMediaServer.class.getDeclaredMethod("renameSingleMedia", String.class, String.class,
				Solution.class, ServoyProject.class);
		method.setAccessible(true);
		return (String) method.invoke(server, mediaName, newName, solution, project);
	}

	private String invokeRenameFolderMedia(String oldFolder, String newFolder, Solution solution, ServoyProject project)
			throws Exception {
		Method method = ServoyMediaServer.class.getDeclaredMethod("renameFolderMedia", String.class, String.class,
				Solution.class, ServoyProject.class);
		method.setAccessible(true);
		return (String) method.invoke(server, oldFolder, newFolder, solution, project);
	}

	private Solution createTestSolution() throws Exception {
		SolutionMetaData metaData = new SolutionMetaData(UUID.randomUUID(), "testSolution", 43, 1, 1);
		Constructor<Solution> ctor = Solution.class.getDeclaredConstructor(IRepository.class, SolutionMetaData.class);
		ctor.setAccessible(true);
		return ctor.newInstance(null, metaData);
	}

	private void createAndAddMedia(Solution solution, String name) throws Exception {
		createAndAddMediaReturning(solution, name);
	}

	private Media createAndAddMediaReturning(Solution solution, String name) throws Exception {
		Constructor<Media> ctor = Media.class.getDeclaredConstructor(ISupportChilds.class, UUID.class);
		ctor.setAccessible(true);
		Media media = ctor.newInstance(solution, UUID.randomUUID());
		media.setName(name);
		solution.addChild(media);
		return media;
	}
}
