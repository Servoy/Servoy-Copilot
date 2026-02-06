package com.servoy.eclipse.servoypilot.services;

import org.eclipse.core.resources.IFolder;

import com.servoy.eclipse.knowledgebase.service.RulesCache;
import com.servoy.eclipse.knowledgebase.service.ServoyEmbeddingService;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.prompts.SystemPrompts;

/**
 * Service for loading instructions from .servoy directory into the knowledge base.
 * Handles clearing and loading knowledge base (system-prompts, rules, and embeddings) from file system.
 */
public class InstructionsLoadService
{
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
