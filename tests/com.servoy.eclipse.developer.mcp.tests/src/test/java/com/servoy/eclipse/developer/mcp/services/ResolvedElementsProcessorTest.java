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
package com.servoy.eclipse.developer.mcp.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.dltk.compiler.problem.IProblemCategory;
import org.eclipse.dltk.compiler.problem.IProblemIdentifier;
import org.eclipse.dltk.core.ILocalVariable;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.javascript.typeinfo.IRConstructor;
import org.eclipse.dltk.javascript.typeinfo.IRMember;
import org.eclipse.dltk.javascript.typeinfo.IRMethod;
import org.eclipse.dltk.javascript.typeinfo.IRParameter;
import org.eclipse.dltk.javascript.typeinfo.IRProperty;
import org.eclipse.dltk.javascript.typeinfo.IRRecordMember;
import org.eclipse.dltk.javascript.typeinfo.IRType;
import org.eclipse.dltk.javascript.typeinfo.IRTypeDeclaration;
import org.eclipse.dltk.javascript.typeinfo.IRTypeTransformer;
import org.eclipse.dltk.javascript.typeinfo.IRVariable;
import org.eclipse.dltk.javascript.typeinfo.ITypeSystem;
import org.eclipse.dltk.javascript.typeinfo.TypeCompatibility;
import org.eclipse.dltk.javascript.typeinfo.model.ParameterKind;
import org.eclipse.dltk.javascript.typeinfo.model.Type;
import org.eclipse.dltk.javascript.typeinfo.model.TypeKind;
import org.eclipse.dltk.javascript.typeinfo.model.Visibility;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService.SelectionResult;
import com.servoy.eclipse.developer.mcp.services.ResolvedElementsProcessor;
import com.servoy.j2db.persistence.IFormElement;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.ValueList;

/**
 * Unit tests for {@link ResolvedElementsProcessor#processForeignElements}.
 * Uses stub implementations of IRElement subtypes to verify JSON output structure.
 */
public class ResolvedElementsProcessorTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private ResolvedElementsProcessor processor;
	private ArrayNode resolvedElements;

	@Before
	public void setUp()
	{
		processor = ResolvedElementsProcessor.getInstance();
		resolvedElements = MAPPER.createArrayNode();
	}


	@Test
	public void testToJson_returnsResolvedElementsWrapper() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createMethod("save", "Boolean", false, false,
			Collections.emptyList(), "JSRecord", false));

		String json = processor.toJson(null, result);

		assertNotNull(json);
		JsonNode root = MAPPER.readTree(json);
		assertTrue(root.has("resolvedElements"));
		assertEquals(1, root.get("resolvedElements").size());
		assertEquals("save", root.get("resolvedElements").get(0).get("name").asText());
	}

	@Test
	public void testProcessForeignElements_IRMethod() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createMethod("getData", "String", false, false,
			List.of(createParameter("index", "Number")), "JSRecord", false));

		processor.processForeignElements(resolvedElements, result);

		assertEquals(1, resolvedElements.size());
		JsonNode el = resolvedElements.get(0);
		assertEquals("getData", el.get("name").asText());
		assertEquals("typeinfo", el.get("source").asText());
		assertEquals("method", el.get("kind").asText());
		assertFalse(el.get("deprecated").asBoolean());
		assertFalse(el.get("abstract").asBoolean());
		assertFalse(el.get("generic").asBoolean());
		assertEquals("JSRecord", el.get("declaringType").asText());
		JsonNode params = el.get("parameters");
		assertEquals(1, params.size());
		assertEquals("index", params.get(0).get("name").asText());
		assertEquals("Number", params.get(0).get("type").asText());
	}

	@Test
	public void testProcessForeignElements_IRProperty() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createProperty("visible", "Boolean", true, "JSComponent", false));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("visible", el.get("name").asText());
		assertEquals("property", el.get("kind").asText());
		assertTrue(el.get("readOnly").asBoolean());
		assertEquals("JSComponent", el.get("declaringType").asText());
	}

	@Test
	public void testProcessForeignElements_IRVariable() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createVariable("count", "Number", "JSFoundSet"));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("count", el.get("name").asText());
		assertEquals("variable", el.get("kind").asText());
		assertEquals("Number", el.get("type").asText());
	}

	@Test
	public void testProcessForeignElements_deprecated() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createMethod("oldMethod", "void", false, false,
			Collections.emptyList(), null, true));

		processor.processForeignElements(resolvedElements, result);

		assertTrue(resolvedElements.get(0).get("deprecated").asBoolean());
	}

	@Test
	public void testProcessForeignElements_abstractGenericMethod() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createMethod("transform", "Object", true, true,
			List.of(createParameter("input", "Object")), "BaseClass", false));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertTrue(el.get("abstract").asBoolean());
		assertTrue(el.get("generic").asBoolean());
	}

	@Test
	public void testProcessForeignElements_emptyResult() throws Exception
	{
		SelectionResult result = new SelectionResult();

		processor.processForeignElements(resolvedElements, result);

		assertNotNull(resolvedElements);
		assertEquals(0, resolvedElements.size());
	}

	@Test
	public void testProcessForeignElements_multipleElements() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createMethod("save", "Boolean", false, false,
			Collections.emptyList(), "JSRecord", false));
		result.foreignElements.add(createProperty("enabled", "Boolean", false, "JSComponent", false));

		processor.processForeignElements(resolvedElements, result);

		assertEquals(2, resolvedElements.size());
		assertEquals("save", resolvedElements.get(0).get("name").asText());
		assertEquals("enabled", resolvedElements.get(1).get("name").asText());
	}

	@Test
	public void testProcessForeignElements_IRTypeDeclaration_parameterized() throws Exception
	{
		SelectionResult result = new SelectionResult();
		StubIRTypeDeclaration typeDecl = new StubIRTypeDeclaration("Array")
		{
			@Override
			public boolean isParameterized()
			{
				return true;
			}

			@Override
			public boolean isGeneric()
			{
				return true;
			}

			@Override
			public List<IRType> getActualTypeArguments()
			{
				return List.of(new StubIRType("String"));
			}
		};
		result.foreignElements.add(typeDecl);

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("type", el.get("kind").asText());
		assertTrue(el.get("generic").asBoolean());
		assertNotNull(el.get("typeArguments"));
		assertEquals(1, el.get("typeArguments").size());
		assertEquals("String", el.get("typeArguments").get(0).asText());
	}

	@Test
	public void testProcessForeignElements_IRTypeDeclaration_notGeneric() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(new StubIRTypeDeclaration("JSRecord"));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertFalse(el.has("generic"));
		assertFalse(el.has("typeArguments"));
	}

	@Test
	public void testProcessForeignElements_IRRecordMember_optional() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(new StubIRRecordMember("optField", "String", "MyRecord")
		{
			@Override
			public boolean isOptional()
			{
				return true;
			}
		});

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("recordMember", el.get("kind").asText());
		assertTrue(el.get("optional").asBoolean());
	}

	@Test
	public void testProcessForeignElements_RParameterizedTypeDeclaration() throws Exception
	{
		SelectionResult result = new SelectionResult();
		StubIRTypeDeclaration typeDecl = new StubIRTypeDeclaration("Array<String>")
		{
			@Override
			public boolean isParameterized()
			{
				return true;
			}

			@Override
			public boolean isGeneric()
			{
				return true;
			}

			@Override
			public List<IRType> getActualTypeArguments()
			{
				return List.of(new StubIRType("String"));
			}
		};
		result.foreignElements.add(typeDecl);

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("type", el.get("kind").asText());
		assertTrue(el.get("generic").asBoolean());
		assertNotNull(el.get("typeArguments"));
		assertEquals(1, el.get("typeArguments").size());
		assertEquals("String", el.get("typeArguments").get(0).asText());
		assertEquals("Array<String>", el.get("name").asText());
	}

	@Test
	public void testProcessElementSource_description() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("myProp");
		elementSource.setDescription("The name of the record");
		elementSource.setAttribute(TypeCreator.RESOURCE, null);

		processor.processElementSource(node, elementSource);

		assertEquals("The name of the record", node.get("description").asText());
	}


	@Test
	public void testProcessModelElements_IField() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.modelElements.add(createFieldProxy("myField", "String", "/project/file.js", "MyScope"));

		processor.processModelElements("/project/file.js", resolvedElements, result);

		assertEquals(1, resolvedElements.size());
		JsonNode el = resolvedElements.get(0);
		assertEquals("myField", el.get("name").asText());
		assertEquals("model", el.get("source").asText());
		assertEquals("field", el.get("kind").asText());
		assertEquals("String", el.get("type").asText());
		assertEquals("MyScope", el.get("declaringType").asText());
	}

	@Test
	public void testProcessModelElements_IMethod_withDeclaringType() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.modelElements.add(createMethodProxyWithDeclaringType("doSomething", "/project/file.js",
			new org.eclipse.dltk.core.IParameter[0], "MyScope"));

		processor.processModelElements("/project/file.js", resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("method", el.get("kind").asText());
		assertEquals("MyScope", el.get("declaringType").asText());
	}

	@Test
	public void testProcessElementSource_noDescription() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("myProp");
		elementSource.setAttribute(TypeCreator.RESOURCE, null);

		processor.processElementSource(node, elementSource);

		assertFalse(node.has("description"));
	}

	@Test
	public void testProcessElementSource_hintsReusedWhenAlreadyPresent() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testProp");

		org.sablo.specification.WebObjectSpecification spec = new org.sablo.specification.WebObjectSpecificationBuilder()
			.withName("myComp")
			.build();
		elementSource.setAttribute(TypeCreator.RESOURCE, spec);
		processor.processElementSource(node, elementSource);

		java.lang.reflect.Constructor< ? > ctor = com.servoy.j2db.persistence.Relation.class.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		com.servoy.j2db.persistence.ISupportChilds parentProxy = createProxy(
			com.servoy.j2db.persistence.ISupportChilds.class, (proxy, method, args) -> null);
		Object uuidParam = ctor.getParameterTypes()[1].getDeclaredMethod("randomUUID").invoke(null);
		com.servoy.j2db.persistence.Relation relation = (com.servoy.j2db.persistence.Relation)ctor.newInstance(parentProxy, uuidParam);
		relation.setName("myRelation");
		elementSource.setAttribute(TypeCreator.RESOURCE, relation);

		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// ServoyModelFinder may NPE
		}

		assertTrue(node.has("hints"));
		JsonNode hints = node.get("hints");
		assertTrue(hints.isArray());
		assertTrue(hints.size() > 0);
	}


	@Test
	public void testProcessElementSource_hintsReusedWhenAlreadyPresent_secondBranch() throws Exception
	{
		// First call creates hints via relation (first branch: node does NOT have hints yet)
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testProp");

		java.lang.reflect.Constructor< ? > ctor = com.servoy.j2db.persistence.Relation.class.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		com.servoy.j2db.persistence.ISupportChilds parentProxy = createProxy(
			com.servoy.j2db.persistence.ISupportChilds.class, (proxy, method, args) -> null);
		Object uuidParam = ctor.getParameterTypes()[1].getDeclaredMethod("randomUUID").invoke(null);
		com.servoy.j2db.persistence.Relation relation = (com.servoy.j2db.persistence.Relation)ctor.newInstance(parentProxy, uuidParam);
		relation.setName("myRelation");
		elementSource.setAttribute(TypeCreator.RESOURCE, relation);

		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// ServoyModelFinder may NPE
		}

		// hints array now exists - second call should reuse it (second branch of getOrCreateHints)
		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// ServoyModelFinder may NPE
		}

		assertTrue(node.has("hints"));
		assertTrue(node.get("hints").isArray());
	}


	@Test
	public void testProcessForeignElements_staticMember() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(new StubIRMethod("getInstance", "Application", false, false,
			Collections.emptyList(), "Application", false, true));

		processor.processForeignElements(resolvedElements, result);

		assertTrue(resolvedElements.get(0).get("static").asBoolean());
	}

	@Test
	public void testProcessModelElements_ILocalVariable() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.modelElements.add(createLocalVariableProxy("myVar", "String", "/project/file.js"));

		processor.processModelElements("/project/file.js", resolvedElements, result);

		assertEquals(1, resolvedElements.size());
		JsonNode el = resolvedElements.get(0);
		assertEquals("myVar", el.get("name").asText());
		assertEquals("model", el.get("source").asText());
		assertEquals("localVariable", el.get("kind").asText());
		assertEquals("String", el.get("type").asText());
	}

	@Test
	public void testProcessModelElements_IMethod() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.modelElements.add(createMethodProxy("calculate", "/project/file.js",
			new org.eclipse.dltk.core.IParameter[] {
				new StubIParameter("a", "Number"),
				new StubIParameter("b", "Number")
			}));

		processor.processModelElements("/project/file.js", resolvedElements, result);

		assertEquals(1, resolvedElements.size());
		JsonNode el = resolvedElements.get(0);
		assertEquals("calculate", el.get("name").asText());
		assertEquals("method", el.get("kind").asText());
		JsonNode params = el.get("parameters");
		assertEquals(2, params.size());
		assertEquals("a", params.get(0).get("name").asText());
		assertEquals("Number", params.get(0).get("type").asText());
	}

	@Test
	public void testProcessModelElements_differentFile() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.modelElements.add(createLocalVariableProxy("otherVar", "Number", "/project/other.js"));

		processor.processModelElements("/project/file.js", resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("/project/other.js", el.get("file").asText());
	}

	@Test
	public void testProcessElementSource_withWebObjectSpecification() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testProp");
		org.sablo.specification.WebObjectSpecification spec = new org.sablo.specification.WebObjectSpecificationBuilder()
			.withName("svy-fullcalendar2")
			.withDisplayName("Full Calendar")
			.withCategoryName("Scheduling")
			.build();
		elementSource.setAttribute(TypeCreator.RESOURCE, spec);

		processor.processElementSource(node, elementSource);

		assertEquals("webComponent", node.get("resourceKind").asText());
		assertEquals("svy-fullcalendar2", node.get("componentName").asText());
		assertEquals("Full Calendar", node.get("displayName").asText());
		assertEquals("Scheduling", node.get("category").asText());
	}

	@Test
	public void testProcessElementSource_withNullResource() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testProp");
		elementSource.setAttribute(TypeCreator.RESOURCE, null);

		processor.processElementSource(node, elementSource);

		assertFalse(node.has("file"));
		assertFalse(node.has("resourceKind"));
	}

	@Test
	public void testProcessForeignElements_RMethodFunctionWrapper() throws Exception
	{
		// Create a real RMethodFunctionWrapper via reflection (internal class)
		// IRFunctionType stub: returns empty parameters list, null return type
		org.eclipse.dltk.javascript.typeinfo.IRFunctionType functionType = createProxy(
			org.eclipse.dltk.javascript.typeinfo.IRFunctionType.class, (proxy, method, args) -> {
				return switch (method.getName())
				{
					case "getParameters" -> Collections.emptyList();
					case "getReturnType" -> null;
					case "getTypeSystem" -> null;
					default -> null;
				};
			});

		// IValueReference stub: returns null for all attribute lookups
		org.eclipse.dltk.javascript.typeinference.IValueReference reference = createProxy(
			org.eclipse.dltk.javascript.typeinference.IValueReference.class, (proxy, method, args) -> null);

		Class< ? > clazz = Class.forName("org.eclipse.dltk.internal.javascript.validation.RMethodFunctionWrapper");
		java.lang.reflect.Constructor< ? > ctor = clazz.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		Class< ? >[] paramTypes = ctor.getParameterTypes();
		IRMethod wrapper;
		if (paramTypes.length == 2 && paramTypes[0].getName().contains("IValueReference"))
			wrapper = (IRMethod)ctor.newInstance(reference, functionType);
		else
			wrapper = (IRMethod)ctor.newInstance(functionType, reference);
		// Ensure the functionType field is set (field may not be assigned by constructor in all DLTK versions)
		try
		{
			java.lang.reflect.Field ftField = clazz.getDeclaredField("functionType");
			ftField.setAccessible(true);
			if (ftField.get(wrapper) == null) ftField.set(wrapper, functionType);
		}
		catch (NoSuchFieldException e)
		{
			// field might be in superclass
			for (java.lang.reflect.Field f : clazz.getSuperclass().getDeclaredFields())
			{
				if (f.getName().equals("functionType"))
				{
					f.setAccessible(true);
					if (f.get(wrapper) == null) f.set(wrapper, functionType);
					break;
				}
			}
		}

		SelectionResult result = new SelectionResult();
		result.foreignElements.add(wrapper);

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("method", el.get("kind").asText());
		assertFalse(el.get("abstract").asBoolean());
		assertFalse(el.get("generic").asBoolean());
		// isTyped() returns true when params are empty (not a single varargs-of-any)
		assertTrue(el.get("typed").asBoolean());
		assertNotNull(el.get("parameters"));
		assertEquals(0, el.get("parameters").size());
	}

	@Test
	public void testProcessForeignElements_IRMethod_typed() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createMethod("typedMethod", "String", false, false,
			List.of(createParameter("arg", "Number")), "MyClass", false));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("method", el.get("kind").asText());
		assertTrue(el.get("typed").asBoolean());
	}


	@Test
	public void testProcessElementSource_withFormElement_noParentForm() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testElement");
		IFormElement formElementProxy = createProxy(IFormElement.class, (proxy, method, args) -> {
			return switch (method.getName())
			{
				case "getName" -> "myButton";
				case "getParent" -> null;
				default -> null;
			};
		});
		elementSource.setAttribute(TypeCreator.RESOURCE, formElementProxy);

		processor.processElementSource(node, elementSource);

		assertEquals("formElement", node.get("resourceKind").asText());
		assertEquals("myButton", node.get("elementName").asText());
		assertFalse(node.has("form"));
	}

	@Test
	public void testProcessElementSource_withRelation() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testRel");

		java.lang.reflect.Constructor<Relation> ctor = Relation.class.getDeclaredConstructor(
			com.servoy.j2db.persistence.ISupportChilds.class, com.servoy.j2db.util.UUID.class);
		ctor.setAccessible(true);
		com.servoy.j2db.persistence.ISupportChilds parentProxy = createProxy(
			com.servoy.j2db.persistence.ISupportChilds.class, (proxy, method, args) -> null);
		Relation relation = ctor.newInstance(parentProxy, com.servoy.j2db.util.UUID.randomUUID());
		relation.setName("orders_to_customers");
		elementSource.setAttribute(TypeCreator.RESOURCE, relation);

		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// ServoyModelFinder static calls may NPE in unit test
		}

		assertEquals("relation", node.get("resourceKind").asText());
		assertEquals("orders_to_customers", node.get("relationName").asText());
	}

	@Test
	public void testProcessElementSource_withValueList() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testVL");

		java.lang.reflect.Constructor< ? > ctor = ValueList.class.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		com.servoy.j2db.persistence.ISupportChilds parentProxy = createProxy(
			com.servoy.j2db.persistence.ISupportChilds.class, (proxy, method, args) -> null);
		Object uuidParam = ctor.getParameterTypes()[1].getDeclaredMethod("randomUUID").invoke(null);
		ValueList valuelist = (ValueList)ctor.newInstance(parentProxy, uuidParam);
		valuelist.setName("statusValues");
		valuelist.setDataProviderID1("status_id");
		valuelist.setCustomValues("value1\nvalue2\nvalue3");
		elementSource.setAttribute(TypeCreator.RESOURCE, valuelist);

		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// SolutionSerializer static calls may NPE in unit test
		}

		assertEquals("valuelist", node.get("resourceKind").asText());
		assertEquals("statusValues", node.get("valuelistName").asText());
		assertEquals("value1\nvalue2\nvalue3", node.get("customValues").asText());
	}

	@Test
	public void testProcessElementSource_withValueList_singleLineCustomValue() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testVL2");

		java.lang.reflect.Constructor< ? > ctor = ValueList.class.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		com.servoy.j2db.persistence.ISupportChilds parentProxy = createProxy(
			com.servoy.j2db.persistence.ISupportChilds.class, (proxy, method, args) -> null);
		Object uuidParam = ctor.getParameterTypes()[1].getDeclaredMethod("randomUUID").invoke(null);
		ValueList valuelist = (ValueList)ctor.newInstance(parentProxy, uuidParam);
		valuelist.setName("simpleVL");
		valuelist.setCustomValues("not-a-uuid-value");
		elementSource.setAttribute(TypeCreator.RESOURCE, valuelist);

		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// SolutionSerializer static calls may NPE in unit test
		}

		assertEquals("valuelist", node.get("resourceKind").asText());
		assertEquals("simpleVL", node.get("valuelistName").asText());
		assertEquals("not-a-uuid-value", node.get("customValues").asText());
	}

	@Test
	public void testProcessElementSource_withValueList_withDataSourceAndRelation() throws Exception
	{
		com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
		org.eclipse.dltk.javascript.typeinfo.model.Property elementSource = org.eclipse.dltk.javascript.typeinfo.model.TypeInfoModelFactory.eINSTANCE.createProperty();
		elementSource.setName("testVL3");

		java.lang.reflect.Constructor< ? > ctor = ValueList.class.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		com.servoy.j2db.persistence.ISupportChilds parentProxy = createProxy(
			com.servoy.j2db.persistence.ISupportChilds.class, (proxy, method, args) -> null);
		Object uuidParam = ctor.getParameterTypes()[1].getDeclaredMethod("randomUUID").invoke(null);
		ValueList valuelist = (ValueList)ctor.newInstance(parentProxy, uuidParam);
		valuelist.setName("relatedVL");
		valuelist.setDataSource("db:/myserver/mytable");
		valuelist.setRelationName("orders_to_customers");
		elementSource.setAttribute(TypeCreator.RESOURCE, valuelist);

		try
		{
			processor.processElementSource(node, elementSource);
		}
		catch (Exception e)
		{
			// SolutionSerializer static calls may NPE in unit test
		}

		assertEquals("valuelist", node.get("resourceKind").asText());
		assertEquals("relatedVL", node.get("valuelistName").asText());
		assertEquals("db:/myserver/mytable", node.get("dataSource").asText());
		assertEquals("orders_to_customers", node.get("relation").asText());
	}



	@Test
	public void testProcessForeignElements_IRTypeDeclaration_withMembers() throws Exception
	{
		SelectionResult result = new SelectionResult();
		StubIRTypeDeclaration typeDecl = new StubIRTypeDeclaration("JSRecord");
		typeDecl.superType = new StubIRTypeDeclaration("JSFoundSet");
		typeDecl.traits = List.of(new StubIRTypeDeclaration("Iterable"));
		typeDecl.members = List.of(
			(IRMember)createMethod("save", "Boolean", false, false, Collections.emptyList(), "JSRecord", false),
			(IRMember)createProperty("foundset", "JSFoundSet", true, "JSRecord", false));
		result.foreignElements.add(typeDecl);

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("JSRecord", el.get("name").asText());
		assertEquals("type", el.get("kind").asText());
		assertEquals("JAVA", el.get("typeKind").asText());
		assertEquals("JSFoundSet", el.get("superType").asText());
		JsonNode traits = el.get("traits");
		assertEquals(1, traits.size());
		assertEquals("Iterable", traits.get(0).asText());
		JsonNode members = el.get("members");
		assertEquals(2, members.size());
		assertEquals("save", members.get(0).get("name").asText());
		assertEquals("method", members.get(0).get("kind").asText());
		assertEquals("foundset", members.get(1).get("name").asText());
		assertEquals("property", members.get(1).get("kind").asText());
	}

	@Test
	public void testProcessForeignElements_IRTypeDeclaration_withConstructors() throws Exception
	{
		SelectionResult result = new SelectionResult();
		StubIRTypeDeclaration typeDecl = new StubIRTypeDeclaration("MyClass");
		typeDecl.constructors = List.of(createConstructor("MyClass",
			List.of(createParameter("name", "String"))));
		typeDecl.staticConstructor = createConstructor("MyClass",
			List.of(createParameter("value", "Number")));
		result.foreignElements.add(typeDecl);

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		JsonNode ctors = el.get("constructors");
		assertNotNull(ctors);
		assertEquals(1, ctors.size());
		assertEquals("MyClass", ctors.get(0).get("name").asText());
		assertEquals("name", ctors.get(0).get("parameters").get(0).get("name").asText());
		JsonNode sc = el.get("staticConstructor");
		assertNotNull(sc);
		assertEquals("MyClass", sc.get("name").asText());
		assertEquals("value", sc.get("parameters").get(0).get("name").asText());
	}

	@Test
	public void testProcessForeignElements_IRConstructor() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(createConstructor("MyClass",
			List.of(createParameter("arg1", "String"), createParameter("arg2", "Number"))));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("MyClass", el.get("name").asText());
		assertEquals("constructor", el.get("kind").asText());
		JsonNode params = el.get("parameters");
		assertEquals(2, params.size());
		assertEquals("arg1", params.get(0).get("name").asText());
		assertEquals("arg2", params.get(1).get("name").asText());
	}

	@Test
	public void testProcessForeignElements_IRRecordMember() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(new StubIRRecordMember("myField", "String", "MyRecord"));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("myField", el.get("name").asText());
		assertEquals("recordMember", el.get("kind").asText());
		assertEquals("String", el.get("type").asText());
	}

	@Test
	public void testProcessForeignElements_memberWithVisibility() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(new StubIRMethod("privateMethod", "void", false, false,
			Collections.emptyList(), null, false, false)
		{
			@Override
			public Visibility getVisibility()
			{
				return Visibility.INTERNAL;
			}
		});

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertEquals("INTERNAL", el.get("visibility").asText());
	}

	@Test
	public void testProcessForeignElements_memberWithNullType() throws Exception
	{
		SelectionResult result = new SelectionResult();
		result.foreignElements.add(new StubIRMethod("noReturn", null, false, false,
			Collections.emptyList(), null, false, false));

		processor.processForeignElements(resolvedElements, result);

		JsonNode el = resolvedElements.get(0);
		assertTrue(el.get("type") == null || el.get("type").isNull());
		assertTrue(el.get("declaringType") == null || el.get("declaringType").isNull());
	}


	// --- Factory methods ---

	private IRMethod createMethod(String name, String returnType, boolean isAbstract, boolean isGeneric,
		List<IRParameter> parameters, String declaringType, boolean deprecated)
	{
		return new StubIRMethod(name, returnType, isAbstract, isGeneric, parameters, declaringType, deprecated, false);
	}

	private IRProperty createProperty(String name, String type, boolean readOnly, String declaringType, boolean deprecated)
	{
		return new StubIRProperty(name, type, readOnly, declaringType, deprecated);
	}

	private IRVariable createVariable(String name, String type, String declaringType)
	{
		return new StubIRVariable(name, type, declaringType);
	}

	private IRParameter createParameter(String name, String type)
	{
		return new StubIRParameter(name, type);
	}

	private IRConstructor createConstructor(String name, List<IRParameter> parameters)
	{
		return new StubIRConstructor(name, parameters);
	}


	// --- Stub implementations ---

	private static class StubIRType implements IRType
	{
		private final String name;

		StubIRType(String name)
		{
			this.name = name;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public String toString()
		{
			return name;
		}

		@Override
		public TypeCompatibility isAssignableFrom(IRType type)
		{
			return TypeCompatibility.TRUE;
		}

		@Override
		public boolean isExtensible()
		{
			return false;
		}

		@Override
		public boolean isJavaScriptObject()
		{
			return false;
		}

		@Override
		public boolean isSynthetic()
		{
			return false;
		}

		@Override
		public IRType transform(IRTypeTransformer transformer)
		{
			return this;
		}

		@Override
		public IRType normalize()
		{
			return this;
		}
	}

	private static class StubIRParameter implements IRParameter
	{
		private final String name;
		private final String type;

		StubIRParameter(String name, String type)
		{
			this.name = name;
			this.type = type;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public IRType getType()
		{
			return new StubIRType(type);
		}

		@Override
		public ParameterKind getKind()
		{
			return ParameterKind.NORMAL;
		}

		@Override
		public String getDescription()
		{
			return null;
		}

		@Override
		public boolean isOptional()
		{
			return false;
		}

		@Override
		public boolean isVarargs()
		{
			return false;
		}

		@Override
		public IRParameter makeImmutable(Map<Object, Object> visited)
		{
			return this;
		}
	}

	private static class StubIRMethod implements IRMethod
	{
		private final String name;
		private final String returnType;
		private final boolean abstractMethod;
		private final boolean genericMethod;
		private final List<IRParameter> parameters;
		private final String declaringType;
		private final boolean deprecated;
		private final boolean staticMethod;

		StubIRMethod(String name, String returnType, boolean abstractMethod, boolean genericMethod,
			List<IRParameter> parameters, String declaringType, boolean deprecated, boolean staticMethod)
		{
			this.name = name;
			this.returnType = returnType;
			this.abstractMethod = abstractMethod;
			this.genericMethod = genericMethod;
			this.parameters = parameters;
			this.declaringType = declaringType;
			this.deprecated = deprecated;
			this.staticMethod = staticMethod;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public IRType getType()
		{
			return returnType != null ? new StubIRType(returnType) : null;
		}

		@Override
		public Visibility getVisibility()
		{
			return null;
		}

		@Override
		public IRTypeDeclaration getDeclaringType()
		{
			return declaringType != null ? new StubIRTypeDeclaration(declaringType) : null;
		}

		@Override
		public boolean isStatic()
		{
			return staticMethod;
		}

		@Override
		public boolean isVisible()
		{
			return true;
		}

		@Override
		public int getParameterCount()
		{
			return parameters.size();
		}

		@Override
		public List<IRParameter> getParameters()
		{
			return parameters;
		}

		@Override
		public boolean isTyped()
		{
			return true;
		}

		@Override
		public boolean isAbstract()
		{
			return abstractMethod;
		}

		@Override
		public boolean isGeneric()
		{
			return genericMethod;
		}

		@Override
		public Object getSource()
		{
			return null;
		}

		@Override
		public boolean isDeprecated()
		{
			return deprecated;
		}

		@Override
		public Set<IProblemCategory> getSuppressedWarnings()
		{
			return Collections.emptySet();
		}

		@Override
		public boolean isSuppressed(IProblemIdentifier id)
		{
			return false;
		}

		@Override
		public IRMethod makeImmutable(Map<Object, Object> visited)
		{
			return this;
		}
	}

	private static class StubIRProperty implements IRProperty
	{
		private final String name;
		private final String type;
		private final boolean readOnly;
		private final String declaringType;
		private final boolean deprecated;

		StubIRProperty(String name, String type, boolean readOnly, String declaringType, boolean deprecated)
		{
			this.name = name;
			this.type = type;
			this.readOnly = readOnly;
			this.declaringType = declaringType;
			this.deprecated = deprecated;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public IRType getType()
		{
			return type != null ? new StubIRType(type) : null;
		}

		@Override
		public Visibility getVisibility()
		{
			return null;
		}

		@Override
		public IRTypeDeclaration getDeclaringType()
		{
			return declaringType != null ? new StubIRTypeDeclaration(declaringType) : null;
		}

		@Override
		public boolean isStatic()
		{
			return false;
		}

		@Override
		public boolean isVisible()
		{
			return true;
		}

		@Override
		public boolean isReadOnly()
		{
			return readOnly;
		}

		@Override
		public org.eclipse.dltk.javascript.typeinfo.model.Property getSource()
		{
			return null;
		}

		@Override
		public boolean isDeprecated()
		{
			return deprecated;
		}

		@Override
		public Set<IProblemCategory> getSuppressedWarnings()
		{
			return Collections.emptySet();
		}

		@Override
		public boolean isSuppressed(IProblemIdentifier id)
		{
			return false;
		}

		@Override
		public IRVariable makeImmutable(Map<Object, Object> visited)
		{
			return this;
		}
	}

	private static class StubIRVariable implements IRVariable
	{
		private final String name;
		private final String type;
		private final String declaringType;

		StubIRVariable(String name, String type, String declaringType)
		{
			this.name = name;
			this.type = type;
			this.declaringType = declaringType;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public IRType getType()
		{
			return type != null ? new StubIRType(type) : null;
		}

		@Override
		public Visibility getVisibility()
		{
			return null;
		}

		@Override
		public IRTypeDeclaration getDeclaringType()
		{
			return declaringType != null ? new StubIRTypeDeclaration(declaringType) : null;
		}

		@Override
		public boolean isStatic()
		{
			return false;
		}

		@Override
		public boolean isVisible()
		{
			return true;
		}

		@Override
		public Object getSource()
		{
			return null;
		}

		@Override
		public boolean isDeprecated()
		{
			return false;
		}

		@Override
		public Set<IProblemCategory> getSuppressedWarnings()
		{
			return Collections.emptySet();
		}

		@Override
		public boolean isSuppressed(IProblemIdentifier id)
		{
			return false;
		}

		@Override
		public IRVariable makeImmutable(Map<Object, Object> visited)
		{
			return this;
		}
	}

	private static class StubIRTypeDeclaration implements IRTypeDeclaration
	{
		private final String name;
		IRTypeDeclaration superType;
		List<IRTypeDeclaration> traits = Collections.emptyList();
		List<IRMember> members = Collections.emptyList();
		List<IRConstructor> constructors = Collections.emptyList();
		IRConstructor staticConstructor;

		StubIRTypeDeclaration(String name)
		{
			this.name = name;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public Type getSource()
		{
			return null;
		}

		@Override
		public boolean isDeprecated()
		{
			return false;
		}

		@Override
		public Set<IProblemCategory> getSuppressedWarnings()
		{
			return Collections.emptySet();
		}

		@Override
		public boolean isSuppressed(IProblemIdentifier id)
		{
			return false;
		}

		@Override
		public ITypeSystem getTypeSystem()
		{
			return null;
		}

		@Override
		public IRTypeDeclaration getSuperType()
		{
			return superType;
		}

		@Override
		public List<IRTypeDeclaration> getTraits()
		{
			return traits;
		}

		@Override
		public List<IRMember> getMembers()
		{
			return members;
		}

		@Override
		public List<IRConstructor> getConstructors()
		{
			return constructors;
		}

		@Override
		public IRConstructor getStaticConstructor()
		{
			return staticConstructor;
		}

		@Override
		public TypeKind getKind()
		{
			return TypeKind.JAVA;
		}

		@Override
		public boolean isInheritStaticMembers()
		{
			return false;
		}

		@Override
		public Object getReadOnlyStatus(IRProperty property)
		{
			return null;
		}

		@Override
		public boolean isGeneric()
		{
			return false;
		}

		@Override
		public boolean isParameterized()
		{
			return false;
		}

		@Override
		public List<IRType> getActualTypeArguments()
		{
			return Collections.emptyList();
		}

		@Override
		public TypeCompatibility isAssignableFrom(IRTypeDeclaration other)
		{
			return TypeCompatibility.TRUE;
		}

		@Override
		public IRMethod findMethod(String methodName, boolean isStatic)
		{
			return null;
		}
	}

	private static class StubIRConstructor implements IRConstructor
	{
		private final String name;
		private final List<IRParameter> parameters;

		StubIRConstructor(String name, List<IRParameter> parameters)
		{
			this.name = name;
			this.parameters = parameters;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public IRType getType()
		{
			return null;
		}

		@Override
		public Visibility getVisibility()
		{
			return null;
		}

		@Override
		public IRTypeDeclaration getDeclaringType()
		{
			return null;
		}

		@Override
		public boolean isStatic()
		{
			return false;
		}

		@Override
		public boolean isVisible()
		{
			return true;
		}

		@Override
		public int getParameterCount()
		{
			return parameters.size();
		}

		@Override
		public List<IRParameter> getParameters()
		{
			return parameters;
		}

		@Override
		public boolean isTyped()
		{
			return false;
		}

		@Override
		public boolean isAbstract()
		{
			return false;
		}

		@Override
		public boolean isGeneric()
		{
			return false;
		}

		@Override
		public Object getSource()
		{
			return null;
		}

		@Override
		public boolean isDeprecated()
		{
			return false;
		}

		@Override
		public Set<IProblemCategory> getSuppressedWarnings()
		{
			return Collections.emptySet();
		}

		@Override
		public boolean isSuppressed(IProblemIdentifier id)
		{
			return false;
		}

		@Override
		public IRMethod makeImmutable(Map<Object, Object> visited)
		{
			return this;
		}
	}

	private static class StubIRRecordMember implements org.eclipse.dltk.javascript.typeinfo.IRRecordMember
	{
		private final String name;
		private final String type;
		private final String declaringType;

		StubIRRecordMember(String name, String type, String declaringType)
		{
			this.name = name;
			this.type = type;
			this.declaringType = declaringType;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public IRType getType()
		{
			return type != null ? new StubIRType(type) : null;
		}

		@Override
		public Visibility getVisibility()
		{
			return null;
		}

		@Override
		public IRTypeDeclaration getDeclaringType()
		{
			return declaringType != null ? new StubIRTypeDeclaration(declaringType) : null;
		}

		@Override
		public boolean isStatic()
		{
			return false;
		}

		@Override
		public boolean isVisible()
		{
			return true;
		}

		@Override
		public Object getSource()
		{
			return null;
		}

		@Override
		public boolean isDeprecated()
		{
			return false;
		}

		@Override
		public Set<IProblemCategory> getSuppressedWarnings()
		{
			return Collections.emptySet();
		}

		@Override
		public boolean isSuppressed(IProblemIdentifier id)
		{
			return false;
		}

		@Override
		public boolean isOptional()
		{
			return false;
		}


		@Override
		public org.eclipse.dltk.javascript.typeinfo.IRRecordMember makeImmutable(Map<Object, Object> visited)
		{
			return this;
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T createProxy(Class<T> iface, java.lang.reflect.InvocationHandler handler)
	{
		return (T)java.lang.reflect.Proxy.newProxyInstance(
			iface.getClassLoader(), new Class<?>[] { iface }, handler);
	}

	private static org.eclipse.dltk.core.ILocalVariable createLocalVariableProxy(String name, String type, String path)
	{
		return createProxy(org.eclipse.dltk.core.ILocalVariable.class, (proxy, method, args) -> {
			return switch (method.getName())
			{
				case "getElementName" -> name;
				case "getType" -> type;
				case "getPath" -> new org.eclipse.core.runtime.Path(path);
				case "exists" -> true;
				default -> null;
			};
		});
	}

	private static org.eclipse.dltk.core.IMethod createMethodProxy(String name, String path, org.eclipse.dltk.core.IParameter[] params)
	{
		return createProxy(org.eclipse.dltk.core.IMethod.class, (proxy, method, args) -> {
			return switch (method.getName())
			{
				case "getElementName" -> name;
				case "getPath" -> new org.eclipse.core.runtime.Path(path);
				case "getParameters" -> params;
				case "exists" -> true;
				default -> null;
			};
		});
	}

	private static class StubIParameter implements org.eclipse.dltk.core.IParameter
	{
		private final String name;
		private final String type;

		StubIParameter(String name, String type)
		{
			this.name = name;
			this.type = type;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public String getType()
		{
			return type;
		}

		@Override
		public String getDefaultValue()
		{
			return null;
		}
	}

	private static org.eclipse.dltk.core.IField createFieldProxy(String name, String type, String path, String declaringTypeName)
	{
		return createProxy(org.eclipse.dltk.core.IField.class, (proxy, method, args) -> {
			return switch (method.getName())
			{
				case "getElementName" -> name;
				case "getType" -> type;
				case "getPath" -> new org.eclipse.core.runtime.Path(path);
				case "getDeclaringType" -> declaringTypeName != null
					? createProxy(org.eclipse.dltk.core.IType.class, (p2, m2, a2) -> {
						if ("getElementName".equals(m2.getName())) return declaringTypeName;
						return null;
					})
					: null;
				case "exists" -> true;
				default -> null;
			};
		});
	}

	private static org.eclipse.dltk.core.IType createTypeProxy(String name, String path, String[] superClasses)
	{
		return createProxy(org.eclipse.dltk.core.IType.class, (proxy, method, args) -> {
			return switch (method.getName())
			{
				case "getElementName" -> name;
				case "getPath" -> new org.eclipse.core.runtime.Path(path);
				case "getSuperClasses" -> superClasses;
				case "getDeclaringType" -> null;
				case "exists" -> true;
				default -> null;
			};
		});
	}

	private static org.eclipse.dltk.core.IMethod createMethodProxyWithDeclaringType(String name, String path,
		org.eclipse.dltk.core.IParameter[] params, String declaringTypeName)
	{
		return createProxy(org.eclipse.dltk.core.IMethod.class, (proxy, method, args) -> {
			return switch (method.getName())
			{
				case "getElementName" -> name;
				case "getPath" -> new org.eclipse.core.runtime.Path(path);
				case "getParameters" -> params;
				case "getDeclaringType" -> declaringTypeName != null
					? createProxy(org.eclipse.dltk.core.IType.class, (p2, m2, a2) -> {
						if ("getElementName".equals(m2.getName())) return declaringTypeName;
						return null;
					})
					: null;
				case "exists" -> true;
				default -> null;
			};
		});
	}

}
