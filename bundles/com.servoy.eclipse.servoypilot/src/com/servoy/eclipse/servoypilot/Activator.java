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
package com.servoy.eclipse.servoypilot;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.servoy.eclipse.servoypilot.ai.VibeCodingAssistant;
import com.servoy.eclipse.servoypilot.ai.CompletionAssistent;
import com.servoy.eclipse.servoypilot.ai.DocumentationAssistant;
import com.servoy.eclipse.servoypilot.ai.ServoyAiModel;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;
import com.servoy.eclipse.servoypilot.preferences.PreferenceConstants;

public class Activator implements BundleActivator
{

	public static final String PLUGIN_ID = "com.servoy.eclipse.servoypilot";

	private static Activator bundle;

	private ScopedPreferenceStore preferenceStore;
	private ServoyAiModel chatModel;
	private final List<Runnable> chatModelChangeListeners = new ArrayList<>();

	public static Activator getDefault()
	{
		return bundle;
	}


	public IPreferenceStore getPreferenceStore()
	{
		if (preferenceStore == null)
		{
			preferenceStore = new ScopedPreferenceStore(InstanceScope.INSTANCE, PLUGIN_ID);
		}
		return preferenceStore;
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception
	{
		bundle = this;
		PreferenceConstants.initializeDefaults(getPreferenceStore());
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception
	{
		SelectionTracker.getInstance().dispose();

		preferenceStore = null;
		clearChatModel();
	}

	public VibeCodingAssistant getChatModel()
	{
		return getServoyAiModel().getAssistant();
	}


	public ServoyAiModel getServoyAiModel()
	{
		if (chatModel == null)
		{
			chatModel = new ServoyAiModel(new AiConfiguration());
		}
		return chatModel;
	}

	public void clearChatModel()
	{
		chatModel = null;
		fireChatModelChanged();
	}

	public void addChatModelChangeListener(Runnable listener)
	{
		chatModelChangeListeners.add(listener);
	}

	public void removeChatModelChangeListener(Runnable listener)
	{
		chatModelChangeListeners.remove(listener);
	}

	private void fireChatModelChanged()
	{
		chatModelChangeListeners.forEach(Runnable::run);
	}


	public CompletionAssistent getCompletionChatModel()
	{
		return getServoyAiModel().getCompletionAssistant();
	}

	public DocumentationAssistant getDocumentationAssistant()
	{
		return getServoyAiModel().getDocumentationAssistant();
	}

	/**
	 * Clear chat memory for a specific memory ID.
	 * This completely erases conversation history, allowing fresh start.
	 * 
	 * @param memoryId The memory ID to clear (typically "default" or solution name)
	 */
	public void clearChatMemory(String memoryId)
	{
		if (chatModel != null)
		{
			chatModel.clearAllMemories(memoryId);
		}
	}
}