package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

public final class RuntimeErrorCapture implements AutoCloseable
{
	private static final String APPENDER_NAME = "ServoyMCP_RuntimeErrorCapture";

	private final CopyOnWriteArrayList<String> capturedErrors = new CopyOnWriteArrayList<>();
	private final CapturingAppender appender;
	private final LoggerContext context;
	private volatile boolean closed = false;

	public RuntimeErrorCapture()
	{
		context = (LoggerContext)LogManager.getContext(false);
		Configuration config = context.getConfiguration();

		appender = new CapturingAppender(APPENDER_NAME + "_" + System.nanoTime());
		appender.start();

		LoggerConfig rootLoggerConfig = config.getRootLogger();
		rootLoggerConfig.addAppender(appender, Level.ERROR, null);
		context.updateLoggers();
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

		Configuration config = context.getConfiguration();
		LoggerConfig rootLoggerConfig = config.getRootLogger();
		rootLoggerConfig.removeAppender(appender.getName());
		context.updateLoggers();
		appender.stop();
	}

	private class CapturingAppender extends AbstractAppender
	{
		protected CapturingAppender(String name)
		{
			super(name, null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event)
		{
			if (closed) return;

			String loggerName = event.getLoggerName();
			if (loggerName != null && (loggerName.startsWith("org.sablo") || loggerName.startsWith("com.servoy")))
			{
				String message = event.getMessage().getFormattedMessage();
				Throwable thrown = event.getThrown();
				if (thrown != null)
				{
					message = message + " — " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
				}
				capturedErrors.add(message);
			}
		}
	}
}
