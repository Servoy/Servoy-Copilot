package com.servoy.eclipse.servoypilot.ai;

import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;
import com.servoy.eclipse.servoypilot.prompts.SystemPrompts;
import com.servoy.eclipse.servoypilot.tools.EclipseTools;
import com.servoy.eclipse.servoypilot.tools.component.ButtonComponentTools;
import com.servoy.eclipse.servoypilot.tools.component.LabelComponentTools;
import com.servoy.eclipse.servoypilot.tools.core.FormTools;
import com.servoy.eclipse.servoypilot.tools.core.RelationTools;
import com.servoy.eclipse.servoypilot.tools.core.StyleTools;
import com.servoy.eclipse.servoypilot.tools.core.ValueListTools;
import com.servoy.eclipse.servoypilot.tools.utility.TargetTools;
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
		String systemPrompt = SystemPrompts.INSTANCE.getChatPrompt();

		// Create message window memory (40 messages max)
		ChatMemory chatMemory = MessageWindowChatMemory.builder()
			.maxMessages(40)
			.chatMemoryStore(chatMemoryStore)
			.build();

		AiServices<Assistant> builder = AiServices.builder(Assistant.class);
		builder.streamingChatModel(model);
		builder.chatMemoryProvider(memoryId -> chatMemory);
		builder.systemMessageProvider(memoryId -> systemPrompt);

		// Register all migrated tools
		builder.tools(
			new EclipseTools(), // General Eclipse/workspace operations
			new ValueListTools(), // core/ - COMPLETE: getValueLists, openValueList, deleteValueLists
			new FormTools(), // core/ - COMPLETE: getForms, openForm, deleteForms
			new RelationTools(), // core/ - COMPLETE: getRelations, openRelation, deleteRelations
			new StyleTools(), // core/ - COMPLETE: getStyles, openStyle, deleteStyle
			new DatabaseTools(), // utility/ - COMPLETE: listTables, getTableInfo
			new TargetTools(), // utility/ - COMPLETE: getTarget, setTarget
			new KnowledgeTools(), // utility/ - COMPLETE: getKnowledge
			new ButtonComponentTools(), // component/ - COMPLETE: listButtons, addButton, updateButton, deleteButton, getButtonInfo
			new LabelComponentTools() // component/ - COMPLETE: listLabels, addLabel, updateLabel, deleteLabel, getLabelInfo
		);

		return builder.build();
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
		builder.systemMessageProvider(object -> SystemPrompts.INSTANCE.getCompletionPrompt());
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
