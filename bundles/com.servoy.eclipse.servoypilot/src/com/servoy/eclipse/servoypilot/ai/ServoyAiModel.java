package com.servoy.eclipse.servoypilot.ai;

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

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;

public class ServoyAiModel
{

	private final Assistant assistant;

	public ServoyAiModel(AiConfiguration conf)
	{
		String apiKey = conf.getApiKey();
		String model = conf.getModel();
		// create the models if there is an api key and model name
		if (apiKey != null && !apiKey.isEmpty() && model != null && !model.isEmpty())
		{
			assistant = switch (conf.getSelectedModel())
			{
				case OPENAI -> createAiServices(createOpenAIModel(conf));
				case GEMINI -> createAiServices(createGeminiModel(conf));
				case NONE -> null;
			};
		}
		else
		{
			assistant = null;
		}
	}

	private OpenAiStreamingChatModel createOpenAIModel(AiConfiguration conf)
	{
		return OpenAiStreamingChatModel.builder().modelName(conf.getModel()).apiKey(conf.getApiKey()).build();
	}

	private GoogleAiGeminiStreamingChatModel createGeminiModel(AiConfiguration conf)
	{
		return GoogleAiGeminiStreamingChatModel.builder().apiKey(conf.getApiKey()).modelName(conf.getModel()).build();
	}

	private Assistant createAiServices(StreamingChatModel model)
	{
		AiServices<Assistant> builder = AiServices.builder(Assistant.class);
		builder.streamingChatModel(model);
		
		// Register all migrated tools
		builder.tools(
			new EclipseTools(),            // General Eclipse/workspace operations
			new ValueListTools(),          // core/ - COMPLETE: getValueLists, openValueList, deleteValueLists
			new FormTools(),               // core/ - COMPLETE: getForms, openForm, deleteForms
			new RelationTools(),           // core/ - COMPLETE: getRelations, openRelation, deleteRelations
			new StyleTools(),              // core/ - COMPLETE: getStyles, openStyle, deleteStyle
			new DatabaseTools(),           // utility/ - COMPLETE: listTables, getTableInfo
			new ContextTools(),            // utility/ - COMPLETE: getContext, setContext
			new KnowledgeTools(),          // utility/ - COMPLETE: getKnowledge
			new ButtonComponentTools(),    // component/ - COMPLETE: listButtons, addButton, updateButton, deleteButton, getButtonInfo
			new LabelComponentTools()      // component/ - COMPLETE: listLabels, addLabel, updateLabel, deleteLabel, getLabelInfo
		);
		
//		builder.systemMessageProvider(null); // TODO add system message provider
		return builder.build();
	}

	public Assistant getAssistant()
	{
		return assistant;
	}
}
