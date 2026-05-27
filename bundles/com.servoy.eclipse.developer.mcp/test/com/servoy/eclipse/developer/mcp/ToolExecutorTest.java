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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.junit.Test;

import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;

/**
 * JUnit 4 tests for {@link ToolExecutor}.
 */
public class ToolExecutorTest
{
	@McpServer(name = "test-server")
	public static class FakeServer
	{
		@Tool(name = "greet", description = "Greets someone", type = "object")
		public String greet(
			@ToolParam(name = "name", description = "Person name", required = true) String name,
			@ToolParam(name = "greeting", description = "Greeting prefix") String greeting)
		{
			String prefix = Optional.ofNullable(greeting).orElse("Hello");
			return prefix + ", " + name + "!";
		}

		@Tool(name = "noArgs", description = "No arguments tool", type = "object")
		public String noArgs()
		{
			return "done";
		}

		@Tool(name = "throwingTool", description = "Always throws", type = "object")
		public String throwingTool()
		{
			throw new IllegalStateException("intentional error");
		}

		public String notATool()
		{
			return "ignored";
		}
	}

	private final ToolExecutor executor = new ToolExecutor(new FakeServer());

	@Test
	public void testGetFunctions_returnsOnlyAnnotatedMethods()
	{
		Method[] functions = executor.getFunctions();
		assertEquals(3, functions.length);
	}

	@Test
	public void testGetFunctionCallbackByName_found()
	{
		Optional<Method> method = executor.getFunctionCallbackByName("greet");
		assertTrue(method.isPresent());
		assertEquals("greet", method.get().getName());
	}

	@Test
	public void testGetFunctionCallbackByName_notFound()
	{
		Optional<Method> method = executor.getFunctionCallbackByName("nonExistent");
		assertTrue(method.isEmpty());
	}

	@Test
	public void testGetFunctionCallbackByName_notATool()
	{
		Optional<Method> method = executor.getFunctionCallbackByName("notATool");
		assertTrue(method.isEmpty());
	}

	@Test
	public void testCall_withArgs() throws Exception
	{
		Map<String, Object> args = new HashMap<>();
		args.put("name", "World");
		args.put("greeting", "Hi");
		Object result = executor.call("greet", args).get();
		assertEquals("Hi, World!", result);
	}

	@Test
	public void testCall_withNullOptionalArg() throws Exception
	{
		Map<String, Object> args = new HashMap<>();
		args.put("name", "World");
		args.put("greeting", null);
		Object result = executor.call("greet", args).get();
		assertEquals("Hello, World!", result);
	}

	@Test
	public void testCall_noArgs() throws Exception
	{
		Object result = executor.call("noArgs", new HashMap<>()).get();
		assertEquals("done", result);
	}

	@Test
	public void testCall_unknownTool()
	{
		try
		{
			executor.call("unknown", new HashMap<>());
			fail("Should throw for unknown tool");
		}
		catch (RuntimeException e)
		{
			assertTrue(e.getMessage().contains("Tool not found"));
		}
	}

	@Test
	public void testCall_throwingTool()
	{
		try
		{
			executor.call("throwingTool", new HashMap<>()).get();
			fail("Should throw");
		}
		catch (ExecutionException e)
		{
			assertNotNull(e.getCause());
		}
		catch (InterruptedException e)
		{
			fail("Unexpected interruption");
		}
	}

	@Test
	public void testMapArguments_correctOrder() throws Exception
	{
		Method method = executor.getFunctionCallbackByName("greet").get();
		Map<String, Object> args = new HashMap<>();
		args.put("name", "Alice");
		args.put("greeting", "Hey");
		Object[] mapped = executor.mapArguments(method, args);
		assertArrayEquals(new Object[]{"Alice", "Hey"}, mapped);
	}

	@Test
	public void testToParamName_withAnnotation() throws Exception
	{
		Method method = executor.getFunctionCallbackByName("greet").get();
		String paramName = ToolExecutor.toParamName(method.getParameters()[0]);
		assertEquals("name", paramName);
	}

	@Test
	public void testToFunctionName_withAnnotation() throws Exception
	{
		Method method = executor.getFunctionCallbackByName("greet").get();
		String funcName = ToolExecutor.toFunctionName(method);
		assertEquals("greet", funcName);
	}

	@Test
	public void testToMap_validKeyValueArray()
	{
		Map<String, Object> map = executor.toMap(new String[]{"key1", "val1", "key2", "val2"});
		assertEquals(2, map.size());
		assertEquals("val1", map.get("key1"));
		assertEquals("val2", map.get("key2"));
	}

	@Test
	public void testToMap_emptyArray()
	{
		Map<String, Object> map = executor.toMap(new String[]{});
		assertTrue(map.isEmpty());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testToMap_oddLengthArray()
	{
		executor.toMap(new String[]{"key1", "val1", "key2"});
	}
}
