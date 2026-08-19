package com.servoy.eclipse.developer.mcp;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.resources.ResourcesPlugin;

public class McpToolLog
{
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	private static final Path LOG_FILE = ResourcesPlugin.getWorkspace().getRoot().getLocation()
		.append(".metadata").append("mcp-tools.log").toFile().toPath();

	public static void logCall(String toolName, long elapsedMs)
	{
		write(toolName + " completed in " + elapsedMs + "ms");
	}

	public static void logError(String toolName, long elapsedMs, Throwable error)
	{
		write(toolName + " FAILED after " + elapsedMs + "ms: " + error.getMessage());
	}

	private static void write(String message)
	{
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(LOG_FILE,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND)))
		{
			pw.println("[" + FORMATTER.format(LocalDateTime.now()) + "] " + message);
		}
		catch (IOException e)
		{
			// best effort — don't break tool execution for a log failure
		}
	}
}
