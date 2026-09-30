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
package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

/**
 * Jupiter (JUnit 6) unit tests for the pure-logic parts of
 * {@link RunningClientExecutionService} (SVY-21443).
 * <p>
 * These tests exercise, in isolation and without a live debug client, the
 * package-private/private helpers that make up the service's deterministic
 * behaviour:
 * <ul>
 * <li>{@code buildScript} - script / named-method synthesis and ordering,</li>
 * <li>{@code buildArgumentList} - JSON-array argument parsing (incl. malformed
 * input),</li>
 * <li>{@code serializeResult} - value/error/{@code null} serialization, the
 * {@code Wrapper}-unwrap contract and the {@code RhinoException}-preferring
 * error path,</li>
 * <li>{@code findRhinoException} - cause-chain walk,</li>
 * <li>{@code formatResult} - the two-section markdown blob,</li>
 * <li>the {@code NOT_FOUND}/{@code Undefined} -&gt; null normalisation used by
 * the evaluate path,</li>
 * <li>and the "no running client" / "at least one of script or methodName"
 * guard messages reachable through
 * {@link RunningClientExecutionService#execute} without a client.</li>
 * </ul>
 * Private members are reached via reflection to keep production code unchanged,
 * mirroring the existing {@code *ReflectionTest} pattern in this bundle.
 */
public class RunningClientExecutionServiceTest {

	private static final String NULL_MARKER = "(null)";

	private final RunningClientExecutionService service = new RunningClientExecutionService();

	// -----------------------------------------------------------------------
	// reflection helpers
	// -----------------------------------------------------------------------

	private static Object invokeStatic(String name, Class<?>[] sig, Object... args) throws Exception {
		Method m = RunningClientExecutionService.class.getDeclaredMethod(name, sig);
		m.setAccessible(true);
		try {
			return m.invoke(null, args);
		} catch (InvocationTargetException ite) {
			Throwable cause = ite.getCause();
			if (cause instanceof Exception e)
				throw e;
			if (cause instanceof Error err)
				throw err;
			throw ite;
		}
	}

	private static String buildScript(String script, String methodName, String argsJson) throws Exception {
		return (String) invokeStatic("buildScript", new Class<?>[] { String.class, String.class, String.class }, script,
				methodName, argsJson);
	}

	private static String buildArgumentList(String argsJson) throws Exception {
		return (String) invokeStatic("buildArgumentList", new Class<?>[] { String.class }, argsJson);
	}

	private static String serializeResult(Object value) throws Exception {
		return (String) invokeStatic("serializeResult", new Class<?>[] { Object.class }, value);
	}

	private static RhinoException findRhinoException(Throwable t) throws Exception {
		return (RhinoException) invokeStatic("findRhinoException", new Class<?>[] { Throwable.class }, t);
	}

	private static String formatResult(String client, String result, String output) throws Exception {
		return (String) invokeStatic("formatResult", new Class<?>[] { String.class, String.class, String.class },
				client, result, output);
	}

	private static String bareIdentifierHint(String message) throws Exception {
		return (String) invokeStatic("bareIdentifierHint", new Class<?>[] { String.class }, message);
	}

	private static String applyValueCap(String rendered, StringBuilder valueFileOut) throws Exception {
		return (String) invokeStatic("applyValueCap", new Class<?>[] { String.class, StringBuilder.class }, rendered,
				valueFileOut);
	}

	// =======================================================================
	// buildArgumentList
	// =======================================================================

	@Nested
	@DisplayName("buildArgumentList")
	class BuildArgumentList {

		@Test
		void nullArgs_producesEmptyString() throws Exception {
			assertEquals("", buildArgumentList(null));
		}

		@Test
		void blankArgs_producesEmptyString() throws Exception {
			assertEquals("", buildArgumentList("   "));
		}

		@Test
		void emptyJsonArray_producesEmptyString() throws Exception {
			assertEquals("", buildArgumentList("[]"));
		}

		@Test
		void mixedScalars_areReserializedAsJsLiterals() throws Exception {
			assertEquals("1, \"two\", true", buildArgumentList("[1, \"two\", true]"));
		}

		@Test
		void nullElement_isRenderedAsJsNull() throws Exception {
			assertEquals("null, 5", buildArgumentList("[null, 5]"));
		}

		@Test
		void nestedArrayAndObject_arePreservedAsLiterals() throws Exception {
			String result = buildArgumentList("[[1,2], {\"a\":1}]");
			assertEquals("[1,2], {\"a\":1}", result);
		}

		@Test
		void singleElement_hasNoTrailingComma() throws Exception {
			assertEquals("42", buildArgumentList("[42]"));
		}

		@Test
		void malformedJson_throwsIllegalArgumentWithHelpfulMessage() {
			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> buildArgumentList("[1, 2"));
			assertTrue(ex.getMessage().contains("valid JSON array"),
					"message should point the caller at the required JSON array shape: " + ex.getMessage());
		}

		@Test
		void jsonObjectInsteadOfArray_isRejected() {
			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> buildArgumentList("{\"a\":1}"));
			assertTrue(ex.getMessage().contains("JSON array"),
					"a JSON object is not a valid argument list: " + ex.getMessage());
		}

		@Test
		void jsonScalarInsteadOfArray_isRejected() {
			assertThrows(IllegalArgumentException.class, () -> buildArgumentList("42"));
		}
	}

	// =======================================================================
	// buildScript
	// =======================================================================

	@Nested
	@DisplayName("buildScript")
	class BuildScript {

		@Test
		void scriptOnly_isReturnedVerbatim() throws Exception {
			String script = "application.output('hi'); forms.f.refresh()";
			assertEquals(script, buildScript(script, null, null));
		}

		@Test
		void methodOnly_noArgs_becomesEmptyParenCall() throws Exception {
			assertEquals("forms.customers.recalcTotals()", buildScript(null, "forms.customers.recalcTotals", null));
		}

		@Test
		void methodOnly_withArgs_becomesCallWithArgList() throws Exception {
			assertEquals("scopes.globals.doThing(1, \"x\")", buildScript(null, "scopes.globals.doThing", "[1, \"x\"]"));
		}

		@Test
		void methodName_isTrimmed() throws Exception {
			assertEquals("scopes.globals.f()", buildScript(null, "  scopes.globals.f  ", null));
		}

		@Test
		@DisplayName("both supplied: documented order is script first, then methodName(args) joined by ';\\n'")
		void scriptAndMethod_scriptFirstThenMethodCall() throws Exception {
			String result = buildScript("application.output('before')", "forms.f.go", "[true]");
			assertEquals("application.output('before');\nforms.f.go(true)", result);
		}

		@Test
		void blankScriptWithMethod_omitsLeadingSeparator() throws Exception {
			// a blank script must not produce a stray leading ";\n"
			assertEquals("forms.f.go()", buildScript("   ", "forms.f.go", null));
		}

		@Test
		void bothBlank_producesEmptyString() throws Exception {
			assertEquals("", buildScript("  ", "  ", null));
		}

		@Test
		void malformedArgsJson_propagatesIllegalArgument() {
			assertThrows(IllegalArgumentException.class, () -> buildScript(null, "forms.f.go", "[oops"));
		}
	}

	// =======================================================================
	// serializeResult - scalars, null, Wrapper unwrap, NOT_FOUND/Undefined
	// =======================================================================

	@Nested
	@DisplayName("serializeResult")
	class SerializeResult {

		@Test
		void javaNull_isTheNullMarker() throws Exception {
			assertEquals(NULL_MARKER, serializeResult(null));
		}

		@ParameterizedTest
		@CsvSource({ "'hello','hello'", "'42','42'", "'true','true'" })
		void charSequences_areReturnedAsIs(String in, String expected) throws Exception {
			assertEquals(expected, serializeResult(in));
		}

		@Test
		void number_isReturnedAsToString() throws Exception {
			assertEquals("42", serializeResult(Integer.valueOf(42)));
			assertEquals("3.5", serializeResult(Double.valueOf(3.5)));
		}

		@Test
		void booleanValue_isReturnedAsToString() throws Exception {
			assertEquals("false", serializeResult(Boolean.FALSE));
		}

		@Test
		void arbitraryObject_fallsBackToStringValueOf() throws Exception {
			Object obj = new Object() {
				@Override
				public String toString() {
					return "custom-toString";
				}
			};
			assertEquals("custom-toString", serializeResult(obj));
		}

		@Test
		void objectWithThrowingToString_isReportedNotRethrown() throws Exception {
			Object hostile = new Object() {
				@Override
				public String toString() {
					throw new RuntimeException("boom");
				}
			};
			String result = serializeResult(hostile);
			assertTrue(result.contains("could not be converted to string"),
					"a throwing toString() must be reported, not propagated: " + result);
			assertTrue(result.contains("boom"), "the underlying failure message should be surfaced: " + result);
		}

		@Test
		void nativeArray_isRenderedAsJsArray_notAnOpaqueJavaReference() throws Exception {
			Context cx = Context.enter();
			try {
				Scriptable scope = cx.initStandardObjects();
				Object array = cx.newArray(scope, new Object[] { Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3) });
				String result = serializeResult(array);
				assertEquals("[1,2,3]", result,
						"a NativeArray must render as its JS form, not a '[Ljava...' reference: " + result);
			} finally {
				Context.exit();
			}
		}

		@Test
		void objectArray_isRenderedAsJsArray_notAnOpaqueJavaReference() throws Exception {
			Object[] array = new Object[] { Integer.valueOf(5), Integer.valueOf(4), Integer.valueOf(5) };
			String result = serializeResult(array);
			assertFalse(result.startsWith("[Ljava"),
					"an Object[] must not be rendered as its Java toString reference: " + result);
			assertEquals("[5,4,5]", result, "an Object[] must render as a JS array: " + result);
		}
	}

	// -----------------------------------------------------------------------
	// The evaluate-path normalisation (NOT_FOUND / Undefined -> null) is a
	// property of the value the service stores. We assert it here by driving the
	// exact same normalisation the run Runnable does through serializeResult:
	// a normalised null must render as the null marker, whereas a genuine value
	// must not.
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("NOT_FOUND / Undefined normalisation")
	class Normalisation {

		private Object normalise(Object eval) {
			if (eval instanceof Wrapper wrapper) {
				eval = wrapper.unwrap();
			}
			if (eval == Scriptable.NOT_FOUND || eval == Undefined.instance) {
				eval = null;
			}
			return eval;
		}

		@Test
		void notFound_normalisesToNull() throws Exception {
			assertEquals(NULL_MARKER, serializeResult(normalise(Scriptable.NOT_FOUND)));
		}

		@Test
		void undefined_normalisesToNull() throws Exception {
			assertEquals(NULL_MARKER, serializeResult(normalise(Undefined.instance)));
		}

		@Test
		void wrapper_isUnwrappedBeforeSerialization() throws Exception {
			Wrapper wrapped = new Wrapper() {
				@Override
				public Object unwrap() {
					return "unwrapped-value";
				}
			};
			assertEquals("unwrapped-value", serializeResult(normalise(wrapped)));
		}

		@Test
		void realValue_isNotNormalisedAway() throws Exception {
			assertEquals("kept", serializeResult(normalise("kept")));
		}
	}

	// =======================================================================
	// findRhinoException + error formatting
	// =======================================================================

	@Nested
	@DisplayName("findRhinoException / error path")
	class ErrorPath {

		private EcmaError newReferenceError() {
			// evaluate a script that throws a ReferenceError so we get a real,
			// message-carrying RhinoException from the engine (not a hand-built stub).
			Context cx = Context.enter();
			try {
				Scriptable scope = cx.initStandardObjects();
				cx.evaluateString(scope, "thisIsNotDefined()", "unit-test", 1, null);
				throw new IllegalStateException("expected the script to throw");
			} catch (EcmaError e) {
				return e;
			} finally {
				Context.exit();
			}
		}

		@Test
		void findRhinoException_null_returnsNull() throws Exception {
			assertNull(findRhinoException(null));
		}

		@Test
		void findRhinoException_directRhino_returnsIt() throws Exception {
			RhinoException rhino = newReferenceError();
			assertSame(rhino, findRhinoException(rhino));
		}

		@Test
		void findRhinoException_wrappedInCauseChain_isFound() throws Exception {
			RhinoException rhino = newReferenceError();
			Exception wrapper = new RuntimeException("outer", new IllegalStateException("middle", rhino));
			assertSame(rhino, findRhinoException(wrapper));
		}

		@Test
		void findRhinoException_noRhinoInChain_returnsNull() throws Exception {
			Exception plain = new RuntimeException("outer", new IllegalStateException("inner"));
			assertNull(findRhinoException(plain));
		}

		@Test
		void findRhinoException_selfReferentialCause_terminates() throws Exception {
			@SuppressWarnings("serial")
			RuntimeException loop = new RuntimeException("loop") {
				@Override
				public synchronized Throwable getCause() {
					return this;
				}
			};
			assertNull(findRhinoException(loop));
		}

		@Test
		@DisplayName("a thrown JS error is serialized to a readable message via the RhinoException path")
		void serializeResult_rhinoError_prefersScriptMessage() throws Exception {
			RhinoException rhino = newReferenceError();
			String result = serializeResult(rhino);
			assertTrue(result.startsWith("Error: "), "error result must be prefixed with 'Error: ': " + result);
			assertTrue(result.contains("thisIsNotDefined") || result.contains("is not defined"),
					"the script-level ReferenceError message must be surfaced: " + result);
		}

		@Test
		@DisplayName("a non-Rhino Throwable falls back to root cause message + stack")
		void serializeResult_plainThrowable_usesRootCause() throws Exception {
			Exception ex = new RuntimeException("outer", new IllegalStateException("the real cause"));
			String result = serializeResult(ex);
			assertTrue(result.startsWith("Error: "), "must be prefixed with 'Error: ': " + result);
			assertTrue(result.contains("the real cause"),
					"the deepest cause message must be surfaced, not the wrapper: " + result);
			assertFalse(result.contains("Error: outer"),
					"the wrapper message must not be used when a deeper cause exists: " + result);
		}

		@Test
		void serializeResult_throwableWithNullMessage_usesSimpleName() throws Exception {
			Exception ex = new IllegalStateException((String) null);
			String result = serializeResult(ex);
			assertTrue(result.contains("IllegalStateException"),
					"a null-message throwable should fall back to its class simple name: " + result);
		}
	}

	// =======================================================================
	// formatResult - the two-section markdown blob
	// =======================================================================

	@Nested
	@DisplayName("formatResult")
	class FormatResult {

		@Test
		void containsHeaderClientResultAndOutputSections() throws Exception {
			String out = formatResult("DebugNGClient (solution: mysol)", "5", "hello\nworld");
			assertTrue(out.contains("**servoy-debug: executeInRunningClient**"), "header missing: " + out);
			assertTrue(out.contains("Client: DebugNGClient (solution: mysol)"), "client line missing: " + out);
			assertTrue(out.contains("Result:\n5"), "result section missing: " + out);
			assertTrue(out.contains("Console output:\nhello\nworld"), "output section missing: " + out);
		}

		@Test
		void blankOutput_rendersNoOutputMarker() throws Exception {
			String out = formatResult("c", "5", "   ");
			assertTrue(out.contains("Console output:\n(no output)"),
					"blank output must render the '(no output)' marker: " + out);
		}

		@Test
		void nullOutput_rendersNoOutputMarker() throws Exception {
			String out = formatResult("c", "5", null);
			assertTrue(out.contains("(no output)"), "null output must render the '(no output)' marker: " + out);
		}

		@Test
		void blankResult_rendersNullMarker() throws Exception {
			String out = formatResult("c", "   ", "some output");
			assertTrue(out.contains("Result:\n" + NULL_MARKER), "a blank result must render the null marker: " + out);
		}

		@Test
		void nullClient_rendersUnknown() throws Exception {
			String out = formatResult(null, "5", "out");
			assertTrue(out.contains("Client: unknown"), "a null client must render 'unknown': " + out);
		}

		@Test
		void trailingWhitespaceInOutput_isStripped() throws Exception {
			String out = formatResult("c", "5", "line\n\n");
			assertTrue(out.endsWith("line"), "trailing blank lines in output should be stripped: [" + out + "]");
		}
	}

	// =======================================================================
	// bareIdentifierHint - the form-context teaching (ported from evaluate)
	// =======================================================================

	@Nested
	@DisplayName("bareIdentifierHint")
	class BareIdentifierHint {

		@Test
		void nullMessage_returnsNull() throws Exception {
			assertNull(bareIdentifierHint(null));
		}

		@Test
		void nonReferenceError_returnsNull() throws Exception {
			assertNull(bareIdentifierHint("TypeError: cannot read property x of undefined"));
		}

		@Test
		void bareFoundsetInReferenceError_returnsHint() throws Exception {
			String hint = bareIdentifierHint("ReferenceError: \"foundset\" is not defined");
			assertNotNull(hint, "a bare foundset reference error should be enriched");
			assertTrue(hint.contains("forms.<formName>.foundset"),
					"the hint should teach the qualified rewrite: " + hint);
		}

		@Test
		void bareControllerInReferenceError_returnsHint() throws Exception {
			assertNotNull(bareIdentifierHint("controller is not defined"));
		}

		@Test
		void qualifiedFoundset_isNotFlagged() throws Exception {
			// forms.customers.foundset is already qualified: a '.foundset' must not trigger the hint.
			assertNull(bareIdentifierHint("forms.customers.foundset is not defined"));
		}

		@Test
		void identifierAsSubstringOfAnotherWord_isNotFlagged() throws Exception {
			// "elementsCount" contains "elements" but is a different identifier.
			assertNull(bareIdentifierHint("elementsCount is not defined"));
		}
	}

	// =======================================================================
	// applyValueCap - size cap + temp-file spill (ported from evaluate)
	// =======================================================================

	@Nested
	@DisplayName("applyValueCap")
	class ApplyValueCap {

		@Test
		void nullValue_isReturnedUnchanged_noSpill() throws Exception {
			StringBuilder valueFile = new StringBuilder();
			assertNull(applyValueCap(null, valueFile));
			assertEquals(0, valueFile.length(), "null must not spill a file");
		}

		@Test
		void smallValue_isReturnedUnchanged_noSpill() throws Exception {
			StringBuilder valueFile = new StringBuilder();
			String result = applyValueCap("small", valueFile);
			assertEquals("small", result);
			assertEquals(0, valueFile.length(), "an under-cap value must not spill a file");
		}

		@Test
		void overCapValue_isTruncatedAndSpilled() throws Exception {
			StringBuilder valueFile = new StringBuilder();
			String big = "x".repeat(9000);
			String result = applyValueCap(big, valueFile);
			assertTrue(result.length() < big.length(), "an over-cap value must be truncated: " + result.length());
			assertTrue(result.contains("truncated, 9000 chars"),
					"the truncation notice should report the full length: " + result);
			assertTrue(valueFile.length() > 0, "an over-cap value must spill to a temp file");
			assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(valueFile.toString())),
					"the spilled temp file should exist");
			java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(valueFile.toString()));
		}
	}

	// =======================================================================
	// execute() guard messages reachable without a live client
	// =======================================================================

	@Nested
	@DisplayName("execute() guards")
	class ExecuteGuards {

		@Test
		void neitherScriptNorMethod_returnsRequiredMessage() {
			String result = service.execute(null, null, null, 30);
			assertTrue(result.contains("at least one of 'script' or 'methodName' is required"),
					"empty request must be rejected with the documented message: " + result);
		}

		@Test
		void bothBlank_returnsRequiredMessage() {
			String result = service.execute("   ", "  ", null, 30);
			assertTrue(result.contains("at least one of 'script' or 'methodName' is required"),
					"all-blank request must be rejected: " + result);
		}

		@Test
		@DisplayName("with a valid request but no debug environment, a clear 'no running client' style message is returned, never a crash")
		void validRequestNoClient_returnsActionableMessage() {
			// In the plain-JUnit environment there is no ApplicationServerRegistry /
			// debug-ready client, so execute() must fall through to one of its
			// actionable, non-throwing guard messages rather than raising.
			String result = service.execute("application.output('x')", null, null, 5);
			assertNotNull(result, "execute must never return null");
			assertTrue(
					result.contains("No running Servoy client")
							|| result.contains("debug environment is not available"),
					"without a client, execute must return an actionable guard message: " + result);
			assertFalse(result.contains("Timed out"),
					"a missing client must NOT be misreported as a timeout: " + result);
		}
	}
}
