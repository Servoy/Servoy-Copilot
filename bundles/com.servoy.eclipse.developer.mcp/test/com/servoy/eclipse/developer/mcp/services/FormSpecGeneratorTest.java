package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

public class FormSpecGeneratorTest
{
	@Test
	public void testFormSpecGenerator_isCreatable()
	{
		assertNotNull("FormSpecGenerator must have @Creatable annotation",
			FormSpecGenerator.class.getAnnotation(
				org.eclipse.e4.core.di.annotations.Creatable.class));
	}

	@Test
	public void testFormSpecGenerator_hasGenerateSpecMethod() throws NoSuchMethodException
	{
		assertNotNull("FormSpecGenerator must have generateSpec(String) method",
			FormSpecGenerator.class.getMethod("generateSpec", String.class));
	}

	@Test
	public void testFormSpecGenerator_hasSpecExistsMethod() throws NoSuchMethodException
	{
		assertNotNull("FormSpecGenerator must have specExists(String) method",
			FormSpecGenerator.class.getMethod("specExists", String.class));
	}

	@Test
	public void testFormSpecGenerator_generateSpecReturnType() throws NoSuchMethodException
	{
		assertEquals("generateSpec must return String", String.class,
			FormSpecGenerator.class.getMethod("generateSpec", String.class).getReturnType());
	}

	@Test
	public void testFormSpecGenerator_specExistsReturnType() throws NoSuchMethodException
	{
		assertEquals("specExists must return boolean", boolean.class,
			FormSpecGenerator.class.getMethod("specExists", String.class).getReturnType());
	}

	@Test
	public void testFormSpecGenerator_hasNoArgConstructor() throws NoSuchMethodException
	{
		assertNotNull("FormSpecGenerator must have a no-arg constructor",
			FormSpecGenerator.class.getConstructor());
	}

	@Test
	public void testFormSpecGenerator_canBeInstantiated()
	{
		FormSpecGenerator gen = new FormSpecGenerator();
		assertNotNull("FormSpecGenerator must be instantiable", gen);
	}

	@Test
	public void testFormSpecGenerator_hasParseFrmFileMethod()
	{
		Method[] methods = FormSpecGenerator.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("parseFrmFile".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecGenerator must have a parseFrmFile method", found);
	}

	@Test
	public void testFormSpecGenerator_hasGenerateSpecContentMethod()
	{
		Method[] methods = FormSpecGenerator.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("generateSpecContent".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecGenerator must have a generateSpecContent method", found);
	}

	@Test
	public void testFormSpecGenerator_hasGenerateSetupContentMethod()
	{
		Method[] methods = FormSpecGenerator.class.getDeclaredMethods();
		boolean found = false;
		for (Method m : methods)
		{
			if ("generateSetupContent".equals(m.getName()))
			{
				found = true;
				break;
			}
		}
		assertTrue("FormSpecGenerator must have a generateSetupContent method", found);
	}

	@Test
	public void testFormSpecGenerator_parseFrmFile_extractsDataSource() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);

		String frmContent = "{\n\"dataSource\":\"db:/servoy_test/people\",\n\"items\":[\n{\n\"name\":\"field1\",\n\"typeName\":\"bootstrapcomponents-textbox\",\n\"typeid\":47\n}\n],\n\"name\":\"testForm\",\n\"typeid\":3\n}";

		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "testForm");
		assertNotNull("parseFrmFile must return non-null metadata", metadata);

		java.lang.reflect.Field dsField = metadata.getClass().getDeclaredField("dataSource");
		dsField.setAccessible(true);
		assertEquals("db:/servoy_test/people", dsField.get(metadata));
	}

	@Test
	public void testFormSpecGenerator_parseFrmFile_extractsElementNames() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);

		String frmContent = "{\n\"dataSource\":\"db:/test/t1\",\n\"items\":[\n" +
			"{\n\"name\":\"btn_save\",\n\"onActionMethodID\":\"abc\",\n\"typeid\":7\n},\n" +
			"{\n\"name\":\"name_field\",\n\"typeName\":\"bootstrapcomponents-textbox\",\n\"typeid\":47,\n\"json\":{\"dataProviderID\":\"name\"}\n}\n" +
			"],\n\"name\":\"myForm\",\n\"typeid\":3\n}";

		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");

		java.lang.reflect.Field elementsField = metadata.getClass().getDeclaredField("namedElements");
		elementsField.setAccessible(true);
		List<?> elements = (List<?>)elementsField.get(metadata);

		assertTrue("parseFrmFile must extract at least 2 elements", elements.size() >= 2);
	}

	@Test
	public void testFormSpecGenerator_parseFrmFile_formNameNotInElements() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);

		String frmContent = "{\n\"dataSource\":\"db:/test/t1\",\n\"items\":[\n" +
			"{\n\"name\":\"field1\",\n\"typeid\":47\n}\n" +
			"],\n\"name\":\"myForm\",\n\"typeid\":3\n}";

		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");

		java.lang.reflect.Field elementsField = metadata.getClass().getDeclaredField("namedElements");
		elementsField.setAccessible(true);
		List<?> elements = (List<?>)elementsField.get(metadata);

		for (Object elem : elements)
		{
			java.lang.reflect.Field nameField = elem.getClass().getDeclaredField("name");
			nameField.setAccessible(true);
			String name = (String)nameField.get(elem);
			assertTrue("Form name 'myForm' should not appear as an element", !"myForm".equals(name));
		}
	}

	@Test
	public void testFormSpecGenerator_generateSetupContent_containsSetUp() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSetupContent = FormSpecGenerator.class.getDeclaredMethod("generateSetupContent", parseFrmFile.getReturnType());
		generateSetupContent.setAccessible(true);

		String frmContent = "{\n\"dataSource\":\"db:/test/t1\",\n\"items\":[],\n\"name\":\"testForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "testForm");
		String setup = (String)generateSetupContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated setup must contain spec_setUp function",
			setup.contains("function spec_setUp()"));
	}

	@Test
	public void testFormSpecGenerator_generateSetupContent_containsTearDown() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSetupContent = FormSpecGenerator.class.getDeclaredMethod("generateSetupContent", parseFrmFile.getReturnType());
		generateSetupContent.setAccessible(true);

		String frmContent = "{\n\"dataSource\":\"db:/test/t1\",\n\"items\":[],\n\"name\":\"testForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "testForm");
		String setup = (String)generateSetupContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated setup must contain spec_tearDown function",
			setup.contains("function spec_tearDown()"));
	}

	@Test
	public void testFormSpecGenerator_generateSetupContent_containsDataSource() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSetupContent = FormSpecGenerator.class.getDeclaredMethod("generateSetupContent", parseFrmFile.getReturnType());
		generateSetupContent.setAccessible(true);

		String frmContent = "{\n\"dataSource\":\"db:/servoy_test/people\",\n\"items\":[],\n\"name\":\"testForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "testForm");
		String setup = (String)generateSetupContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated setup must mention the dataSource",
			setup.contains("db:/servoy_test/people"));
	}

	@Test
	public void testFormSpecGenerator_generateSetupContent_containsUuid() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSetupContent = FormSpecGenerator.class.getDeclaredMethod("generateSetupContent", parseFrmFile.getReturnType());
		generateSetupContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"testForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "testForm");
		String setup = (String)generateSetupContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated setup must contain @properties with uuid",
			setup.contains("@properties={typeid:24,uuid:\""));
	}

	@Test
	public void testFormSpecGenerator_generateSetupContent_containsFormName() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSetupContent = FormSpecGenerator.class.getDeclaredMethod("generateSetupContent", parseFrmFile.getReturnType());
		generateSetupContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"myTestForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myTestForm");
		String setup = (String)generateSetupContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated setup must mention the form name",
			setup.contains("myTestForm"));
	}

	@Test
	public void testFormSpecGenerator_generateSetupContent_noDataSource_hasComment() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSetupContent = FormSpecGenerator.class.getDeclaredMethod("generateSetupContent", parseFrmFile.getReturnType());
		generateSetupContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"testForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "testForm");
		String setup = (String)generateSetupContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated setup without dataSource must have appropriate comment",
			setup.contains("No dataSource"));
	}
}
