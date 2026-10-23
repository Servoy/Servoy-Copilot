package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.eclipse.cypress.services.FormSpecModel.Effect;
import com.servoy.eclipse.cypress.services.FormSpecModel.EffectKind;
import com.servoy.eclipse.developer.mcp.services.HandlerEffectRecognizer;
import com.servoy.eclipse.developer.mcp.services.HandlerEffectRecognizer.Result;

class HandlerEffectRecognizerTest {
	private static final String OUTER = "com.servoy.eclipse.developer.mcp.plainjunit.services.HandlerEffectRecognizerTest";

	private static Result analyzeBody(String body) {
		return HandlerEffectRecognizer.analyze("function handler(event) {\n" + body + "\n}");
	}

	private static Effect offForm(String source) {
		return new Effect(EffectKind.OFF_FORM, null, source);
	}

	private static Effect visible(String target, boolean value) {
		return new Effect(EffectKind.VISIBLE, target, Boolean.toString(value));
	}

	static Stream<Arguments> singleEffectRows() {
		return Stream.of(Arguments.of("elements.detail.visible = false;", visible("detail", false)),
				Arguments.of("elements.detail.visible = true;", visible("detail", true)),
				Arguments.of("elements . x . visible = false ;", visible("x", false)),
				Arguments.of("elements.x.enabled = false;", new Effect(EffectKind.ENABLED, "x", "false")),
				Arguments.of("elements.x.enabled = true;", new Effect(EffectKind.ENABLED, "x", "true")),
				Arguments.of("elements.x.text = 'Hi';", new Effect(EffectKind.TEXT, "x", "Hi")),
				Arguments.of("elements.x.text = \"Hi\";", new Effect(EffectKind.TEXT, "x", "Hi")),
				Arguments.of("elements.x.text = 'http://a.b/c';", new Effect(EffectKind.TEXT, "x", "http://a.b/c")),
				Arguments.of("elements.x.text = 'a;b';", new Effect(EffectKind.TEXT, "x", "a;b")),
				Arguments.of("elements.x.addStyleClass('active');",
						new Effect(EffectKind.ADD_STYLE_CLASS, "x", "active")),
				Arguments.of("elements.x.addStyleClass(\"active\");",
						new Effect(EffectKind.ADD_STYLE_CLASS, "x", "active")),
				Arguments.of("elements.x.removeStyleClass('active');",
						new Effect(EffectKind.REMOVE_STYLE_CLASS, "x", "active")),
				Arguments.of("elements.x.removeStyleClass('a-b_1');",
						new Effect(EffectKind.REMOVE_STYLE_CLASS, "x", "a-b_1")),
				Arguments.of("elements.x.text = 'a' + b;", offForm("elements.x.text = 'a' + b")),
				Arguments.of("elements.x.addStyleClass('a b');", offForm("elements.x.addStyleClass('a b')")),
				Arguments.of("elements.x.removeStyleClass('a b');", offForm("elements.x.removeStyleClass('a b')")),
				Arguments.of("elements.x.text = '';", offForm("elements.x.text = ''")),
				Arguments.of("elements.x.text = \"\";", offForm("elements.x.text = \"\"")),
				Arguments.of("elements.x.text = '   ';", offForm("elements.x.text = ' '")),
				Arguments.of("elements.x.text = '<b>Hi</b>';", offForm("elements.x.text = '<b>Hi</b>'")),
				Arguments.of("elements.x.text = 'a & b';", offForm("elements.x.text = 'a & b'")),
				Arguments.of("elements.x.text = '1 > 0';", offForm("elements.x.text = '1 > 0'")),
				Arguments.of("elements.x.text = 'It\\'s';", offForm("elements.x.text = 'It\\'s'")),
				Arguments.of("elements.x.visible = flag;", offForm("elements.x.visible = flag")),
				Arguments.of("elements.x.bgcolor == 'a';", offForm("elements.x.bgcolor == 'a'")),
				Arguments.of("scopes.orders.load(id);", offForm("scopes.orders.load(id)")),
				Arguments.of("forms.other.show();", offForm("forms.other.show()")),
				Arguments.of("var a = 1;", offForm("var a = 1")),
				Arguments.of("return scopes.x.check(v);", offForm("return scopes.x.check(v)")));
	}

	@Nested
	class Statements {
		@ParameterizedTest
		@MethodSource(OUTER + "#singleEffectRows")
		@DisplayName("a single statement yields exactly one effect and is not branching")
		void singleStatement(String statement, Effect expected) {
			Result result = analyzeBody(statement);

			assertAll(() -> assertTrue(result.analyzable()), () -> assertFalse(result.branching()),
					() -> assertEquals(List.of(expected), result.effects()));
		}

		@Test
		@DisplayName("a mixed body yields exactly two effects in source order")
		void mixedBody() {
			Result result = analyzeBody("elements.detail.visible = false; scopes.orders.load(id);");

			assertEquals(List.of(visible("detail", false), offForm("scopes.orders.load(id)")), result.effects());
		}

		@Test
		@DisplayName("sources hold the collapsed statement of every effect, in the same order")
		void sources_alignWithEffects() {
			Result result = analyzeBody("elements.a.visible = false;\n\tscopes.x.load(\n\t\t1,\n\t\t2\n\t);");

			assertEquals(List.of("elements.a.visible = false", "scopes.x.load( 1, 2 )"), result.sources());
		}

		@Test
		@DisplayName("an empty body yields no effects")
		void emptyBody() {
			Result result = HandlerEffectRecognizer.analyze("function f() {}");

			assertAll(() -> assertTrue(result.analyzable()), () -> assertFalse(result.branching()),
					() -> assertEquals(List.of(), result.effects()));
		}

		@Test
		@DisplayName("a statement longer than 160 characters is collapsed to 157 characters plus ...")
		void longStatement_isTruncated() {
			Result result = analyzeBody("scopes.a.b(" + "x".repeat(300) + ");");

			assertEquals(List.of(offForm("scopes.a.b(" + "x".repeat(146) + "...")), result.effects());
		}

		@Test
		@DisplayName("a statement without a trailing semicolon merges with the next one into a single OFF_FORM")
		void missingSemicolon_mergesStatements() {
			Result result = analyzeBody("scopes.a.b()\nscopes.c.d();");

			assertEquals(List.of(offForm("scopes.a.b() scopes.c.d()")), result.effects());
		}
	}

	@Nested
	class DroppedStyleAssignments {
		@ParameterizedTest
		@ValueSource(strings = { "bgcolor", "fgcolor", "font", "border" })
		@DisplayName("an assignment to a style property yields no effect")
		void styleProperty_isDropped(String property) {
			assertEquals(List.of(), analyzeBody("elements.x." + property + " = '#fff';").effects());
		}

		@ParameterizedTest
		@ValueSource(strings = { "elements.x.bgcolor += 'a';", "elements['x'].border = 'b';",
				"elements[\"x\"].fgcolor = 'b';", "forms.f.elements.x.font = 'c';",
				"forms.f.elements['x'].border = 'c';", "elements.x.font = 'bold ' + size;",
				"elements.x.border = 'a;b';" })
		@DisplayName("compound, bracket and form qualified assignments to a style property yield no effect")
		void styleVariants_areDropped(String statement) {
			assertEquals(List.of(), analyzeBody(statement).effects());
		}

		@Test
		@DisplayName("a dropped assignment does not hide the effects around it")
		void droppedBetweenEffects() {
			Result result = analyzeBody("elements.x.bgcolor = '#fff'; elements.a.visible = false;");

			assertEquals(List.of(visible("a", false)), result.effects());
		}

		@Test
		@DisplayName("a dropped assignment without a semicolon does not swallow the next statement")
		void droppedWithoutSemicolon_doesNotSwallowNext() {
			Result result = analyzeBody("elements.x.bgcolor = '#fff'\nscopes.a.b();");

			assertEquals(List.of(offForm("scopes.a.b()")), result.effects());
		}

		@Test
		@DisplayName("several dropped assignments without semicolons are all dropped and the following call is kept")
		void severalDroppedWithoutSemicolon() {
			Result result = analyzeBody("elements.x.bgcolor = 'a'\nelements.x.font = 'b'\nscopes.a.b();");

			assertEquals(List.of(offForm("scopes.a.b()")), result.effects());
		}
	}

	@Nested
	class Returns {
		@ParameterizedTest
		@ValueSource(strings = { "return;", "return true;", "return false;", "return null;", "return undefined;",
				"return 1;", "return -1;", "return 1.5;", "return 'x';", "return \"x\";" })
		@DisplayName("a final return of nothing or of a literal has no effect and is not branching")
		void finalLiteralReturn_isDropped(String statement) {
			Result result = analyzeBody(statement);

			assertAll(() -> assertEquals(List.of(), result.effects()), () -> assertFalse(result.branching()));
		}

		@Test
		@DisplayName("a final literal return after an effect keeps the effect and is not branching")
		void finalReturnAfterEffect() {
			Result result = analyzeBody("elements.a.visible = false;\nreturn true;");

			assertAll(() -> assertEquals(List.of(visible("a", false)), result.effects()),
					() -> assertFalse(result.branching()));
		}

		@Test
		@DisplayName("a final return of a call is an OFF_FORM and is not branching")
		void finalCallReturn() {
			Result result = analyzeBody("return scopes.x.check(v);");

			assertAll(() -> assertEquals(List.of(offForm("return scopes.x.check(v)")), result.effects()),
					() -> assertFalse(result.branching()));
		}

		@Test
		@DisplayName("a non-final return makes the handler branching")
		void nonFinalReturn_isBranching() {
			assertTrue(analyzeBody("return true;\nelements.a.visible = false;").branching());
		}
	}

	@Nested
	class Collapsing {
		@Test
		@DisplayName("the same target set twice keeps only the last statement")
		void sameTargetTwice_keepsLast() {
			Result result = analyzeBody("elements.a.visible = false; scopes.x.y(); elements.a.visible = true;");

			assertEquals(List.of(offForm("scopes.x.y()"), visible("a", true)), result.effects());
		}

		@Test
		@DisplayName("different families on the same target are both kept")
		void differentFamilies_bothKept() {
			Result result = analyzeBody("elements.a.visible = false; elements.a.enabled = false;");

			assertEquals(List.of(visible("a", false), new Effect(EffectKind.ENABLED, "a", "false")), result.effects());
		}

		@Test
		@DisplayName("the same family on different targets is kept for both")
		void differentTargets_bothKept() {
			Result result = analyzeBody("elements.a.visible = false; elements.b.visible = false;");

			assertEquals(List.of(visible("a", false), visible("b", false)), result.effects());
		}

		@Test
		@DisplayName("adding and then removing the same class keeps only the removal")
		void addThenRemoveSameClass() {
			Result result = analyzeBody("elements.a.addStyleClass('on'); elements.a.removeStyleClass('on');");

			assertEquals(List.of(new Effect(EffectKind.REMOVE_STYLE_CLASS, "a", "on")), result.effects());
		}

		@Test
		@DisplayName("different class names on the same target are both kept")
		void differentClasses_bothKept() {
			Result result = analyzeBody("elements.a.addStyleClass('one'); elements.a.addStyleClass('two');");

			assertEquals(List.of(new Effect(EffectKind.ADD_STYLE_CLASS, "a", "one"),
					new Effect(EffectKind.ADD_STYLE_CLASS, "a", "two")), result.effects());
		}

		@Test
		@DisplayName("identical off-form statements are never collapsed")
		void offForm_isNotCollapsed() {
			Result result = analyzeBody("scopes.x.y(); scopes.x.y();");

			assertEquals(List.of(offForm("scopes.x.y()"), offForm("scopes.x.y()")), result.effects());
		}

		@Test
		@DisplayName("an off-form text clear does not supersede a real text effect on the same target")
		void textClear_doesNotCollapseWithText() {
			Result result = analyzeBody("elements.a.text = 'Hi'; elements.a.text = '';");

			assertEquals(List.of(new Effect(EffectKind.TEXT, "a", "Hi"), offForm("elements.a.text = ''")),
					result.effects());
		}
	}

	@Nested
	class CommentsAndStrings {
		@Test
		@DisplayName("comments and JSDoc around the function and inside the body are ignored")
		void commentsAreIgnored() {
			String declaration = "/**\n * Hides. elements.doc.visible = false;\n * @param {JSEvent} event\n * @properties={typeid:24,uuid:\"x\"}\n */\n"
					+ "function hide(event) {\n\t// elements.a.visible = false;\n\t/* elements.b.visible = false; */\n"
					+ "\telements.c.visible = false; // trailing elements.d.visible = false;\n}";

			assertEquals(List.of(visible("c", false)), HandlerEffectRecognizer.analyze(declaration).effects());
		}

		@Test
		@DisplayName("comment markers inside a string literal are not comments")
		void commentMarkersInString() {
			assertEquals(List.of(new Effect(EffectKind.TEXT, "x", "a // b /* c */")),
					analyzeBody("elements.x.text = 'a // b /* c */';").effects());
		}

		@Test
		@DisplayName("a semicolon inside a string literal does not split the statement")
		void semicolonInString() {
			assertEquals(List.of(offForm("scopes.log.write('a;b', 1)")),
					analyzeBody("scopes.log.write('a;b', 1);").effects());
		}

		@Test
		@DisplayName("a semicolon inside parentheses does not split the statement")
		void semicolonInParentheses() {
			Result result = analyzeBody("for (var i = 0; i < 3; i++) { scopes.x.y(i); }");

			assertAll(() -> assertTrue(result.branching()),
					() -> assertEquals(List.of(offForm("for (var i = 0; i < 3; i++) { scopes.x.y(i); }")),
							result.effects()));
		}
	}

	@Nested
	class Branching {
		@ParameterizedTest
		@ValueSource(strings = { "if (a) { elements.x.visible = false; }", "if (a) { x(); } else { y(); }",
				"switch (a) { default: x(); }", "var a = b ? 1 : 2;", "for (var i = 0; i < 3; i++) { x(); }",
				"while (a) { x(); }", "do { x(); } while (a);", "try { x(); } catch (e) { y(); }",
				"return true;\nscopes.x.y();" })
		@DisplayName("conditional, looping and early-return code is branching")
		void branching(String body) {
			assertTrue(analyzeBody(body).branching());
		}

		@ParameterizedTest
		@ValueSource(strings = { "scopes.x.y();", "return x;", "var format = doIt(); scopes.x.try(1);",
				"elements.x.text = 'if for while';", "var a = b?.c;", "var a = b ?? c;",
				"elements.x.visible = false;\nelements.y.enabled = true;" })
		@DisplayName("plain statements, a single final return, keywords inside identifiers or strings and optional chaining are not branching")
		void notBranching(String body) {
			assertFalse(analyzeBody(body).branching());
		}
	}

	@Nested
	class NotAnalyzable {
		static final String NOTE = "method body could not be analyzed";

		private void assertNotAnalyzable(Result result) {
			assertAll(() -> assertFalse(result.analyzable()), () -> assertFalse(result.branching()),
					() -> assertEquals(List.of(offForm(NOTE)), result.effects()),
					() -> assertEquals(List.of(NOTE), result.sources()));
		}

		@Test
		@DisplayName("a null declaration is not analysable")
		void nullDeclaration() {
			assertNotAnalyzable(HandlerEffectRecognizer.analyze(null));
		}

		@Test
		@DisplayName("the NOT_ANALYZABLE constant is the documented note")
		void constant() {
			assertEquals(NOTE, HandlerEffectRecognizer.NOT_ANALYZABLE);
		}

		@Test
		@DisplayName("unbalanced braces are not analysable")
		void unbalancedBraces() {
			assertNotAnalyzable(HandlerEffectRecognizer.analyze("function f() { elements.a.visible = false;"));
		}

		@Test
		@DisplayName("a missing function header is not analysable")
		void missingFunctionHeader() {
			assertNotAnalyzable(HandlerEffectRecognizer.analyze("elements.a.visible = false;"));
		}

		@Test
		@DisplayName("a function header without a body is not analysable")
		void headerWithoutBody() {
			assertNotAnalyzable(HandlerEffectRecognizer.analyze("function f()"));
		}

		@Test
		@DisplayName("an unterminated string is not analysable")
		void unterminatedString() {
			assertNotAnalyzable(HandlerEffectRecognizer.analyze("function f() { var s = 'abc; }"));
		}

		@Test
		@DisplayName("an unterminated block comment is not analysable")
		void unterminatedComment() {
			assertNotAnalyzable(HandlerEffectRecognizer.analyze("function f() { /* elements.a.visible = false; }"));
		}
	}
}
