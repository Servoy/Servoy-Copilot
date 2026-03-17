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
package com.servoy.eclipse.servoypilot.ai;

import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;
import com.servoy.eclipse.servoypilot.prompts.SystemPrompts;
import com.servoy.eclipse.servoypilot.tools.CodeContextTools;
import com.servoy.eclipse.servoypilot.tools.EclipseTools;
import com.servoy.eclipse.servoypilot.tools.FileReadingTools;
import com.servoy.eclipse.servoypilot.tools.component.ButtonComponentTools;
import com.servoy.eclipse.servoypilot.tools.component.LabelComponentTools;
import com.servoy.eclipse.servoypilot.tools.core.FormTools;
import com.servoy.eclipse.servoypilot.tools.core.RelationTools;
import com.servoy.eclipse.servoypilot.tools.core.StyleTools;
import com.servoy.eclipse.servoypilot.tools.core.ValueListTools;
import com.servoy.eclipse.servoypilot.tools.utility.DatabaseTools;
import com.servoy.eclipse.servoypilot.tools.utility.KnowledgeTools;
import com.servoy.eclipse.servoypilot.tools.utility.TargetTools;
import com.servoy.eclipse.servoypilot.tools.utility.WebFetchTools;

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
	private static final int MAX_MESSAGES = 40;

	private final AiConfiguration conf;
	private final ChatMemoryStore sharedMemoryStore;

	private VibeCodingAssistant vibeCodingAssistant;
	private CompletionAssistent completionAssistant;
	private DocumentationAssistant documentationAssistant;
	private QuickFixAssistant quickFixAssistant;
	private ExplainAssistant explainAssistant;
	private ReviewAssistant reviewAssistant;

	public ServoyAiModel(AiConfiguration conf)
	{
		this.conf = conf;
		this.sharedMemoryStore = new InMemoryChatMemoryStore();
	}


	public VibeCodingAssistant getVibeCodingAssistant()
	{
		if (vibeCodingAssistant == null && conf.isValid())
		{
			vibeCodingAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createVibeCodingServices(createOpenAIModel(conf));
				case GEMINI -> createVibeCodingServices(createGeminiModel(conf));
				case NONE -> null;
			};
		}
		return vibeCodingAssistant;
	}

	public CompletionAssistent getCompletionAssistant()
	{
		if (completionAssistant == null && conf.isValid())
		{
			completionAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createCompletionServices(createOpenAICompletionModel(conf));
				case GEMINI -> createCompletionServices(createGeminiCompletionModel(conf));
				case NONE -> null;
			};
		}
		return completionAssistant;
	}

	public DocumentationAssistant getDocumentationAssistant()
	{
		if (documentationAssistant == null && conf.isValid())
		{
			documentationAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createDocumentationServices(createOpenAIDocumentationModel(conf));
				case GEMINI -> createDocumentationServices(createGeminiDocumentationModel(conf));
				case NONE -> null;
			};
		}
		return documentationAssistant;
	}

	public QuickFixAssistant getQuickFixAssistant()
	{
		if (quickFixAssistant == null && conf.isValid())
		{
			quickFixAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createQuickFixServices(createQuickFixOpenAIModel(conf), createOpenAIModel(conf));
				case GEMINI -> createQuickFixServices(createQuickFixGeminiModel(conf), createGeminiModel(conf));
				case NONE -> null;
			};
		}
		return quickFixAssistant;
	}

	public ExplainAssistant getExplainAssistant()
	{
		if (explainAssistant == null && conf.isValid())
		{
			explainAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createExplainServices(createOpenAIModel(conf));
				case GEMINI -> createExplainServices(createGeminiModel(conf));
				case NONE -> null;
			};
		}
		return explainAssistant;
	}

	public ReviewAssistant getReviewAssistant()
	{
		if (reviewAssistant == null && conf.isValid())
		{
			reviewAssistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createReviewServices(createOpenAIModel(conf));
				case GEMINI -> createReviewServices(createGeminiModel(conf));
				case NONE -> null;
			};
		}
		return reviewAssistant;
	}

	private ChatModel createQuickFixOpenAIModel(AiConfiguration conf)
	{
		return OpenAiChatModel.builder().modelName(conf.getModel()).apiKey(conf.getApiKey()).build();
	}

	private ChatModel createQuickFixGeminiModel(AiConfiguration conf)
	{
		return GoogleAiGeminiChatModel.builder().apiKey(conf.getApiKey()).modelName(conf.getModel()).allowCodeExecution(true).build();
	}

	public AiConfiguration getConfiguration()
	{
		return conf;
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
			.allowCodeExecution(false)
			.allowGoogleSearch(false)
			.build();
	}

	private VibeCodingAssistant createVibeCodingServices(StreamingChatModel model)
	{
		// Load system prompt (auto-selects based on model provider)
		String systemPrompt = SystemPrompts.INSTANCE.getChatPrompt();

		AiServices<VibeCodingAssistant> builder = AiServices.builder(VibeCodingAssistant.class);
		builder.streamingChatModel(model);
		builder.chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
			.id(memoryId)
			.alwaysKeepSystemMessageFirst(true)
			.maxMessages(MAX_MESSAGES)
			.chatMemoryStore(sharedMemoryStore)
			.build());
		builder.systemMessageProvider(memoryId -> systemPrompt);

		// Register all tools
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

	private OpenAiStreamingChatModel createOpenAIDocumentationModel(AiConfiguration conf)
	{
		return OpenAiStreamingChatModel.builder().modelName(conf.getModel()).apiKey(conf.getApiKey()).build();
	}

	private GoogleAiGeminiStreamingChatModel createGeminiDocumentationModel(AiConfiguration conf)
	{
		return GoogleAiGeminiStreamingChatModel.builder()
			.apiKey(conf.getApiKey())
			.modelName(conf.getModel())
			.allowCodeExecution(true)
			.build();
	}

	private DocumentationAssistant createDocumentationServices(StreamingChatModel model)
	{
		String systemPrompt = SystemPrompts.INSTANCE.getDocumentationPrompt();

		AiServices<DocumentationAssistant> builder = AiServices.builder(DocumentationAssistant.class);
		builder.streamingChatModel(model);
		builder.chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
			.id(memoryId)
			.alwaysKeepSystemMessageFirst(true)
			.maxMessages(MAX_MESSAGES)
			.chatMemoryStore(sharedMemoryStore)
			.build());
		builder.systemMessageProvider(memoryId -> systemPrompt);

		// Register documentation tools
		builder.tools(new com.servoy.eclipse.servoypilot.tools.DocumentationTools());

		return builder.build();
	}

	private QuickFixAssistant createQuickFixServices(ChatModel model, StreamingChatModel streamingModel)
	{
		String systemPrompt = SystemPrompts.INSTANCE.getQuickFixPrompt();

		return AiServices.builder(QuickFixAssistant.class)
			.chatModel(model)
			.streamingChatModel(streamingModel)
			.chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
				.id(memoryId)
				.maxMessages(MAX_MESSAGES)
				.chatMemoryStore(sharedMemoryStore)
				.build())
			.systemMessageProvider(memoryId -> systemPrompt)
			.tools(new CodeContextTools())
			.build();
	}

	private ExplainAssistant createExplainServices(StreamingChatModel model)
	{
		String systemPrompt = SystemPrompts.INSTANCE.getExplainPrompt();

		return AiServices.builder(ExplainAssistant.class)
			.streamingChatModel(model)
			.chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
				.id(memoryId)
				.alwaysKeepSystemMessageFirst(true)
				.maxMessages(MAX_MESSAGES)
				.chatMemoryStore(sharedMemoryStore)
				.build())
			.systemMessageProvider(memoryId -> systemPrompt)
			.tools(
				new FileReadingTools(), // Read file contents (e.g., for code explanation)
				new EclipseTools(), // Code search, file operations, workspace queries
				new KnowledgeTools(), // Servoy API documentation lookup
				new WebFetchTools() // Fetch docs.servoy.com pages when needed
			)
			.build();
	}

	private ReviewAssistant createReviewServices(StreamingChatModel model)
	{
		String systemPrompt = SystemPrompts.INSTANCE.getReviewPrompt();

		return AiServices.builder(ReviewAssistant.class)
			.streamingChatModel(model)
			.chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
				.id(memoryId)
				.alwaysKeepSystemMessageFirst(true)
				.maxMessages(MAX_MESSAGES)
				.chatMemoryStore(sharedMemoryStore)
				.build())
			.systemMessageProvider(memoryId -> systemPrompt)
			.tools(
				new FileReadingTools(), // Read file contents (e.g., for code explanation)
				new EclipseTools(), // Code search, file operations, workspace queries
				new KnowledgeTools(), // Servoy API documentation lookup
				new WebFetchTools() // Fetch docs.servoy.com pages when needed
			)
			.build();
	}

	/**
	 * Clear memory for a specific memory ID
	 * @param memoryId the memory ID to clear (e.g., "MySolution-vibe")
	 */
	public void clearMemory(String memoryId)
	{
		if (sharedMemoryStore != null)
		{
			sharedMemoryStore.deleteMessages(memoryId);
		}
	}

	/**
	 * Clear all assistant memories for a specific solution.
	 * Iterates through all assistant types and clears their memory IDs.
	 * @param solutionName the solution name (e.g., "MySolution")
	 */
	public void clearAllMemories(String solutionName)
	{
		if (sharedMemoryStore != null)
		{
			// Iterate through all assistant types and clear their memories
			for (AssistantType assistantType : AssistantType.values())
			{
				String memoryId = solutionName + assistantType.getMemorySuffix();
				sharedMemoryStore.deleteMessages(memoryId);
			}
		}
	}

	/**
	 * Get the shared memory store used by all assistants
	 * @return the shared memory store
	 */
	public ChatMemoryStore getSharedMemoryStore()
	{
		return sharedMemoryStore;
	}
}
