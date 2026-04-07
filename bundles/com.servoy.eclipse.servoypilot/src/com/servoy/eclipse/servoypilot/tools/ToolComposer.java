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
package com.servoy.eclipse.servoypilot.tools;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.ILog;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * Builds a LangChain4j tool registration map directly from tool interfaces.
 *
 * This solves the problem where LangChain4j's ToolService uses getDeclaredMethods()
 * on the registered object, which only sees methods physically declared in the class
 * bytecode — not interface default methods. By calling getDeclaredMethods() on the
 * interface itself and creating anonymous implementors, we bypass this limitation
 * while keeping @Tool/@P annotations as a single source of truth on the interfaces.
 *
 * Usage in ServoyAiModel:
 *   builder.tools(ToolComposer.from(IGetFormsTool.class, IOpenFormTool.class, ...));
 */
public class ToolComposer
{
	private static final ILog logger = ILog.of(ToolComposer.class);

	/**
	 * Builds a Map of ToolSpecification -> ToolExecutor from the given tool interfaces.
	 *
	 * For each interface:
	 * 1. getDeclaredMethods() is called on the interface itself (not any implementor)
	 * 2. Methods annotated with @Tool are collected
	 * 3. An anonymous implementor of the interface is created (uses default method bodies)
	 * 4. ToolSpecification is built via ToolSpecifications.toolSpecificationFrom(method)
	 * 5. DefaultToolExecutor is created to invoke the default method on the anonymous instance
	 *
	 * @param toolInterfaces one or more tool interface classes with @Tool default methods
	 * @return map ready to pass to AiServices builder.tools(Map)
	 */
	@SafeVarargs
	public static Map<ToolSpecification, ToolExecutor> from(Class<?>... toolInterfaces)
	{
		Map<ToolSpecification, ToolExecutor> result = new HashMap<>();

		for (Class<?> iface : toolInterfaces)
		{
			if (iface != null && iface.isInterface())
			{
				Object instance = createAnonymousInstance(iface);
				if (instance != null)
				{
					for (Method method : iface.getDeclaredMethods())
					{
						if (method.isAnnotationPresent(Tool.class))
						{
							try
							{
								ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
								ToolExecutor executor = new DefaultToolExecutor(instance, method);
								result.put(spec, executor);
							}
							catch (Exception e)
							{
								logger.error("ToolComposer: failed to register tool method '" +
									method.getName() + "' from interface " + iface.getName(), e);
							}
						}
					}
				}
			}
		}

		return result;
	}

	/**
	 * Creates an anonymous instance of the given interface.
	 * Since all methods are default, the anonymous class body is empty —
	 * all calls fall through to the default implementations on the interface.
	 *
	 * Uses MethodHandles.privateLookupIn() (Java 9+) to correctly invoke
	 * interface default methods from outside the interface's own module.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T createAnonymousInstance(Class<T> iface)
	{
		try
		{
			return (T) Proxy.newProxyInstance(
				iface.getClassLoader(),
				new Class<?>[] { iface },
				(proxy, method, args) -> {
					if (method.isDefault())
					{
						return MethodHandles.privateLookupIn(iface, MethodHandles.lookup())
							.unreflectSpecial(method, iface)
							.bindTo(proxy)
							.invokeWithArguments(args);
					}
					// toString/equals/hashCode fallback
					return switch (method.getName())
					{
						case "toString" -> iface.getSimpleName() + "Proxy";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						default -> null;
					};
				});
		}
		catch (Exception e)
		{
			logger.error("ToolComposer: failed to create instance for interface " + iface.getName(), e);
			return null;
		}
	}
}
