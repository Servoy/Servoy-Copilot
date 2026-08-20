/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Display;

import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Shared base class for JSUnit runner integration tests (Layer 3 and Layer 4).
 * <p>
 * Provides common constants and utility methods:
 * <ul>
 *   <li>App-server wait / skip logic via {@link #waitForAppServer()}.</li>
 *   <li>SWT event-pump wrapper via {@link #runOnBackgroundThread(ThrowingSupplier)}.</li>
 *   <li>Workspace file helper via {@link #writeProjectFile}.</li>
 *   <li>Markdown-table count extractors via {@link #extractCount}.</li>
 * </ul>
 */
public abstract class ServoyRunnerTestBase
{
	/** Timeout in seconds for each JSUnit SmartClient run. */
	protected static final int TIMEOUT_SECONDS = 20;

	/**
	 * How long (ms) to poll for the Servoy ApplicationServer singleton on the first check.
	 * 5 seconds gives enough headroom for an in-progress startup without hanging too long.
	 */
	protected static final long APP_SERVER_POLL_MS = 5_000;

	/** How long (ms) to wait after calling setActiveProject() for activation to settle. */
	protected static final long ACTIVATE_SETTLE_MS = 10_000;

	// -----------------------------------------------------------------------
	// App-server guard
	// -----------------------------------------------------------------------

	/**
	 * Cached result of the one-time app-server availability check.
	 * {@code null} = not yet checked; {@code true/false} = result of the check.
	 * Using a single class-level cache means the 60-second poll runs at most once
	 * per JVM session regardless of how many test methods call waitForAppServer().
	 */
	private static Boolean appServerAvailableCache = null;

	/**
	 * Checks whether the Servoy ApplicationServer is available, caching the result.
	 * <p>
	 * On the first call per JVM session: polls {@link ApplicationServerRegistry#exists()}
	 * for up to {@link #APP_SERVER_POLL_MS} (5 s). All subsequent calls return the cached
	 * result immediately, so only one wait ever occurs regardless of how many test methods
	 * call this guard.
	 * <p>
	 * Calls {@code assumeTrue} (skipping the calling test) if the server is not available.
	 */
	protected void waitForAppServer() throws InterruptedException
	{
		if (appServerAvailableCache == null)
		{
			long deadline = System.currentTimeMillis() + APP_SERVER_POLL_MS;
			while (!ApplicationServerRegistry.exists() && System.currentTimeMillis() < deadline)
			{
				Thread.sleep(500);
			}
			appServerAvailableCache = ApplicationServerRegistry.exists();
		}
		assumeTrue(
			"Servoy application server not started (ApplicationServerRegistry.exists() == false) - skipping",
			appServerAvailableCache);
	}

	// -----------------------------------------------------------------------
	// SWT event-pump wrapper
	// -----------------------------------------------------------------------

	/** Functional interface for a callable that throws Exception. */
	@FunctionalInterface
	protected interface ThrowingSupplier
	{
		String get() throws Exception;
	}

	/**
	 * Runs the supplied callable on a dedicated background thread and waits for it to
	 * finish while pumping the SWT event queue.
	 * <p>
	 * PDE JUnit tests execute <em>on the SWT UI thread</em>.  Calling {@code t.join()}
	 * would block {@code readAndDispatch()}, deadlocking every {@code Display.syncExec()}
	 * call made by {@code JSUnitRunnerService}.  This method pumps SWT events in a loop
	 * so those runnables can execute.
	 */
	protected String runOnBackgroundThread(ThrowingSupplier supplier) throws Exception
	{
		String[] result = new String[1];
		Exception[] error = new Exception[1];

		Thread t = new Thread(() -> {
			try
			{
				result[0] = supplier.get();
			}
			catch (Exception e)
			{
				error[0] = e;
			}
		}, "jsunit-test-runner");
		t.setDaemon(true); // don't block JVM shutdown if test thread moves on
		t.start();

		Display display = Display.getDefault();
		long timeoutMs = (TIMEOUT_SECONDS + 30) * 1000L;
		long deadline = System.currentTimeMillis() + timeoutMs;

		boolean onUIThread = display != null && !display.isDisposed()
			&& display.getThread() == Thread.currentThread();

		if (onUIThread)
		{
			// Pump SWT events so syncExec calls from the background thread are dispatched.
			while (t.isAlive() && System.currentTimeMillis() < deadline)
			{
				if (display.readAndDispatch())
				{
					continue;
				}
				Thread.sleep(10);
			}
		}
		else
		{
			// Not on the UI thread - just wait. Eclipse's main event loop will
			// dispatch any syncExec calls made by the background thread.
			t.join(timeoutMs);
		}

		if (error[0] != null) throw error[0];
		assertNotNull("Background thread did not complete within timeout", result[0]);
		return result[0];
	}

	// -----------------------------------------------------------------------
	// Workspace file helper
	// -----------------------------------------------------------------------

	/**
	 * Creates a file inside the given project; no-op if the file already exists.
	 * Content is written in UTF-8 encoding.
	 */
	protected void writeProjectFile(IProject project, String fileName, String content,
		IProgressMonitor monitor) throws CoreException
	{
		IFile file = project.getFile(fileName);
		if (!file.exists())
			file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, monitor);
	}

	// -----------------------------------------------------------------------
	// Markdown-table count extractors
	// -----------------------------------------------------------------------

	/**
	 * Pattern that matches {@code | **N**} cells in the summary row produced by
	 * {@code JSUnitRunnerService.formatResults()}.
	 */
	private static final Pattern COUNT_PATTERN = Pattern.compile("\\| \\*\\*(\\d+)\\*\\*");

	/**
	 * Extracts the numeric value from the {@code columnIndex}-th count cell in the
	 * {@code formatResults()} markdown summary row.
	 * <p>
	 * Column indices: 0 = Passed, 1 = Failed, 2 = Errors, 3 = Ignored.
	 *
	 * @return the extracted count, or {@code -1} if parsing fails
	 */
	protected int extractCount(String result, int columnIndex)
	{
		Matcher m = COUNT_PATTERN.matcher(result);
		for (int i = 0; i <= columnIndex; i++)
		{
			if (!m.find()) return -1;
			if (i == columnIndex)
			{
				try
				{
					return Integer.parseInt(m.group(1));
				}
				catch (NumberFormatException e)
				{
					return -1;
				}
			}
		}
		return -1;
	}

	/** Extracts the passed-test count from the {@code formatResults()} output. */
	protected int extractPassedCount(String result)
	{
		return extractCount(result, 0);
	}

	/** Extracts the failed-test count from the {@code formatResults()} output. */
	protected int extractFailedCount(String result)
	{
		return extractCount(result, 1);
	}

	/** Extracts the error count from the {@code formatResults()} output. */
	protected int extractErrorCount(String result)
	{
		return extractCount(result, 2);
	}
}
