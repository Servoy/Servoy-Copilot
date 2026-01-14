package com.servoy.eclipse.servoypilot.preferences;

import org.eclipse.jface.preference.IPreferenceStore;

public final class PreferenceConstants
{

	public enum ModelKind
	{
		NONE("None"), OPENAI("OpenAI"), GEMINI("Gemini");

		private final String displayName;

		ModelKind(String displayName)
		{
			this.displayName = displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	public static final String OPENAI_API_KEY = "openaiApiKey";
	public static final String GEMINI_API_KEY = "geminiApiKey";
	public static final String OPENAI_MODEL = "openaiModel";
	public static final String GEMINI_MODEL = "geminiModel";
	public static final String DEFAULT_MODEL = "defaultModel";

	private PreferenceConstants()
	{
		// Utility class
	}

	public static void initializeDefaults(IPreferenceStore store)
	{
		store.setDefault(OPENAI_API_KEY, "");
		store.setDefault(GEMINI_API_KEY, "");
		store.setDefault(OPENAI_MODEL, "");
		store.setDefault(GEMINI_MODEL, "");
		store.setDefault(DEFAULT_MODEL, "");
	}

}