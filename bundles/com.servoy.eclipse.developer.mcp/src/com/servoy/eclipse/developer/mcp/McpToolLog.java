package com.servoy.eclipse.developer.mcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class McpToolLog
{
	public static final Logger log = LogManager.getLogger("mcp.tools");

	public static void logCall(String toolName, long elapsedMs)
	{
		log.info("{} completed in {}ms", toolName, elapsedMs);
	}

	public static void logError(String toolName, long elapsedMs, Throwable error)
	{
		log.error("{} FAILED after {}ms", toolName, elapsedMs, error);
	}
}
