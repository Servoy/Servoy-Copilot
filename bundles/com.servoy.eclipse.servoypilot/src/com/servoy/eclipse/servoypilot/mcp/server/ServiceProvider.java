package com.servoy.eclipse.servoypilot.mcp.server;

import java.util.HashSet;
import java.util.Set;

import org.apache.tomcat.starter.IServicesProvider;

public class ServiceProvider implements IServicesProvider
{
	@Override
	public Set<Class< ? >> getAnnotatedClasses(String context)
	{
		if ("".equals(context))
		{
			HashSet<Class< ? >> set = new HashSet<Class< ? >>();
			set.add(McpServlet.class);
			return set;
		}
		return null;
	}
}
