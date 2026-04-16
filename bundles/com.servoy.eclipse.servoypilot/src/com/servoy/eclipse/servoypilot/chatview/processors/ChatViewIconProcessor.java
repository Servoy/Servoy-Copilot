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
package com.servoy.eclipse.servoypilot.chatview.processors;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.MApplication;

import com.servoy.eclipse.servoypilot.util.ChatViewActivator;
import com.servoy.eclipse.ui.tweaks.IconPreferences;

/**
 * Model processor that updates the Servoy AI Chat view icon based on the theme.
 * This runs at Eclipse startup to ensure the correct icon is shown in the Show View dialog.
 */
public class ChatViewIconProcessor
{
	@Execute
	public void execute(MApplication application)
	{
		try
		{
			boolean isDarkTheme = IconPreferences.getInstance().getUseDarkThemeIcons();
			ChatViewActivator.updatePartDescriptorIcon(application, isDarkTheme);
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
}
