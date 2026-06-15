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

import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.WorkspaceUserManager;
import com.servoy.j2db.dataprocessing.IDataSet;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRepository;
import com.servoy.j2db.persistence.ISupportName;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.server.shared.SecurityInfo;

public class SecurityToolsHelper
{
	private static final SecurityToolsHelper INSTANCE = new SecurityToolsHelper();

	private SecurityToolsHelper()
	{
	}

	public static SecurityToolsHelper getInstance()
	{
		return INSTANCE;
	}

	private String getClientId()
	{
		return ApplicationServerRegistry.get().getClientId();
	}

	private WorkspaceUserManager getUserManager()
	{
		return (WorkspaceUserManager)ServoyModelManager.getServoyModelManager().getServoyModel().getUserManager();
	}

	public String listUsersImpl()
	{
		try
		{
			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			IDataSet users = userManager.getUsers(getClientId());
			if (users == null || users.getRowCount() == 0)
			{
				return "No users defined.";
			}

			StringBuilder result = new StringBuilder();
			result.append("Users (").append(users.getRowCount()).append("):\n\n");
			result.append("| # | Name | UID |\n");
			result.append("|---|------|-----|\n");
			for (int i = 0; i < users.getRowCount(); i++)
			{
				Object[] row = users.getRow(i);
				result.append("| ").append(i + 1).append(" | ").append(row[1]).append(" | ").append(row[0]).append(" |\n");
			}
			return result.toString();
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String createUserImpl(String userName, String password, String userUID)
	{
		try
		{
			if (userName == null || userName.trim().isEmpty()) return "Error: userName is required";
			if (password == null || password.isEmpty()) return "Error: password is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			int result = userManager.createUser(getClientId(), userName, password, userUID, false);
			if (result < 0)
			{
				if (result == -2) return "Error: User '" + userName + "' already exists";
				if (result == -3) return "Error: Empty username or password";
				if (result == -4) return "Error: Resource project missing";
				return "Error: Failed to create user (code: " + result + ")";
			}

			userManager.writeAllSecurityInformation(false);

			String uid = userManager.getUserUID(getClientId(), userName);
			return "User '" + userName + "' created successfully.\n  UID: " + uid;
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String changeUserNameImpl(String oldName, String newName)
	{
		try
		{
			if (oldName == null || oldName.trim().isEmpty()) return "Error: oldName is required";
			if (newName == null || newName.trim().isEmpty()) return "Error: newName is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String userUID = userManager.getUserUID(getClientId(), oldName);
			if (userUID == null) return "Error: User '" + oldName + "' not found";

			boolean success = userManager.changeUserName(getClientId(), userUID, newName);
			if (!success) return "Error: Failed to rename user. The new name '" + newName + "' may already be in use.";

			userManager.writeAllSecurityInformation(false);
			return "User renamed from '" + oldName + "' to '" + newName + "' successfully.";
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String setUserPasswordImpl(String userName, String newPassword)
	{
		try
		{
			if (userName == null || userName.trim().isEmpty()) return "Error: userName is required";
			if (newPassword == null || newPassword.isEmpty()) return "Error: newPassword is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String userUID = userManager.getUserUID(getClientId(), userName);
			if (userUID == null) return "Error: User '" + userName + "' not found";

			boolean success = userManager.setPassword(getClientId(), userUID, newPassword, true);
			if (!success) return "Error: Failed to set password for user '" + userName + "'";

			userManager.writeAllSecurityInformation(false);
			return "Password updated for user '" + userName + "' successfully.";
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String createPermissionImpl(String permissionName)
	{
		try
		{
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			int result = userManager.createGroup(getClientId(), permissionName);
			if (result == -1) return "Error: Permission '" + permissionName + "' already exists or could not be created";

			userManager.writeAllSecurityInformation(false);
			return "Permission '" + permissionName + "' created successfully.";
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String getFormSecurityImpl(String permissionName, String formName, String solutionName)
	{
		try
		{
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";
			if (formName == null || formName.trim().isEmpty()) return "Error: formName is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			Form form = resolveForm(formName, solutionName);
			if (form == null) return "Error: Form '" + formName + "' not found" + (solutionName != null ? " in solution '" + solutionName + "'" : "");

			List<SecurityInfo> infos = userManager.getSecurityInfos(permissionName, form);

			StringBuilder result = new StringBuilder();
			result.append("Form security for '").append(formName).append("' with permission '").append(permissionName).append("':\n\n");

			if (infos == null || infos.isEmpty())
			{
				result.append("No explicit access rights set. Default access applies (VIEWABLE + ACCESSIBLE).");
				return result.toString();
			}

			result.append("| Element | Viewable | Accessible | Access |\n");
			result.append("|---------|----------|------------|--------|\n");

			for (SecurityInfo info : infos)
			{
				String elementName = resolveElementName(form, info.element_uid);
				boolean viewable = (info.access & IRepository.VIEWABLE) != 0;
				boolean accessible = (info.access & IRepository.ACCESSIBLE) != 0;
				result.append("| ").append(elementName).append(" | ").append(viewable).append(" | ").append(accessible).append(" | ").append(info.access).append(" |\n");
			}

			return result.toString();
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String setFormElementAccessImpl(String permissionName, String formName, String elementName, boolean viewable, boolean accessible, String solutionName)
	{
		try
		{
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";
			if (formName == null || formName.trim().isEmpty()) return "Error: formName is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String resolvedSolutionName = resolveSolutionName(solutionName);
			if (resolvedSolutionName == null) return "Error: No active solution found";

			Form form = resolveForm(formName, solutionName);
			if (form == null) return "Error: Form '" + formName + "' not found" + (solutionName != null ? " in solution '" + solutionName + "'" : "");

			int accessMask = (viewable ? IRepository.VIEWABLE : 0) | (accessible ? IRepository.ACCESSIBLE : 0);

			String elementUID;
			if (elementName == null || elementName.trim().isEmpty())
			{
				elementUID = form.getUUID().toString();
			}
			else
			{
				elementUID = resolveElementUID(form, elementName);
				if (elementUID == null) return "Error: Element '" + elementName + "' not found in form '" + formName + "'";
			}

			userManager.setFormSecurityAccess(getClientId(), permissionName, Integer.valueOf(accessMask), elementUID, resolvedSolutionName);
			userManager.writeAllSecurityInformation(false);

			String target = (elementName == null || elementName.trim().isEmpty()) ? "form '" + formName + "'" : "element '" + elementName + "' in form '" + formName + "'";
			return "Access set on " + target + " for permission '" + permissionName + "': viewable=" + viewable + ", accessible=" + accessible;
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String setFormSecurityBulkImpl(String permissionName, String formName, String accessEntries, String solutionName)
	{
		try
		{
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";
			if (formName == null || formName.trim().isEmpty()) return "Error: formName is required";
			if (accessEntries == null || accessEntries.trim().isEmpty()) return "Error: accessEntries is required";

			WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String resolvedSolutionName = resolveSolutionName(solutionName);
			if (resolvedSolutionName == null) return "Error: No active solution found";

			Form form = resolveForm(formName, solutionName);
			if (form == null) return "Error: Form '" + formName + "' not found" + (solutionName != null ? " in solution '" + solutionName + "'" : "");

			JSONArray entries = new JSONArray(accessEntries);
			int successCount = 0;
			StringBuilder errors = new StringBuilder();

			for (int i = 0; i < entries.length(); i++)
			{
				JSONObject entry = entries.getJSONObject(i);
				String elementName = entry.optString("elementName", null);
				boolean viewable = entry.getBoolean("viewable");
				boolean accessible = entry.getBoolean("accessible");

				int accessMask = (viewable ? IRepository.VIEWABLE : 0) | (accessible ? IRepository.ACCESSIBLE : 0);

				String elementUID;
				if (elementName == null || elementName.trim().isEmpty())
				{
					elementUID = form.getUUID().toString();
				}
				else
				{
					elementUID = resolveElementUID(form, elementName);
					if (elementUID == null)
					{
						errors.append("  - Element '").append(elementName).append("' not found\n");
						continue;
					}
				}

				userManager.setFormSecurityAccess(getClientId(), permissionName, Integer.valueOf(accessMask), elementUID, resolvedSolutionName);
				successCount++;
			}

			userManager.writeAllSecurityInformation(false);

			StringBuilder result = new StringBuilder();
			result.append("Bulk access update on form '").append(formName).append("' for permission '").append(permissionName).append("':\n");
			result.append("  ").append(successCount).append("/").append(entries.length()).append(" entries applied successfully.");
			if (errors.length() > 0)
			{
				result.append("\n\nErrors:\n").append(errors);
			}
			return result.toString();
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	private Form resolveForm(String formName, String solutionName)
	{
		if (solutionName != null && !solutionName.trim().isEmpty())
		{
			ServoyProject project = ServoyModelManager.getServoyModelManager().getServoyModel().getServoyProject(solutionName);
			if (project != null && project.getEditingSolution() != null)
			{
				return project.getEditingSolution().getForm(formName);
			}
			return null;
		}

		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null && activeProject.getEditingSolution() != null)
		{
			Form form = activeProject.getEditingSolution().getForm(formName);
			if (form != null) return form;

			ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
			for (ServoyProject module : modules)
			{
				if (module != null && module.getEditingSolution() != null)
				{
					form = module.getEditingSolution().getForm(formName);
					if (form != null) return form;
				}
			}
		}
		return null;
	}

	private String resolveSolutionName(String solutionName)
	{
		if (solutionName != null && !solutionName.trim().isEmpty())
		{
			return solutionName;
		}
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null && activeProject.getEditingSolution() != null)
		{
			return activeProject.getEditingSolution().getName();
		}
		return null;
	}

	private String resolveElementUID(Form form, String elementName)
	{
		Iterator<IPersist> children = form.getAllObjects();
		while (children.hasNext())
		{
			IPersist child = children.next();
			if (child instanceof ISupportName named)
			{
				if (elementName.equals(named.getName()))
				{
					return child.getUUID().toString();
				}
			}
		}
		return null;
	}

	private String resolveElementName(Form form, String elementUID)
	{
		String formUUID = form.getUUID().toString();
		if (formUUID.equals(elementUID))
		{
			return "(form: " + form.getName() + ")";
		}

		Iterator<IPersist> children = form.getAllObjects();
		while (children.hasNext())
		{
			IPersist child = children.next();
			if (child.getUUID().toString().equals(elementUID))
			{
				if (child instanceof ISupportName named && named.getName() != null)
				{
					return named.getName();
				}
				return elementUID;
			}
		}
		return elementUID;
	}
}
