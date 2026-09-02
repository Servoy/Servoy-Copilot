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
package com.servoy.eclipse.developer.mcp.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Global intercepter that intercepts SWT dialog shells during PDE integration tests.
 * <p>
 * Tests push {@link DialogExpectation}s before triggering code that shows a dialog;
 * the Display filter pops the first matching expectation and satisfies it automatically
 * (filling fields and clicking the configured button). If a dialog appears with no
 * matching expectation, the shell is closed and the failure is recorded so that
 * {@link DialogGuardRule} can fail the test at the end of the test method with the
 * UI-thread stack trace captured at dialog-open time.
 * <p>
 * <b>Threading note:</b> Integration tests run on a background thread; dialogs open
 * on the SWT UI thread. Expectations are therefore stored in a plain
 * {@link ConcurrentLinkedDeque} (not a {@code ThreadLocal}), and tests are assumed to
 * run sequentially within a suite.
 * <p>
 * <b>Lifecycle:</b>
 * <ol>
 *   <li>The filter is installed lazily the first time {@link #beginTest()} is called and
 *       is never removed during the test run (it is a no-op when {@code active == false}).</li>
 *   <li>{@link #beginTest()} activates the guard and clears per-test state.</li>
 *   <li>{@link #endTest()} deactivates the guard (so non-test code is never intercepted)
 *       and returns any recorded failures.</li>
 * </ol>
 */
public class TestDialogInterceptor
{
	// -------------------------------------------------------------------------
	// State shared across test thread and UI thread
	// -------------------------------------------------------------------------

	/** Whether the guard is currently active (i.e. a test is running). */
	private static volatile boolean active = false;

	/** Expectations pushed by the test thread, consumed by the UI thread. */
	private static final ConcurrentLinkedDeque<DialogExpectation> expectations = new ConcurrentLinkedDeque<>();

	/** Failures recorded by the UI thread, read by the test thread in endTest(). */
	private static final CopyOnWriteArrayList<UnexpectedDialogFailure> failures = new CopyOnWriteArrayList<>();

	/** The installed Display filter — non-null once {@link #ensureInstalled()} has run. */
	private static volatile Listener shellShowFilter = null;

	/** Guards one-time installation of the filter. */
	private static final Object INSTALL_LOCK = new Object();

	// -------------------------------------------------------------------------
	// Public API used by tests
	// -------------------------------------------------------------------------

	/**
	 * Push an expectation. The next dialog whose title and message match this
	 * expectation (substring, case-insensitive) will be satisfied automatically.
	 * Expectations are consumed in FIFO order but only the first one that matches
	 * a given dialog is used.
	 */
	public static void expect(DialogExpectation.Builder builder)
	{
		expect(builder.build());
	}

	/** @see #expect(DialogExpectation.Builder) */
	public static void expect(DialogExpectation expectation)
	{
		expectations.addLast(expectation);
	}

	// -------------------------------------------------------------------------
	// Lifecycle — called by DialogGuardRule
	// -------------------------------------------------------------------------

	/** Called by {@link DialogGuardRule} before each test method. */
	static void beginTest()
	{
		ensureInstalled();
		expectations.clear();
		failures.clear();
		active = true;
	}

	/**
	 * Called by {@link DialogGuardRule} after each test method.
	 * Returns a snapshot of any recorded unexpected-dialog failures.
	 */
	static List<UnexpectedDialogFailure> endTest()
	{
		active = false;
		List<UnexpectedDialogFailure> snapshot = new ArrayList<>(failures);
		failures.clear();
		expectations.clear();
		return Collections.unmodifiableList(snapshot);
	}

	// -------------------------------------------------------------------------
	// Filter installation
	// -------------------------------------------------------------------------

	private static void ensureInstalled()
	{
		if (shellShowFilter != null) return;
		synchronized (INSTALL_LOCK)
		{
			if (shellShowFilter != null) return;
			Display display = Display.getDefault();
			if (display.getThread() == Thread.currentThread())
			{
				doInstall(display);
			}
			else
			{
				display.syncExec(() -> doInstall(display));
			}
		}
	}

	private static void doInstall(Display display)
	{
		Listener filter = event -> {
			if (!(event.widget instanceof Shell)) return;
			Shell shell = (Shell)event.widget;

			// Skip non-modal shells — workbench windows, progress monitors without modality, etc.
			int style = shell.getStyle();
			boolean isModal = (style & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
			if (!isModal) return;

			if (!active) return;

			// Capture the UI-thread stack at dialog-open time for diagnostics
			StackTraceElement[] openStack = Thread.currentThread().getStackTrace();

			// Use asyncExec so the shell has finished initialising its contents
			// this is currently commented out as I think the shell is already initialized here
//			display.asyncExec(() -> {
//				if (shell.isDisposed() || !shell.isVisible()) return;

				String title = shell.getText();
				String message = collectMessage(shell);

				DialogExpectation match = findAndRemoveExpectation(title, message);
				if (match != null)
				{
					applyExpectation(shell, match);
				}
				else
				{
					UnexpectedDialogFailure udf = new UnexpectedDialogFailure(title, message, openStack);
					failures.add(udf); // just in case the throw below does not end up failing the junit test (with error) - so if it gets intercepted, remember it for later

					// make sure it gets closed
					display.asyncExec(() -> {
						if (shell.isDisposed() || !shell.isVisible()) return;
						shell.close();
					});
					
					// try to fail fast
					StringBuilder sb = new StringBuilder();
					udf.addTitleAndMessageToDescription(sb);
					throw new AssertionError(sb.toString()); // this might close it as well
				}
//			});
		};

		display.addFilter(SWT.Show, filter);
		shellShowFilter = filter;
	}

	// -------------------------------------------------------------------------
	// Expectation matching
	// -------------------------------------------------------------------------

	private static DialogExpectation findAndRemoveExpectation(String title, String message)
	{
		for (DialogExpectation e : expectations)
		{
			if (matches(e, title, message))
			{
				expectations.remove(e);
				return e;
			}
		}
		return null;
	}

	private static boolean matches(DialogExpectation e, String title, String message)
	{
		if (e.titleContains != null && !containsIgnoreCase(title, e.titleContains)) return false;
		if (e.messageContains != null && !containsIgnoreCase(message, e.messageContains)) return false;
		return true;
	}

	private static boolean containsIgnoreCase(String haystack, String needle)
	{
		if (haystack == null) return false;
		return haystack.toLowerCase().contains(needle.toLowerCase());
	}

	// -------------------------------------------------------------------------
	// Expectation application — runs on UI thread
	// -------------------------------------------------------------------------

	private static void applyExpectation(Shell shell, DialogExpectation e)
	{
		// 1. Fill the first editable Text widget (if requested)
		if (e.inputText != null)
		{
			Text textWidget = findFirstText(shell);
			if (textWidget != null && !textWidget.isDisposed())
			{
				textWidget.setText(e.inputText);
				// fire ModifyEvent so validators update
				textWidget.notifyListeners(SWT.Modify, new Event());
			}
		}

		// 2. Select item in the first Combo (if requested)
		if (e.comboSelectionContains != null)
		{
			Combo combo = findFirstCombo(shell);
			if (combo != null && !combo.isDisposed())
			{
				String[] items = combo.getItems();
				for (int i = 0; i < items.length; i++)
				{
					if (containsIgnoreCase(items[i], e.comboSelectionContains))
					{
						combo.select(i);
						combo.notifyListeners(SWT.Selection, new Event());
						break;
					}
				}
			}
		}

		// 3. Set the first SWT.CHECK button (if requested)
		if (e.checkboxState != null)
		{
			Button checkbox = findFirstCheckbox(shell);
			if (checkbox != null && !checkbox.isDisposed())
			{
				checkbox.setSelection(e.checkboxState.booleanValue());
				checkbox.notifyListeners(SWT.Selection, new Event());
			}
		}

		// 4. Click the named button, or just close the shell
		if (e.buttonTextContains != null)
		{
			Button btn = findButtonByText(shell, e.buttonTextContains);
			if (btn != null && !btn.isDisposed() && btn.isEnabled())
			{
				btn.notifyListeners(SWT.Selection, new Event());
			}
			else
			{
				// Named button not found / disabled — fall back to closing
				shell.close();
			}
		}
		else
		{
			shell.close();
		}
	}

	// -------------------------------------------------------------------------
	// Widget traversal helpers — all run on UI thread
	// -------------------------------------------------------------------------

	/**
	 * Collects all visible, non-empty Label texts from the shell's widget tree,
	 * joining them with " | " as a diagnostic message string.
	 */
	static String collectMessage(Shell shell)
	{
		List<String> texts = new ArrayList<>();
		collectLabels(shell, texts);
		return String.join(" | ", texts);
	}

	private static void collectLabels(Composite composite, List<String> out)
	{
		for (Control child : composite.getChildren())
		{
			if (child instanceof Label)
			{
				String t = ((Label)child).getText();
				if (t != null && !t.isBlank() && t.length() > 1)
				{
					out.add(t.trim());
				}
			}
			else if (child instanceof Composite)
			{
				collectLabels((Composite)child, out);
			}
		}
	}

	private static Text findFirstText(Composite composite)
	{
		for (Control child : composite.getChildren())
		{
			if (child instanceof Text)
			{
				Text t = (Text)child;
				// Skip read-only text widgets
				if ((t.getStyle() & SWT.READ_ONLY) == 0) return t;
			}
			else if (child instanceof Composite)
			{
				Text found = findFirstText((Composite)child);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static Combo findFirstCombo(Composite composite)
	{
		for (Control child : composite.getChildren())
		{
			if (child instanceof Combo) return (Combo)child;
			if (child instanceof Composite)
			{
				Combo found = findFirstCombo((Composite)child);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static Button findFirstCheckbox(Composite composite)
	{
		for (Control child : composite.getChildren())
		{
			if (child instanceof Button && (child.getStyle() & SWT.CHECK) != 0)
			{
				return (Button)child;
			}
			if (child instanceof Composite)
			{
				Button found = findFirstCheckbox((Composite)child);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static Button findButtonByText(Composite composite, String text)
	{
		for (Control child : composite.getChildren())
		{
			if (child instanceof Button)
			{
				Button btn = (Button)child;
				if ((btn.getStyle() & SWT.PUSH) != 0 && containsIgnoreCase(btn.getText(), text))
				{
					return btn;
				}
			}
			if (child instanceof Composite)
			{
				Button found = findButtonByText((Composite)child, text);
				if (found != null) return found;
			}
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Failure record
	// -------------------------------------------------------------------------

	/**
	 * Records an unexpected dialog that appeared during a test.
	 * Carries the title, collected message text, and the UI-thread stack trace
	 * at the moment the shell became visible — so the callsite in production code
	 * that opened the dialog is easily identified.
	 */
	public static class UnexpectedDialogFailure
	{
		public final String title;
		public final String message;
		public final String fullInfoIncludingStackTrace;

		UnexpectedDialogFailure(String title, String message, StackTraceElement[] uiThreadStack)
		{
			this.title = title;
			this.message = message;
			this.fullInfoIncludingStackTrace = describe(uiThreadStack);
		}

		/** Formats a human-readable description for use in assertion failure messages. */
		private String describe(StackTraceElement[] uiThreadStack)
		{
			StringBuilder sb = new StringBuilder();
			addTitleAndMessageToDescription(sb);
			sb.append("  Opened from (UI thread):\n");
			for (StackTraceElement frame : uiThreadStack)
			{
				sb.append("    at ").append(frame).append("\n");
			}
			return sb.toString();
		}

		public void addTitleAndMessageToDescription(StringBuilder sb) {
			sb.append("Unexpected dialog (use TestDialogInterceptor.expect(...) if you expect one):\n");
			sb.append("  Title  : ").append(title).append("\n");
			sb.append("  Message: ").append(message).append("\n");
		}
	}
}
