package com.servoy.eclipse.servoypilot.preferences;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.preferences.PreferenceConstants.ModelKind;

public class AiConfiguration {

	public ModelKind getSelectedModel() {
		return ModelKind.valueOf(
			Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.DEFAULT_MODEL));
	}
	
	public String getApiKey() {
		switch (getSelectedModel()) {
		case OPENAI:
			return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_API_KEY);
		case GEMINI:
			return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.GEMINI_API_KEY);
		default:
			return "";
		}
	}
	
	public String getModel() {
		switch (getSelectedModel()) {
		case OPENAI:
			return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_MODEL);
		case GEMINI:
			return Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.GEMINI_MODEL);
		default:
			return "";
		}
	}
	
}
