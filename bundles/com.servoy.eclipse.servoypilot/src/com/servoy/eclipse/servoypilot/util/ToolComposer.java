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

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.servoy.eclipse.model.util.ServoyLog;

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
 * Supports interface inheritance: @Tool methods declared in base interfaces are
 * discovered via recursive hierarchy walk. Leaf interface methods win over base
 * interface methods when the same signature is declared in both (override semantics).
 *
 * Usage in ServoyAiModel:
 *   builder.tools(ToolComposer.from(IGetFormsTool.class, IOpenFormTool.class, ...));
 */
public class ToolComposer
{

	/**
	 * Builds a Map of ToolSpecification -> ToolExecutor from the given tool interfaces.
	 *
	 * For each interface:
	 * 1. All @Tool methods are collected recursively from the full interface hierarchy
	 *    (leaf methods win over base methods on same signature — override semantics)
	 * 2. An anonymous implementor of the leaf interface is created (uses default method bodies)
	 * 3. ToolSpecification is built via ToolSpecifications.toolSpecificationFrom(method)
	 * 4. DefaultToolExecutor is created using the declaring interface of each method
	 *
	 * @param toolInterfaces one or more tool interface classes with @Tool default methods
	 * @return map ready to pass to AiServices builder.tools(Map)
	 */
	@SafeVarargs
	public static Map<ToolSpecification, ToolExecutor> from(Class< ? >... toolInterfaces)
	{
		Map<ToolSpecification, ToolExecutor> result = new HashMap<>();

		for (Class< ? > iface : toolInterfaces)
		{
			if (iface != null && iface.isInterface())
			{
				Object instance = createAnonymousInstance(iface);
				if (instance != null)
				{
					for (Method method : collectToolMethods(iface))
					{
						try
						{
							ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
							ToolExecutor executor = new DefaultToolExecutor(instance, method);
							result.put(spec, executor);
						}
						catch (Exception e)
						{
							ServoyLog.logError("ToolComposer: failed to register tool method '" +
								method.getName() + "' from interface " + method.getDeclaringClass().getName(), e);
						}
					}
				}
			}
		}

		return result;
	}

	/**
	 * Collects all @Tool-annotated methods from the full interface hierarchy.
	 * Leaf interface methods take priority over base interface methods on the same signature
	 * (name + parameter types), implementing override semantics.
	 *
	 * Uses LinkedHashMap keyed by method signature to preserve declaration order
	 * and enforce leaf-wins collision resolution.
	 */
	private static List<Method> collectToolMethods(Class< ? > iface)
	{
		// LinkedHashMap keyed by "methodName(paramType1,paramType2,...)" — leaf processed first
		LinkedHashMap<String, Method> methodsBySignature = new LinkedHashMap<>();
		collectToolMethodsRecursive(iface, methodsBySignature);
		return new ArrayList<>(methodsBySignature.values());
	}

	/**
	 * Recursively walks the interface hierarchy depth-first (leaf first, then ancestors).
	 * First occurrence of a signature wins — guarantees leaf overrides base.
	 */
	private static void collectToolMethodsRecursive(Class< ? > iface, LinkedHashMap<String, Method> methodsBySignature)
	{
		for (Method method : iface.getDeclaredMethods())
		{
			if (method.isAnnotationPresent(Tool.class))
			{
				String signature = buildSignature(method);
				if (!methodsBySignature.containsKey(signature))
				{
					methodsBySignature.put(signature, method);
				}
			}
		}

		for (Class< ? > parent : iface.getInterfaces())
		{
			collectToolMethodsRecursive(parent, methodsBySignature);
		}
	}

	/**
	 * Builds a method signature string used as the override-resolution key.
	 * Format: "methodName(fully.qualified.ParamType1,fully.qualified.ParamType2)"
	 */
	private static String buildSignature(Method method)
	{
		StringBuilder sig = new StringBuilder(method.getName()).append("(");
		Class< ? >[] params = method.getParameterTypes();
		for (int i = 0; i < params.length; i++)
		{
			if (i > 0) sig.append(",");
			sig.append(params[i].getName());
		}
		sig.append(")");
		return sig.toString();
	}

	/**
	 * Creates an anonymous instance of the given leaf interface.
	 * The proxy implements the full leaf interface (including all inherited defaults).
	 *
	 * Uses MethodHandles.privateLookupIn() (Java 9+) to correctly invoke
	 * interface default methods from outside the interface's own module.
	 * The declaring interface of each method is used for privateLookupIn —
	 * critical for correctly dispatching default methods from base interfaces.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T createAnonymousInstance(Class<T> iface)
	{
		try
		{
			return (T)Proxy.newProxyInstance(
				iface.getClassLoader(),
				new Class< ? >[] { iface },
				(proxy, method, args) -> {
					if (method.isDefault())
					{
						Class< ? > declaringIface = method.getDeclaringClass();
						return MethodHandles.privateLookupIn(declaringIface, MethodHandles.lookup())
							.unreflectSpecial(method, declaringIface)
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
			ServoyLog.logError("ToolComposer: failed to create instance for interface " + iface.getName(), e);
			return null;
		}
	}
}
