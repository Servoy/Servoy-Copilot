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
