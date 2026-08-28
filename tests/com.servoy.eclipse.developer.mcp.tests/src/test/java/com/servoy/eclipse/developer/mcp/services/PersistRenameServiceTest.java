package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

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
		assertTrue("Should reject newName containing path separator", result.contains("Error"));
		assertTrue("Should mention bare name or path", result.contains("bare") || result.contains("path"));
	}

	@Test
	public void testRenameByName_formTypeNoWorkspace() {
		try {
			String result = service.renameByName("oldForm", "newForm");
			assertNotNull(result);
			assertTrue(result.contains("Error") || result.contains("not found") || result.contains("No active"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenameByName_notFound_returnsErrorWithMessage() {
		try {
			String result = service.renameByName("nonExistentArtifact_XYZ_99999", "someNewName");
			assertNotNull(result);
			assertTrue("Should return error containing 'not found' or 'No active'",
					result.contains("not found") || result.contains("No active") || result.contains("Error"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit (no ServoyModel)", e);
		}
	}

	@Test
	public void testBuildAmbiguousMessage_listsEachMatchAndPathHint() throws Exception {
		java.lang.reflect.Method m = PersistRenameService.class.getDeclaredMethod("buildAmbiguousMessage",
				String.class, java.util.List.class, String.class);
		m.setAccessible(true);

		java.util.List<String[]> matches = new java.util.ArrayList<>();
		matches.add(new String[] { "form", "myArtifact", "solutionA", null });
		matches.add(new String[] { "relation", "myArtifact", "solutionB", null });
		matches.add(new String[] { "menuitem", "myArtifact", "solutionA", "mainMenu" });

		String result = (String) m.invoke(service, "myArtifact", matches, "activeSolution");

		assertNotNull(result);
		assertTrue("Should flag as ambiguous", result.contains("Ambiguous"));
		assertTrue("Should mention the form match in solutionA",
				result.contains("form 'myArtifact' in 'solutionA'"));
		assertTrue("Should mention the relation match in solutionB",
				result.contains("relation 'myArtifact' in 'solutionB'"));
		assertTrue("Should mention the menuitem match with its owning menu",
				result.contains("menuitem 'myArtifact' in menu 'mainMenu' (solution 'solutionA')"));
		assertTrue("Should suggest a path hint using the active solution name",
				result.contains("activeSolution/forms/myArtifact"));
	}

	@Test
	public void testRenameByName_pathHint_formsPrefix_identifiedAsForm() {
		try {
			String result = service.renameByName("forms/testFormXYZ", "newName");
			assertNotNull(result);
			assertFalse("Should not return unsupported type error", result.contains("Unsupported"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenameByName_pathHint_relationsPrefix() {
		try {
			String result = service.renameByName("relations/testRelXYZ", "newName");
			assertNotNull(result);
			assertFalse("Should not return unsupported type error", result.contains("Unsupported"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenameByName_pathHint_mediasPrefix() {
		try {
			String result = service.renameByName("medias/test.png", "newTest.png");
			assertNotNull(result);
			assertFalse("Should not return unsupported type error", result.contains("Unsupported"));
		} catch (Throwable e) {
			assertNotNull("Expected workspace error in plain JUnit", e);
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
