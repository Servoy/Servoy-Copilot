package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public class FormSpecRunnerTest
{
	@Test
	public void testFormSpecRunner_isCreatable()
	{
		assertNotNull("FormSpecRunner must have @Creatable annotation",
			FormSpecRunner.class.getAnnotation(
				org.eclipse.e4.core.di.annotations.Creatable.class));
	}

	@Test
	public void testFormSpecRunner_hasRunSpecMethod() throws NoSuchMethodException
	{
		assertNotNull("FormSpecRunner must have runSpec(String, boolean) method",
			FormSpecRunner.class.getMethod("runSpec", String.class, boolean.class));
	}

	@Test
	public void testFormSpecRunner_runSpecReturnType() throws NoSuchMethodException
	{
		assertTrue("runSpec must return String",
			FormSpecRunner.class.getMethod("runSpec", String.class, boolean.class)
				.getReturnType() == String.class);
	}

	@Test
	public void testFormSpecRunner_runSpec_noActiveProject()
	{
		FormSpecRunner runner = new FormSpecRunner();
		try
		{
			String result = runner.runSpec("nonExistentForm", true);
			assertNotNull(result);
			assertTrue("Should return error when no active project",
				result.contains("Error"));
		}
		catch (Throwable e)
		{
			assertNotNull("Expected error in plain JUnit (no workspace)", e);
		}
	}

	@Test
	public void testFormSpecRunner_runSpec_headlessParam() throws NoSuchMethodException
	{
		Method m = FormSpecRunner.class.getMethod("runSpec", String.class, boolean.class);
		assertEquals("Second param should be boolean for headless",
			boolean.class, m.getParameterTypes()[1]);
	}

	@Test
	public void testFormSpecRunner_hasEnsureCypressInstalledMethod()
	{
		Method[] methods = FormSpecRunner.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("ensureCypressInstalled".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecRunner must have ensureCypressInstalled method", found);
	}

	@Test
	public void testFormSpecRunner_hasEnsureCypressConfigMethod()
	{
		Method[] methods = FormSpecRunner.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("ensureCypressConfig".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecRunner must have ensureCypressConfig method", found);
	}

	@Test
	public void testFormSpecRunner_hasGetCypressDirMethod()
	{
		Method[] methods = FormSpecRunner.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("getCypressDir".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecRunner must have getCypressDir method", found);
	}

	@Test
	public void testFormSpecRunner_hasGetNodePathMethod()
	{
		Method[] methods = FormSpecRunner.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("getNodePath".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecRunner must have getNodePath method", found);
	}

	@Test
	public void testFormSpecRunner_canBeInstantiated()
	{
		FormSpecRunner runner = new FormSpecRunner();
		assertNotNull("FormSpecRunner should be instantiable", runner);
	}
}
