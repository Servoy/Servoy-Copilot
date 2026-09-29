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
package com.servoy.eclipse.developer.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.services.ExpressionEvaluationService;
import com.servoy.eclipse.developer.mcp.services.ExpressionEvaluationService.EvaluationResult;

/**
 * Plain-JUnit (Jupiter) unit tests for the SVY-21473 {@code evaluate} tool: the
 * workbench-free parts of
 * {@link ServoyDevServer#evaluate(String, String, String)} (annotation /
 * parameter / description contract and the {@code timeoutSeconds} default) and
 * the pure-logic helpers of {@link ExpressionEvaluationService} reached by
 * reflection (bare-identifier hint, value rendering, size-cap + temp-file
 * spill, and Servoy-stack error formatting).
 * <p>
 * Anything that needs a running debug client / Servoy model (client selection,
 * real foundset describe, console diff, {@code application.output} capture,
 * no-client message against a live model) belongs in the integration test.
 */
public class EvaluateToolTest {

	// -----------------------------------------------------------------------
	// ServoyDevServer.evaluate — @Tool / @ToolParam / description contract
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("evaluate is registered as a @Tool on servoy-dev")
	void evaluateToolIsRegistered() {
		assertTrue(hasToolNamed("evaluate"), "ServoyDevServer must have an 'evaluate' tool");
	}

	@Test
	@DisplayName("evaluate returns a String")
	void evaluateReturnsString() {
		Method m = findToolMethod("evaluate");
		assertNotNull(m, "evaluate tool method must exist");
		assertEquals(String.class, m.getReturnType(), "evaluate must return String");
	}

	@Test
	@DisplayName("evaluate has expression, solutionName and timeoutSeconds params in order")
	void evaluateHasExpectedParameters() {
		Method m = findToolMethod("evaluate");
		assertNotNull(m, "evaluate tool method must exist");
		ToolParam[] params = Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(ToolParam.class))
				.filter(a -> a != null).toArray(ToolParam[]::new);
		assertEquals(3, params.length, "evaluate must have 3 @ToolParam parameters");
		assertEquals("expression", params[0].name());
		assertEquals("solutionName", params[1].name());
		assertEquals("timeoutSeconds", params[2].name());
	}

	@Test
	@DisplayName("only the expression parameter is required")
	void evaluateExpressionIsRequiredOthersOptional() {
		Method m = findToolMethod("evaluate");
		assertNotNull(m);
		ToolParam[] params = Arrays.stream(m.getParameters()).map(p -> p.getAnnotation(ToolParam.class))
				.filter(a -> a != null).toArray(ToolParam[]::new);
		assertTrue(params[0].required(), "expression must be required");
		assertFalse(params[1].required(), "solutionName must be optional");
		assertFalse(params[2].required(), "timeoutSeconds must be optional");
	}

	@Test
	@DisplayName("evaluate description warns it executes arbitrary mutating code")
	void evaluateDescriptionMentionsMutating() {
		Method m = findToolMethod("evaluate");
		assertNotNull(m);
		String description = m.getAnnotation(Tool.class).description().toLowerCase();
		assertTrue(description.contains("mutating"), "description must state the tool executes mutating code");
	}

	@Test
	@DisplayName("evaluate description says it is not a substitute for a JSUnit test")
	void evaluateDescriptionSaysNotATest() {
		Method m = findToolMethod("evaluate");
		assertNotNull(m);
		String description = m.getAnnotation(Tool.class).description();
		assertTrue(description.contains("NOT a substitute"),
				"description must say it is NOT a substitute for a JSUnit test");
	}

	@Test
	@DisplayName("default timeout is 15 seconds")
	void defaultTimeoutIsFifteen() {
		assertEquals(15, ExpressionEvaluationService.DEFAULT_TIMEOUT_SECONDS,
				"DEFAULT_TIMEOUT_SECONDS must be 15 (spec 3.1 / 5)");
	}

	// -----------------------------------------------------------------------
	// bareIdentifierHint — enrich only when the error clearly names a bare id
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("bare 'foundset' ReferenceError is enriched with the forms.<f>.foundset hint")
	void bareIdentifierHint_foundset() throws Exception {
		String hint = invokeBareIdentifierHint("\"foundset\" is not defined.");
		assertNotNull(hint, "a bare 'foundset' reference error must be enriched");
		assertTrue(hint.contains("forms.<formName>.foundset"), "hint must point at forms.<formName>.foundset");
	}

	@Test
	@DisplayName("bare 'controller' ReferenceError is enriched")
	void bareIdentifierHint_controller() throws Exception {
		assertNotNull(invokeBareIdentifierHint("\"controller\" is not defined."));
	}

	@Test
	@DisplayName("bare 'currentcontroller' ReferenceError is enriched")
	void bareIdentifierHint_currentcontroller() throws Exception {
		assertNotNull(invokeBareIdentifierHint("ReferenceError: \"currentcontroller\" is not defined."));
	}

	@Test
	@DisplayName("bare 'elements' ReferenceError is enriched")
	void bareIdentifierHint_elements() throws Exception {
		assertNotNull(invokeBareIdentifierHint("\"elements\" is not defined."));
	}

	@Test
	@DisplayName("a qualified forms.x.foundset error is NOT enriched")
	void bareIdentifierHint_qualifiedNotEnriched() throws Exception {
		assertNull(invokeBareIdentifierHint("\"forms.orders.foundset\" is not defined."),
				"a qualified reference must not trigger the bare-identifier hint");
	}

	@Test
	@DisplayName("a non-reference error naming nothing bare stays unchanged")
	void bareIdentifierHint_unrelatedError() throws Exception {
		assertNull(invokeBareIdentifierHint("Cannot read property length of null"),
				"an unrelated error must not be enriched");
	}

	@Test
	@DisplayName("an error that is not reference-like is not enriched even if it contains 'foundset'")
	void bareIdentifierHint_notReferenceLike() throws Exception {
		assertNull(invokeBareIdentifierHint("foundset was modified during iteration"),
				"only reference-like errors get the hint");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@DisplayName("null/empty error message yields no hint")
	void bareIdentifierHint_nullOrEmpty(String message) throws Exception {
		assertNull(invokeBareIdentifierHint(message));
	}

	// -----------------------------------------------------------------------
	// renderValue — plain values stringified
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("renderValue stringifies a plain String")
	void renderValue_string() throws Exception {
		String rendered = invokeRenderValue("hello");
		assertNotNull(rendered);
		assertTrue(rendered.contains("hello"), "rendered plain value must contain its content");
	}

	@Test
	@DisplayName("renderValue stringifies a number")
	void renderValue_number() throws Exception {
		String rendered = invokeRenderValue(Integer.valueOf(42));
		assertNotNull(rendered);
		assertTrue(rendered.contains("42"), "rendered number must contain 42");
	}

	// -----------------------------------------------------------------------
	// describeValue — size cap + temp-file spill (spec 3.6)
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("null value -> value/valueFile null, console preserved")
	void describeValue_null() throws Exception {
		EvaluationResult result = invokeDescribeValue(null, "some console");
		assertNull(result.value);
		assertNull(result.valueFile);
		assertEquals("some console", result.console);
		assertNull(result.error);
	}

	@Test
	@DisplayName("small value stays inline, no temp file")
	void describeValue_underCap_inline() throws Exception {
		EvaluationResult result = invokeDescribeValue("short value", "");
		assertNotNull(result.value);
		assertTrue(result.value.contains("short value"));
		assertNull(result.valueFile, "a small value must not be spilled to a temp file");
	}

	@Test
	@DisplayName("value at exactly the 8000 cap stays inline")
	void describeValue_atCapBoundary_inline() throws Exception {
		// A plain String is rendered verbatim by Utils.getScriptableString, so length
		// is preserved.
		String atCap = repeat('x', 8000);
		EvaluationResult result = invokeDescribeValue(atCap, "");
		assertNull(result.valueFile, "a value exactly at the cap must not be spilled");
		assertFalse(result.value.contains("truncated"), "a value at the cap must not be truncated");
	}

	@Test
	@DisplayName("value just over the 8000 cap is truncated inline AND spilled to a temp file")
	void describeValue_overCap_truncatedAndSpilled() throws Exception {
		String overCap = repeat('y', 8001);
		EvaluationResult result = invokeDescribeValue(overCap, "");
		assertNotNull(result.value);
		assertTrue(result.value.contains("truncated"), "over-cap value must carry the truncation marker");
		assertTrue(result.value.contains("8001"), "the truncation marker must report the full length");
		assertNotNull(result.valueFile, "an over-cap value must be spilled to a temp file");

		Path spilled = Path.of(result.valueFile);
		assertTrue(Files.exists(spilled), "the spilled temp file must exist: " + result.valueFile);
		try {
			String full = new String(Files.readAllBytes(spilled), StandardCharsets.UTF_8);
			assertEquals(overCap, full, "the spilled file must hold the FULL, untruncated value");
		} finally {
			Files.deleteIfExists(spilled);
		}
	}

	// -----------------------------------------------------------------------
	// spillToTempFile — writes the content and returns a readable path
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("spillToTempFile writes the content to a svy-evaluate temp file")
	void spillToTempFile_writesContent() throws Exception {
		String content = "line1\nline2\n";
		Method m = ExpressionEvaluationService.class.getDeclaredMethod("spillToTempFile", String.class);
		m.setAccessible(true);
		String path = (String) m.invoke(newService(), content);
		assertNotNull(path, "spillToTempFile must return a path");
		Path file = Path.of(path);
		try {
			assertTrue(Files.exists(file), "spilled file must exist");
			assertTrue(file.getFileName().toString().startsWith("svy-evaluate-"),
					"temp file must use the svy-evaluate- prefix");
			assertEquals(content, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	// -----------------------------------------------------------------------
	// formatScriptError — Servoy stack, hint enrichment
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a plain (non-Rhino) exception is formatted to its message")
	void formatScriptError_plainException() throws Exception {
		String formatted = invokeFormatScriptError(new IllegalStateException("boom"));
		assertNotNull(formatted);
		assertTrue(formatted.contains("boom"), "the formatted error must contain the exception message");
	}

	@Test
	@DisplayName("a plain exception whose message names a bare 'foundset' gets the rewrite hint appended")
	void formatScriptError_bareIdentifierEnriched() throws Exception {
		// formatScriptError appends the bare-identifier hint for ANY caught exception whose message is
		// reference-like and names foundset/controller/currentcontroller/elements (the RhinoException-specific
		// stack rendering is exercised in the integration test, where the real Rhino runtime is initialized).
		String formatted = invokeFormatScriptError(new IllegalStateException("\"foundset\" is not defined."));
		assertNotNull(formatted);
		assertTrue(formatted.contains("\"foundset\" is not defined."), "the original message must be preserved");
		assertTrue(formatted.contains("forms.<formName>.foundset"),
				"a bare-identifier error must be enriched with the rewrite hint");
	}

	@Test
	@DisplayName("a plain exception that names no bare identifier is not enriched")
	void formatScriptError_noBareIdentifierNotEnriched() throws Exception {
		String formatted = invokeFormatScriptError(new IllegalStateException("syntax error"));
		assertNotNull(formatted);
		assertTrue(formatted.contains("syntax error"));
		assertFalse(formatted.contains("forms.<formName>.foundset"),
				"an unrelated error must not carry the bare-identifier hint");
	}

	// -----------------------------------------------------------------------
	// Reflection helpers
	// -----------------------------------------------------------------------

	private static ExpressionEvaluationService newService() {
		return new ExpressionEvaluationService();
	}

	private static String invokeBareIdentifierHint(String message) throws Exception {
		Method m = ExpressionEvaluationService.class.getDeclaredMethod("bareIdentifierHint", String.class);
		m.setAccessible(true);
		return (String) m.invoke(newService(), message);
	}

	private static String invokeRenderValue(Object value) throws Exception {
		Method m = ExpressionEvaluationService.class.getDeclaredMethod("renderValue", Object.class);
		m.setAccessible(true);
		return (String) m.invoke(newService(), value);
	}

	private static EvaluationResult invokeDescribeValue(Object value, String consoleDelta) throws Exception {
		Method m = ExpressionEvaluationService.class.getDeclaredMethod("describeValue", Object.class, String.class);
		m.setAccessible(true);
		return (EvaluationResult) m.invoke(newService(), value, consoleDelta);
	}

	private static String invokeFormatScriptError(Exception scriptError) throws Exception {
		Method m = ExpressionEvaluationService.class.getDeclaredMethod("formatScriptError", Exception.class);
		m.setAccessible(true);
		return (String) m.invoke(newService(), scriptError);
	}

	private static String repeat(char c, int count) {
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			sb.append(c);
		}
		return sb.toString();
	}

	private boolean hasToolNamed(String name) {
		return Arrays.stream(ServoyDevServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.anyMatch(m -> name.equals(m.getAnnotation(Tool.class).name()));
	}

	private Method findToolMethod(String toolName) {
		return Arrays.stream(ServoyDevServer.class.getMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
				.filter(m -> toolName.equals(m.getAnnotation(Tool.class).name())).findFirst().orElse(null);
	}
}
