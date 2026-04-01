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
package com.servoy.eclipse.servoypilot.services;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.IParent;
import org.eclipse.dltk.core.IScriptProject;
import org.eclipse.dltk.core.ISourceModule;

import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Service for intelligently resolving file paths in Servoy projects.
 * 
 * Handles various input formats:
 * - Workspace-relative: /ProjectName/forms/customers/customers.js
 * - Form reference: customers (searches in forms folder)
 * - Scope reference: utils (searches in scopes folder)
 * - Partial path: forms/customers/customers.js
 */
public class FilePathResolver
{
	private static FilePathResolver instance;

	private FilePathResolver()
	{
	}

	public static FilePathResolver getInstance()
	{
		if (instance == null)
		{
			instance = new FilePathResolver();
		}
		return instance;
	}

	/**
	 * Resolve a file path intelligently.
	 * 
	 * @param pathOrName the path or name to resolve (e.g., "testCustomers", "utils", "/Project/forms/...", etc.)
	 * @return the resolved IFile or null if not found
	 */
	public IFile resolveFile(String pathOrName)
	{
		if (pathOrName == null || pathOrName.isBlank())
		{
			return null;
		}

		String normalized = pathOrName.trim();
		// Strategy 1: Try as workspace-relative path (starts with /)
		if (normalized.startsWith("/"))
		{
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(normalized));
			if (file.exists())
			{
				return file;
			}

			// Path doesn't exist - extract filename and search
			String fileName = new Path(normalized).lastSegment();
			if (fileName != null)
			{
				return resolveByFileName(fileName);
			}
		}

		// Strategy 2: Get active Servoy project
		ServoyProject activeProject = ServoyModelFinder.getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			IProject project = activeProject.getProject();

			// Strategy 3: Try as form name (search in forms folder)
			IFile formFile = findFormFile(project, normalized);
			if (formFile != null && formFile.exists())
			{
				return formFile;
			}

			// Strategy 4: Try as scope name (search in scopes folder)
			IFile scopeFile = findScopeFile(project, normalized);
			if (scopeFile != null && scopeFile.exists())
			{
				return scopeFile;
			}

			// Strategy 5: Try as project-relative path
			IFile file = project.getFile(new Path(normalized));
			if (file.exists())
			{
				return file;
			}

			// Path doesn't exist - extract filename and search
			String fileName = new Path(normalized).lastSegment();
			if (fileName != null && !fileName.equals(normalized))
			{
				IFile foundFile = searchByFileName(project, fileName);
				if (foundFile != null && foundFile.exists())
				{
					return foundFile;
				}
			}

			// Strategy 6: Search by filename in forms and scopes (original input is just filename)
			IFile foundFile = searchByFileName(project, normalized);
			if (foundFile != null && foundFile.exists())
			{
				return foundFile;
			}
		}
		return null;
	}

	/**
	 * Resolve by filename only (extracted from a path).
	 * Searches in active project.
	 */
	private IFile resolveByFileName(String fileName)
	{
		ServoyProject activeProject = ServoyModelFinder.getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			IProject project = activeProject.getProject();
			return searchByFileName(project, fileName);
		}
		return null;
	}

	/**
	 * Find form file by form name.
	 * Form structure: forms/<formName>.js (directly in forms folder)
	 */
	private IFile findFormFile(IProject project, String formName)
	{
		if (formName != null && !formName.isBlank())
		{
			// Remove .js extension if provided
			String cleanName = formName.endsWith(".js") ? formName.substring(0, formName.length() - 3) : formName;

			// Try forms/<formName>.js (direct in forms folder)
			String directPath = "forms/" + cleanName + ".js";
			IFile file = project.getFile(new Path(directPath));

			if (file.exists())
			{
				return file;
			}
		}
		return null;
	}

	/**
	 * Find scope file by scope name using DLTK API.
	 * Scopes may be in different locations, so we use Servoy's DLTK integration to find them.
	 */
	private IFile findScopeFile(IProject project, String scopeName)
	{
		if (scopeName != null && !scopeName.isBlank())
		{
			// Remove .js extension if provided
			String cleanName = scopeName.endsWith(".js") ? scopeName.substring(0, scopeName.length() - 3) : scopeName;

			try
			{
				// Get DLTK script project
				IScriptProject scriptProject = DLTKCore.create(project);
				if (scriptProject != null && scriptProject.exists())
				{

					// Search for source module matching scope name
					// Scopes are typically named <scopeName>.js
					String searchName = cleanName + ".js";
					IFile foundFile = findSourceModuleByName(scriptProject, searchName);

					if (foundFile != null && foundFile.exists())
					{
						return foundFile;
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error finding scope via DLTK", e);
			}

			// Fallback: Try traditional scopes/ folder
			IFile file = project.getFile(new Path("scopes/" + cleanName + ".js"));
			if (file.exists())
			{
				return file;
			}
		}
		return null;
	}

	/**
	 * Find source module by filename in DLTK script project.
	 * Searches recursively through all source modules.
	 */
	private IFile findSourceModuleByName(IScriptProject scriptProject, String fileName) throws CoreException
	{
		if (scriptProject != null && scriptProject.exists())
		{
			// Get all children recursively
			IModelElement[] children = scriptProject.getChildren();
			for (IModelElement child : children)
			{
				IFile file = searchModelElementForFile(child, fileName);
				if (file != null)
				{
					return file;
				}
			}
		}
		return null;
	}

	/**
	 * Recursively search model element tree for a file with given name.
	 */
	private IFile searchModelElementForFile(IModelElement element, String fileName) throws CoreException
	{
		if (element instanceof ISourceModule)
		{
			ISourceModule module = (ISourceModule)element;
			IResource resource = module.getResource();
			if (resource instanceof IFile)
			{
				IFile file = (IFile)resource;
				if (file.getName().equals(fileName))
				{
					return file;
				}
			}
		}

		// Recurse into children if element is a parent
		if (element instanceof IParent)
		{
			IParent parent = (IParent)element;
			if (parent.hasChildren())
			{
				IModelElement[] children = parent.getChildren();
				if (children != null)
				{
					for (IModelElement child : children)
					{
						IFile file = searchModelElementForFile(child, fileName);
						if (file != null)
						{
							return file;
						}
					}
				}
			}
		}

		return null;
	}

	/**
	 * Search for file by filename in forms and scopes folders.
	 */
	private IFile searchByFileName(IProject project, String fileName)
	{
		if (fileName != null && !fileName.isBlank())
		{
			try
			{
				// Search in forms folder
				IFolder formsFolder = project.getFolder("forms");
				if (formsFolder.exists())
				{
					IFile found = searchInFolder(formsFolder, fileName);
					if (found != null)
					{
						return found;
					}
				}

				// Search in scopes folder
				IFolder scopesFolder = project.getFolder("scopes");
				if (scopesFolder.exists())
				{
					IFile found = searchInFolder(scopesFolder, fileName);
					if (found != null)
					{
						return found;
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error searching for file: " + fileName, e);
			}
		}
		return null;
	}

	/**
	 * Recursively search for a file by name in a folder.
	 */
	private IFile searchInFolder(IFolder folder, String fileName) throws CoreException
	{
		if (folder != null && folder.exists())
		{
			for (IResource resource : folder.members())
			{
				if (resource instanceof IFile)
				{
					IFile file = (IFile)resource;
					if (file.getName().equals(fileName) || file.getName().equals(fileName + ".js"))
					{
						return file;
					}
				}
				else if (resource instanceof IFolder)
				{
					IFile found = searchInFolder((IFolder)resource, fileName);
					if (found != null)
					{
						return found;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Build a user-friendly message when file is not found.
	 * 
	 * @param pathOrName the path/name that was not found
	 * @return error message with suggestions
	 */
	public String buildNotFoundMessage(String pathOrName)
	{
		StringBuilder message = new StringBuilder();
		message.append("File not found: ").append(pathOrName).append("\n\n");

		ServoyProject activeProject = ServoyModelFinder.getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			String projectName = activeProject.getProject().getName();
			message.append("Searched in active solution: ").append(projectName).append("\n\n");
			message.append("Tips:\n");
			message.append("- For forms: use form name (e.g., 'testCustomers') or full path (e.g., '/")
				.append(projectName).append("/forms/testCustomers/testCustomers.js')\n");
			message.append("- For scopes: use scope name (e.g., 'utils') or full path (e.g., '/")
				.append(projectName).append("/scopes/utils.js')\n");
			message.append("- Verify the file exists in the solution\n");
		}
		else
		{
			message.append("No active Servoy solution found.\n");
			message.append("Please activate a solution first, then try again.\n");
		}

		return message.toString();
	}
}
