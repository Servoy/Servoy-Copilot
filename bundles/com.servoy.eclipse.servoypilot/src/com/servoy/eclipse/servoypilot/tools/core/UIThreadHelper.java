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
package com.servoy.eclipse.servoypilot.tools.core;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Helper utility for executing operations on the Eclipse UI thread.
 * Provides clean abstraction over Display.syncExec and Display.asyncExec patterns.
 */
public class UIThreadHelper
{
	/**
	 * Functional interface for operations that need to run on the UI thread and return a result.
	 * 
	 * @param <T> Return type of the operation
	 */
	@FunctionalInterface
	public interface UIOperation<T>
	{
		/**
		 * Executes the operation.
		 * 
		 * @return Result of the operation
		 * @throws Exception If operation fails
		 */
		T execute() throws Exception;
	}

	/**
	 * Functional interface for operations that need to run on the UI thread without returning a result.
	 */
	@FunctionalInterface
	public interface UITask
	{
		/**
		 * Executes the task.
		 * 
		 * @throws Exception If task fails
		 */
		void execute() throws Exception;
	}

	/**
	 * Executes an operation synchronously on the UI thread.
	 * Blocks until the operation completes and returns the result.
	 * Handles exceptions and logs errors automatically.
	 * 
	 * @param <T> Return type of the operation
	 * @param operationName Name of the operation (for logging)
	 * @param operation The operation to execute
	 * @return Result of the operation, or error message if operation fails
	 */
	public static <T> T syncExec(String operationName, UIOperation<T> operation)
	{
		final Object[] result = new Object[1];
		final Exception[] exception = new Exception[1];

		Display.getDefault().syncExec(() -> {
			try
			{
				result[0] = operation.execute();
			}
			catch (Exception e)
			{
				exception[0] = e;
			}
		});

		if (exception[0] != null)
		{
			ServoyLog.logError("Error in " + operationName, exception[0]);
			@SuppressWarnings("unchecked")
			T errorResult = (T)("Error: " + exception[0].getMessage());
			return errorResult;
		}

		@SuppressWarnings("unchecked")
		T typedResult = (T)result[0];
		return typedResult;
	}

	/**
	 * Executes a task asynchronously on the UI thread.
	 * Does not block - schedules the task and returns immediately.
	 * Use this for UI updates that don't need to complete before continuing (e.g., opening editors).
	 * 
	 * @param operationName Name of the operation (for logging)
	 * @param task The task to execute
	 */
	public static void asyncExec(String operationName, UITask task)
	{
		Display.getDefault().asyncExec(() -> {
			try
			{
				task.execute();
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error in async " + operationName, e);
			}
		});
	}
}
