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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.JSUnitRunnerService;
import com.servoy.eclipse.developer.mcp.services.LaunchPromptSuppressor;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ui.preferences.StartupPreferences;

/**
 * SVY-21659: an agent-requested JSUnit run must not open the "Errors in project - Are you sure
 * you want to launch?" (or save-dirty-editors) dialog, and must report the error markers in the
 * tool result instead.
 */
class JSUnitRunnerServiceLaunchPromptTest
{
	private static final String CONFIRM_ERRORS = StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_ERRORS;
	private static final String CONFIRM_WARNINGS = StartupPreferences.DEBUG_CLIENT_CONFIRMATION_WHEN_WARNINGS;
	private static final String SAVE_DIRTY = "org.eclipse.debug.ui.save_dirty_editors_before_launch";

	/**
	 * An in-memory preference node backed by {@code store}. Only get/getBoolean/put/putBoolean/
	 * remove/flush are used by the code under test; every call is also recorded in {@code calls}.
	 * A dynamic proxy rather than Mockito answers, because the answer API lives in
	 * {@code org.mockito.invocation}, which this test bundle does not import.
	 */
	private static IEclipsePreferences fakePrefs(Map<String, String> store, List<String> calls)
	{
		return (IEclipsePreferences)Proxy.newProxyInstance(JSUnitRunnerServiceLaunchPromptTest.class.getClassLoader(),
			new Class< ? >[] { IEclipsePreferences.class }, (proxy, method, args) -> {
				String name = method.getName();
				calls.add(name + (args != null && args.length > 0 ? ":" + args[0] : ""));
				switch (name)
				{
					case "getBoolean" :
					{
						String v = store.get(args[0]);
						return v != null ? Boolean.valueOf(v) : args[1];
					}
					case "get" :
					{
						String v = store.get(args[0]);
						return v != null ? v : args[1];
					}
					case "putBoolean" :
						store.put((String)args[0], String.valueOf(args[1]));
						return null;
					case "put" :
						store.put((String)args[0], (String)args[1]);
						return null;
					case "remove" :
						store.remove(args[0]);
						return null;
					case "flush" :
						return null;
					default :
						throw new UnsupportedOperationException(name);
				}
			});
	}

	private static IEclipsePreferences fakePrefs(Map<String, String> store)
	{
		return fakePrefs(store, new ArrayList<>());
	}

	@Test
	@DisplayName("acquire turns both prompts off; release restores the original values")
	void acquireAndRelease()
	{
		Map<String, String> ui = new HashMap<>();
		ui.put(CONFIRM_ERRORS, "true");
		Map<String, String> debugUi = new HashMap<>();
		debugUi.put(SAVE_DIRTY, "prompt");
		LaunchPromptSuppressor s = new LaunchPromptSuppressor(fakePrefs(ui), fakePrefs(debugUi));

		s.acquire();
		assertEquals("false", ui.get(CONFIRM_ERRORS));
		assertEquals("false", ui.get(CONFIRM_WARNINGS));
		assertEquals("always", debugUi.get(SAVE_DIRTY));

		s.release();
		assertEquals("true", ui.get(CONFIRM_ERRORS));
		assertEquals("false", ui.get(CONFIRM_WARNINGS), "warnings default (false) restored");
		assertEquals("prompt", debugUi.get(SAVE_DIRTY));
		assertEquals(0, s.holders());
	}

	@Test
	@DisplayName("an unset save-dirty preference is removed again on release, not pinned")
	void unsetSaveDirtyIsRemoved()
	{
		Map<String, String> debugUi = new HashMap<>();
		LaunchPromptSuppressor s = new LaunchPromptSuppressor(fakePrefs(new HashMap<>()), fakePrefs(debugUi));
		s.acquire();
		assertEquals("always", debugUi.get(SAVE_DIRTY));
		s.release();
		assertFalse(debugUi.containsKey(SAVE_DIRTY));
	}

	@Test
	@DisplayName("nested holds share one suppression; only the last release restores")
	void nestedHolds()
	{
		Map<String, String> ui = new HashMap<>();
		ui.put(CONFIRM_ERRORS, "true");
		List<String> calls = new ArrayList<>();
		LaunchPromptSuppressor s = new LaunchPromptSuppressor(fakePrefs(ui, calls), null);

		s.acquire();
		s.acquire();
		assertEquals(2, s.holders());
		s.release();
		assertEquals("false", ui.get(CONFIRM_ERRORS), "still held by the second run");
		s.release();
		assertEquals("true", ui.get(CONFIRM_ERRORS));
		assertEquals(1, calls.stream().filter(("getBoolean:" + CONFIRM_ERRORS)::equals).count(), "values saved once, by the first holder");
	}

	@Test
	@DisplayName("a release without an acquire is ignored")
	void unmatchedRelease()
	{
		List<String> calls = new ArrayList<>();
		LaunchPromptSuppressor s = new LaunchPromptSuppressor(fakePrefs(new HashMap<>(), calls), null);
		s.release();
		assertEquals(0, s.holders());
		assertTrue(calls.isEmpty(), "nothing touched: " + calls);
	}

	@Test
	@DisplayName("formatErrorMarkerNote: null without errors; lists markers and the overflow count")
	void formatNote()
	{
		assertNull(JSUnitRunnerService.formatErrorMarkerNote(List.of(), 0));
		String note = JSUnitRunnerService.formatErrorMarkerNote(List.of("/s/forms/a.js:3 bad\nthing"), 12);
		assertTrue(note.contains("count: 12"), note);
		assertTrue(note.contains("- /s/forms/a.js:3 bad thing"), note);
		assertTrue(note.contains("... and 11 more"), note);
		assertFalse(note.matches("(?s).*(^|\\n)\\s*(FAIL|ERROR)\\b.*"), "no line may start with FAIL/ERROR: " + note);
		assertFalse(note.toLowerCase().matches("(?s).*\\b[1-9]\\d*\\s+(failure|failures|error|errors)\\b.*"),
			"no '<n> error(s)' phrase: " + note);
	}

	@Test
	@DisplayName("appendNote: keeps the result, adds the note after a blank line")
	void appendNote()
	{
		assertEquals("r", JSUnitRunnerService.appendNote("r", null));
		assertEquals("n", JSUnitRunnerService.appendNote("", "n"));
		assertEquals("r\n\nn", JSUnitRunnerService.appendNote("r", "n"));
		assertEquals("r\n\nn", JSUnitRunnerService.appendNote("r\n", "n"));
	}

	/** Exposes the protected note builder with a mocked set of projects. */
	private static final class MarkerRunner extends JSUnitRunnerService
	{
		private final ServoyProject[] projects;

		MarkerRunner(ServoyProject... projects)
		{
			this.projects = projects;
		}

		@Override
		protected ServoyProject[] getProjectsToCheckForErrors()
		{
			return projects;
		}

		String note()
		{
			return errorMarkerNote();
		}
	}

	private static IMarker marker(int severity, String message, int line, String path)
	{
		IMarker m = mock(IMarker.class);
		when(m.getAttribute(eq(IMarker.SEVERITY), anyInt())).thenReturn(severity);
		when(m.getAttribute(eq(IMarker.MESSAGE), anyString())).thenReturn(message);
		when(m.getAttribute(eq(IMarker.LINE_NUMBER), anyInt())).thenReturn(line);
		IResource res = mock(IResource.class);
		when(res.getFullPath()).thenReturn(new Path(path));
		when(m.getResource()).thenReturn(res);
		return m;
	}

	private static ServoyProject project(String name, IMarker... markers) throws CoreException
	{
		IProject p = mock(IProject.class);
		when(p.getName()).thenReturn(name);
		when(p.isAccessible()).thenReturn(true);
		when(p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)).thenReturn(markers);
		ServoyProject sp = mock(ServoyProject.class);
		when(sp.getProject()).thenReturn(p);
		return sp;
	}

	@Test
	@DisplayName("errorMarkerNote lists only error markers, across the solution and its modules")
	void noteFromMarkers() throws CoreException
	{
		MarkerRunner runner = new MarkerRunner(
			project("main", marker(IMarker.SEVERITY_ERROR, "Syntax issue", 7, "/main/forms/orders_test.js"),
				marker(IMarker.SEVERITY_WARNING, "just a warning", 2, "/main/x.js")),
			project("mod", marker(IMarker.SEVERITY_ERROR, "Missing dataprovider", -1, "/mod/forms/f.frm")));

		String note = runner.note();
		assertTrue(note.contains("count: 2"), note);
		assertTrue(note.contains("/main/forms/orders_test.js:7 Syntax issue"), note);
		assertTrue(note.contains("/mod/forms/f.frm Missing dataprovider"), note);
		assertFalse(note.contains("just a warning"), note);
	}

	@Test
	@DisplayName("errorMarkerNote is null when there are no error markers or no projects")
	void noNote() throws CoreException
	{
		assertNull(new MarkerRunner(project("main", marker(IMarker.SEVERITY_WARNING, "w", 1, "/main/a.js"))).note());
		assertNull(new MarkerRunner().note());
	}
}
