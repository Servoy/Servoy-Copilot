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

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.sablo.specification.Package.IPackageReader;

import com.servoy.eclipse.knowledgebase.ServoyBundlePackageReader;
import com.servoy.eclipse.knowledgebase.service.RulesCache;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.prompts.SystemPrompts;

/**
 * Service for loading instructions from .servoy directory or bundle resources into the knowledge base.
 * Handles clearing and loading knowledge base (system-prompts, rules, and embeddings) from file system or bundle.
 */
public class InstructionsLoadService
{
	private static final String KNOWLEDGEBASE_BUNDLE_ID = "com.servoy.eclipse.servoypilot.knowledgebase";
	
	public InstructionsLoadService()
	{
	}

	/**
	 * Clear the entire knowledge base (rules and embeddings).
	 */
	public void clearKnowledgeBase()
	{
		try
		{
			// Clear rules cache
			RulesCache.clear();

			// Clear embeddings
			ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
			embeddingService.clearEmbeddings();
			ServoyLog.logInfo("[InstructionsLoaderService] Knowledge base cleared");
		}
		catch (Exception e)
		{
			ServoyLog.logError("[InstructionsLoaderService] Error clearing knowledge base: " + e.getMessage(), e);
			throw new RuntimeException("Failed to clear knowledge base", e);
		}
	}

	/**
	 * Load knowledge base from the .servoy directory in the file system.
	 * 
	 * @param servoyFolder the .servoy folder containing system-prompts/, rules/, and embeddings/ subdirectories
	 */
	public void loadFromFileSystem(IFolder servoyFolder)
	{
		if (servoyFolder != null && servoyFolder.exists())
		{
			try
			{
				IFolder systemPromptsFolder = servoyFolder.getFolder("system-prompts");
				IFolder rulesFolder = servoyFolder.getFolder("rules");
				IFolder embeddingsFolder = servoyFolder.getFolder("embeddings");

				if (rulesFolder.exists() && embeddingsFolder.exists())
				{
					// Load system prompts if folder exists (optional)
					if (systemPromptsFolder.exists())
					{
						SystemPrompts.INSTANCE.loadFromPath(systemPromptsFolder);
					}

				// Load rules from file system
				loadRulesFromFolder(rulesFolder);

				// Load embeddings from file system
				loadEmbeddingsFromFolder(embeddingsFolder);

				ServoyLog.logInfo("[InstructionsLoaderService] Knowledge base loaded from: " + servoyFolder.getFullPath());
				return;
				}

				if (!rulesFolder.exists())
				{
					throw new IllegalStateException("rules folder does not exist in .servoy directory");
				}
				throw new IllegalStateException("embeddings folder does not exist in .servoy directory");
			}
			catch (Exception e)
			{
				ServoyLog.logError("[InstructionsLoaderService] Error loading knowledge base from file system: " + e.getMessage(), e);
				throw new RuntimeException("Failed to load knowledge base", e);
			}
		}
		throw new IllegalArgumentException(".servoy folder does not exist");
	}

	/**
	 * Load default knowledge base from the knowledgebase bundle resources.
	 * Used when solution doesn't have a .servoy directory.
	 * Loads from resources/ directory in com.servoy.eclipse.servoypilot.knowledgebase bundle.
	 */
	public void loadFromBundleResources()
	{
		try
		{
			Bundle knowledgebaseBundle = Platform.getBundle(KNOWLEDGEBASE_BUNDLE_ID);
			if (knowledgebaseBundle == null)
			{
				throw new IllegalStateException("Knowledgebase bundle not found: " + KNOWLEDGEBASE_BUNDLE_ID);
			}

			// Create package reader for bundle's resources directory
			IPackageReader bundleReader = new ServoyBundlePackageReader(knowledgebaseBundle, "resources");
			
			// Load rules and embeddings from bundle
			int rulesLoaded = RulesCache.loadFromPackageReader(bundleReader);
			
			ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
			int embeddingsLoaded = embeddingService.loadKnowledgeBaseFromReader(bundleReader);
			
			ServoyLog.logInfo("[InstructionsLoaderService] Default knowledge base loaded from bundle - " + 
				embeddingsLoaded + " embeddings, " + rulesLoaded + " rules");
		}
		catch (Exception e)
		{
			ServoyLog.logError("[InstructionsLoaderService] Error loading default knowledge base from bundle: " + e.getMessage(), e);
			throw new RuntimeException("Failed to load default knowledge base from bundle", e);
		}
	}

	/**
	 * Check if the knowledge base is currently loaded (has content).
	 * Checks both rules cache and embeddings.
	 * 
	 * @return true if knowledge base has content
	 */
	public boolean isKnowledgeBaseLoaded()
	{
		ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
		return RulesCache.getRuleCount() > 0 || embeddingService.hasEmbeddings();
	}

	/**
	 * Load rules from a workspace folder.
	 * 
	 * @param rulesFolder the folder containing rule markdown files
	 */
	private void loadRulesFromFolder(IFolder rulesFolder)
	{
		try
		{
			// Convert IFolder to Path for RulesCache
			java.nio.file.Path rulesPath = java.nio.file.Paths.get(rulesFolder.getLocationURI());

			// Load rules from directory
			int rulesLoaded = RulesCache.loadFromDirectory(rulesPath);
			ServoyLog.logInfo("[InstructionsLoaderService] Loaded " + rulesLoaded + " rules from " + rulesPath);
		}
		catch (Exception e)
		{
			ServoyLog.logError("[InstructionsLoaderService] Error loading rules from folder: " + e.getMessage(), e);
			throw new RuntimeException("Failed to load rules", e);
		}
	}

	/**
	 * Load embeddings from a workspace folder.
	 * 
	 * @param embeddingsFolder the folder containing embedding files
	 */
	private void loadEmbeddingsFromFolder(IFolder embeddingsFolder)
	{
		try
		{
			// Convert IFolder to Path for ServoyEmbeddingService
			java.nio.file.Path embeddingsPath = java.nio.file.Paths.get(embeddingsFolder.getLocationURI());

			// Load embeddings from directory
			ServoyEmbeddingService embeddingService = ServoyEmbeddingService.getInstance();
			int embeddingsLoaded = embeddingService.loadFromDirectory(embeddingsPath);
			ServoyLog.logInfo("[InstructionsLoaderService] Loaded " + embeddingsLoaded + " embeddings from " + embeddingsPath);
		}
		catch (Exception e)
		{
			ServoyLog.logError("[InstructionsLoaderService] Error loading embeddings from folder: " + e.getMessage(), e);
			throw new RuntimeException("Failed to load embeddings", e);
		}
	}
}
