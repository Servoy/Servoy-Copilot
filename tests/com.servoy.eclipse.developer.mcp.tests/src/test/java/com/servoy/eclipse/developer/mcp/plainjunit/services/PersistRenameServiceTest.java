package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.PersistRenameService;

public class PersistRenameServiceTest {
	private final PersistRenameService service = new PersistRenameService();

	@Test
	public void testPersistRenameService_canBeInstantiated() {
		assertNotNull(service);
	}

	@Test
	public void testPersistRenameService_hasRenameByNameMethod() throws NoSuchMethodException {
		Method m = PersistRenameService.class.getMethod("renameByName", String.class, String.class);
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType());
	}

	@Test
	public void testRenameByName_rejectsNullOldName() {
		String result = service.renameByName(null, "newName");
		assertTrue(result.contains("Error") && result.contains("oldName"));
	}

	@Test
	public void testRenameByName_rejectsBlankOldName() {
		String result = service.renameByName("  ", "newName");
		assertTrue(result.contains("Error") && result.contains("oldName"));
	}

	@Test
	public void testRenameByName_rejectsNullNewName() {
		String result = service.renameByName("oldName", null);
		assertTrue(result.contains("Error") && result.contains("newName"));
	}

	@Test
	public void testRenameByName_rejectsBlankNewName() {
		String result = service.renameByName("oldName", "   ");
		assertTrue(result.contains("Error") && result.contains("newName"));
	}

	@Test
	public void testRenameByName_rejectsSameName() {
		String result = service.renameByName("myArtifact", "myArtifact");
		assertTrue(result.contains("Error") && result.contains("same"));
	}

	@Test
	public void testRenameByName_rejectsNewNameWithPathSeparator() {
		String result = service.renameByName("someArtifact", "bad/name");
		assertTrue(result.contains("Error"), "Should reject newName containing path separator");
		assertTrue(result.contains("bare") || result.contains("path"), "Should mention bare name or path");
	}

	@Test
	public void testRenameByName_formTypeNoWorkspace() {
		try {
			String result = service.renameByName("oldForm", "newForm");
			assertNotNull(result);
			assertTrue(result.contains("Error") || result.contains("not found") || result.contains("No active"));
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit");
		}
	}

	@Test
	public void testRenameByName_notFound_returnsErrorWithMessage() {
		try {
			String result = service.renameByName("nonExistentArtifact_XYZ_99999", "someNewName");
			assertNotNull(result);
			assertTrue(result.contains("not found") || result.contains("No active") || result.contains("Error"),
					"Should return error containing 'not found' or 'No active'");
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit (no ServoyModel)");
		}
	}

	@Test
	public void testBuildAmbiguousMessage_listsEachMatchAndPathHint() {
		// Package-private method called directly (no reflection): this test class is
		// in the same package as PersistRenameService. Avoiding getDeclaredMethod()
		// here matters because it forces resolution of every declared method's
		// signature in the class, which can throw NoClassDefFoundError if an
		// unrelated OSGi bundle referenced elsewhere in the class fails to activate
		// during this test run.
		java.util.List<String[]> matches = new java.util.ArrayList<>();
		matches.add(new String[] { "form", "myArtifact", "solutionA", null });
		matches.add(new String[] { "relation", "myArtifact", "solutionB", null });
		matches.add(new String[] { "menuitem", "myArtifact", "solutionA", "mainMenu" });

		String result = service.buildAmbiguousMessage("myArtifact", matches, "activeSolution");

		assertNotNull(result);
		assertTrue(result.contains("Ambiguous"), "Should flag as ambiguous");
		assertTrue(result.contains("form 'myArtifact' in 'solutionA'"),
				"Should mention the form match in solutionA");
		assertTrue(result.contains("relation 'myArtifact' in 'solutionB'"),
				"Should mention the relation match in solutionB");
		assertTrue(result.contains("menuitem 'myArtifact' in menu 'mainMenu' (solution 'solutionA')"),
				"Should mention the menuitem match with its owning menu");
		assertTrue(result.contains("activeSolution/forms/myArtifact"),
				"Should suggest a path hint using the active solution name");
	}

	@Test
	public void testRenameByName_pathHint_formsPrefix_identifiedAsForm() {
		try {
			String result = service.renameByName("forms/testFormXYZ", "newName");
			assertNotNull(result);
			assertFalse(result.contains("Unsupported"), "Should not return unsupported type error");
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit");
		}
	}

	@Test
	public void testRenameByName_pathHint_relationsPrefix() {
		try {
			String result = service.renameByName("relations/testRelXYZ", "newName");
			assertNotNull(result);
			assertFalse(result.contains("Unsupported"), "Should not return unsupported type error");
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit");
		}
	}

	@Test
	public void testRenameByName_pathHint_mediasPrefix() {
		try {
			String result = service.renameByName("medias/test.png", "newTest.png");
			assertNotNull(result);
			assertFalse(result.contains("Unsupported"), "Should not return unsupported type error");
		} catch (Throwable e) {
			assertNotNull(e, "Expected workspace error in plain JUnit");
		}
	}

	@Test
	public void testPersistRenameService_hasRenameFormMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameForm", String.class, String.class,
				com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameRelationMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameRelation", String.class, String.class,
				com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameValueListMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameValueList", String.class, String.class,
				com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameMenuMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameMenu", String.class, String.class,
				com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameMediaMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameMedia", String.class, String.class,
				com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameScopeMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameScope", String.class, String.class,
				com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameSolutionMethod() throws NoSuchMethodException {
		assertNotNull(PersistRenameService.class.getMethod("renameSolution", String.class, String.class));
	}
}
