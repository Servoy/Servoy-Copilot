package com.servoy.eclipse.servoypilot;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.servoy.eclipse.servoypilot.ai.Assistant;
import com.servoy.eclipse.servoypilot.ai.ServoyAiModel;
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

	public void start(BundleContext bundleContext) throws Exception
	{
		bundle = this;
		PreferenceConstants.initializeDefaults(getPreferenceStore());
	}

	public void stop(BundleContext bundleContext) throws Exception
	{
		preferenceStore = null;
		clearChatModel();
	}

	public Assistant getChatModel()
	{
		if (chatModel == null)
		{
			chatModel = new ServoyAiModel(new AiConfiguration());
		}
		return chatModel.getAssistant();
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

}