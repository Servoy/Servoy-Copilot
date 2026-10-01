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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchResult;
import com.servoy.eclipse.developer.mcp.services.WorkspaceService.SearchResults;

/**
 * Plain unit tests (no Eclipse workbench) for the SVY-21523 search-result-cap
 * feature. Covers everything observable without a live workspace: the
 * {@link SearchResults} record contract, the {@code SEARCH_MAX_RESULTS_DEFAULT}
 * constant, the method/overload signatures that carry the cap and the
 * {@code SearchResults} return type, the unchanged argument validation of the
 * public search methods, and the truncation-vs-plain header rendered by the
 * {@code ServoyIdeServer.formatSearchResults(SearchResults)} helper (reached
 * reflectively; its visibility is not loosened).
 * <p>
 * The search itself requires a workspace and is exercised by the integration
 * tests; it is deliberately not run here.
 */
public class WorkspaceServiceSearchTest {
	private WorkspaceService service;

	@BeforeEach
	void setUp() {
		service = new WorkspaceService();
	}

	// --- SearchResults record shape / accessors ------------------------------

	@Test
	@DisplayName("SearchResults exposes matches() and truncated() accessors")
	void searchResultsAccessors() {
		SearchResult match = new SearchResult("/proj/file.js", 7, "var x = foundset;");
		List<SearchResult> matches = List.of(match);

		SearchResults truncatedResults = new SearchResults(matches, true);
		assertSame(matches, truncatedResults.matches());
		assertTrue(truncatedResults.truncated());

		SearchResults completeResults = new SearchResults(matches, false);
		assertEquals(matches, completeResults.matches());
		assertFalse(completeResults.truncated());
	}

	@Test
	@DisplayName("SearchResults carries an empty match list without truncation")
	void searchResultsEmptyMatches() {
		SearchResults results = new SearchResults(List.of(), false);
		assertNotNull(results.matches());
		assertTrue(results.matches().isEmpty());
		assertFalse(results.truncated());
	}

	@Test
	@DisplayName("SearchResults equality is value based")
	void searchResultsEquality() {
		SearchResult match = new SearchResult("/proj/file.js", 1, "foundset");
		SearchResults a = new SearchResults(List.of(match), true);
		SearchResults b = new SearchResults(List.of(match), true);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());

		SearchResults differentFlag = new SearchResults(List.of(match), false);
		assertFalse(a.equals(differentFlag));
	}

	@Test
	@DisplayName("SearchResult record exposes filePath, lineNumber and lineContent")
	void searchResultAccessors() {
		SearchResult r = new SearchResult("/proj/a.js", 42, "  application.output('hi');  ");
		assertEquals("/proj/a.js", r.filePath());
		assertEquals(42, r.lineNumber());
		assertEquals("  application.output('hi');  ", r.lineContent());
	}

	// --- SEARCH_MAX_RESULTS_DEFAULT constant ---------------------------------

	@Test
	@DisplayName("SEARCH_MAX_RESULTS_DEFAULT is 500")
	void defaultCapIs500() {
		assertEquals(500, WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT);
	}

	@Test
	@DisplayName("SEARCH_MAX_RESULTS_DEFAULT is a public static final int")
	void defaultCapIsPublicStaticFinal() throws NoSuchFieldException {
		int modifiers = WorkspaceService.class.getField("SEARCH_MAX_RESULTS_DEFAULT").getModifiers();
		assertTrue(Modifier.isPublic(modifiers));
		assertTrue(Modifier.isStatic(modifiers));
		assertTrue(Modifier.isFinal(modifiers));
		assertEquals(int.class, WorkspaceService.class.getField("SEARCH_MAX_RESULTS_DEFAULT").getType());
	}

	// --- method / overload existence and signatures -------------------------

	@Test
	@DisplayName("fileSearch has a maxResults overload returning SearchResults")
	void fileSearchMaxResultsOverloadExists() throws NoSuchMethodException {
		Method m = WorkspaceService.class.getMethod("fileSearch", String.class, int.class, String[].class);
		assertEquals(SearchResults.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	@DisplayName("fileSearch keeps the no-maxResults overload returning SearchResults")
	void fileSearchNoMaxResultsOverloadExists() throws NoSuchMethodException {
		Method m = WorkspaceService.class.getMethod("fileSearch", String.class, String[].class);
		assertEquals(SearchResults.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	@DisplayName("fileSearchRegExp has a maxResults overload returning SearchResults")
	void fileSearchRegExpMaxResultsOverloadExists() throws NoSuchMethodException {
		Method m = WorkspaceService.class.getMethod("fileSearchRegExp", String.class, int.class, String[].class);
		assertEquals(SearchResults.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	@DisplayName("fileSearchRegExp keeps the no-maxResults overload returning SearchResults")
	void fileSearchRegExpNoMaxResultsOverloadExists() throws NoSuchMethodException {
		Method m = WorkspaceService.class.getMethod("fileSearchRegExp", String.class, String[].class);
		assertEquals(SearchResults.class, m.getReturnType());
		assertTrue(Modifier.isPublic(m.getModifiers()));
	}

	@Test
	@DisplayName("private search(Pattern, int, String...) returns SearchResults")
	void privateSearchReturnsSearchResults() throws NoSuchMethodException {
		Method m = WorkspaceService.class.getDeclaredMethod("search", Pattern.class, int.class, String[].class);
		assertEquals(SearchResults.class, m.getReturnType());
		assertTrue(Modifier.isPrivate(m.getModifiers()));
	}

	// --- tool methods expose the optional maxResults @ToolParam -------------

	@Test
	@DisplayName("ServoyIdeServer.fileSearch tool exposes a maxResults parameter")
	void fileSearchToolHasMaxResultsParam() throws Exception {
		assertTrue(toolMethodHasMaxResultsParam("fileSearch"),
				"fileSearch tool method should accept a maxResults parameter");
	}

	@Test
	@DisplayName("ServoyIdeServer.fileSearchRegExp tool exposes a maxResults parameter")
	void fileSearchRegExpToolHasMaxResultsParam() throws Exception {
		assertTrue(toolMethodHasMaxResultsParam("fileSearchRegExp"),
				"fileSearchRegExp tool method should accept a maxResults parameter");
	}

	// --- maxResults parsing falls back to the default on bad input ----------

	@Test
	@DisplayName("parseMaxResults returns the default for null, blank or whitespace")
	void parseMaxResultsDefaultsOnMissing() throws Exception {
		int def = WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT;
		assertEquals(def, invokeParseMaxResults(null));
		assertEquals(def, invokeParseMaxResults(""));
		assertEquals(def, invokeParseMaxResults("   "));
	}

	@Test
	@DisplayName("parseMaxResults returns the default for non-numeric input instead of throwing")
	void parseMaxResultsDefaultsOnNonNumeric() throws Exception {
		int def = WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT;
		assertEquals(def, invokeParseMaxResults("abc"));
		assertEquals(def, invokeParseMaxResults("12x"));
		assertEquals(def, invokeParseMaxResults("1.5"));
	}

	@Test
	@DisplayName("parseMaxResults parses a valid number, trimming surrounding whitespace")
	void parseMaxResultsParsesValidNumber() throws Exception {
		assertEquals(10, invokeParseMaxResults("10"));
		assertEquals(500, invokeParseMaxResults(" 500 "));
		assertEquals(-1, invokeParseMaxResults("-1"));
	}

	// --- argument validation unchanged --------------------------------------

	@Test
	@DisplayName("fileSearch(null) still throws IllegalArgumentException")
	void fileSearchNullTextThrows() {
		assertThrows(IllegalArgumentException.class, () -> service.fileSearch(null, new String[0]));
		assertThrows(IllegalArgumentException.class,
				() -> service.fileSearch(null, WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, new String[0]));
	}

	@Test
	@DisplayName("fileSearch(blank) still throws IllegalArgumentException")
	void fileSearchBlankTextThrows() {
		assertThrows(IllegalArgumentException.class, () -> service.fileSearch("   ", new String[0]));
		assertThrows(IllegalArgumentException.class, () -> service.fileSearch("", 10, new String[0]));
	}

	@Test
	@DisplayName("fileSearchRegExp(null) still throws IllegalArgumentException")
	void fileSearchRegExpNullPatternThrows() {
		assertThrows(IllegalArgumentException.class, () -> service.fileSearchRegExp(null, new String[0]));
		assertThrows(IllegalArgumentException.class,
				() -> service.fileSearchRegExp(null, WorkspaceService.SEARCH_MAX_RESULTS_DEFAULT, new String[0]));
	}

	@Test
	@DisplayName("fileSearchRegExp(blank) still throws IllegalArgumentException")
	void fileSearchRegExpBlankPatternThrows() {
		assertThrows(IllegalArgumentException.class, () -> service.fileSearchRegExp("   ", new String[0]));
		assertThrows(IllegalArgumentException.class, () -> service.fileSearchRegExp("", 10, new String[0]));
	}

	// --- formatSearchResults truncation rendering ---------------------------

	@Test
	@DisplayName("formatSearchResults marks a truncated result as having more matches")
	void formatSearchResultsTruncatedHeader() throws Exception {
		List<SearchResult> matches = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			matches.add(new SearchResult("/proj/f" + i + ".js", i, "function foo" + i + "()"));
		}
		String rendered = invokeFormatSearchResults(new SearchResults(matches, true));

		String header = rendered.lines().findFirst().orElse("");
		// The truncation header must explicitly tell the caller MORE matches exist.
		assertTrue(header.toLowerCase().contains("more matches exist"),
				"truncated header should state more matches exist, was: " + header);
		assertTrue(header.toLowerCase().contains("truncated"),
				"truncated header should say results were truncated, was: " + header);
		// It must still reflect how many are shown ("first N") and must NOT claim an
		// exact total.
		assertTrue(header.contains("first 3"), "truncated header should state 'first N' shown, was: " + header);
		// Each match is still listed.
		assertTrue(rendered.contains("/proj/f1.js:1"));
		assertTrue(rendered.contains("/proj/f3.js:3"));
	}

	@Test
	@DisplayName("formatSearchResults renders a plain N match(es) header when not truncated")
	void formatSearchResultsPlainHeader() throws Exception {
		List<SearchResult> matches = List.of(new SearchResult("/proj/a.js", 10, "foundset.getSize()"),
				new SearchResult("/proj/b.js", 20, "application.output('x')"));
		String rendered = invokeFormatSearchResults(new SearchResults(matches, false));

		String header = rendered.lines().findFirst().orElse("");
		assertTrue(header.contains("2 match(es)"),
				"non-truncated header should report the exact count, was: " + header);
		// A non-truncated result must NOT claim more matches exist.
		assertFalse(header.toLowerCase().contains("more matches exist"),
				"non-truncated header must not claim more matches exist, was: " + header);
		assertFalse(header.toLowerCase().contains("truncated"),
				"non-truncated header must not say truncated, was: " + header);
		// Match content is trimmed in the listing.
		assertTrue(rendered.contains("/proj/a.js:10 - foundset.getSize()"));
	}

	@Test
	@DisplayName("formatSearchResults reports no matches for an empty result")
	void formatSearchResultsEmpty() throws Exception {
		String rendered = invokeFormatSearchResults(new SearchResults(List.of(), false));
		assertEquals("No matches found.", rendered);
	}

	/**
	 * Reflectively invokes the package-visible-only {@code formatSearchResults}
	 * helper on {@code ServoyIdeServer} without loosening its visibility.
	 */
	private static String invokeFormatSearchResults(SearchResults results) throws Exception {
		Class<?> serverClass = Class.forName("com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer");
		Method m = serverClass.getDeclaredMethod("formatSearchResults", SearchResults.class);
		m.setAccessible(true);
		return (String) m.invoke(null, results);
	}

	/**
	 * Reflectively invokes the private static {@code parseMaxResults} helper on
	 * {@code ServoyIdeServer} without loosening its visibility.
	 */
	private static int invokeParseMaxResults(String maxResults) throws Exception {
		Class<?> serverClass = Class.forName("com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer");
		Method m = serverClass.getDeclaredMethod("parseMaxResults", String.class);
		m.setAccessible(true);
		return (int) m.invoke(null, maxResults);
	}

	/**
	 * Returns true when the named {@code ServoyIdeServer} tool method declares a
	 * parameter annotated with {@code @ToolParam(name = "maxResults")}.
	 */
	private static boolean toolMethodHasMaxResultsParam(String toolMethodName) throws Exception {
		Class<?> serverClass = Class.forName("com.servoy.eclipse.developer.mcp.servers.ServoyIdeServer");
		@SuppressWarnings("unchecked")
		Class<? extends java.lang.annotation.Annotation> toolParamClass = (Class<? extends java.lang.annotation.Annotation>) Class
				.forName("com.servoy.eclipse.developer.mcp.annotations.ToolParam");
		Method nameAccessor = toolParamClass.getMethod("name");
		for (Method method : serverClass.getDeclaredMethods()) {
			if (!method.getName().equals(toolMethodName))
				continue;
			for (java.lang.annotation.Annotation[] paramAnnotations : method.getParameterAnnotations()) {
				for (java.lang.annotation.Annotation annotation : paramAnnotations) {
					if (toolParamClass.isInstance(annotation) && "maxResults".equals(nameAccessor.invoke(annotation))) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
