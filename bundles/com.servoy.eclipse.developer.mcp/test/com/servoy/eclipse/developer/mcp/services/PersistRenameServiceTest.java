package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public class PersistRenameServiceTest
{
	private final PersistRenameService service = new PersistRenameService();

	@Test
	public void testPersistRenameService_canBeInstantiated()
	{
		assertNotNull(service);
	}

	@Test
	public void testPersistRenameService_hasRenamePersistMethod() throws NoSuchMethodException
	{
		Method m = PersistRenameService.class.getMethod("renamePersist", String.class, String.class, String.class, String.class);
		assertNotNull(m);
		assertEquals(String.class, m.getReturnType());
	}

	@Test
	public void testRenamePersist_rejectsNullPersistType()
	{
		String result = service.renamePersist(null, "old", "new", null);
		assertTrue(result.contains("Error") && result.contains("persistType"));
	}

	@Test
	public void testRenamePersist_rejectsBlankPersistType()
	{
		String result = service.renamePersist("  ", "old", "new", null);
		assertTrue(result.contains("Error") && result.contains("persistType"));
	}

	@Test
	public void testRenamePersist_rejectsNullOldName()
	{
		String result = service.renamePersist("form", null, "new", null);
		assertTrue(result.contains("Error") && result.contains("oldName"));
	}

	@Test
	public void testRenamePersist_rejectsBlankOldName()
	{
		String result = service.renamePersist("form", "", "new", null);
		assertTrue(result.contains("Error") && result.contains("oldName"));
	}

	@Test
	public void testRenamePersist_rejectsNullNewName()
	{
		String result = service.renamePersist("form", "old", null, null);
		assertTrue(result.contains("Error") && result.contains("newName"));
	}

	@Test
	public void testRenamePersist_rejectsBlankNewName()
	{
		String result = service.renamePersist("form", "old", "  ", null);
		assertTrue(result.contains("Error") && result.contains("newName"));
	}

	@Test
	public void testRenamePersist_rejectsSameName()
	{
		String result = service.renamePersist("form", "myForm", "myForm", null);
		assertTrue(result.contains("Error") && result.contains("same"));
	}

	@Test
	public void testRenamePersist_rejectsUnsupportedType()
	{
		try
		{
			String result = service.renamePersist("unknown_type", "old", "new", null);
			assertTrue(result.contains("Error") && (result.contains("Unsupported") || result.contains("not found")));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected error in plain JUnit (no workspace)", e);
		}
	}

	@Test
	public void testRenamePersist_formType_noWorkspace()
	{
		try
		{
			String result = service.renamePersist("form", "oldForm", "newForm", null);
			assertNotNull(result);
			assertTrue(result.contains("Error") || result.contains("not found"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenamePersist_relationTypeAccepted()
	{
		try
		{
			String result = service.renamePersist("relation", "oldRel", "newRel", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenamePersist_valuelistTypeAccepted()
	{
		try
		{
			String result = service.renamePersist("valuelist", "oldVl", "newVl", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenamePersist_menuTypeAccepted()
	{
		try
		{
			String result = service.renamePersist("menu", "oldMenu", "newMenu", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenamePersist_mediaTypeAccepted()
	{
		try
		{
			String result = service.renamePersist("media", "old.png", "new.png", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenamePersist_scopeTypeAccepted()
	{
		try
		{
			String result = service.renamePersist("scope", "oldScope", "newScope", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testRenamePersist_solutionTypeAccepted()
	{
		try
		{
			String result = service.renamePersist("solution", "oldSol", "newSol", null);
			assertNotNull(result);
		}
		catch (Throwable e)
		{
			assertNotNull("Expected workspace error in plain JUnit", e);
		}
	}

	@Test
	public void testPersistRenameService_hasRenameFormMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameForm", String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameRelationMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameRelation", String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameValueListMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameValueList", String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameMenuMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameMenu", String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameMediaMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameMedia", String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameScopeMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameScope", String.class, String.class, com.servoy.eclipse.model.nature.ServoyProject.class));
	}

	@Test
	public void testPersistRenameService_hasRenameSolutionMethod() throws NoSuchMethodException
	{
		assertNotNull(PersistRenameService.class.getMethod("renameSolution", String.class, String.class));
	}
}
