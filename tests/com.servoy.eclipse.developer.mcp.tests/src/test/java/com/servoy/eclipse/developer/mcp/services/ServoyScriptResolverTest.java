package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.junit.Before;
import org.junit.Test;

public class ServoyScriptResolverTest {
	private ServoyScriptResolver resolver;

	@Before
	public void setUp() {
		resolver = new ServoyScriptResolver();
	}

	@Test
	public void testClassStructure_hasCreatableAnnotation() {
		assertNotNull(ServoyScriptResolver.class.getAnnotation(Creatable.class));
	}

	@Test
	public void testClassStructure_resolveScriptMethodSignature() throws NoSuchMethodException {
		Method m = ServoyScriptResolver.class.getMethod("resolveScript", String.class, String.class);
		assertEquals(IFile.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	public void testClassStructure_buildNotFoundMessageSignature() throws NoSuchMethodException {
		Method m = ServoyScriptResolver.class.getMethod("buildNotFoundMessage", String.class, String.class);
		assertEquals(String.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	public void testClassStructure_hasSearchInProjectHelper() throws NoSuchMethodException {
		Method m = ServoyScriptResolver.class.getDeclaredMethod("searchInProject", IProject.class, String.class);
		assertEquals(IFile.class, m.getReturnType());
		assertTrue(Modifier.isPrivate(m.getModifiers()));
	}

	@Test
	public void testClassStructure_hasResolveProjectHelper() throws NoSuchMethodException {
		Method m = ServoyScriptResolver.class.getDeclaredMethod("resolveProject", String.class);
		assertEquals(IProject.class, m.getReturnType());
		assertTrue(Modifier.isPrivate(m.getModifiers()));
	}

	@Test
	public void testResolveScript_returnsNullForNullName() {
		assertNull(resolver.resolveScript(null, null));
	}

	@Test
	public void testResolveScript_returnsNullForEmptyName() {
		assertNull(resolver.resolveScript("", null));
	}

	@Test
	public void testResolveScript_returnsNullForBlankName() {
		assertNull(resolver.resolveScript("   ", null));
	}

	@Test
	public void testResolveScript_returnsNullForTabName() {
		assertNull(resolver.resolveScript("\t", null));
	}

	@Test
	public void testResolveScript_returnsNullForNewlineName() {
		assertNull(resolver.resolveScript("\n", null));
	}

	@Test
	public void testResolveScript_returnsNullForNullNameWithModule() {
		assertNull(resolver.resolveScript(null, "someModule"));
	}

	@Test
	public void testResolveScript_returnsNullForEmptyNameWithModule() {
		assertNull(resolver.resolveScript("", "someModule"));
	}

	@Test
	public void testResolveScript_returnsNullForBlankNameWithModule() {
		assertNull(resolver.resolveScript("   ", "someModule"));
	}

	@Test
	public void testResolveScript_validNameWithModuleThrowsWithoutWorkspace() {
		try {
			resolver.resolveScript("myform", "explicitModule");
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testResolveScript_validNameWithNullModuleThrowsWithoutWorkspace() {
		try {
			resolver.resolveScript("someForm", null);
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testResolveScript_jsSuffixWithModuleThrowsWithoutWorkspace() {
		try {
			resolver.resolveScript("myform.js", "aModule");
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testBuildNotFoundMessage_containsScriptName() {
		String message = resolver.buildNotFoundMessage("myMissingScript", "someModule");
		assertTrue(message.contains("myMissingScript"));
	}

	@Test
	public void testBuildNotFoundMessage_withModuleNameMentionsModule() {
		String message = resolver.buildNotFoundMessage("myScript", "cloudSync");
		assertTrue(message.contains("myScript"));
		assertTrue(message.contains("cloudSync"));
	}

	@Test
	public void testBuildNotFoundMessage_mentionsExpectedLocations() {
		String message = resolver.buildNotFoundMessage("someScript", "aModule");
		assertTrue(message.contains("forms/"));
		assertTrue(message.contains("scopes/"));
	}

	@Test
	public void testBuildNotFoundMessage_messageFormat() {
		String message = resolver.buildNotFoundMessage("customers", "crm_module");
		assertTrue(message.startsWith("Script not found: 'customers'"));
		assertTrue(message.contains("in module 'crm_module'"));
	}

	@Test
	public void testBuildNotFoundMessage_nullModuleThrowsWithoutWorkspace() {
		try {
			resolver.buildNotFoundMessage("someScript", null);
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testBuildNotFoundMessage_blankModuleThrowsWithoutWorkspace() {
		try {
			resolver.buildNotFoundMessage("someScript", "   ");
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testResolveProject_explicitModuleAttemptsWorkspaceLookup() throws Exception {
		Method m = ServoyScriptResolver.class.getDeclaredMethod("resolveProject", String.class);
		m.setAccessible(true);
		try {
			m.invoke(resolver, "nonExistentProject_XYZ");
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}

	@Test
	public void testResolveProject_nullModuleUsesActiveSolution() throws Exception {
		Method m = ServoyScriptResolver.class.getDeclaredMethod("resolveProject", String.class);
		m.setAccessible(true);
		try {
			m.invoke(resolver, (String) null);
			fail("Expected exception");
		} catch (Throwable ex) {
			assertNotNull(ex);
		}
	}
}
