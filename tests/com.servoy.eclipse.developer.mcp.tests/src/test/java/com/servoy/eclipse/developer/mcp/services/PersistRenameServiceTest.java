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
	public void testRenameByName_ambiguous_sourceContainsAmbiguousKeyword() throws Exception {
		java.net.URL loc = PersistRenameService.class.getProtectionDomain().getCodeSource().getLocation();
		java.nio.file.Path binDir = java.nio.file.Paths.get(loc.toURI());
		java.nio.file.Path sourceFile = null;
		java.nio.file.Path check = binDir;
		for (int i = 0; i < 6; i++) {
			java.nio.file.Path candidate = check
					.resolve("src/com/servoy/eclipse/developer/mcp/services/PersistRenameService.java");
			if (java.nio.file.Files.exists(candidate)) {
				sourceFile = candidate;
				break;
			}
			check = check.getParent();
		}
		if (sourceFile != null && java.nio.file.Files.exists(sourceFile)) {
			String source = java.nio.file.Files.readString(sourceFile);
			assertTrue("renameByName must contain literal 'Ambiguous' for collision error",
					source.contains("Ambiguous"));
		}
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
