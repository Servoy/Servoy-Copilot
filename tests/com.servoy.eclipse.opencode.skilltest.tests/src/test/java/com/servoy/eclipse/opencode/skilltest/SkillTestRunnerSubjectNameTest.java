/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

package com.servoy.eclipse.opencode.skilltest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;
import com.servoy.eclipse.opencode.skilltest.outcome.PersistKind;

/**
 * Unit tests for {@link SkillTestRunner#firstSubject(List)} - which expected
 * persist the runner hands the acceptance judge as the artifact to read, and
 * its concrete source-file hint (SVY-21366). Pure logic: no workbench, no LLM.
 * <p>
 * The judge "could not find the artifact" / {@code [FAIL] null} failures traced
 * back here: the judge needs a CONCRETE FILE to read, not a name to search for.
 * The runner picks the first root-by-name persist (form, value list, relation,
 * menu, media) and {@link PersistKind#sourceHint(String)} turns it into the
 * exact solution path - {@code forms/<n>.frm}, {@code valuelists/<n>.val}, etc.
 * These tests pin both the selection and the path mapping.
 * </p>
 */
class SkillTestRunnerSubjectNameTest {

	private static OutcomeAssertion persist(PersistKind kind, String name) {
		return new OutcomeAssertion(kind, name, null, true, null, null);
	}

	private static String nameOf(List<OutcomeAssertion> expected) {
		OutcomeAssertion a = SkillTestRunner.firstSubject(expected);
		return a == null ? null : a.name();
	}

	// --- which persist the judge is pointed at ---

	@Test
	@DisplayName("a form outcome yields the form")
	void formName() {
		assertEquals("order_tabs", nameOf(List.of(persist(PersistKind.FORM, "order_tabs"))));
	}

	@Test
	@DisplayName("a value-list outcome yields the value list (not form-specific)")
	void valueListName() {
		assertEquals("orderStatusVl", nameOf(List.of(persist(PersistKind.VALUELIST, "orderStatusVl"))));
	}

	@Test
	@DisplayName("a relation outcome yields the relation")
	void relationName() {
		assertEquals("orders_to_details", nameOf(List.of(persist(PersistKind.RELATION, "orders_to_details"))));
	}

	@Test
	@DisplayName("a menu outcome yields the menu")
	void menuName() {
		assertEquals("mainMenu", nameOf(List.of(persist(PersistKind.MENU, "mainMenu"))));
	}

	@Test
	@DisplayName("the FIRST root-by-name persist wins when several are expected")
	void firstRootWins() {
		assertEquals("vlFirst", nameOf(List.of(
				persist(PersistKind.VALUELIST, "vlFirst"),
				persist(PersistKind.FORM, "formSecond"))));
	}

	@Test
	@DisplayName("a non-root child persist (component/method) is skipped; the root-by-name form is chosen")
	void skipsNonRootChild() {
		// A baseline whose expected list starts with a child (a component or a global
		// method) must not yield the child - the judge needs the root artifact to read.
		assertEquals("customerDetail", nameOf(List.of(
				persist(PersistKind.SCRIPTMETHOD, "getOrderStatusValues"),
				persist(PersistKind.COMPONENT, "myButton"),
				persist(PersistKind.FORM, "customerDetail"))));
	}

	@Test
	@DisplayName("no root-by-name persist (only methods/components) yields null, so the judge discovers it")
	void onlyNonRootYieldsNull() {
		assertNull(nameOf(List.of(
				persist(PersistKind.SCRIPTMETHOD, "getOrderStatusValues"),
				persist(PersistKind.COMPONENT, "myButton"))));
	}

	@Test
	@DisplayName("a blank or missing name is not returned")
	void blankNameSkipped() {
		assertNull(nameOf(List.of(
				persist(PersistKind.FORM, "  "),
				persist(PersistKind.VALUELIST, null))));
	}

	@Test
	@DisplayName("an empty or null expected list yields null")
	void emptyOrNull() {
		assertNull(nameOf(List.of()));
		assertNull(nameOf(null));
	}

	// --- the concrete source-file hint the judge is told to read ---

	@Test
	@DisplayName("a form's source hint names forms/<name>.frm (+ .js/.less)")
	void formSourceHint() {
		String hint = PersistKind.FORM.sourceHint("order_tabs");
		assertEquals("`forms/order_tabs.frm` (plus its `forms/order_tabs.js` and `forms/order_tabs.less` when present)",
				hint);
	}

	@Test
	@DisplayName("a value list's source hint names valuelists/<name>.val")
	void valueListSourceHint() {
		assertEquals("`valuelists/orderStatusVl.val`", PersistKind.VALUELIST.sourceHint("orderStatusVl"));
	}

	@Test
	@DisplayName("a relation's source hint names relations/<name>.rel")
	void relationSourceHint() {
		assertEquals("`relations/orders_to_details.rel`", PersistKind.RELATION.sourceHint("orders_to_details"));
	}

	@Test
	@DisplayName("a menu's source hint names menus/<name>.men")
	void menuSourceHint() {
		assertEquals("`menus/mainMenu.men`", PersistKind.MENU.sourceHint("mainMenu"));
	}

	@Test
	@DisplayName("a kind with no single predictable file (method/component) has no source hint")
	void noHintForNonRootKinds() {
		assertNull(PersistKind.SCRIPTMETHOD.sourceHint("getOrderStatusValues"));
		assertNull(PersistKind.COMPONENT.sourceHint("myButton"));
	}

	@Test
	@DisplayName("a blank name yields no source hint")
	void noHintForBlankName() {
		assertNull(PersistKind.FORM.sourceHint(" "));
		assertNull(PersistKind.FORM.sourceHint(null));
	}
}
