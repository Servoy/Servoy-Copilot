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

package com.servoy.eclipse.knowledgebase;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.sablo.specification.Package.IPackageReader;

import com.servoy.eclipse.knowledgebase.service.RulesCache;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService;
import com.servoy.eclipse.knowledgebase.util.DebugUtils;
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
	 * Get the rules cache.
	 * 
	 * @return RulesCache class (static methods)
	 */
	public static Class<RulesCache> getRulesCache()
	{
		return RulesCache.class;
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
		DebugUtils.logMethodEntry("KnowledgeBaseManager", "loadKnowledgeBasesForSolution", solution);
		
		if (solution instanceof ServoyProject servoyProject)
		{
			DebugUtils.log("KnowledgeBaseManager", "Solution is ServoyProject: " + servoyProject.getProject().getName());
			
			DebugUtils.log("KnowledgeBaseManager", "Discovering knowledge base packages...");
			IPackageReader[] packageReaders = discoverKnowledgeBasePackagesInSolution(servoyProject);
			
			DebugUtils.log("KnowledgeBaseManager", "Discovered " + packageReaders.length + " knowledge base packages");
			for (int i = 0; i < packageReaders.length; i++)
			{
				DebugUtils.log("KnowledgeBaseManager", "  Package " + (i+1) + ": " + packageReaders[i].getPackageName());
			}
			
			try
			{
				DebugUtils.log("KnowledgeBaseManager", "Getting embedding service instance...");
				ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
				
				DebugUtils.log("KnowledgeBaseManager", "Calling reloadAllKnowledgeBasesFromReaders()...");
				embeddingService.reloadAllKnowledgeBasesFromReaders(packageReaders);
				
				int embeddingCount = embeddingService.getEmbeddingCount();
				int ruleCount = RulesCache.getRuleCount();
				
				DebugUtils.log("KnowledgeBaseManager", "Knowledge base loading complete:");
				DebugUtils.log("KnowledgeBaseManager", "  - Embeddings: " + embeddingCount);
				DebugUtils.log("KnowledgeBaseManager", "  - Rules: " + ruleCount);
				DebugUtils.log("KnowledgeBaseManager", "  - Available intents: " + String.join(", ", RulesCache.getAvailableIntents()));
				
				if (packageReaders.length > 0)
				{
					ServoyLog.logInfo("[KnowledgeBaseManager] Knowledge bases loaded successfully - " + 
						embeddingCount + " embeddings, " + ruleCount + " rules");
				}
				
				DebugUtils.logMethodExit("KnowledgeBaseManager", "loadKnowledgeBasesForSolution", 
					"Success - " + embeddingCount + " embeddings, " + ruleCount + " rules");
			}
			catch (Exception e)
			{
				DebugUtils.logException("KnowledgeBaseManager", "Error loading knowledge bases", e);
				ServoyLog.logError("[KnowledgeBaseManager] Error loading/clearing knowledge bases: " + 
					e.getMessage(), e);
			}
		}
		else
		{
			DebugUtils.log("KnowledgeBaseManager", "Solution is not a ServoyProject instance: " + 
				(solution != null ? solution.getClass().getName() : "null"));
		}
	}

	/**
	 * Reload all knowledge bases from active solution.
	 * Called manually by user via UI action.
	 * Clears existing knowledge and reloads fresh from active solution's .servoy directory.
	 */
	public static void reloadAllKnowledgeBases()
	{
		ServoyLog.logInfo("[KnowledgeBaseManager] reloadAllKnowledgeBases called (manual trigger)");
		ServoyEmbeddingService.getInstance().reloadAllKnowledgeBasesFromReaders(new IPackageReader[0]);
		
		ServoyProject activeProject = ServoyModelFinder.getServoyModel().getActiveProject();
		if (activeProject != null)
		{
			IPackageReader[] packageReaders = discoverKnowledgeBasePackagesInSolution(activeProject);
			try
			{
				ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
				embeddingService.reloadAllKnowledgeBasesFromReaders(packageReaders);
				
				int embeddingCount = embeddingService.getEmbeddingCount();
				int ruleCount = RulesCache.getRuleCount();
				
				ServoyLog.logInfo("[KnowledgeBaseManager] Reload complete - Loaded " + embeddingCount + 
					" embeddings and " + ruleCount + " rules from " + packageReaders.length + " package(s)");
			}
			
			catch (Exception e)
			{
				ServoyLog.logError("[KnowledgeBaseManager] Error reloading knowledge bases: " + e.getMessage(), e);
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
}
