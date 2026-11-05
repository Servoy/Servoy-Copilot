/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormLayoutInspectionService;

/**
 * Deterministic string-contract tests for the two injected-JavaScript templates of
 * {@code FormLayoutInspectionService} that SVY-21614 changed:
 * <ul>
 * <li>{@code READINESS_JS_TEMPLATE} must wait for every solution stylesheet
 * {@code <link rel="stylesheet" svy-stylesheet>} to have applied (a non-null
 * {@code .sheet}) before declaring the form ready to read - the fix for the Run 005
 * Bootstrap-defaults race;</li>
 * <li>{@code READ_JS_TEMPLATE} must collect the {@code outline-*} computed styles and emit a
 * {@code disabled}/{@code readonly} boolean per element.</li>
 * </ul>
 * <p>
 * The readiness race itself is timing-dependent, so an end-to-end render test can only catch a
 * regression probabilistically (see {@code FormLayoutStyledRenderIntegrationTest}). This test is
 * the <em>deterministic</em> guard: it asserts the clause is present in the predicate string and
 * ordered after the cheap form-name / laid-out-node checks, which is exactly what a regression
 * would remove. No browser, workbench or Servoy model is involved; the two template constants are
 * package-private in another package, so they are read by reflection (the test fragment shares the
 * host bundle's classloader).
 */
class FormLayoutReadinessTemplateTest {
	private static String readiness;
	private static String read;

	@BeforeAll
	static void loadTemplates() throws Exception {
		readiness = constant("READINESS_JS_TEMPLATE");
		read = constant("READ_JS_TEMPLATE");
	}

	private static String constant(String name) throws Exception {
		Field f = FormLayoutInspectionService.class.getDeclaredField(name);
		f.setAccessible(true);
		return (String) f.get(null);
	}

	@Test
	@DisplayName("readiness waits for every svy-stylesheet link's .sheet before returning true (SVY-21614)")
	void readinessWaitsForStylesheets() {
		assertTrue(readiness.contains("link[rel=\"stylesheet\"][svy-stylesheet]"),
				"the readiness predicate must query the solution stylesheet links: " + readiness);
		// the guard reads .sheet and rejects (return false) while any sheet has not parsed
		assertTrue(readiness.contains("!sheets[s].sheet"),
				"the readiness predicate must test each link's .sheet: " + readiness);
		assertTrue(readiness.contains("if (!sheets[s].sheet) return false"),
				"a link whose .sheet is not yet applied must keep the form 'not ready' (return false): " + readiness);
	}

	@Test
	@DisplayName("the stylesheet wait runs only after the form-name and laid-out-node checks (ordering)")
	void stylesheetWaitIsOrderedLast() {
		int nameCheck = readiness.indexOf("window.formtemplateName");
		int laidOut = readiness.indexOf("getBoundingClientRect");
		int sheets = readiness.indexOf("svy-stylesheet");
		assertTrue(nameCheck >= 0 && laidOut >= 0 && sheets >= 0, "all three checks must be present: " + readiness);
		assertTrue(sheets > nameCheck,
				"the stylesheet wait must come after the form-name short-circuit: " + readiness);
		assertTrue(sheets > laidOut,
				"the stylesheet wait must come after the laid-out-node check: " + readiness);
	}

	@Test
	@DisplayName("no solution stylesheet is a no-op: the loop is bounded by the (possibly empty) link set")
	void emptyStylesheetSetIsANoOp() {
		// The guard iterates querySelectorAll('link[...svy-stylesheet]'); an empty NodeList makes the
		// for-loop body run zero times, so readiness proceeds on the existing checks. Assert the clause
		// is a bounded loop over that set (not an unconditional 'return false' that would break the
		// no-stylesheet case).
		assertTrue(readiness.contains("querySelectorAll('link[rel=\"stylesheet\"][svy-stylesheet]')"),
				"the guard must iterate the svy-stylesheet link set (empty = no-op): " + readiness);
		assertTrue(readiness.contains("for (var s=0;s<sheets.length;s++)"),
				"the guard must be a for-loop bounded by the link set length: " + readiness);
	}

	@Test
	@DisplayName("the read template collects the outline-* longhand computed styles (SVY-21614)")
	void readCollectsOutlineStyles() {
		for (String prop : new String[] { "outline-width", "outline-style", "outline-color", "outline-offset" }) {
			assertTrue(read.contains("'" + prop + "'"),
					"the VISUAL style list must collect '" + prop + "': " + read);
		}
	}

	@Test
	@DisplayName("the read template emits disabled/readonly booleans per appearance entry (SVY-21614)")
	void readEmitsDisabledReadonly() {
		assertTrue(read.contains("disabled:"), "each appearance entry must carry a disabled boolean: " + read);
		assertTrue(read.contains("readonly:"), "each appearance entry must carry a readonly boolean: " + read);
		// meaningful across both native controls and Servoy <div> components: property OR attribute OR
		// .disabled class OR aria-*
		assertTrue(read.contains("el.disabled") && read.contains("getAttribute('disabled')")
				&& read.contains("aria-disabled"),
				"disabled must be read from the property, the attribute and aria-disabled: " + read);
		assertTrue(read.contains("el.readOnly") && read.contains("getAttribute('readonly')")
				&& read.contains("aria-readonly"),
				"readonly must be read from the property, the attribute and aria-readonly: " + read);
	}
}
