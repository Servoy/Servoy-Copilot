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
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormLayoutInspectionService;

/**
 * Plain-JUnit unit tests for the <em>pure</em> helper logic of
 * {@link FormLayoutInspectionService}: the public-static Tomcat error-page
 * message extractor ({@code extractErrorMessage}), the package-static viewport
 * validator ({@code validateViewport}), the package-static jsoup HTML
 * pretty-printer ({@code prettyPrintHtml}) and the private instance JS
 * string-literal escaper ({@code jsStringLiteral}).
 * <p>
 * All four take their input as plain parameters and have no browser/SWT/Display
 * dependency, so <b>no production seam is needed and no production code is
 * changed</b> and no mock is required. Everything else in the service
 * (rendering, screenshot capture, HTTP probing, FS spills) needs a real
 * embedded browser / workbench and stays in the integrationpde suite.
 * </p>
 * <p>
 * The test lives in a different package than the production class
 * ({@code ...plainjunit.services} vs {@code ...services}), so the package-visible
 * statics and the private instance method are reached by reflection
 * ({@code setAccessible}); the public static {@code extractErrorMessage} is
 * called directly. This mirrors the {@code FileStructureServiceTest} style
 * (pure helpers + reflection, zero production change).
 * </p>
 *
 * @since 2026.06
 */
class FormLayoutInspectionServiceTest
{
	private static final int MIN_VIEWPORT_SIZE = readIntConstant("MIN_VIEWPORT_SIZE");
	private static final int MAX_VIEWPORT_SIZE = readIntConstant("MAX_VIEWPORT_SIZE");

	private static int readIntConstant(String name)
	{
		try
		{
			Field f = FormLayoutInspectionService.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(null);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("could not read constant " + name, e);
		}
	}

	private static String validateViewport(String paramName, Integer value) throws Exception
	{
		Method m = FormLayoutInspectionService.class.getDeclaredMethod("validateViewport", String.class, Integer.class);
		m.setAccessible(true);
		return (String)m.invoke(null, paramName, value);
	}

	private static String prettyPrintHtml(String html) throws Exception
	{
		Method m = FormLayoutInspectionService.class.getDeclaredMethod("prettyPrintHtml", String.class);
		m.setAccessible(true);
		return (String)m.invoke(null, html);
	}

	private static String jsStringLiteral(String value) throws Exception
	{
		Method m = FormLayoutInspectionService.class.getDeclaredMethod("jsStringLiteral", String.class);
		m.setAccessible(true);
		return (String)m.invoke(new FormLayoutInspectionService(), value);
	}

	@Nested
	class ExtractErrorMessage
	{
		@Test
		@DisplayName("a real Tomcat-style error page yields the extracted, whitespace-normalised message")
		void tomcatErrorPage()
		{
			String body = "<html><head><title>HTTP Status 404</title></head><body>" +
				"<h1>HTTP Status 404 \u2013 Not Found</h1>" +
				"<p><b>Message</b> Form 'customers' not found</p>" +
				"<p><b>Description</b> The origin server did not find a current representation...</p>" +
				"</body></html>";
			assertEquals("Form 'customers' not found", FormLayoutInspectionService.extractErrorMessage(body));
		}

		@Test
		@DisplayName("multi-line / collapsed whitespace in the message is normalised to single spaces")
		void whitespaceCollapsed()
		{
			String body = "<p><b>Message</b>   render   failed\n   badly   </p>";
			assertEquals("render failed badly", FormLayoutInspectionService.extractErrorMessage(body));
		}

		@Test
		@DisplayName("HTML entities in the message are decoded back to their characters")
		void entityDecoding()
		{
			String body = "<p><b>Message</b> a &lt;b&gt; &amp; &quot;c&quot; &#39;d&#39;</p>";
			assertEquals("a <b> & \"c\" 'd'", FormLayoutInspectionService.extractErrorMessage(body));
		}

		@Test
		@DisplayName("a body that is not a Tomcat error page yields an empty string")
		void notAnErrorPage()
		{
			assertEquals("", FormLayoutInspectionService.extractErrorMessage("<html><body>all good</body></html>"));
		}

		@Test
		@DisplayName("null and blank bodies yield an empty string (never null, never an exception)")
		void nullAndBlank()
		{
			assertEquals("", FormLayoutInspectionService.extractErrorMessage(null));
			assertEquals("", FormLayoutInspectionService.extractErrorMessage(""));
			assertEquals("", FormLayoutInspectionService.extractErrorMessage("   "));
		}
	}

	@Nested
	class ValidateViewport
	{
		@Test
		@DisplayName("a null value is accepted (no error)")
		void nullValueOk() throws Exception
		{
			assertNull(validateViewport("viewportWidth", null));
		}

		@Test
		@DisplayName("values at and within the inclusive MIN..MAX range are accepted")
		void inRangeOk() throws Exception
		{
			assertNull(validateViewport("viewportWidth", Integer.valueOf(MIN_VIEWPORT_SIZE)));
			assertNull(validateViewport("viewportWidth", Integer.valueOf(MAX_VIEWPORT_SIZE)));
			assertNull(validateViewport("viewportWidth", Integer.valueOf((MIN_VIEWPORT_SIZE + MAX_VIEWPORT_SIZE) / 2)));
		}

		@Test
		@DisplayName("a value below MIN is rejected with a message naming the parameter and the bounds")
		void belowMin() throws Exception
		{
			String msg = validateViewport("viewportWidth", Integer.valueOf(MIN_VIEWPORT_SIZE - 1));
			assertErrorMessage(msg, "viewportWidth", MIN_VIEWPORT_SIZE - 1);
		}

		@Test
		@DisplayName("a value above MAX is rejected with a message naming the parameter and the bounds")
		void aboveMax() throws Exception
		{
			String msg = validateViewport("viewportHeight", Integer.valueOf(MAX_VIEWPORT_SIZE + 1));
			assertErrorMessage(msg, "viewportHeight", MAX_VIEWPORT_SIZE + 1);
		}

		private void assertErrorMessage(String msg, String paramName, int value)
		{
			assertTrue(msg != null && !msg.isBlank(), "expected a non-blank error message");
			assertTrue(msg.contains(paramName), "message should name the parameter: " + msg);
			assertTrue(msg.contains(String.valueOf(MIN_VIEWPORT_SIZE)), "message should name MIN: " + msg);
			assertTrue(msg.contains(String.valueOf(MAX_VIEWPORT_SIZE)), "message should name MAX: " + msg);
			assertTrue(msg.contains(String.valueOf(value)), "message should name the offending value: " + msg);
		}
	}

	@Nested
	class PrettyPrintHtml
	{
		@Test
		@DisplayName("a single-line nested subtree becomes multi-line, indented, one element per line")
		void nestedBecomesMultiLine() throws Exception
		{
			String out = prettyPrintHtml("<div><span>x</span></div>");
			assertTrue(out.indexOf('\n') >= 0, "output should span multiple lines: " + out);
			assertTrue(out.contains("<div>"), "div preserved: " + out);
			assertTrue(out.contains("<span>"), "span preserved: " + out);
			assertTrue(out.contains("x"), "text preserved: " + out);
			// the span should be indented deeper than the div (nesting reflected as indentation)
			int divIndent = leadingSpaces(lineContaining(out, "<div>"));
			int spanIndent = leadingSpaces(lineContaining(out, "<span>"));
			assertTrue(spanIndent > divIndent, "span should be indented deeper than div: " + out);
		}

		@Test
		@DisplayName("identity attributes and text are preserved and not re-encoded")
		void attributesPreserved() throws Exception
		{
			String out = prettyPrintHtml("<div id=\"f_customers\" data-svy-name=\"lbl\" class=\"svy-form a\">hello</div>");
			assertTrue(out.contains("id=\"f_customers\""), "id preserved: " + out);
			assertTrue(out.contains("data-svy-name=\"lbl\""), "data-* preserved: " + out);
			assertTrue(out.contains("class=\"svy-form a\""), "class preserved: " + out);
			assertTrue(out.contains("hello"), "text preserved: " + out);
		}

		@Test
		@DisplayName("null yields an empty string and blank is returned unchanged (defensive)")
		void nullAndBlank() throws Exception
		{
			assertEquals("", prettyPrintHtml(null));
			assertEquals("   ", prettyPrintHtml("   "));
			assertEquals("", prettyPrintHtml(""));
		}

		private static String lineContaining(String text, String needle)
		{
			for (String line : text.split("\n", -1))
			{
				if (line.contains(needle)) return line;
			}
			throw new AssertionError("no line contains '" + needle + "' in:\n" + text);
		}

		private static int leadingSpaces(String line)
		{
			int n = 0;
			while (n < line.length() && line.charAt(n) == ' ') n++;
			return n;
		}
	}

	@Nested
	class JsStringLiteral
	{
		@Test
		@DisplayName("a plain value becomes a double-quoted JS string literal")
		void plainValue() throws Exception
		{
			assertEquals("\"customers\"", jsStringLiteral("customers"));
		}

		@Test
		@DisplayName("embedded double quotes are escaped so the value cannot break out of the literal")
		void quotesEscaped() throws Exception
		{
			// input: a"); alert(1); //   -> every double quote must be escaped (\")
			String out = jsStringLiteral("a\"); alert(1); //");
			assertTrue(out.startsWith("\"") && out.endsWith("\""), "must be a quoted literal: " + out);
			// no raw (unescaped) double quote inside the literal body
			String body = out.substring(1, out.length() - 1);
			assertNoRawQuote(body);
			assertTrue(out.contains("\\\""), "the embedded quote must be backslash-escaped: " + out);
		}

		@Test
		@DisplayName("a backslash is escaped so it cannot start an escape sequence that breaks the literal")
		void backslashEscaped() throws Exception
		{
			String out = jsStringLiteral("a\\b");
			// JSON encodes a single backslash as two backslashes
			assertEquals("\"a\\\\b\"", out);
		}

		@Test
		@DisplayName("null is encoded as an empty literal, not the JS keyword null")
		void nullBecomesEmptyLiteral() throws Exception
		{
			assertEquals("\"\"", jsStringLiteral(null));
		}

		/** fails if the body contains a double quote that is not preceded by a backslash */
		private static void assertNoRawQuote(String body)
		{
			for (int i = 0; i < body.length(); i++)
			{
				if (body.charAt(i) == '"')
				{
					boolean escaped = i > 0 && body.charAt(i - 1) == '\\';
					assertFalse(!escaped, "found an unescaped double quote at index " + i + " in: " + body);
				}
			}
		}
	}
}
