/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

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
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Generic file path resolver - handles workspace-relative paths, partial paths, and recursive search.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.services.FilePathResolver}.
 * </p>
 * <p>
 * Handles various input formats:
 * <ul>
 *   <li>Workspace-relative: {@code /ProjectName/forms/customers/customers.js}</li>
 *   <li>Form reference: {@code customers} (searches in forms folder)</li>
 *   <li>Scope reference: {@code utils} (searches in scopes folder)</li>
 *   <li>Partial path: {@code forms/customers/customers.js}</li>
 * </ul>
 * </p>
 * <p>
 * Distinct from {@link ServoyScriptResolver} which is specialized for resolving Servoy
 * form/scope names within a specific module. {@code FilePathResolver} is a general-purpose
 * workspace path resolver that uses the active solution as the implicit context.
 * </p>
 */
@Creatable
@SuppressWarnings("restriction")
public class FilePathResolver
{
	/**
	 * Resolves a file path intelligently.
	 *
	 * @param pathOrName the path or name to resolve (e.g. {@code "testCustomers"}, {@code "utils"},
	 *                   {@code "/Project/forms/..."}, etc.)
	 * @return the resolved {@link IFile} or {@code null} if not found
	 */
	public IFile resolveFile(String pathOrName)
	{
		if (pathOrName == null || pathOrName.isBlank()) return null;

		String normalized = pathOrName.trim();

		// Strategy 1: workspace-relative path (starts with /)
		if (normalized.startsWith("/"))
		{
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(normalized));
			if (file.exists()) return file;

			String fileName = new Path(normalized).lastSegment();
			if (fileName != null) return resolveByFileName(fileName);
		}

		// Strategy 2: get active Servoy project
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			IProject project = activeProject.getProject();

			// Strategy 3: try as form name
			IFile formFile = findFormFile(project, normalized);
			if (formFile != null && formFile.exists()) return formFile;

			// Strategy 4: try as scope name
			IFile scopeFile = findScopeFile(project, normalized);
			if (scopeFile != null && scopeFile.exists()) return scopeFile;

			// Strategy 5: try as project-relative path
			IFile file = project.getFile(new Path(normalized));
			if (file.exists()) return file;

			// Strategy 6: extract filename and search
			String fileName = new Path(normalized).lastSegment();
			if (fileName != null && !fileName.equals(normalized))
			{
				IFile foundFile = searchByFileName(project, fileName);
				if (foundFile != null && foundFile.exists()) return foundFile;
			}

			// Strategy 7: search by filename in forms and scopes (original input is just filename)
			IFile foundFile = searchByFileName(project, normalized);
			if (foundFile != null && foundFile.exists()) return foundFile;
		}
		return null;
	}

	/**
	 * Builds a user-friendly message when a file cannot be resolved.
	 */
	public String buildNotFoundMessage(String pathOrName)
	{
		StringBuilder message = new StringBuilder();
		message.append("File not found: ").append(pathOrName).append("\n\n");

		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			String projectName = activeProject.getProject().getName();
			message.append("Searched in active solution: ").append(projectName).append("\n\n");
			message.append("Tips:\n");
			message.append("- For forms: use form name (e.g. 'testCustomers') or full path (e.g. '/")
				.append(projectName).append("/forms/testCustomers/testCustomers.js')\n");
			message.append("- For scopes: use scope name (e.g. 'utils') or full path (e.g. '/")
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

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private IFile resolveByFileName(String fileName)
	{
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			IProject project = activeProject.getProject();
			return searchByFileName(project, fileName);
		}
		return null;
	}

	private IFile findFormFile(IProject project, String formName)
	{
		if (formName != null && !formName.isBlank())
		{
			String cleanName = formName.endsWith(".js") ? formName.substring(0, formName.length() - 3) : formName;
			IFile file = project.getFile(new Path("forms/" + cleanName + ".js"));
			if (file.exists()) return file;
		}
		return null;
	}

	private IFile findScopeFile(IProject project, String scopeName)
	{
		if (scopeName != null && !scopeName.isBlank())
		{
			String cleanName = scopeName.endsWith(".js") ? scopeName.substring(0, scopeName.length() - 3) : scopeName;

			try
			{
				IScriptProject scriptProject = DLTKCore.create(project);
				if (scriptProject != null && scriptProject.exists())
				{
					String searchName = cleanName + ".js";
					IFile foundFile = findSourceModuleByName(scriptProject, searchName);
					if (foundFile != null && foundFile.exists()) return foundFile;
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("FilePathResolver: error finding scope via DLTK", e);
			}

			// Fallback: traditional scopes/ folder
			IFile file = project.getFile(new Path("scopes/" + cleanName + ".js"));
			if (file.exists()) return file;
		}
		return null;
	}

	private IFile findSourceModuleByName(IScriptProject scriptProject, String fileName) throws CoreException
	{
		if (scriptProject != null && scriptProject.exists())
		{
			IModelElement[] children = scriptProject.getChildren();
			for (IModelElement child : children)
			{
				IFile file = searchModelElementForFile(child, fileName);
				if (file != null) return file;
			}
		}
		return null;
	}

	private IFile searchModelElementForFile(IModelElement element, String fileName) throws CoreException
	{
		if (element instanceof ISourceModule module)
		{
			IResource resource = module.getResource();
			if (resource instanceof IFile file && file.getName().equals(fileName)) return file;
		}
		if (element instanceof IParent parent && parent.hasChildren())
		{
			IModelElement[] children = parent.getChildren();
			if (children != null)
			{
				for (IModelElement child : children)
				{
					IFile file = searchModelElementForFile(child, fileName);
					if (file != null) return file;
				}
			}
		}
		return null;
	}

	private IFile searchByFileName(IProject project, String fileName)
	{
		if (fileName == null || fileName.isBlank()) return null;
		try
		{
			IFolder formsFolder = project.getFolder("forms");
			if (formsFolder.exists())
			{
				IFile found = searchInFolder(formsFolder, fileName);
				if (found != null) return found;
			}
			IFolder scopesFolder = project.getFolder("scopes");
			if (scopesFolder.exists())
			{
				IFile found = searchInFolder(scopesFolder, fileName);
				if (found != null) return found;
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("FilePathResolver: error searching for file: " + fileName, e);
		}
		return null;
	}

	private IFile searchInFolder(IFolder folder, String fileName) throws CoreException
	{
		if (folder == null || !folder.exists()) return null;
		for (IResource resource : folder.members())
		{
			if (resource instanceof IFile file && (file.getName().equals(fileName) || file.getName().equals(fileName + ".js")))
				return file;
			if (resource instanceof IFolder subFolder)
			{
				IFile found = searchInFolder(subFolder, fileName);
				if (found != null) return found;
			}
		}
		return null;
	}
}
