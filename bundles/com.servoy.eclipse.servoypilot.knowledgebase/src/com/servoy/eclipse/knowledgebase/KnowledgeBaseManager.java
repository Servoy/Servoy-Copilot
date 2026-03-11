/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2025 Servoy BV

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
package com.servoy.eclipse.knowledgebase;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.sablo.specification.Package.IPackageReader;

import com.servoy.eclipse.knowledgebase.service.RulesCache;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Central manager for knowledge base operations.
 * Provides facade for:
 * - Loading/reloading knowledge bases from SPM packages
 * - Accessing embedding service and rules cache
 * - Discovering knowledge base bundles in active solution
 * 
 * This class is the main integration point for knowledge base core functionality.
 */
public class KnowledgeBaseManager
{
	/**
	 * Get the embedding service singleton.
	 * 
	 * @return ServoyEmbeddingService instance
	 */
	public static ServoyEmbeddingService getEmbeddingService()
	{
		return ServoyEmbeddingService.getInstance();
	}

	/**
	 * Load knowledge bases for the active solution.
	 * Called automatically when solution activates.
	 * 
	 * Discovers all knowledge base packages in the solution and its modules:
	 * 1. Gets all NG packages from solution via ServoyProject.getNGPackageProjects()
	 * 2. Gets all NG packages from modules
	 * 3. Filters for knowledge base packages (Knowledge-Base: true in MANIFEST.MF)
	 * 4. Loads embeddings and rules from discovered packages
	 * 
	 * @param solution The active Servoy solution
	 */
	public static void loadKnowledgeBasesForSolution(Object solution)
	{
		if (solution instanceof ServoyProject servoyProject)
		{
			String solutionName = servoyProject.getProject().getName();
			
			IPackageReader[] packageReaders = discoverKnowledgeBasePackagesInSolution(servoyProject);
			
			if (packageReaders.length == 0)
			{
				packageReaders = loadDefaultKnowledgeBaseFromBundle();
			}
			
			try
			{
				ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
				
				embeddingService.reloadAllKnowledgeBasesFromReaders(packageReaders);
				
				int embeddingCount = embeddingService.getEmbeddingCount();
				int ruleCount = RulesCache.getRuleCount();
				
				if (packageReaders.length > 0)
				{
					ServoyLog.logInfo("[KnowledgeBaseManager] Knowledge bases loaded successfully - " + 
						embeddingCount + " embeddings, " + ruleCount + " rules");
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("[KnowledgeBaseManager] Error loading/clearing knowledge bases: " + 
					e.getMessage(), e);
			}
		}
	}

	/**
	 * Discover knowledge base in a solution.
	 * 
	 * ONLY loads from .servoy directory if it exists.
	 * If no .servoy directory exists, returns empty array (no knowledge base loaded).
	 * 
	 * @param solution The Servoy solution to scan
	 * @return Array with single package reader for .servoy folder, or empty if folder doesn't exist
	 */
	private static IPackageReader[] discoverKnowledgeBasePackagesInSolution(ServoyProject solution)
	{
		String solutionName = solution.getProject().getName();
		ServoyLog.logInfo("[KnowledgeBaseManager] Discovering knowledge base in solution: " + solutionName);
		
		IProject project = solution.getProject();
		IFolder servoyFolder = project.getFolder(".servoy");
		
		// Check if .servoy directory exists
		if (!servoyFolder.exists())
		{
			ServoyLog.logInfo("[KnowledgeBaseManager] No .servoy directory found - no knowledge base will be loaded");
			return new IPackageReader[0];
		}
		
		ServoyLog.logInfo("[KnowledgeBaseManager] .servoy directory found - creating package reader for it");
		
		// Create package reader for .servoy folder
		ServoyFolderPackageReader reader = new ServoyFolderPackageReader(servoyFolder, solutionName);
		
		return new IPackageReader[] { reader };
	}
	
	/**
	 * Load default knowledge base from bundle resources.
	 * Called when no .servoy directory exists in the solution.
	 * 
	 * @return Array with single package reader for bundle resources
	 */
	private static IPackageReader[] loadDefaultKnowledgeBaseFromBundle()
	{
		try
		{
			System.out.println(">>> [KnowledgeBaseManager.loadDefaultKnowledgeBaseFromBundle] START");
			
			// Get bundle - works even during STARTING state (before start() completes)
			org.osgi.framework.Bundle knowledgebaseBundle = org.eclipse.core.runtime.Platform.getBundle("com.servoy.eclipse.servoypilot.knowledgebase");
			if (knowledgebaseBundle != null)
			{
				ServoyLog.logInfo("[KnowledgeBaseManager] Loading default knowledge base from bundle resources");
				IPackageReader bundleReader = new ServoyBundlePackageReader(knowledgebaseBundle, "resources");
				return new IPackageReader[] { bundleReader };
			}
			else
			{
				ServoyLog.logInfo("[KnowledgeBaseManager] Knowledgebase bundle not found - cannot load default KB");
				return new IPackageReader[0];
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("[KnowledgeBaseManager] Error loading default KB from bundle: " + e.getMessage(), e);
			return new IPackageReader[0];
		}
	}
}
