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
package com.servoy.eclipse.developer.mcp.services;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Resolves Servoy script names (form names, scope names) to {@link IFile} instances.
 *
 * <p>Resolution strategy:
 * <ol>
 *   <li>If {@code moduleName} is provided, look up that project in the workspace.</li>
 *   <li>Otherwise, use the currently active Servoy solution.</li>
 *   <li>Within the resolved project, search {@code forms/<name>.js} then {@code scopes/<name>.js},
 *       then recursively in both folders.</li>
 * </ol>
 * </p>
 *
 * <p>This service is reusable across multiple MCP tools (getSource, getClassOutline,
 * getMethodSource, etc.).</p>
 */
@SuppressWarnings("restriction")
@Creatable
public class ServoyScriptResolver
{
	/**
	 * Resolves a Servoy script name to an {@link IFile}.
	 *
	 * @param name       form name or scope name (e.g. {@code "customers"}, {@code "utils"})
	 * @param moduleName optional module/project name; if null, uses the active solution
	 * @return the resolved {@link IFile}, or {@code null} if not found
	 */
	public IFile resolveScript(String name, String moduleName)
	{
		if (name == null || name.isBlank()) return null;

		String cleanName = name.endsWith(".js") ? name.substring(0, name.length() - 3) : name;

		IProject project = resolveProject(moduleName);
		if (project == null || !project.isOpen()) return null;

		// 1. forms/<name>.js (direct)
		IFile file = project.getFile("forms/" + cleanName + ".js");
		if (file.exists()) return file;

		// 2. scopes/<name>.js (direct)
		file = project.getFile("scopes/" + cleanName + ".js");
		if (file.exists()) return file;

		// 3. Recursive search in forms/ folder
		IFolder formsFolder = project.getFolder("forms");
		if (formsFolder.exists())
		{
			IFile found = searchInFolder(formsFolder, cleanName + ".js");
			if (found != null) return found;
		}

		// 4. Recursive search in scopes/ folder
		IFolder scopesFolder = project.getFolder("scopes");
		if (scopesFolder.exists())
		{
			IFile found = searchInFolder(scopesFolder, cleanName + ".js");
			if (found != null) return found;
		}

		return null;
	}

	/**
	 * Returns a human-readable error message when a script cannot be resolved.
	 */
	public String buildNotFoundMessage(String name, String moduleName)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Script not found: '").append(name).append("'");
		if (moduleName != null && !moduleName.isBlank())
		{
			sb.append(" in module '").append(moduleName).append("'");
		}
		else
		{
			IProject project = resolveProject(null);
			if (project != null)
				sb.append(" in active solution '").append(project.getName()).append("'");
			else
				sb.append(" - no active Servoy solution found");
		}
		sb.append(".\nExpected locations: forms/<name>.js or scopes/<name>.js");
		return sb.toString();
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private IProject resolveProject(String moduleName)
	{
		if (moduleName != null && !moduleName.isBlank())
		{
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(moduleName);
			return (project != null && project.exists()) ? project : null;
		}

		// Use active Servoy solution
		IServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = model.getActiveProject();
		return (activeProject != null) ? activeProject.getProject() : null;
	}

	private IFile searchInFolder(IFolder folder, String fileName)
	{
		try
		{
			for (IResource resource : folder.members())
			{
				if (resource instanceof IFile file && file.getName().equals(fileName))
				{
					return file;
				}
				else if (resource instanceof IFolder subFolder)
				{
					IFile found = searchInFolder(subFolder, fileName);
					if (found != null) return found;
				}
			}
		}
		catch (CoreException e)
		{
			ServoyLog.logError("ServoyScriptResolver: error searching folder " + folder.getFullPath(), e);
		}
		return null;
	}
}
