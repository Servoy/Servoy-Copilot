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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools.security;

import com.servoy.eclipse.servoypilot.tools.core.UIThreadHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IPermissionTool
{
	@Tool("Lists all users defined in the workspace security configuration. Returns user names and their UIDs.")
	default String listUsers()
	{
		return UIThreadHelper.syncExec("listUsers",
			() -> SecurityToolsHelper.getInstance().listUsersImpl());
	}

	@Tool("Creates a new user. Password is plain text and will be hashed internally. If userUID is not provided, a UUID will be auto-generated.")
	default String createUser(
		@P(value = "The user name", required = true) String userName,
		@P(value = "Plain text password (will be hashed internally)", required = true) String password,
		@P(value = "Optional user UUID. If not provided, one will be auto-generated.", required = false) String userUID)
	{
		return UIThreadHelper.syncExec("createUser",
			() -> SecurityToolsHelper.getInstance().createUserImpl(userName, password, userUID));
	}

	@Tool("Renames an existing user.")
	default String changeUserName(
		@P(value = "Current user name", required = true) String oldName,
		@P(value = "New user name", required = true) String newName)
	{
		return UIThreadHelper.syncExec("changeUserName",
			() -> SecurityToolsHelper.getInstance().changeUserNameImpl(oldName, newName));
	}

	@Tool("Changes a user's password. Password is plain text and will be hashed internally.")
	default String setUserPassword(
		@P(value = "The user name", required = true) String userName,
		@P(value = "New plain text password (will be hashed internally)", required = true) String newPassword)
	{
		return UIThreadHelper.syncExec("setUserPassword",
			() -> SecurityToolsHelper.getInstance().setUserPasswordImpl(userName, newPassword));
	}

	@Tool("Creates a new permission. Permissions control access rights to form elements.")
	default String createPermission(
		@P(value = "Name of the new permission", required = true) String permissionName)
	{
		return UIThreadHelper.syncExec("createPermission",
			() -> SecurityToolsHelper.getInstance().createPermissionImpl(permissionName));
	}

	@Tool("Returns current form element access rights for a given permission. Shows VIEWABLE and ACCESSIBLE flags for each element.")
	default String getFormSecurity(
		@P(value = "Permission name", required = true) String permissionName,
		@P(value = "Form name", required = true) String formName,
		@P(value = "Solution name (defaults to active solution if not provided)", required = false) String solutionName)
	{
		return UIThreadHelper.syncExec("getFormSecurity",
			() -> SecurityToolsHelper.getInstance().getFormSecurityImpl(permissionName, formName, solutionName));
	}

	@Tool("Sets VIEWABLE and ACCESSIBLE flags for a form or a specific element within a form. When elementName is omitted, sets access on the form itself.")
	default String setFormElementAccess(
		@P(value = "Permission name", required = true) String permissionName,
		@P(value = "Form name", required = true) String formName,
		@P(value = "Element name (omit to set access on the form itself)", required = false) String elementName,
		@P(value = "Whether the element should be viewable", required = true) boolean viewable,
		@P(value = "Whether the element should be accessible (interactive)", required = true) boolean accessible,
		@P(value = "Solution name (defaults to active solution if not provided)", required = false) String solutionName)
	{
		return UIThreadHelper.syncExec("setFormElementAccess",
			() -> SecurityToolsHelper.getInstance().setFormElementAccessImpl(permissionName, formName, elementName, viewable, accessible, solutionName));
	}

	@Tool("Sets access for multiple elements in one call. accessEntries is a JSON array of objects with fields: elementName (optional, omit for form itself), viewable (boolean), accessible (boolean).")
	default String setFormSecurityBulk(
		@P(value = "Permission name", required = true) String permissionName,
		@P(value = "Form name", required = true) String formName,
		@P(value = "JSON array of access entries, e.g. [{\"elementName\":\"btn1\",\"viewable\":true,\"accessible\":false}]", required = true) String accessEntries,
		@P(value = "Solution name (defaults to active solution if not provided)", required = false) String solutionName)
	{
		return UIThreadHelper.syncExec("setFormSecurityBulk",
			() -> SecurityToolsHelper.getInstance().setFormSecurityBulkImpl(permissionName, formName, accessEntries, solutionName));
	}
}
