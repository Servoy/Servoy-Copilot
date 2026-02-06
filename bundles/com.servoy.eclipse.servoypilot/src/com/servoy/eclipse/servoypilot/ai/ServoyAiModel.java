package com.servoy.eclipse.servoypilot.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;
import com.servoy.eclipse.servoypilot.tools.EclipseTools;
import com.servoy.eclipse.servoypilot.tools.component.ButtonComponentTools;
import com.servoy.eclipse.servoypilot.tools.component.LabelComponentTools;
import com.servoy.eclipse.servoypilot.tools.core.FormTools;
import com.servoy.eclipse.servoypilot.tools.core.RelationTools;
import com.servoy.eclipse.servoypilot.tools.core.StyleTools;
import com.servoy.eclipse.servoypilot.tools.core.ValueListTools;
import com.servoy.eclipse.servoypilot.tools.utility.ContextTools;
import com.servoy.eclipse.servoypilot.tools.utility.DatabaseTools;
import com.servoy.eclipse.servoypilot.tools.utility.KnowledgeTools;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

public class ServoyAiModel
{
	private final Assistant assistant;
	private final ChatMemoryStore chatMemoryStore;
	private final CompletionAssistent completionAssistant;
	private final AiConfiguration configuration;

	public ServoyAiModel(AiConfiguration conf)
	{
		this.configuration = conf;
		String apiKey = conf.getApiKey();
		String model = conf.getModel();
		// Create chat memory store
		this.chatMemoryStore = new InMemoryChatMemoryStore();

		// create the models if there is an api key and model name
		if (apiKey != null && !apiKey.isEmpty() && model != null && !model.isEmpty())
		{
			assistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createChatServices(createOpenAIModel(conf));
				case GEMINI -> createChatServices(createGeminiModel(conf));
				case NONE -> null;
			};
			completionAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createCompletionServices(createOpenAICompletionModel(conf));
				case GEMINI -> createCompletionServices(createGeminiCompletionModel(conf));
				case NONE -> null;
			};
		}
		else
		{
			assistant = null;
			completionAssistant = null;
		}
	}


	public Assistant getAssistant()
	{
		return assistant;
	}

	public CompletionAssistent getCompletionAssistant()
	{
		return completionAssistant;
	}


	private OpenAiStreamingChatModel createOpenAIModel(AiConfiguration conf)
	{
		return OpenAiStreamingChatModel.builder().modelName(conf.getModel()).apiKey(conf.getApiKey()).build();
	}

	private GoogleAiGeminiStreamingChatModel createGeminiModel(AiConfiguration conf)
	{
		return GoogleAiGeminiStreamingChatModel.builder()
			.apiKey(conf.getApiKey())
			.modelName(conf.getModel())
			.allowCodeExecution(true)
			.build();
	}

	private Assistant createChatServices(StreamingChatModel model)
	{
		// Load system prompt (auto-selects based on model provider)
		String systemPrompt = loadSystemPrompt();

		// DEBUG: ServoyLog system prompt info
		System.out.println("=== ServoyAI DEBUG ===");
		System.out.println("System prompt loaded: " + systemPrompt.length() + " characters");
		System.out.println("First 200 chars: " + systemPrompt.substring(0, Math.min(200, systemPrompt.length())));
		System.out.println("======================");

		// Create message window memory (40 messages max)
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
			.maxMessages(40)
			.chatMemoryStore(chatMemoryStore)
			.build();

		AiServices<Assistant> builder = AiServices.builder(Assistant.class);
		builder.streamingChatModel(model);
		builder.chatMemoryProvider(memoryId -> chatMemory);
		builder.systemMessageProvider(memoryId -> {
			System.out.println("=== SYSTEM MESSAGE REQUESTED for memoryId: " + memoryId + " ===");
			return systemPrompt;
		});

		// Register all migrated tools
		builder.tools(
			new EclipseTools(), // General Eclipse/workspace operations
			new ValueListTools(), // core/ - COMPLETE: getValueLists, openValueList, deleteValueLists
			new FormTools(), // core/ - COMPLETE: getForms, openForm, deleteForms
			new RelationTools(), // core/ - COMPLETE: getRelations, openRelation, deleteRelations
			new StyleTools(), // core/ - COMPLETE: getStyles, openStyle, deleteStyle
			new DatabaseTools(), // utility/ - COMPLETE: listTables, getTableInfo
			new ContextTools(), // utility/ - COMPLETE: getContext, setContext
			new KnowledgeTools(), // utility/ - COMPLETE: getKnowledge
			new ButtonComponentTools(), // component/ - COMPLETE: listButtons, addButton, updateButton, deleteButton, getButtonInfo
			new LabelComponentTools() // component/ - COMPLETE: listLabels, addLabel, updateLabel, deleteLabel, getLabelInfo
		);

		return builder.build();
	}

	/**
	 * Load the system prompt from active solution's .servoy directory first, with fallback to plugin resources.
	 * Tries to load from .servoy/system-prompts/chat-system-prompt.txt in the active solution.
	 * If not found, falls back to plugin resources with auto-selection based on AI provider.
	 * 
	 * @return the system prompt text or fallback message if loading fails
	 */
	private String loadSystemPrompt()
	{
		// Try to load from active solution's .servoy directory first
		String promptFromSolution = loadSystemPromptFromSolution();
		if (promptFromSolution != null)
		{
			System.out.println("=== SYSTEM PROMPT LOADED FROM SOLUTION ===");
			System.out.println("Source: .servoy/system-prompts/chat-system-prompt.txt");
			System.out.println("Length: " + promptFromSolution.length() + " characters");
			System.out.println("==========================================");
			return promptFromSolution;
		}

		// Fallback to plugin resources
		String promptFile = selectPromptFile();

		try (InputStream is = getClass().getResourceAsStream(promptFile))
		{
			if (is != null)
			{
				String prompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				System.out.println("=== SYSTEM PROMPT LOADED FROM RESOURCES ===");
				System.out.println("Selected prompt: " + promptFile);
				System.out.println("Provider: " + configuration.getSelectedModel());
				System.out.println("Model: " + configuration.getModel());
				System.out.println("===========================================");
				return prompt;
			}

			System.err.println("System prompt resource not found: " + promptFile);

			// Try default prompt as fallback
			if (!promptFile.equals("/prompts/core-system-prompt.txt"))
			{
				try (InputStream fallbackIs = getClass().getResourceAsStream("/prompts/core-system-prompt.txt"))
				{
					if (fallbackIs != null)
					{
						return new String(fallbackIs.readAllBytes(), StandardCharsets.UTF_8);
					}
				}
				catch (IOException e)
				{
					// Continue to final fallback
				}
			}

			return "You are a Servoy development assistant."; // Final fallback
		}
		catch (IOException e)
		{
			System.err.println("Failed to load system prompt: " + e.getMessage());
			return "You are a Servoy development assistant."; // Fallback
		}
	}

	/**
	 * Load system prompt from active solution's .servoy/system-prompts/ directory.
	 * 
	 * @return the prompt text if found, null otherwise
	 */
	private String loadSystemPromptFromSolution()
	{
		try
		{
			// Get active project
			com.servoy.eclipse.model.nature.ServoyProject activeProject = com.servoy.eclipse.model.ServoyModelFinder.getServoyModel().getActiveProject();

			if (activeProject != null && activeProject.getProject() != null)
			{
				org.eclipse.core.resources.IProject project = activeProject.getProject();
				org.eclipse.core.resources.IFolder servoyFolder = project.getFolder(".servoy");

				if (servoyFolder.exists())
				{
					org.eclipse.core.resources.IFolder systemPromptsFolder = servoyFolder.getFolder("system-prompts");

					if (systemPromptsFolder.exists())
					{
						org.eclipse.core.resources.IFile promptFile = systemPromptsFolder.getFile("chat-system-prompt.txt");

						if (promptFile.exists())
						{
							try (InputStream is = promptFile.getContents())
							{
								return new String(is.readAllBytes(), StandardCharsets.UTF_8);
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			System.err.println("Error loading system prompt from solution: " + e.getMessage());
			// Return null to fall back to resources
		}

		return null;
	}

	/**
	 * Select the appropriate prompt file based on the AI provider.
	 * 
	 * @return the path to the prompt resource file
	 */
	private String selectPromptFile()
	{
		//TODO: fallback to class loader system prompts (standup discussion needed)
		return "/system-prompts/chat-system-prompt.txt";
	}


	private OpenAiChatModel createOpenAICompletionModel(AiConfiguration conf)
	{
		return OpenAiChatModel.builder().modelName("gpt-4o-mini").apiKey(conf.getApiKey()).build(); // hard coded once per chat model, completion must be fast
	}

	private GoogleAiGeminiChatModel createGeminiCompletionModel(AiConfiguration conf)
	{
		return GoogleAiGeminiChatModel.builder().apiKey(conf.getApiKey()).modelName("gemini-2.0-flash").build(); // hard coded once per chat model, completion must be fast
	}

	private CompletionAssistent createCompletionServices(ChatModel model)
	{
		AiServices<CompletionAssistent> builder = AiServices.builder(CompletionAssistent.class);
		builder.chatModel(model);
		builder.systemMessageProvider(object -> "You are a code completion engine for Servoy JavaScript. " +
			"Complete the following code. Return ONLY the code snippet to insert at the cursor. " +
			"Do not include markdown formatting or explanations.\n\nCode:\n");
		return builder.build();

	}

	/**
	 * Clear the chat memory for a specific memory ID (solution name)
	 * @param memoryId the memory ID to clear
	 */
	public void clearMemory(String memoryId)
	{
		if (chatMemoryStore != null)
		{
			chatMemoryStore.deleteMessages(memoryId);
		}
	}
}
