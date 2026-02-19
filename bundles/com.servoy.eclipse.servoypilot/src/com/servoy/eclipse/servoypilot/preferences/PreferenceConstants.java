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