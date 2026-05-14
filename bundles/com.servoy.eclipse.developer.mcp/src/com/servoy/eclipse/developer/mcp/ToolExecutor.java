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
package com.servoy.eclipse.developer.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

/**
 * Reflection-based dispatcher that maps MCP tool call requests to annotated
 * methods on a server implementation object.
 */
public class ToolExecutor
{
	private final Object serverImpl;

	public ToolExecutor(Object serverImpl)
	{
		this.serverImpl = serverImpl;
	}

	/** Returns all methods on the server object annotated with {@link Tool}. */
	public Method[] getFunctions()
	{
		return Arrays.stream(serverImpl.getClass().getDeclaredMethods())
			.filter(m -> Objects.nonNull(m.getAnnotation(Tool.class)))
			.toArray(Method[]::new);
	}

	/** Invokes the named tool asynchronously with the given argument map. */
	public CompletableFuture<Object> call(String name, Map<String, Object> args)
	{
		Method method = getFunctionCallbackByName(name)
			.orElseThrow(() -> new RuntimeException("Tool not found: " + name));
		Object[] argValues = mapArguments(method, args);
		return CompletableFuture.supplyAsync(() -> invokeMethod(method, argValues));
	}

	private Object invokeMethod(Method method, Object[] args)
	{
		try
		{
			return method.invoke(serverImpl, args);
		}
		catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
		{
			throw new RuntimeException(e);
		}
	}

	public Object[] mapArguments(Method method, Map<String, Object> argMap)
	{
		return Arrays.stream(method.getParameters())
			.map(ToolExecutor::toParamName)
			.map(argMap::get)
			.toArray();
	}

	public Optional<Method> getFunctionCallbackByName(String name)
	{
		return Arrays.stream(getFunctions())
			.filter(m -> toFunctionName(m).equals(name))
			.findFirst();
	}

	public static String toParamName(Parameter parameter)
	{
		return Optional.ofNullable(parameter.getAnnotation(ToolParam.class))
			.map(ToolParam::name)
			.filter(Predicate.not(String::isBlank))
			.orElse(parameter.getName());
	}

	public static String toFunctionName(Method method)
	{
		return Optional.ofNullable(method.getAnnotation(Tool.class))
			.map(Tool::name)
			.filter(Predicate.not(String::isBlank))
			.orElse(method.getName());
	}

	public Map<String, Object> toMap(String[] keyVal)
	{
		if (keyVal.length % 2 != 0)
		{
			throw new IllegalArgumentException("Not a key-value array");
		}
		var map = new HashMap<String, Object>();
		for (int i = 0; i < keyVal.length; i += 2)
		{
			map.put(keyVal[i], keyVal[i + 1]);
		}
		return map;
	}
}
