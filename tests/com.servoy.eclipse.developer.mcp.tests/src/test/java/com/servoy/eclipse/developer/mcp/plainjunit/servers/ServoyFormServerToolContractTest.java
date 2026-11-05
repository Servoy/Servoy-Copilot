/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.servers.ServoyFormServer;

/**
 * Pure-reflection contract tests for the {@code servoy-form} tool surface after SVY-21614
 * (design-time half). They pin that:
 * <ul>
 * <li>{@code getFormLayout} keeps its exact 7-parameter {@code @ToolParam} contract (SVY-21591
 * already added {@code viewportWidth}/{@code viewportHeight}); this ticket does not touch the
 * signature;</li>
 * <li>there is <strong>no</strong> {@code captureFormState} tool - the interactive tool was
 * dropped, interaction belongs to the testing phase;</li>
 * <li>each {@code @Tool} name is declared exactly once (no overloads reach the MCP surface).</li>
 * </ul>
 * No OSGi / workbench / Servoy model is involved: the tool surface is discovered the same way the
 * runtime does, by scanning {@code @Tool}-annotated methods via reflection.
 */
class ServoyFormServerToolContractTest {

	/** The 7 getFormLayout @ToolParam names, in declaration order. */
	private static final List<String> GET_FORM_LAYOUT_PARAMS = List.of("formName", "selector", "screenshot",
			"timeoutSeconds", "sampleData", "viewportWidth", "viewportHeight");

	private static List<Method> toolMethods() {
		List<Method> out = new ArrayList<>();
		for (Method m : ServoyFormServer.class.getDeclaredMethods()) {
			if (m.getAnnotation(Tool.class) != null) out.add(m);
		}
		return out;
	}

	private static Method toolNamed(String toolName) {
		for (Method m : toolMethods()) {
			if (toolName.equals(m.getAnnotation(Tool.class).name())) return m;
		}
		return null;
	}

	@Test
	@DisplayName("getFormLayout is exposed exactly once as an @Tool")
	void getFormLayoutDeclaredOnce() {
		long count = toolMethods().stream().filter(m -> "getFormLayout".equals(m.getAnnotation(Tool.class).name())).count();
		assertEquals(1, count, "getFormLayout must be a single @Tool (no overload reaches the MCP surface)");
	}

	@Test
	@DisplayName("there is NO captureFormState tool (dropped - interaction is a testing-phase concern)")
	void noCaptureFormStateTool() {
		assertFalse(toolMethods().stream().anyMatch(m -> "captureFormState".equals(m.getAnnotation(Tool.class).name())),
				"captureFormState must not exist as an @Tool after SVY-21614");
		assertFalse(Arrays.stream(ServoyFormServer.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("captureFormState")),
				"ServoyFormServer must declare no captureFormState method at all");
	}

	@Test
	@DisplayName("getFormLayout keeps its exact 7 @ToolParam names in order")
	void getFormLayoutParamNamesAndOrder() {
		Method m = toolNamed("getFormLayout");
		assertNotNull(m, "getFormLayout @Tool must be present");

		List<String> names = new ArrayList<>();
		for (Parameter p : m.getParameters()) {
			ToolParam tp = p.getAnnotation(ToolParam.class);
			assertNotNull(tp, "every getFormLayout parameter must carry @ToolParam: " + p);
			names.add(tp.name());
		}
		assertEquals(GET_FORM_LAYOUT_PARAMS, names, "the 7 @ToolParam names must match, in order");
	}

	@Test
	@DisplayName("getFormLayout parameter required-flags and types are unchanged")
	void getFormLayoutParamFlags() {
		Method m = toolNamed("getFormLayout");
		assertNotNull(m, "getFormLayout @Tool must be present");
		Parameter[] ps = m.getParameters();
		assertEquals(7, ps.length, "getFormLayout must have exactly 7 parameters");

		// only formName is required; the rest are optional
		assertTrue(ps[0].getAnnotation(ToolParam.class).required(), "formName is required");
		for (int i = 1; i < ps.length; i++) {
			assertFalse(ps[i].getAnnotation(ToolParam.class).required(),
					ps[i].getAnnotation(ToolParam.class).name() + " must be optional");
		}
		// the two numeric viewport params and timeout are declared as integer; screenshot/sampleData boolean
		assertEquals("integer", paramType(m, "timeoutSeconds"));
		assertEquals("integer", paramType(m, "viewportWidth"));
		assertEquals("integer", paramType(m, "viewportHeight"));
		assertEquals("boolean", paramType(m, "screenshot"));
		assertEquals("boolean", paramType(m, "sampleData"));
	}

	@Test
	@DisplayName("getFormLayout's description documents the SVY-21614 appearance additions")
	void descriptionMentionsNewAppearance() {
		Method m = toolNamed("getFormLayout");
		String desc = m.getAnnotation(Tool.class).description();
		assertTrue(desc.contains("outline"), "the description must mention outline in the appearance map: " + desc);
		assertTrue(desc.contains("disabled") && desc.contains("readonly"),
				"the description must mention the disabled/readonly booleans: " + desc);
		assertTrue(desc.toLowerCase().contains("wait") && desc.toLowerCase().contains("stylesheet"),
				"the description must state the render waits for the solution stylesheets: " + desc);
	}

	private static String paramType(Method m, String paramName) {
		for (Parameter p : m.getParameters()) {
			ToolParam tp = p.getAnnotation(ToolParam.class);
			if (tp != null && paramName.equals(tp.name())) return tp.type();
		}
		throw new AssertionError("no parameter named " + paramName);
	}
}
