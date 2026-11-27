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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.preferences.StartupPreferences;

/**
 * Keeps the debug-client launch from opening modal prompts while an agent-requested JSUnit run is
 * in progress.
 * <p>
 * The JSUnit SmartClient launch goes through {@code StartDebugHandler.testAndStartDebugger}, which
 * opens "Errors in project - Are you sure you want to launch?" when the active solution or one of
 * its modules has error markers, and may ask to save dirty editors first. An MCP tool call has no
 * user at the dialog, so the call waits until someone clicks it (SVY-21659: a skill-test replay sat
 * on it). While suppressed, both prompts are off; {@link JSUnitRunnerService} reports the error
 * markers in the tool result instead, so the agent still learns about them.
 * </p>
 * <p>
 * Reference counted: nested or concurrent runs share one suppression, the original values are
 * saved by the first {@link #acquire()} and restored by the last {@link #release()}.
 * </p>
 */
public final class LaunchPromptSuppressor
{
	/** Node {@code StartDebugHandler} reads the confirmation prefs from ({@code com.servoy.eclipse.ui}). */
	private static final String UI_NODE = "com.servoy.eclipse.ui";
	/** Platform debug-UI node holding the save-dirty-editors-before-launch choice. */
	private static final String DEBUG_UI_NODE = "org.eclipse.debug.ui";
	/** Mirrors {@code IInternalDebugUIConstants.PREF_SAVE_DIRTY_EDITORS_BEFORE_LAUNCH} (internal API). */
	static final String SAVE_DIRTY_EDITORS = "org.eclipse.debug.ui.save_dirty_editors_before_launch";
	/** Save silently, then launch. */
	static final String SAVE_ALWAYS = "always";

	private final IEclipsePreferences uiPrefs;
	private final IEclipsePreferences debugUiPrefs;

	private int holders;
	private boolean savedConfirmErrors;
	private boolean savedConfirmWarnings;
	private String savedSaveDirty;

	/**
	 * @param uiPrefs      the {@code com.servoy.eclipse.ui} instance node
	 * @param debugUiPrefs the {@code org.eclipse.debug.ui} instance node (may be {@code null})
	 */
	public LaunchPromptSuppressor(IEclipsePreferences uiPrefs, IEclipsePreferences debugUiPrefs)
	{
		this.uiPrefs = uiPrefs;
		this.debugUiPrefs = debugUiPrefs;
	}

	/**
	 * @return a new suppressor working on the real instance-scope preference nodes. Callers share
	 *         ONE instance so the reference count spans every concurrent run.
	 */
	public static LaunchPromptSuppressor forInstanceScope()
	{
		return new LaunchPromptSuppressor(InstanceScope.INSTANCE.getNode(UI_NODE), InstanceScope.INSTANCE.getNode(DEBUG_UI_NODE));
	}

	/**
	 * Turns both launch prompts off. The first holder saves the current values. Best-effort: a
	 * preference failure is logged and the launch may prompt as before.
	 */
	public synchronized void acquire()
	{
		holders++;
		if (holders > 1) return;
		try
		{
			savedConfirmErrors = uiPrefs.getBoolean(StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_ERRORS,
				StartupPreferences.DEFAULT_ERROR_CONFIRMATION);
			savedConfirmWarnings = uiPrefs.getBoolean(StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_WARNINGS,
				StartupPreferences.DEFAULT_WARNING_CONFIRMATION);
			uiPrefs.putBoolean(StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_ERRORS, false);
			uiPrefs.putBoolean(StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_WARNINGS, false);
			uiPrefs.flush();
			if (debugUiPrefs != null)
			{
				savedSaveDirty = debugUiPrefs.get(SAVE_DIRTY_EDITORS, null);
				debugUiPrefs.put(SAVE_DIRTY_EDITORS, SAVE_ALWAYS);
				debugUiPrefs.flush();
			}
		}
		catch (BackingStoreException | RuntimeException e)
		{
			ServoyLog.logWarning("JSUnit runner: could not suppress the debug-client launch prompts", e);
		}
	}

	/**
	 * Releases one hold; the last holder restores the values saved by the first {@link #acquire()}.
	 * A release without a matching acquire is ignored.
	 */
	public synchronized void release()
	{
		if (holders == 0) return;
		holders--;
		if (holders > 0) return;
		try
		{
			uiPrefs.putBoolean(StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_ERRORS, savedConfirmErrors);
			uiPrefs.putBoolean(StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_WARNINGS, savedConfirmWarnings);
			uiPrefs.flush();
			if (debugUiPrefs != null)
			{
				if (savedSaveDirty == null) debugUiPrefs.remove(SAVE_DIRTY_EDITORS);
				else debugUiPrefs.put(SAVE_DIRTY_EDITORS, savedSaveDirty);
				debugUiPrefs.flush();
			}
		}
		catch (BackingStoreException | RuntimeException e)
		{
			ServoyLog.logWarning("JSUnit runner: could not restore the debug-client launch prompts", e);
		}
	}

	/**
	 * @return the number of current holders (for tests)
	 */
	public synchronized int holders()
	{
		return holders;
	}
}
