/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormLayoutInspectionService;

/**
 * Deterministic string-contract tests for {@code FormLayoutInspectionService.PSEUDO_JS_TEMPLATE},
 * the injected JavaScript that puts one target element into a CSS pseudo-state before the
 * appearance read (SVY-21614). The template is run in the hidden Chromium against a real render
 * (covered end to end by {@code FormLayoutStyledRenderIntegrationTest}); this test is the
 * timing-independent guard that its structure is intact — a regression that removed the focus
 * path, the hover/active CSS-rewrite, the result labels or the per-sheet guard would fail here.
 * The constant is package-private in another package, so it is read by reflection (the test
 * fragment shares the host bundle's classloader).
 */
class FormLayoutPseudoStateTemplateTest {
	private static String pseudo;

	@BeforeAll
	static void loadTemplate() throws Exception {
		Field f = FormLayoutInspectionService.class.getDeclaredField("PSEUDO_JS_TEMPLATE");
		f.setAccessible(true);
		pseudo = (String) f.get(null);
	}

	@Test
	@DisplayName("focus is applied with the real element.focus() DOM API and labelled approximate:false")
	void focusPathIsReal() {
		assertTrue(pseudo.contains("action === 'focus'"), "the template branches on the focus action: " + pseudo);
		assertTrue(pseudo.contains("el.focus()"), "focus must use the real element.focus() DOM API: " + pseudo);
		assertTrue(pseudo.contains("approximate: false"),
				"the focus result must be labelled approximate:false (it is a true pseudo-state): " + pseudo);
	}

	@Test
	@DisplayName("hover/active scan the stylesheets for the matching pseudo-rule and apply it to the target element")
	void hoverPathScansAndApplies() {
		// picks :active vs :hover from the action
		assertTrue(pseudo.contains("(action === 'active') ? ':active' : ':hover'"),
				"the pseudo class is chosen from the action: " + pseudo);
		// iterates the document's own stylesheets (not a fixed list)
		assertTrue(pseudo.contains("document.styleSheets"), "it must scan document.styleSheets: " + pseudo);
		// only rules whose selector carries the pseudo and whose base matches the target
		assertTrue(pseudo.contains("indexOf(pseudo)") && pseudo.contains("el.matches(base)"),
				"only pseudo-rules whose stripped base matches the target are collected: " + pseudo);
		// the declarations are applied directly to the matched target element (no shared/global state)
		assertTrue(pseudo.contains("el.style.setProperty(prop, val, 'important')"),
				"the collected declarations are applied as inline !important declarations on the target element: " + pseudo);
		assertTrue(pseudo.contains("approximate: true"),
				"the hover/active result must be labelled approximate:true: " + pseudo);
	}

	@Test
	@DisplayName("the matched declarations are applied as inline !important so they beat any stylesheet rule (the cascade-win fix)")
	void injectedRuleWinsTheCascade() {
		// The fix: set each matched pseudo-rule property as an INLINE !important declaration. An inline
		// !important declaration beats ANY stylesheet rule regardless of specificity - including an
		// !important rest rule or a more specific pseudo-rule - which the old injected-<style> approach
		// did not guarantee (it only tied .btn-important.btn:hover and lost to anything more specific).
		assertTrue(pseudo.contains("el.style.setProperty(prop, val, 'important')"),
				"each matched property must be set as an inline !important declaration (wins over any stylesheet rule): " + pseudo);
		// properties are read individually from rule.style (not the raw cssText) so each carries 'important'
		assertTrue(pseudo.contains("rs.getPropertyValue(prop)") && pseudo.contains("for (var d=0; d<rs.length; d++)"),
				"each property is read individually from rule.style: " + pseudo);
		// the old marker-<style> / specificity heuristic must be GONE (a regression to it would not be robust)
		assertTrue(!pseudo.contains("createElement('style')") && !pseudo.contains("k + k + k"),
				"the fragile injected-<style> / repeated-marker heuristic must be replaced by inline !important: " + pseudo);
		// appliedProps counts how many inline declarations were set, reported in the result
		assertTrue(pseudo.contains("appliedProps++") && pseudo.contains("appliedProps: appliedProps"),
				"the number of applied inline declarations is counted and reported: " + pseudo);
	}

	@Test
	@DisplayName("a cross-origin stylesheet is skipped and counted, not fatal")
	void crossOriginSheetsAreSkipped() {
		// cssRules throws on a cross-origin sheet; it must be caught and counted, never thrown
		assertTrue(pseudo.contains("try { rules = sheets[s].cssRules; } catch (e) { skippedSheets++; continue; }"),
				"a sheet whose cssRules is inaccessible is skipped and counted: " + pseudo);
	}

	@Test
	@DisplayName("the matched-rule collection is capped and flags truncation")
	void collectionIsCapped() {
		assertTrue(pseudo.contains("matchedRules >= CAP") && pseudo.contains("truncated = true"),
				"the collected rules are capped with a truncated flag: " + pseudo);
	}

	@Test
	@DisplayName("every path returns a JSON string, including the no-match and error paths")
	void everyPathReturnsJson() {
		// no element matched
		assertTrue(pseudo.contains("JSON.stringify({ matched: false, action: action })"),
				"a missing target returns matched:false (soft outcome, not an error): " + pseudo);
		// the outer catch still returns valid JSON
		assertTrue(pseudo.contains("catch (err) { return JSON.stringify({ matched: false, error: String(err) }); }"),
				"an unexpected error returns matched:false + error, never throws: " + pseudo);
		// the hover/active summary carries the counters
		assertTrue(pseudo.contains("matchedRules: matchedRules") && pseudo.contains("skippedSheets: skippedSheets")
				&& pseudo.contains("truncated: truncated"),
				"the approximate result carries matchedRules/skippedSheets/truncated: " + pseudo);
	}

	@Test
	@DisplayName("the cap placeholder (%3$s) is filled by the service with MAX_PSEUDO_RULES")
	void capConstantIsExposed() throws Exception {
		Field f = FormLayoutInspectionService.class.getDeclaredField("MAX_PSEUDO_RULES");
		f.setAccessible(true);
		int cap = f.getInt(null);
		assertTrue(cap > 0, "MAX_PSEUDO_RULES must be a positive cap, was " + cap);
		// the template references the cap via the CAP local bound to the %3$s placeholder
		assertTrue(pseudo.contains("var CAP = %3$s"), "the template binds CAP to the %3$s placeholder: " + pseudo);
	}

	@Test
	@DisplayName("the action constants are the three documented values")
	void actionConstants() throws Exception {
		assertEquals("focus", constant("ACTION_FOCUS"));
		assertEquals("hover", constant("ACTION_HOVER"));
		assertEquals("active", constant("ACTION_ACTIVE"));
	}

	private static String constant(String name) throws Exception {
		Field f = FormLayoutInspectionService.class.getDeclaredField(name);
		f.setAccessible(true);
		return (String) f.get(null);
	}
}
