package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class RuntimeErrorCapture implements AutoCloseable
{
	private final CopyOnWriteArrayList<String> capturedErrors = new CopyOnWriteArrayList<>();
	private final CapturingHandler handler;
	private final Logger rootLogger;
	private volatile boolean closed = false;

	public RuntimeErrorCapture()
	{
		handler = new CapturingHandler();
		rootLogger = Logger.getLogger("");
		rootLogger.addHandler(handler);
	}

	public List<String> getCapturedErrors()
	{
		return Collections.unmodifiableList(new ArrayList<>(capturedErrors));
	}

	public String formatCapturedErrors()
	{
		if (capturedErrors.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (String error : capturedErrors)
		{
			sb.append("- ").append(error).append("\n");
		}
		return sb.toString().trim();
	}

	@Override
	public void close()
	{
		if (closed) return;
		closed = true;
		rootLogger.removeHandler(handler);
	}

	private class CapturingHandler extends Handler
	{
		CapturingHandler()
		{
			setLevel(Level.SEVERE);
		}

		@Override
		public void publish(LogRecord record)
		{
			if (closed) return;
			if (record.getLevel().intValue() < Level.SEVERE.intValue()) return;

			String loggerName = record.getLoggerName();
			if (loggerName != null && (loggerName.startsWith("org.sablo") || loggerName.startsWith("com.servoy")))
			{
				String message = record.getMessage();
				Throwable thrown = record.getThrown();
				if (thrown != null)
				{
					message = message + " \u2014 " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
				}
				capturedErrors.add(message);
			}
		}

		@Override
		public void flush()
		{
		}

		@Override
		public void close()
		{
		}
	}
}
