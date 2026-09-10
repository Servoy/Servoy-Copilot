package com.servoy.eclipse.developer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpToolLog
{
	public static final Logger log = LoggerFactory.getLogger("mcp.tools");

	public static void logCall(String toolName, long elapsedMs)
	{
		log.info("{} completed in {}ms", toolName, elapsedMs);
	}

	public static void logError(String toolName, long elapsedMs, Throwable error)
	{
		ResourceNotFoundException notFound = findNotFound(error);
		if (notFound != null)
		{
			// Expected outcome (agents probe for files that may not exist) - no stack trace.
			log.debug("{} not found after {}ms: {}", toolName, elapsedMs, notFound.getMessage());
		}
		else
		{
			log.error("{} FAILED after {}ms", toolName, elapsedMs, error);
		}
	}

	/**
	 * Walks the cause chain of {@code error} and returns the first
	 * {@link ResourceNotFoundException} found, or {@code null} if the failure is a
	 * genuine (unexpected) error. Package-visible for unit testing.
	 */
	static ResourceNotFoundException findNotFound(Throwable error)
	{
		Throwable cause = error;
		while (cause != null)
		{
			if (cause instanceof ResourceNotFoundException tnf) return tnf;
			if (cause.getCause() == cause) break;
			cause = cause.getCause();
		}
		return null;
	}
}
