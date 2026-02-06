package com.servoy.eclipse.servoypilot.services;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Service for managing instructions file operations in the .servoy directory.
 * Handles copying resources from knowledgebase bundle, creating/deleting .servoy directory structure,
 * and managing system prompts, rules, and embeddings files.
 */
public class InstructionsSaveService
{
	public static final String SERVOY_DIR = ".servoy";
	public static final String RESOURCES_PATH = "resources/";
	public static final String SYSTEM_PROMPTS_DIR = "system-prompts";
	public static final String EMBEDDINGS_DIR = "embeddings";
	public static final String RULES_DIR = "rules";

	private static final String KNOWLEDGEBASE_BUNDLE_ID = "com.servoy.eclipse.servoypilot.knowledgebase";

	/**
	 * Get the active Servoy solution project.
	 * 
	 * @return the active project, or null if none
	 */
	public IProject getActiveProject()
	{
		ServoyProject activeProject = ServoyModelFinder.getServoyModel().getActiveProject();
		if (activeProject != null && activeProject.getProject() != null)
		{
			return activeProject.getProject();
		}
		return null;
	}

	/**
	 * Check if the .servoy directory exists in the given project.
	 * 
	 * @param project the project to check
	 * @return true if .servoy directory exists
	 */
	public boolean servoyDirectoryExists(IProject project)
	{
		if (project != null && project.exists())
		{
			IFolder servoyFolder = project.getFolder(SERVOY_DIR);
			return servoyFolder.exists();
		}
		return false;
	}

	/**
	 * Copy resources from the knowledgebase bundle to the project's .servoy directory.
	 * 
	 * @param project the target project
	 * @param monitor progress monitor (can be null)
	 * @throws CoreException if copy fails
	 * @throws IOException if I/O error occurs
	 */
	public void copyResourcesToSolution(IProject project, IProgressMonitor monitor) throws CoreException, IOException
	{
		if (project != null && project.exists())
		{
			// Create .servoy directory
			IFolder servoyFolder = project.getFolder(SERVOY_DIR);
			if (!servoyFolder.exists())
			{
				servoyFolder.create(true, true, monitor);
			}

			// Create subdirectories
			createFolderIfNeeded(servoyFolder.getFolder(SYSTEM_PROMPTS_DIR), monitor);
			createFolderIfNeeded(servoyFolder.getFolder(EMBEDDINGS_DIR), monitor);
			createFolderIfNeeded(servoyFolder.getFolder(RULES_DIR), monitor);

			// Get knowledgebase bundle
			Bundle knowledgebaseBundle = findKnowledgebaseBundle();
			if (knowledgebaseBundle != null)
			{
				// Copy files from knowledgebase bundle
				IFolder systemPromptsFolder = servoyFolder.getFolder(SYSTEM_PROMPTS_DIR);
				IFolder embeddingsFolder = servoyFolder.getFolder(EMBEDDINGS_DIR);
				IFolder rulesFolder = servoyFolder.getFolder(RULES_DIR);

				copyBundleDirectory(knowledgebaseBundle, RESOURCES_PATH + SYSTEM_PROMPTS_DIR, systemPromptsFolder, monitor);
				copyBundleDirectory(knowledgebaseBundle, RESOURCES_PATH + EMBEDDINGS_DIR, embeddingsFolder, monitor);
				copyBundleDirectory(knowledgebaseBundle, RESOURCES_PATH + RULES_DIR, rulesFolder, monitor);
				return;
			}
			throw new IOException("Knowledgebase bundle not found: " + KNOWLEDGEBASE_BUNDLE_ID);
		}
		throw new IllegalArgumentException("Project does not exist");
	}

	/**
	 * Delete the .servoy directory and all its contents.
	 * 
	 * @param project the project containing the .servoy directory
	 * @param monitor progress monitor (can be null)
	 * @throws CoreException if deletion fails
	 */
	public void deleteServoyDirectory(IProject project, IProgressMonitor monitor) throws CoreException
	{
		if (project != null && project.exists())
		{
			IFolder servoyFolder = project.getFolder(SERVOY_DIR);
			if (servoyFolder.exists())
			{
				servoyFolder.delete(true, monitor);
			}
		}
	}

	/**
	 * Find the knowledgebase bundle.
	 * 
	 * @return the bundle, or null if not found
	 */
	public static Bundle findKnowledgebaseBundle()
	{
		Bundle knowledgebaseBundle = FrameworkUtil.getBundle(InstructionsSaveService.class).getBundleContext().getBundle(KNOWLEDGEBASE_BUNDLE_ID);
		if (knowledgebaseBundle != null)
		{
			return knowledgebaseBundle;
		}

		// Fallback: try to get by symbolic name
		for (Bundle bundle : FrameworkUtil.getBundle(InstructionsSaveService.class).getBundleContext().getBundles())
		{
			if (KNOWLEDGEBASE_BUNDLE_ID.equals(bundle.getSymbolicName()))
			{
				return bundle;
			}
		}

		return null;
	}

	/**
	 * Create a folder if it doesn't exist.
	 * 
	 * @param folder the folder to create
	 * @param monitor progress monitor
	 * @throws CoreException if creation fails
	 */
	private void createFolderIfNeeded(IFolder folder, IProgressMonitor monitor) throws CoreException
	{
		if (!folder.exists())
		{
			folder.create(true, true, monitor);
		}
	}

	/**
	 * Copy a directory from a bundle to a workspace folder.
	 * 
	 * @param bundle the source bundle
	 * @param bundlePath the path within the bundle
	 * @param targetFolder the target workspace folder
	 * @param monitor progress monitor
	 * @throws IOException if I/O error occurs
	 * @throws CoreException if workspace operation fails
	 */
	private void copyBundleDirectory(Bundle bundle, String bundlePath, IFolder targetFolder, IProgressMonitor monitor)
		throws IOException, CoreException
	{
		Enumeration<URL> entries = bundle.findEntries(bundlePath, "*", true);
		if (entries != null)
		{
			while (entries.hasMoreElements())
			{
				URL entryUrl = entries.nextElement();
				String entryPath = entryUrl.getPath();

				// Extract relative path
				int bundlePathIndex = entryPath.indexOf(bundlePath);
				if (bundlePathIndex >= 0)
				{
					String relativePath = entryPath.substring(bundlePathIndex + bundlePath.length());
					if (relativePath.startsWith("/"))
					{
						relativePath = relativePath.substring(1);
					}

					if (!relativePath.isEmpty())
					{
						// Check if it's a directory or file
						if (entryPath.endsWith("/"))
						{
							// It's a directory
							IFolder subFolder = targetFolder.getFolder(new Path(relativePath));
							createFolderIfNeeded(subFolder, monitor);
						}
						else
						{
							// It's a file
							copyBundleFile(entryUrl, relativePath, targetFolder, monitor);
						}
					}
				}
			}
			return;
		}
		throw new IOException("Bundle path not found: " + bundlePath);
	}

	/**
	 * Copy a single file from bundle to workspace.
	 * 
	 * @param entryUrl the bundle entry URL
	 * @param relativePath the relative path for the file
	 * @param targetFolder the target folder
	 * @param monitor progress monitor
	 * @throws CoreException if workspace operation fails
	 * @throws IOException if I/O error occurs
	 */
	private void copyBundleFile(URL entryUrl, String relativePath, IFolder targetFolder, IProgressMonitor monitor)
		throws CoreException, IOException
	{
		IPath filePath = new Path(relativePath);

		// Create parent folders if needed
		if (filePath.segmentCount() > 1)
		{
			IPath parentPath = filePath.removeLastSegments(1);
			IFolder parentFolder = targetFolder.getFolder(parentPath);
			if (!parentFolder.exists())
			{
				createFolderRecursively(parentFolder, monitor);
			}
		}

		// Copy file
		try (InputStream is = entryUrl.openStream())
		{
			org.eclipse.core.resources.IFile targetFile = targetFolder.getFile(filePath);
			if (targetFile.exists())
			{
				targetFile.setContents(is, true, true, monitor);
			}
			else
			{
				targetFile.create(is, true, monitor);
			}
		}
	}

	/**
	 * Create a folder and all its parent folders recursively.
	 * 
	 * @param folder the folder to create
	 * @param monitor progress monitor
	 * @throws CoreException if creation fails
	 */
	private void createFolderRecursively(IFolder folder, IProgressMonitor monitor) throws CoreException
	{
		if (!folder.exists())
		{
			if (folder.getParent() instanceof IFolder)
			{
				createFolderRecursively((IFolder)folder.getParent(), monitor);
			}

			folder.create(true, true, monitor);
		}
	}
}
