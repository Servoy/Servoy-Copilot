/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.servoypilot.assistenttests.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.Iterator;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integration.ServoyRunnerTestBase;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.tools.security.SecurityToolsHelper;
import com.servoy.j2db.persistence.Form;

public class PermissionToolIntegrationTest extends ServoyRunnerTestBase
{
	private SecurityToolsHelper helper;
	private String uniqueSuffix;

	@Before
	public void setUp() throws Exception
	{
		waitForAppServer();
		helper = SecurityToolsHelper.getInstance();
		uniqueSuffix = "_" + System.currentTimeMillis();
	}

	@Test
	public void testListUsers()
	{
		String result = helper.listUsersImpl();
		assertNotNull(result);
		assertFalse("Should not return error", result.startsWith("Error:"));
	}

	@Test
	public void testCreateUser()
	{
		String userName = "testuser" + uniqueSuffix;
		String result = helper.createUserImpl(userName, "password123", null);
		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("created successfully"));

		String listResult = helper.listUsersImpl();
		assertTrue("Created user should appear in list", listResult.contains(userName));
	}

	@Test
	public void testChangeUserName()
	{
		String oldName = "renameuser" + uniqueSuffix;
		String newName = "renamed" + uniqueSuffix;

		helper.createUserImpl(oldName, "password123", null);

		String result = helper.changeUserNameImpl(oldName, newName);
		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("successfully"));

		String listResult = helper.listUsersImpl();
		assertFalse("Old name should no longer appear", listResult.contains(oldName));
		assertTrue("New name should appear", listResult.contains(newName));
	}

	@Test
	public void testSetUserPassword()
	{
		String userName = "pwduser" + uniqueSuffix;
		helper.createUserImpl(userName, "oldpass", null);

		String result = helper.setUserPasswordImpl(userName, "newpass123");
		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("Password updated"));
	}

	@Test
	public void testCreatePermission()
	{
		String permName = "TestPerm" + uniqueSuffix;
		String result = helper.createPermissionImpl(permName);
		assertNotNull(result);
		assertTrue("Should indicate success: " + result, result.contains("created successfully"));
	}

	@Test
	public void testCreateDuplicatePermission()
	{
		String permName = "DupPerm" + uniqueSuffix;
		helper.createPermissionImpl(permName);

		String result = helper.createPermissionImpl(permName);
		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result, result.startsWith("Error:"));
	}

	@Test
	public void testCreateDuplicateUser()
	{
		String userName = "dupuser" + uniqueSuffix;
		helper.createUserImpl(userName, "password123", null);

		String result = helper.createUserImpl(userName, "password456", null);
		assertNotNull(result);
		assertTrue("Should return error for duplicate: " + result, result.startsWith("Error:"));
		assertTrue("Should mention already exists: " + result, result.contains("already exists"));
	}

	@Test
	public void testGetFormSecurity()
	{
		String permName = "SecPerm" + uniqueSuffix;
		helper.createPermissionImpl(permName);

		String formName = findFirstFormName();
		assumeTrue("No forms available in workspace - skipping", formName != null);

		String result = helper.getFormSecurityImpl(permName, formName, null);
		assertNotNull(result);
		assertFalse("Should not return error: " + result, result.startsWith("Error:"));
	}

	@Test
	public void testSetFormElementAccess()
	{
		String permName = "AccPerm" + uniqueSuffix;
		helper.createPermissionImpl(permName);

		String formName = findFirstFormName();
		assumeTrue("No forms available in workspace - skipping", formName != null);

		String result = helper.setFormElementAccessImpl(permName, formName, null, true, false, null);
		assertNotNull(result);
		assertTrue("Should indicate access set: " + result, result.contains("Access set on"));

		String secResult = helper.getFormSecurityImpl(permName, formName, null);
		assertFalse("Should have security info: " + secResult, secResult.contains("No explicit access rights set"));
	}

	@Test
	public void testSetFormSecurityBulk()
	{
		String permName = "BulkPerm" + uniqueSuffix;
		helper.createPermissionImpl(permName);

		String formName = findFirstFormName();
		assumeTrue("No forms available in workspace - skipping", formName != null);

		String accessEntries = "[{\"viewable\":true,\"accessible\":true},{\"viewable\":false,\"accessible\":false}]";
		String result = helper.setFormSecurityBulkImpl(permName, formName, accessEntries, null);
		assertNotNull(result);
		assertTrue("Should indicate bulk update: " + result, result.contains("Bulk access update"));
		assertTrue("Should report entries applied: " + result, result.contains("/2 entries applied"));
	}

	private String findFirstFormName()
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null) return null;

		Iterator<Form> forms = activeProject.getEditingSolution().getForms(null, false);
		if (forms.hasNext())
		{
			return forms.next().getName();
		}

		ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && module.getEditingSolution() != null)
			{
				forms = module.getEditingSolution().getForms(null, false);
				if (forms.hasNext())
				{
					return forms.next().getName();
				}
			}
		}
		return null;
	}
}
