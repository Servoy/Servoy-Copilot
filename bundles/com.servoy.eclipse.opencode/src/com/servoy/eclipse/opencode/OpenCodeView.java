/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

package com.servoy.eclipse.opencode;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.servoy.eclipse.core.IActiveProjectListener;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.browser.BrowserFactory;
import com.servoy.eclipse.ui.browser.IBrowser;

/**
 * Singleton view that hosts the embedded opencode browser.
 * <p>
 * URL initialisation happens entirely inside {@link #createPartControl} so
 * that the view self-initialises correctly whether it is opened for the first
 * time or reopened after being closed - without needing a restart of the
 * opencode server process.
 * </p>
 * <p>
 * Three startup paths are handled:
 * <ol>
 * <li><b>No active solution</b> - a warning page is shown and an
 * {@link IActiveProjectListener} is registered. When a solution is
 * activated the listener navigates to the correct URL and unregisters
 * itself.</li>
 * <li><b>Active solution, server still starting</b> - the loading page is
 * shown and a background thread waits for the server, then
 * navigates.</li>
 * <li><b>Active solution, server already ready</b> - navigation happens
 * immediately (typical on reopen after the server is up).</li>
 * </ol>
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class OpenCodeView extends ViewPart {
	public static final String VIEW_ID = "com.servoy.eclipse.opencode.OpenCodeView";

	private static final String DEFAULT_SERVER_URL = "http://127.0.0.1:" + RunOpencodeCommand.DEFAULT_PORT + "/";

	private static final String INJECT_CSS_JS = OpenCodeBranding.buildInjectScript();

	private IBrowser browser;

	/**
	 * Non-null only while this view is waiting for the first active solution.
	 * Cleared (and removed from the model) on first {@code activeProjectChanged}
	 * call or when the view is disposed.
	 */
	private IActiveProjectListener activeProjectListener;

	// -----------------------------------------------------------------------
	// ViewPart lifecycle
	// -----------------------------------------------------------------------

	@Override
	public void createPartControl(Composite parent) {
		browser = BrowserFactory.createBrowser(parent);
		browser.addLocationListener(new org.eclipse.swt.browser.LocationAdapter() {
			@Override
			public void changed(org.eclipse.swt.browser.LocationEvent event) {
				browser.execute(INJECT_CSS_JS);
			}
		});
		initUrl();
	}

	@Override
	public void setFocus() {
		if (browser != null)
			browser.setFocus();
	}

	@Override
	public void dispose() {
		unregisterActiveProjectListener();
		if (browser != null && !browser.isDisposed()) {
			browser.dispose();
		}
		super.dispose();
	}

	// -----------------------------------------------------------------------
	// Public API
	// -----------------------------------------------------------------------

	public void setUrl(String url) {
		if (browser != null && !browser.isDisposed()) {
			browser.setUrl(url);
		}
	}

	public IBrowser getBrowser() {
		return browser;
	}

	// -----------------------------------------------------------------------
	// URL initialisation - called on every createPartControl
	// -----------------------------------------------------------------------


	private static boolean isServoyAiConfigured() {
		String apiKey = System.getProperty(ProviderConfigWriter.ENV_API_KEY);
		return apiKey != null && !apiKey.isBlank() && SkillsZipExtractor.getSkillsZipSource() != null;
	}

	/**
	 * Single state machine for the view URL. Re-entered whenever any precondition
	 * changes (login completes, solution activated).
	 * <ol>
	 * <li>Login not yet done - show loading, wait for login event.</li>
	 * <li>Login done, Servoy AI not configured - show "enable Servoy AI" page.</li>
	 * <li>Dev/external-server override - use that URL directly.</li>
	 * <li>No active solution - show "no solution" page, wait for project event.</li>
	 * <li>All conditions met - start opencode (first time) and navigate.</li>
	 * </ol>
	 */
	private void initUrl() {
		if (browser == null || browser.isDisposed()) return;

		// State 1: waiting for login
		if (!com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.isLoginComplete()) {
			browser.setUrl(getPageUrl("/resources/opencode-loading.html")); //$NON-NLS-1$
			com.servoy.eclipse.ui.dialogs.ServoyLoginDialog.addLoginListener(
				username -> PlatformUI.getWorkbench().getDisplay().asyncExec(this::initUrl));
			return;
		}

		// State 2: login done but Servoy AI not configured in Servoy Cloud
		if (!isServoyAiConfigured()) {
			browser.setUrl(getPageUrl("/resources/opencode-not-enabled.html")); //$NON-NLS-1$
			return;
		}

		// State 3: dev / external-server override
		String overrideUrl = System.getProperty(OpencodePerspective.URL_PROPERTY);
		if (overrideUrl != null) {
			browser.setUrl(overrideUrl);
			return;
		}

		// State 4: no active solution yet
		String projectPath = getActiveProjectPath();
		if (projectPath == null) {
			browser.setUrl(getPageUrl("/resources/opencode-no-solution.html")); //$NON-NLS-1$
			registerActiveProjectListener();
			return;
		}

		// State 5: all conditions met - start opencode if not already started
		Activator activator = Activator.getInstance();
		if (activator == null) return;

		activator.ensureServerStarting();

		// Always resolve session via the switcher thread - it may need to wait for
		// server startup AND call the REST API to get/create a session.
		browser.setUrl(getPageUrl("/resources/opencode-loading.html")); //$NON-NLS-1$
		startUrlSwitcherThread();
	}

	// -----------------------------------------------------------------------
	// Active-project listener (no-solution path)
	// -----------------------------------------------------------------------

	private void registerActiveProjectListener() {
		IServoyModel model = ServoyModelFinder.getServoyModel();
		if (model == null)
			return;

		activeProjectListener = new IActiveProjectListener.ActiveProjectListener() {
			@Override
			public void activeProjectChanged(ServoyProject activeProject) {
				if (activeProject == null)
					return;
				unregisterActiveProjectListener();
				onActiveSolutionAvailable();
			}
		};

		try {
			model.getClass()
					.getMethod("addActiveProjectListener", IActiveProjectListener.class)
					.invoke(model, activeProjectListener);
		} catch (Exception e) {
			ServoyLog.logError("OpenCodeView: cannot add active project listener", e);
			activeProjectListener = null;
		}
	}

	private void unregisterActiveProjectListener() {
		IActiveProjectListener l = activeProjectListener;
		if (l == null)
			return;
		activeProjectListener = null;

		IServoyModel model = ServoyModelFinder.getServoyModel();
		if (model == null)
			return;
		try {
			model.getClass()
					.getMethod("removeActiveProjectListener", IActiveProjectListener.class)
					.invoke(model, l);
		} catch (Exception e) {
			ServoyLog.logError("OpenCodeView: cannot remove active project listener", e);
		}
	}


	/** Called when a solution is activated - re-enter the state machine on the UI thread. */
	private void onActiveSolutionAvailable() {
		PlatformUI.getWorkbench().getDisplay().asyncExec(this::initUrl);
	}


	// -----------------------------------------------------------------------
	// URL-switcher thread (server-starting path)
	// -----------------------------------------------------------------------

	/**
	 * Spawns a daemon thread that blocks until the opencode server is ready
	 * (up to 120 s), then navigates the browser to the correct project URL.
	 */
	private void startUrlSwitcherThread() {
		Thread switcher = new Thread(() -> {
			try {
				Activator activator = Activator.getInstance();
				if (activator == null)
					return;

				boolean started = activator.waitForServer(120_000);
				final String targetUrl;
				if (started) {
					String projectPath = getActiveProjectPath();
					targetUrl = projectPath != null
							? resolveSessionUrl(activator.getServerPort(), projectPath)
							: "http://127.0.0.1:" + activator.getServerPort() + "/"; //$NON-NLS-1$
				} else {
					targetUrl = DEFAULT_SERVER_URL;
				}

				PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
					if (getSite() != null && getSite().getPage().isPartVisible(OpenCodeView.this)) {
						ServoyLog.logInfo("opencode the url is: " + targetUrl );
						setUrl(targetUrl);
					}
				});
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "opencode-url-switcher");
		switcher.setDaemon(true);
		switcher.start();
	}

	// -----------------------------------------------------------------------
	// Path helpers
	// -----------------------------------------------------------------------

	/**
	 * Returns the path to open in opencode for the currently active Servoy
	 * solution project, walking up to the git root if found.
	 *
	 * @return the path, or {@code null} if no solution is active
	 */
	private String getActiveProjectPath() {
		return OpenCodeUtil.getActiveProjectPath();
	}

	/**
	 * Builds the URL to open for a project. Uses {@code /<base64>/session}
	 * (no specific session ID) - the same path opencode's own UI navigates to
	 * when opening a project, so it goes through the correct routing.
	 */
	private String resolveSessionUrl(int port, String projectPath) {
		String encoded = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(projectPath.getBytes(StandardCharsets.UTF_8));
		return "http://127.0.0.1:" + port + "/" + encoded + "/session"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	private String getPageUrl(String bundlePath) {
		try {
			URL entry = Activator.getInstance().getBundle().getEntry(bundlePath);
			if (entry != null) {
				return FileLocator.toFileURL(entry).toString();
			}
		} catch (IOException e) {
			ServoyLog.logError(e);
		}
		return DEFAULT_SERVER_URL;
	}

}
