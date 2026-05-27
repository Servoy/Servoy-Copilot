package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
}
