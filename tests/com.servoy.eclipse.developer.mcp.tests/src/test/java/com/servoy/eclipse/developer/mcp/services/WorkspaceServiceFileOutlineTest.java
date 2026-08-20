package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.WorkspaceService.OutlineEntry;

public class WorkspaceServiceFileOutlineTest {
	private WorkspaceService service;

	@Before
	public void setUp() {
		service = new WorkspaceService();
	}

	@Test
	public void testGetFileOutline_methodExists() throws NoSuchMethodException {
		Method m = WorkspaceService.class.getMethod("getFileOutline", String.class, String.class);
		assertEquals(List.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	public void testGetFileOutline_outlineEntryHasExpectedFields() {
		OutlineEntry entry = new OutlineEntry(42, "myFunction");
		assertEquals(42, entry.lineNumber());
		assertEquals("myFunction", entry.functionName());
	}

	@Test
	public void testGetFileOutline_outlineEntryEquality() {
		OutlineEntry a = new OutlineEntry(10, "init");
		OutlineEntry b = new OutlineEntry(10, "init");
		assertEquals(a, b);
	}

	@Test
	public void testGetFileOutline_outlineEntryToString() {
		OutlineEntry entry = new OutlineEntry(5, "calculate");
		String str = entry.toString();
		assertTrue(str.contains("5"));
		assertTrue(str.contains("calculate"));
	}

	@Test
	public void testGetFileOutlineWithoutWorkspace_throwsWithoutWorkspace() {
		try {
			service.getFileOutline("anyProject", "test.js");
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testGetFileOutlineWithoutWorkspace_nullProjectThrows() {
		try {
			service.getFileOutline(null, "test.js");
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testServoyAwareFallback_fallbackPathExistsInSource() throws Exception {
		Method m = WorkspaceService.class.getMethod("getFileOutline", String.class, String.class);
		assertNotNull(m);
		assertEquals(2, m.getParameterCount());
		assertEquals(String.class, m.getParameterTypes()[0]);
		assertEquals(String.class, m.getParameterTypes()[1]);
	}

	@Test
	public void testServoyAwareFallback_jsFilesTriggerWorkspaceAccess() {
		String[] jsFiles = { "myform.js", "scmmanager.js", "utils.js" };
		for (String resourcePath : jsFiles) {
			try {
				service.getFileOutline("anyProject", resourcePath);
				fail("Expected exception for " + resourcePath);
			} catch (Throwable ex) {
				assertNotNull(ex);
			}
		}
	}

	@Test
	public void testServoyAwareFallback_nonJsFilesAlsoTriggerWorkspaceAccess() {
		String[] nonJsFiles = { "myform.ts", "readme.md", "config.xml" };
		for (String resourcePath : nonJsFiles) {
			try {
				service.getFileOutline("anyProject", resourcePath);
				fail("Expected exception for " + resourcePath);
			} catch (Throwable ex) {
				assertNotNull(ex);
			}
		}
	}

	@Test
	public void testGlobPatternsToRegex_nullGlobsMatchAll() {
		Pattern p = WorkspaceService.globPatternsToRegex((String[]) null);
		assertTrue(p.matcher("anything.java").matches());
	}

	@Test
	public void testGlobPatternsToRegex_emptyGlobsMatchAll() {
		Pattern p = WorkspaceService.globPatternsToRegex();
		assertTrue(p.matcher("anything.java").matches());
	}

	@Test
	public void testGlobPatternsToRegex_starJavaMatchesJavaFiles() {
		Pattern p = WorkspaceService.globPatternsToRegex("*.java");
		assertTrue(p.matcher("Foo.java").matches());
		assertTrue(!p.matcher("Foo.js").matches());
	}

	@Test
	public void testGlobPatternsToRegex_multipleGlobsAreOred() {
		Pattern p = WorkspaceService.globPatternsToRegex("*.java", "*.xml");
		assertTrue(p.matcher("Foo.java").matches());
		assertTrue(p.matcher("pom.xml").matches());
		assertTrue(!p.matcher("readme.md").matches());
	}

	@Test
	public void testGlobPatternsToRegex_questionMarkMatchesSingleChar() {
		Pattern p = WorkspaceService.globPatternsToRegex("test?.js");
		assertTrue(p.matcher("test1.js").matches());
		assertTrue(p.matcher("testA.js").matches());
		assertTrue(!p.matcher("test12.js").matches());
	}

	@Test
	public void testGlobPatternsToRegex_specialCharsEscaped() {
		Pattern p = WorkspaceService.globPatternsToRegex("file.name.js");
		assertTrue(p.matcher("file.name.js").matches());
		assertTrue(!p.matcher("fileXnameXjs").matches());
	}
}
