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

package com.servoy.eclipse.opencode.skilltest;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Temporarily disables the Servoy debug-client "Errors in project — Are you sure
 * you want to launch?" confirmation dialog around an unattended JSUnit launch,
 * then restores the previous values.
 * <p>
 * The JSUnit SmartClient launch goes through
 * {@code com.servoy.eclipse.debug.handlers.StartDebugHandler.testAndStartDebugger},
 * which reads two preferences from the {@code com.servoy.eclipse.debug} instance
 * preference node and, when the active solution has error/warning markers, opens
 * a modal confirmation. In a skilltest run (which runs fully autonomously) that
 * modal blocks forever and the run times out with zero tests executed. This guard
 * flips both preferences off for the duration of the launch so the client is
 * launched without prompting, and restores them in a {@code finally} so the
 * user's own interactive launches are unaffected.
 * </p>
 * <p>
 * The preference node and key names are referenced by string, not via a compile
 * dependency on {@code com.servoy.eclipse.debug} /
 * {@code com.servoy.eclipse.ui.preferences.StartupPreferences}, to keep this
 * bundle's manifest free of a UI/debug dependency it does not otherwise need. The
 * keys mirror {@code StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_ERRORS} /
 * {@code DEBUG_CLIENT_CONFIRMATION_WHEN_WARNINGS}; if those ever change this guard
 * silently stops suppressing (the launch would prompt again) rather than failing.
 * </p>
 */
final class DebugConfirmationGuard {

	/**
	 * Preference node holding the confirmation prefs.
	 * <p>
	 * NOT {@code com.servoy.eclipse.debug}: {@code StartDebugHandler} lives in that bundle but
	 * reads the prefs via {@code com.servoy.eclipse.ui.Activator.getDefault().getEclipsePreferences()},
	 * which is {@code InstanceScope.getNode("com.servoy.eclipse.ui")}. Writing them under the
	 * debug node had no effect at all, so the "Errors in project - Are you sure you want to
	 * launch?" modal still appeared and blocked the unattended run (SVY-21366).
	 * </p>
	 */
	private static final String DEBUG_NODE = "com.servoy.eclipse.ui"; //$NON-NLS-1$
	private static final String CONFIRM_ON_ERRORS = "debugger.showConfirmationDialogWhenErrors"; //$NON-NLS-1$
	private static final String CONFIRM_ON_WARNINGS = "debugger.showConfirmationDialogWhenWarnings"; //$NON-NLS-1$

	/**
	 * The platform debug-UI preference that decides whether launching prompts to save
	 * dirty editors ({@code "always"} / {@code "never"} / {@code "prompt"}). The skill
	 * run leaves the form editor it created open and dirty, so with the default
	 * {@code "prompt"} the JSUnit launch opens a "Save and launch?" dialog and the
	 * unattended run blocks until it times out with zero tests. Forced to
	 * {@code "always"} for the launch (save silently, then launch) and restored after.
	 */
	private static final String DEBUG_UI_NODE = "org.eclipse.debug.ui"; //$NON-NLS-1$
	private static final String SAVE_DIRTY_EDITORS = "org.eclipse.debug.ui.save_dirty_editors_before_launch"; //$NON-NLS-1$
	private static final String SAVE_ALWAYS = "always"; //$NON-NLS-1$

	/** StartupPreferences defaults: errors confirm by default, warnings do not. */
	private static final boolean DEFAULT_CONFIRM_ON_ERRORS = true;
	private static final boolean DEFAULT_CONFIRM_ON_WARNINGS = false;

	private final SkillTestRunner.Logger logger;
	private final IEclipsePreferences prefs;
	private final boolean active;
	private final boolean prevErrors;
	private final boolean prevWarnings;
	private final IEclipsePreferences debugUiPrefs;
	private final String prevSaveDirty;

	private DebugConfirmationGuard(SkillTestRunner.Logger logger, IEclipsePreferences prefs, boolean active,
			boolean prevErrors, boolean prevWarnings, IEclipsePreferences debugUiPrefs, String prevSaveDirty) {
		this.logger = logger;
		this.prefs = prefs;
		this.active = active;
		this.prevErrors = prevErrors;
		this.prevWarnings = prevWarnings;
		this.debugUiPrefs = debugUiPrefs;
		this.prevSaveDirty = prevSaveDirty;
	}

	/**
	 * Reads and remembers the current confirmation prefs, sets both to {@code false}
	 * and returns a guard whose {@link #restore()} puts them back. Best-effort: if
	 * the preference node is unavailable the returned guard is a no-op.
	 *
	 * @param logger progress sink (may be {@code null})
	 * @return a guard to {@link #restore()} in a {@code finally}
	 */
	static DebugConfirmationGuard suppress(SkillTestRunner.Logger logger) {
		try {
			IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(DEBUG_NODE);
			boolean prevErrors = prefs.getBoolean(CONFIRM_ON_ERRORS, DEFAULT_CONFIRM_ON_ERRORS);
			boolean prevWarnings = prefs.getBoolean(CONFIRM_ON_WARNINGS, DEFAULT_CONFIRM_ON_WARNINGS);
			prefs.putBoolean(CONFIRM_ON_ERRORS, false);
			prefs.putBoolean(CONFIRM_ON_WARNINGS, false);
			prefs.flush();

			// Also stop the platform asking to save dirty editors before the launch: the
			// skill run leaves the editor of the form it created open and dirty, and the
			// default "prompt" opens a modal that blocks the unattended run just as surely.
			IEclipsePreferences debugUiPrefs = InstanceScope.INSTANCE.getNode(DEBUG_UI_NODE);
			String prevSaveDirty = debugUiPrefs.get(SAVE_DIRTY_EDITORS, null);
			debugUiPrefs.put(SAVE_DIRTY_EDITORS, SAVE_ALWAYS);
			debugUiPrefs.flush();

			if (logger != null) {
				logger.log("[skilltest] suppressed debug-client error/warning confirmation" //$NON-NLS-1$
						+ " and save-dirty-editors prompt for the JSUnit launch"); //$NON-NLS-1$
			}
			return new DebugConfirmationGuard(logger, prefs, true, prevErrors, prevWarnings, debugUiPrefs,
					prevSaveDirty);
		} catch (RuntimeException | BackingStoreException | LinkageError e) {
			if (logger != null) {
				logger.log("[skilltest] WARN: could not suppress debug-client confirmation (" //$NON-NLS-1$
						+ e.getMessage() + "); a modal dialog may block the run"); //$NON-NLS-1$
			}
			return new DebugConfirmationGuard(logger, null, false, false, false, null, null);
		}
	}

	/** Restores the confirmation prefs to the values they had before {@link #suppress}. */
	void restore() {
		if (!active || prefs == null) {
			return;
		}
		try {
			prefs.putBoolean(CONFIRM_ON_ERRORS, prevErrors);
			prefs.putBoolean(CONFIRM_ON_WARNINGS, prevWarnings);
			prefs.flush();
			if (debugUiPrefs != null) {
				if (prevSaveDirty == null) {
					debugUiPrefs.remove(SAVE_DIRTY_EDITORS); // was not set - leave it unset
				} else {
					debugUiPrefs.put(SAVE_DIRTY_EDITORS, prevSaveDirty);
				}
				debugUiPrefs.flush();
			}
		} catch (RuntimeException | BackingStoreException | LinkageError e) {
			if (logger != null) {
				logger.log("[skilltest] WARN: could not restore debug-client confirmation prefs: " //$NON-NLS-1$
						+ e.getMessage());
			}
		}
	}
}
