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

package com.servoy.eclipse.opencode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link OpenCodeBranding}.
 * <p>
 * Verifies that the injected JS correctly hides scratchpad tool calls, applies
 * Servoy branding, and is structurally valid JavaScript. No SWT or workbench
 * required — all methods under test are pure Java string operations.
 * </p>
 */
public class OpenCodeViewBrandingTest
{
	// -----------------------------------------------------------------------
	// Scratchpad hiding
	// -----------------------------------------------------------------------

	@Test
	public void testInjectJs_containsScratchpadThinkFilter()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("Inject script must contain 'scratchpad_think' filter",
			js.contains("scratchpad_think"));
	}

	@Test
	public void testInjectJs_containsScratchpadCompletionMetaFilter()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("Inject script must contain 'scratchpad_completion_meta' filter",
			js.contains("scratchpad_completion_meta"));
	}

	@Test
	public void testInjectJs_containsToolPartWrapperSelector()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("Inject script must query tool-part-wrapper elements",
			js.contains("tool-part-wrapper"));
	}

	@Test
	public void testInjectJs_hidesMatchingElements()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("Inject script must set display:none on matching elements",
			js.contains("display") && js.contains("none"));
	}

	@Test
	public void testHiddenToolPrefixes_containsExpectedEntries()
	{
		assertEquals("Should have exactly 1 hidden tool prefix",
			1, OpenCodeBranding.HIDDEN_TOOL_PREFIXES.length);
		assertEquals("scratchpad", OpenCodeBranding.HIDDEN_TOOL_PREFIXES[0]);
	}

	// -----------------------------------------------------------------------
	// JS structure
	// -----------------------------------------------------------------------

	@Test
	public void testInjectJs_isWrappedInIIFE()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("Inject script must start with an IIFE",
			js.startsWith("(function(){"));
		assertTrue("Inject script must end with closing IIFE",
			js.endsWith("})();"));
	}

	@Test
	public void testInjectJs_balancedBraces()
	{
		String js = OpenCodeBranding.buildInjectScript();
		int open = 0;
		for (char c : js.toCharArray())
		{
			if (c == '{') open++;
			else if (c == '}') open--;
		}
		assertEquals("Inject script must have balanced braces", 0, open);
	}

	@Test
	public void testInjectJs_balancedParentheses()
	{
		String js = OpenCodeBranding.buildInjectScript();
		int open = 0;
		for (char c : js.toCharArray())
		{
			if (c == '(') open++;
			else if (c == ')') open--;
		}
		assertEquals("Inject script must have balanced parentheses", 0, open);
	}

	@Test
	public void testInjectJs_doesNotContainRawNewlines()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertFalse("Inject script must not contain unescaped newline characters",
			js.contains("\n"));
	}

	// -----------------------------------------------------------------------
	// MutationObserver (ensures filtering runs on dynamic content)
	// -----------------------------------------------------------------------

	@Test
	public void testInjectJs_registersMutationObserver()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("Inject script must register a MutationObserver for dynamic content",
			js.contains("MutationObserver"));
	}

	@Test
	public void testInjectJs_observesChildListAndSubtree()
	{
		String js = OpenCodeBranding.buildInjectScript();
		assertTrue("MutationObserver must observe childList changes",
			js.contains("childList: true"));
		assertTrue("MutationObserver must observe subtree changes",
			js.contains("subtree: true"));
	}

	// -----------------------------------------------------------------------
	// Brand CSS
	// -----------------------------------------------------------------------

	@Test
	public void testBrandCss_containsServoyOrangeColor()
	{
		assertTrue("BRAND_CSS must contain Servoy orange brand colour",
			OpenCodeBranding.BRAND_CSS.contains("#f5a623"));
	}

	@Test
	public void testBrandCss_hidesSidebarRail()
	{
		assertTrue("BRAND_CSS must hide the sidebar rail",
			OpenCodeBranding.BRAND_CSS.contains("[data-component=\"sidebar-rail\"]"));
	}

	// -----------------------------------------------------------------------
	// toJsString helper
	// -----------------------------------------------------------------------

	@Test
	public void testToJsString_escapesNewlines()
	{
		String result = OpenCodeBranding.toJsString("line1\nline2");
		assertFalse("Must not contain literal newline", result.contains("\n"));
		assertTrue("Must contain escaped newline", result.contains("\\n"));
	}

	@Test
	public void testToJsString_escapesSingleQuotes()
	{
		String result = OpenCodeBranding.toJsString("it's");
		assertTrue("Must escape single quote", result.contains("\\'"));
	}

	@Test
	public void testToJsString_wrapsInSingleQuotes()
	{
		String result = OpenCodeBranding.toJsString("hello");
		assertTrue("Must start with single quote", result.startsWith("'"));
		assertTrue("Must end with single quote", result.endsWith("'"));
	}

	@Test
	public void testBuildInjectScript_isNotNull()
	{
		assertNotNull("buildInjectScript() must not return null",
			OpenCodeBranding.buildInjectScript());
	}

	@Test
	public void testBuildInjectScript_containsBrandCssContent()
	{
		String js = OpenCodeBranding.buildInjectScript();
		// The CSS is injected via toJsString, so the orange colour should be present (escaped)
		assertTrue("Inject script must contain Servoy orange colour from BRAND_CSS",
			js.contains("#f5a623"));
	}

	// -----------------------------------------------------------------------
	// shouldHideToolCall - functional filtering tests
	// -----------------------------------------------------------------------

	@Test
	public void testShouldHide_scratchpadThink_returnsTrue()
	{
		assertTrue("scratchpad_think tool call must be hidden",
			OpenCodeBranding.shouldHideToolCall("scratchpad_think"));
	}

	@Test
	public void testShouldHide_scratchpadThinkWithContent_returnsTrue()
	{
		assertTrue("scratchpad_think with surrounding text must be hidden",
			OpenCodeBranding.shouldHideToolCall("Tool: scratchpad_think - reasoning about the problem"));
	}

	@Test
	public void testShouldHide_scratchpadCompletionMeta_returnsTrue()
	{
		assertTrue("scratchpad_completion_meta tool call must be hidden",
			OpenCodeBranding.shouldHideToolCall("scratchpad_completion_meta"));
	}

	@Test
	public void testShouldHide_scratchpadCompletionMetaWithContent_returnsTrue()
	{
		assertTrue("scratchpad_completion_meta with surrounding text must be hidden",
			OpenCodeBranding.shouldHideToolCall("Tool: scratchpad_completion_meta - non-code output"));
	}

	@Test
	public void testShouldHide_normalToolCall_returnsFalse()
	{
		assertFalse("Normal tool calls must NOT be hidden",
			OpenCodeBranding.shouldHideToolCall("servoy-model_runTests"));
	}

	@Test
	public void testShouldHide_timeToolCall_returnsFalse()
	{
		assertFalse("time tool calls must NOT be hidden",
			OpenCodeBranding.shouldHideToolCall("time_currentTime"));
	}

	@Test
	public void testShouldHide_editorToolCall_returnsFalse()
	{
		assertFalse("Editor tool calls must NOT be hidden",
			OpenCodeBranding.shouldHideToolCall("servoy-editor_replaceString"));
	}

	@Test
	public void testShouldHide_emptyString_returnsFalse()
	{
		assertFalse("Empty text content must NOT be hidden",
			OpenCodeBranding.shouldHideToolCall(""));
	}

	@Test
	public void testShouldHide_null_returnsFalse()
	{
		assertFalse("null text content must NOT be hidden",
			OpenCodeBranding.shouldHideToolCall(null));
	}

	@Test
	public void testShouldHide_partialMatch_returnsTrue()
	{
		assertTrue("'scratchpad' alone must be hidden (prefix match)",
			OpenCodeBranding.shouldHideToolCall("scratchpad"));
	}
}
