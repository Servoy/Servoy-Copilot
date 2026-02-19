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
package com.servoy.eclipse.servoypilot.util;

/**
 * Debug utility for console logging controlled by -Dconsole.debug=true VM argument.
 * 
 * Usage:
 *   DebugUtils.log("Component", "Message with details: " + value);
 *   DebugUtils.logMethodEntry("ClassName", "methodName", param1, param2);
 *   DebugUtils.logMethodExit("ClassName", "methodName", returnValue);
 */
public class DebugUtils
{
	private static final boolean DEBUG_ENABLED = Boolean.getBoolean("console.debug");
	
	private static final String PREFIX = "[ServoyPilot-DEBUG]";
	
	/**
	 * Check if debug mode is enabled.
	 * 
	 * @return true if -Dconsole.debug=true is set
	 */
	public static boolean isDebugEnabled()
	{
		return DEBUG_ENABLED;
	}
	
	/**
	 * Log a simple debug message to System.out.
	 * Always logs regardless of debug mode (use for important events).
	 * 
	 * @param message Debug message
	 */
	public static void debug(String message)
	{
		System.out.println(PREFIX + " " + message);
	}
	
	/**
	 * Log a debug message to System.out if debug mode is enabled.
	 * 
	 * @param component Component/class name
	 * @param message Debug message
	 */
	public static void log(String component, String message)
	{
		if (DEBUG_ENABLED)
		{
			System.out.println(PREFIX + " [" + component + "] " + message);
		}
	}
	
	/**
	 * Log method entry with parameters.
	 * 
	 * @param className Class name
	 * @param methodName Method name
	 * @param params Method parameters
	 */
	public static void logMethodEntry(String className, String methodName, Object... params)
	{
		if (DEBUG_ENABLED)
		{
			StringBuilder sb = new StringBuilder();
			sb.append(PREFIX).append(" [").append(className).append(".").append(methodName).append("] ENTRY");
			if (params != null && params.length > 0)
			{
				sb.append(" - Params: ");
				for (int i = 0; i < params.length; i++)
				{
					if (i > 0)
					{
						sb.append(", ");
					}
					sb.append(params[i]);
				}
			}
			System.out.println(sb.toString());
		}
	}
	
	/**
	 * Log method exit with return value.
	 * 
	 * @param className Class name
	 * @param methodName Method name
	 * @param returnValue Return value
	 */
	public static void logMethodExit(String className, String methodName, Object returnValue)
	{
		if (DEBUG_ENABLED)
		{
			System.out.println(PREFIX + " [" + className + "." + methodName + "] EXIT - Return: " + returnValue);
		}
	}
	
	/**
	 * Log an exception.
	 * 
	 * @param component Component/class name
	 * @param message Error message
	 * @param exception Exception object
	 */
	public static void logException(String component, String message, Throwable exception)
	{
		if (DEBUG_ENABLED)
		{
			System.out.println(PREFIX + " [" + component + "] ERROR: " + message);
			if (exception != null)
			{
				exception.printStackTrace(System.out);
			}
		}
	}
	
	/**
	 * Log a separator line for readability.
	 */
	public static void logSeparator()
	{
		if (DEBUG_ENABLED)
		{
			System.out.println(PREFIX + " ========================================");
		}
	}
}
