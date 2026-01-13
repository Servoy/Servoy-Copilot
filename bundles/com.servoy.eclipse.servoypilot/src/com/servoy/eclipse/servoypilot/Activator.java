package com.servoy.eclipse.servoypilot;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import com.servoy.eclipse.servoypilot.ai.Assistant;
import com.servoy.eclipse.servoypilot.ai.ServoyAiModel;
import com.servoy.eclipse.servoypilot.preferences.AiConfiguration;
import com.servoy.eclipse.servoypilot.preferences.PreferenceConstants;

public class Activator implements BundleActivator {

    public static final String PLUGIN_ID = "com.servoy.eclipse.servoypilot";

    private static Activator bundle;

    private ScopedPreferenceStore preferenceStore;
    private ServoyAiModel chatModel;

    
    public static Activator getDefault() {
		return bundle;
	}


    public IPreferenceStore getPreferenceStore() {
        if (preferenceStore == null) {
            preferenceStore = new ScopedPreferenceStore(InstanceScope.INSTANCE, PLUGIN_ID);
        }
        return preferenceStore;
    }

    public void start(BundleContext bundleContext) throws Exception {
    	bundle= this;
        PreferenceConstants.initializeDefaults(getPreferenceStore());
    }

    public void stop(BundleContext bundleContext) throws Exception {
        preferenceStore = null;
        clearModels();
    }
    
	public Assistant getChatModel() {
		if (chatModel == null) {
			chatModel = new ServoyAiModel(new AiConfiguration());
		}
		return chatModel.getAssistant();
	}
	
	public void clearModels() {
		chatModel = null;
	}

}