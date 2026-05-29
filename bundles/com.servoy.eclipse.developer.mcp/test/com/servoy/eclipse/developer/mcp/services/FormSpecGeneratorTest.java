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

	// --- New tests for spec content generation ---

	@Test
	public void testFormSpecGenerator_generateSpecContent_noPropertiesAnnotation() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[{\"name\":\"btn1\",\"typeid\":7,\"onActionMethodID\":\"x\"}],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must NOT contain @properties annotation",
			!spec.contains("@properties"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_containsPlaywrightImport() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must import @playwright/test",
			spec.contains("require('@playwright/test')"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_containsNavigateToFormRetry() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must contain navigateToForm retry helper",
			spec.contains("async function navigateToForm(page)"));
		assertTrue("Generated spec must have retry logic with 3 attempts",
			spec.contains("attempt < 3"));
		assertTrue("Generated spec must have 3s delay between retries",
			spec.contains("waitForTimeout(3000)"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_usesDomContentLoaded() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must use domcontentloaded (not networkidle)",
			spec.contains("domcontentloaded"));
		assertTrue("Generated spec must NOT use networkidle",
			!spec.contains("networkidle"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_usesRelativeUrl() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must use relative URL with formpreview param",
			spec.contains("?formpreview=myForm&svy_testmode=true"));
		assertTrue("Generated spec must NOT contain hardcoded localhost URL",
			!spec.contains("http://localhost"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_containsDataCySelectors() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[" +
			"{\"name\":\"btn_save\",\"onActionMethodID\":\"abc\",\"typeid\":7}," +
			"{\"name\":\"name_field\",\"typeName\":\"bootstrapcomponents-textbox\",\"typeid\":47}" +
			"],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must use data-cy selectors with form prefix",
			spec.contains("[data-cy=\"myForm.btn_save\"]"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_buttonsAreClickable() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[" +
			"{\"name\":\"btn_save\",\"onActionMethodID\":\"abc\",\"typeid\":7}" +
			"],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must have interactions describe block",
			spec.contains("myForm - interactions"));
		assertTrue("Generated spec must check button is enabled",
			spec.contains("toBeEnabled()"));
	}

	@Test
	public void testFormSpecGenerator_generateSpecContent_staticChecksBlock() throws Exception
	{
		Method parseFrmFile = FormSpecGenerator.class.getDeclaredMethod("parseFrmFile", String.class, String.class);
		parseFrmFile.setAccessible(true);
		Method generateSpecContent = FormSpecGenerator.class.getDeclaredMethod("generateSpecContent", parseFrmFile.getReturnType());
		generateSpecContent.setAccessible(true);

		String frmContent = "{\n\"items\":[{\"name\":\"lbl1\",\"typeid\":7}],\n\"name\":\"myForm\",\n\"typeid\":3\n}";
		Object metadata = parseFrmFile.invoke(new FormSpecGenerator(), frmContent, "myForm");
		String spec = (String)generateSpecContent.invoke(new FormSpecGenerator(), metadata);

		assertTrue("Generated spec must have static checks describe block",
			spec.contains("myForm - static checks"));
		assertTrue("Generated spec must check for error overlay",
			spec.contains(".svy-error, .error-overlay"));
	}

	@Test
	public void testFormSpecGenerator_hasGetPwTestsDirMethod() throws NoSuchMethodException
	{
		assertNotNull("FormSpecGenerator must have getPwTestsDir(String) method",
			FormSpecGenerator.class.getMethod("getPwTestsDir", String.class));
	}

	@Test
	public void testFormSpecGenerator_hasGetSpecFilePathMethod() throws NoSuchMethodException
	{
		assertNotNull("FormSpecGenerator must have getSpecFilePath(String) method",
			FormSpecGenerator.class.getMethod("getSpecFilePath", String.class));
	}
}
