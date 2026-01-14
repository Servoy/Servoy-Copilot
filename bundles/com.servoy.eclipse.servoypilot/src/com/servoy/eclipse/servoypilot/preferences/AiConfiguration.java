package com.servoy.eclipse.servoypilot.preferences;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.preferences.PreferenceConstants.ModelKind;

public class AiConfiguration
{

	public ModelKind getSelectedModel()
	{
		String model = Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.DEFAULT_MODEL);
		if (model == null || model.isEmpty())
		{
			return ModelKind.NONE;
		}
		return ModelKind.valueOf(model);
	}

	public String getApiKey()
	{
		switch (getSelectedModel())
		{
			case OPENAI :
				return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_API_KEY);
			case GEMINI :
				return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.GEMINI_API_KEY);
			default :
				return null;
		}
	}

	public String getModel()
	{
		switch (getSelectedModel())
		{
			case OPENAI :
				return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_MODEL);
			case GEMINI :
				return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.GEMINI_MODEL);
			default :
				return null;
		}
	}

}
