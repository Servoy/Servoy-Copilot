package com.servoy.eclipse.servoypilot.prompts;

import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.osgi.framework.Bundle;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.services.InstructionsSaveService;

public class SystemPrompts
{
	public static final SystemPrompts INSTANCE = new SystemPrompts();

	private final Map<String, String> prompts = new HashMap<>();

	private SystemPrompts()
	{
		loadFromBundle();
	}

	public void loadFromPath(IFolder systemPromptsFolder)
	{
		// go over all the files in that filder and store them in the prompts map.
		try
		{
			for (org.eclipse.core.resources.IResource resource : systemPromptsFolder.members())
			{
				if (resource instanceof IFile file)
				{
					String content = new String(file.getContents().readAllBytes());
					prompts.put(file.getName(), content);
				}
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to load system prompts from .servoy directory", e);
		}
		// now that the system prompts are (re)loaded we need to flush the models
		Activator.getDefault().clearChatModel();
	}


	public String getChatPrompt()
	{
		return prompts.get("chat.txt");
	}

	public String getCompletionPrompt()
	{
		return prompts.get("completion.txt");
	}

	public String getDocumentationPrompt()
	{
		return prompts.get("documentation.txt");
	}

	public String getPrompt(String name)
	{
		return prompts.get(name);
	}

	private void loadFromBundle()
	{
		Bundle knowledgebaseBundle = InstructionsSaveService.findKnowledgebaseBundle();
		if (knowledgebaseBundle == null)
		{
			throw new RuntimeException("Knowledgebase bundle not found for loading the system prompts");
		}
		String bundlePath = InstructionsSaveService.RESOURCES_PATH + InstructionsSaveService.SYSTEM_PROMPTS_DIR + "/";
		Enumeration<URL> entries = knowledgebaseBundle.findEntries(bundlePath, "*", true);
		if (entries != null)
		{
			while (entries.hasMoreElements())
			{
				URL entryUrl = entries.nextElement();
				// save the contents of that url by its name into the prompts map.
				String name = entryUrl.getPath().substring(entryUrl.getPath().lastIndexOf("/") + 1);
				try (var stream = entryUrl.openStream())
				{
					String content = new String(stream.readAllBytes());
					prompts.put(name, content);
				}
				catch (Exception e)
				{
					throw new RuntimeException("Failed to load system prompt from bundle: " + name, e);
				}
			}
			return;
		}
	}
}
