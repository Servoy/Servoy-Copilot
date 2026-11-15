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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.dltk.core.IModelElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FileStructureService;
import com.servoy.eclipse.developer.mcp.services.FileStructureService.FileStructure;
import com.servoy.eclipse.developer.mcp.services.FileStructureService.SymbolInfo;

/**
 * Plain-JUnit unit tests for {@link FileStructureService}'s <em>pure</em>
 * logic - the two nested records' formatting ({@link SymbolInfo#isFunction()},
 * {@link SymbolInfo#toFormattedString()}, {@link FileStructure#toFormattedString()}),
 * the public {@link FileStructureService#findSymbol(FileStructure, String)}
 * list search, and the private-static {@code calculateLineNumber(String,int)}
 * reached by reflection.
 * <p>
 * Every piece under test takes its inputs as parameters (the records are
 * directly constructable, {@code findSymbol} operates on a given structure, and
 * {@code calculateLineNumber} is a pure function over a string), so <b>no
 * production seam is needed and no production code is changed</b> and no mock is
 * required. The DLTK entry point {@link FileStructureService#analyzeFile} needs
 * a real {@code ISourceModule} and stays for the integration tests.
 * </p>
 * <p>
 * The private static helper is reached by reflection, mirroring the pilot
 * {@code CodeContextServiceTest} / {@code CodeAnalysisServiceTest} style.
 * </p>
 *
 * @since 2026.06
 */
class FileStructureServiceTest {
	private static final FileStructureService SERVICE = new FileStructureService();

	/** A non-function elementType (anything other than {@link IModelElement#METHOD}). */
	private static final int FIELD = IModelElement.FIELD;

	private static SymbolInfo function(String name, String... params) {
		return new SymbolInfo(name, IModelElement.METHOD, 0, 10, 1, params);
	}

	@Nested
	class SymbolInfoRecord {
		@Test
		@DisplayName("isFunction() is true only when elementType == IModelElement.METHOD")
		void isFunction() {
			assertTrue(new SymbolInfo("f", IModelElement.METHOD, 0, 1, 1, new String[0]).isFunction());
			assertFalse(new SymbolInfo("v", FIELD, 0, 1, 1, new String[0]).isFunction());
		}

		@Test
		@DisplayName("a function with no parameters renders as 'Line N: name()'")
		void functionNoParams() {
			SymbolInfo s = new SymbolInfo("doIt", IModelElement.METHOD, 0, 5, 7, new String[0]);
			assertEquals("Line 7: doIt()", s.toFormattedString());
		}

		@Test
		@DisplayName("a function with null parameters still renders empty parentheses")
		void functionNullParams() {
			SymbolInfo s = new SymbolInfo("doIt", IModelElement.METHOD, 0, 5, 3, null);
			assertEquals("Line 3: doIt()", s.toFormattedString());
		}

		@Test
		@DisplayName("a function with parameters renders them comma-separated inside the parentheses")
		void functionWithParams() {
			SymbolInfo s = new SymbolInfo("sum", IModelElement.METHOD, 0, 5, 12, new String[] { "a", "b", "c" });
			assertEquals("Line 12: sum(a, b, c)", s.toFormattedString());
		}

		@Test
		@DisplayName("a non-function (variable) renders with no parentheses")
		void nonFunction() {
			SymbolInfo s = new SymbolInfo("counter", FIELD, 0, 5, 42, new String[] { "ignored" });
			assertEquals("Line 42: counter", s.toFormattedString());
		}
	}

	@Nested
	class FileStructureRecord {
		@Test
		@DisplayName("null symbols yield the 'No symbols found' message")
		void nullSymbols() {
			FileStructure fs = new FileStructure("/a/b.js", null);
			assertEquals("No symbols found in: /a/b.js", fs.toFormattedString());
		}

		@Test
		@DisplayName("empty symbols yield the 'No symbols found' message")
		void emptySymbols() {
			FileStructure fs = new FileStructure("/a/b.js", new ArrayList<>());
			assertEquals("No symbols found in: /a/b.js", fs.toFormattedString());
		}

		@Test
		@DisplayName("a populated structure lists header, count and each symbol's formatted line")
		void populated() {
			List<SymbolInfo> symbols = new ArrayList<>();
			symbols.add(new SymbolInfo("onLoad", IModelElement.METHOD, 0, 5, 1, new String[] { "event" }));
			symbols.add(new SymbolInfo("globalVar", FIELD, 0, 5, 5, new String[0]));
			FileStructure fs = new FileStructure("forms/myform.js", symbols);

			String expected = "# File Structure: forms/myform.js\n\n"
					+ "Found 2 symbol(s):\n\n"
					+ "- Line 1: onLoad(event)\n"
					+ "- Line 5: globalVar\n";
			assertEquals(expected, fs.toFormattedString());
		}
	}

	@Nested
	class FindSymbol {
		@Test
		@DisplayName("a null structure yields null")
		void nullStructure() {
			assertNull(SERVICE.findSymbol(null, "foo"));
		}

		@Test
		@DisplayName("a null symbol name yields null")
		void nullName() {
			FileStructure fs = new FileStructure("/a.js", List.of(function("foo")));
			assertNull(SERVICE.findSymbol(fs, null));
		}

		@Test
		@DisplayName("a matching name returns the exact symbol instance")
		void found() {
			SymbolInfo target = function("bar", "x");
			FileStructure fs = new FileStructure("/a.js", List.of(function("foo"), target, function("baz")));
			assertSame(target, SERVICE.findSymbol(fs, "bar"));
		}

		@Test
		@DisplayName("a non-matching name returns null")
		void notFound() {
			FileStructure fs = new FileStructure("/a.js", List.of(function("foo"), function("bar")));
			assertNull(SERVICE.findSymbol(fs, "missing"));
		}

		@Test
		@DisplayName("the first match wins when several symbols share a name")
		void firstMatchWins() {
			SymbolInfo first = function("dup", "a");
			SymbolInfo second = function("dup", "b");
			FileStructure fs = new FileStructure("/a.js", List.of(first, second));
			assertSame(first, SERVICE.findSymbol(fs, "dup"));
		}
	}

	@Nested
	class CalculateLineNumber {
		private int calc(String source, int offset) throws Exception {
			Method m = FileStructureService.class.getDeclaredMethod("calculateLineNumber", String.class, int.class);
			m.setAccessible(true);
			return (int) m.invoke(null, source, offset);
		}

		@Test
		@DisplayName("offset 0 is line 1")
		void offsetZero() throws Exception {
			assertEquals(1, calc("abc\ndef", 0));
		}

		@Test
		@DisplayName("the line number counts the newlines strictly before the offset")
		void countsNewlinesBeforeOffset() throws Exception {
			// "a\nb\nc" -> offsets: 0='a',1='\n',2='b',3='\n',4='c'
			assertEquals(1, calc("a\nb\nc", 1)); // still on line 1 (newline not yet passed)
			assertEquals(2, calc("a\nb\nc", 2)); // past first newline
			assertEquals(3, calc("a\nb\nc", 4)); // past second newline
		}

		@Test
		@DisplayName("the end-of-string offset is accepted and counts all preceding newlines")
		void endOfString() throws Exception {
			String src = "a\nb\nc";
			assertEquals(3, calc(src, src.length()));
		}

		@Test
		@DisplayName("CRLF line endings count the same as LF (only '\\n' increments the line)")
		void crlf() throws Exception {
			// "a\r\nb" -> offsets: 0='a',1='\r',2='\n',3='b'
			assertEquals(2, calc("a\r\nb", 3));
		}

		@Test
		@DisplayName("null source, a negative offset or an out-of-range offset defensively yield line 1")
		void defensiveFallbacks() throws Exception {
			assertEquals(1, calc(null, 5));
			assertEquals(1, calc("abc", -1));
			assertEquals(1, calc("abc", 4));
		}
	}
}
