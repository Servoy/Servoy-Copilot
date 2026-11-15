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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Plain-JUnit unit tests for {@link ServoySolutionService}'s <em>own</em> logic -
 * the relevance scoring/ranking of forms, the candidate comparator, the origin
 * and valuelist formatting helpers, the form-layout-type mapping, and the
 * no-active-solution guards - with the Servoy model boundary stubbed via Mockito.
 * <p>
 * The service obtains the model through its {@code getServoyModel()} seam, which
 * this test overrides with a mock {@link IDeveloperServoyModel}. The Servoy core
 * it exposes ({@link ServoyProject}, {@link Solution}, {@link Form},
 * {@link ValueList}) is only stubbed to drive this service's branches - the core's
 * behaviour itself is NOT under test here (that belongs in servoy-eclipse). The
 * pure scoring/formatting helpers are reached directly through reflection (they are
 * {@code static}/private); everything that actually walks the flattened solution or
 * mutates the repository stays for the integration tests.
 * </p>
 *
 * @since 2026.06
 */
class ServoySolutionServiceTest {
	private static final String NO_ACTIVE = "Error: No active Servoy solution project found";

	/** A {@link ServoySolutionService} whose model seam returns the supplied mock. */
	private static ServoySolutionService serviceWith(IDeveloperServoyModel model) {
		return new ServoySolutionService() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}
		};
	}

	// ---------------------------------------------------------------------------
	// Pure scoring helper: score(name, title, ds, comment, queryLower)
	// ---------------------------------------------------------------------------

	@Nested
	class Score {
		private int[] score(String name, String title, String ds, String comment, String queryLower) throws Exception {
			Method m = ServoySolutionService.class.getDeclaredMethod("score",
					String.class, String.class, String.class, String.class, String.class);
			m.setAccessible(true);
			return (int[]) m.invoke(null, name, title, ds, comment, queryLower);
		}

		@Test
		@DisplayName("a blank or null query scores {0, 0}")
		void blankQuery() throws Exception {
			assertArrayEquals(new int[] { 0, 0 }, score("orders", "Orders", "db:/x/y", "c", ""));
			assertArrayEquals(new int[] { 0, 0 }, score("orders", "Orders", "db:/x/y", "c", null));
		}

		@Test
		@DisplayName("tier 3 for an exact name or title match")
		void tierThreeExact() throws Exception {
			assertArrayEquals(new int[] { 3, 0 }, score("orders", null, null, null, "orders"));
			assertArrayEquals(new int[] { 3, 0 }, score("frm1", "Orders", null, null, "orders"),
					"exact title match also tier 3");
		}

		@Test
		@DisplayName("tier 2 for a name or title containing the query")
		void tierTwoContains() throws Exception {
			assertArrayEquals(new int[] { 2, 0 }, score("orderDetails", null, null, null, "order"));
			assertArrayEquals(new int[] { 2, 0 }, score("frm1", "Customer Orders", null, null, "order"));
		}

		@Test
		@DisplayName("tier 1 for a datasource or comment containing the query")
		void tierOneDatasourceOrComment() throws Exception {
			assertArrayEquals(new int[] { 1, 0 }, score("frm1", "Title", "db:/example/orders", null, "orders"));
			assertArrayEquals(new int[] { 1, 0 }, score("frm1", "Title", null, "shows the orders list", "orders"));
		}

		@Test
		@DisplayName("name/title win over datasource/comment (checked highest-tier first)")
		void tierPrecedence() throws Exception {
			// name contains query (tier 2) even though comment also contains it
			assertArrayEquals(new int[] { 2, 0 }, score("orderForm", null, null, "an order comment", "order"));
		}

		@Test
		@DisplayName("tier 0 falls back to counting query words (3+ chars) found in name/title")
		void tierZeroWordMatches() throws Exception {
			// neither name nor title nor ds/comment CONTAINS the whole phrase, but individual words hit the name
			int[] s = score("customerOrderList", null, null, null, "order zzz");
			assertEquals(0, s[0], "no contiguous match -> tier 0");
			assertEquals(1, s[1], "'order' (>=3 chars) is in the name; 'zzz' is not");
		}

		@Test
		@DisplayName("words shorter than 3 characters are ignored in the word-match count")
		void shortWordsIgnored() throws Exception {
			int[] s = score("abXYZ", null, null, null, "ab cd");
			assertEquals(0, s[0]);
			assertEquals(0, s[1], "both query words are < 3 chars, so no word matches");
		}

		@Test
		@DisplayName("null name/title/ds/comment are treated as empty, no NPE")
		void nullFieldsSafe() throws Exception {
			assertArrayEquals(new int[] { 0, 0 }, score(null, null, null, null, "order"));
		}

		@Test
		@DisplayName("matching is case-insensitive against the already-lowercased query")
		void caseInsensitive() throws Exception {
			assertArrayEquals(new int[] { 3, 0 }, score("Orders", null, null, null, "orders"));
			assertArrayEquals(new int[] { 2, 0 }, score("OrderDetails", null, null, null, "order"));
		}
	}

	// ---------------------------------------------------------------------------
	// compareCandidates / legacyScore
	// ---------------------------------------------------------------------------

	@Nested
	class CandidateOrdering {
		private ServoySolutionService.FormCandidate candidate(int tier, int wordMatches) {
			return new ServoySolutionService.FormCandidate(mock(Form.class), "mod", tier, wordMatches);
		}

		private int compareCandidates(ServoySolutionService.FormCandidate a, ServoySolutionService.FormCandidate b)
				throws Exception {
			Method m = ServoySolutionService.class.getDeclaredMethod("compareCandidates",
					ServoySolutionService.FormCandidate.class, ServoySolutionService.FormCandidate.class);
			m.setAccessible(true);
			return (int) m.invoke(null, a, b);
		}

		private int legacyScore(ServoySolutionService.FormCandidate c, String queryLower) throws Exception {
			Method m = ServoySolutionService.class.getDeclaredMethod("legacyScore",
					ServoySolutionService.FormCandidate.class, String.class);
			m.setAccessible(true);
			return (int) m.invoke(null, c, queryLower);
		}

		@Test
		@DisplayName("compareCandidates orders by tier descending")
		void byTierDescending() throws Exception {
			assertTrue(compareCandidates(candidate(3, 0), candidate(1, 0)) < 0, "higher tier sorts first");
			assertTrue(compareCandidates(candidate(1, 0), candidate(3, 0)) > 0);
		}

		@Test
		@DisplayName("compareCandidates breaks a tier tie by wordMatches descending")
		void tieBrokenByWordMatches() throws Exception {
			assertTrue(compareCandidates(candidate(0, 5), candidate(0, 2)) < 0);
			assertTrue(compareCandidates(candidate(0, 2), candidate(0, 5)) > 0);
			assertEquals(0, compareCandidates(candidate(2, 3), candidate(2, 3)), "fully equal -> 0");
		}

		@Test
		@DisplayName("legacyScore returns 1 for a blank query")
		void legacyBlankQuery() throws Exception {
			assertEquals(1, legacyScore(candidate(3, 7), ""));
		}

		@Test
		@DisplayName("legacyScore uses the tier when above 0, else the word-match count")
		void legacyCollapse() throws Exception {
			assertEquals(3, legacyScore(candidate(3, 7), "order"), "tier > 0 -> tier");
			assertEquals(7, legacyScore(candidate(0, 7), "order"), "tier == 0 -> wordMatches");
		}
	}

	// ---------------------------------------------------------------------------
	// getFormLayoutType(Form)
	// ---------------------------------------------------------------------------

	@Nested
	class FormLayoutType {
		private String getFormLayoutType(Form form) throws Exception {
			Method m = ServoySolutionService.class.getDeclaredMethod("getFormLayoutType", Form.class);
			m.setAccessible(true);
			return (String) m.invoke(null, form);
		}

		@Test
		@DisplayName("responsive layout wins over everything")
		void responsive() throws Exception {
			Form form = mock(Form.class);
			when(form.isResponsiveLayout()).thenReturn(true);
			assertEquals("responsive", getFormLayoutType(form));
		}

		@Test
		@DisplayName("css when not responsive and useCssPosition is TRUE")
		void css() throws Exception {
			Form form = mock(Form.class);
			when(form.isResponsiveLayout()).thenReturn(false);
			when(form.getUseCssPosition()).thenReturn(Boolean.TRUE);
			assertEquals("css", getFormLayoutType(form));
		}

		@Test
		@DisplayName("absolute when not responsive and useCssPosition is FALSE or null")
		void absolute() throws Exception {
			Form falseForm = mock(Form.class);
			when(falseForm.isResponsiveLayout()).thenReturn(false);
			when(falseForm.getUseCssPosition()).thenReturn(Boolean.FALSE);
			assertEquals("absolute", getFormLayoutType(falseForm));

			Form nullForm = mock(Form.class);
			when(nullForm.isResponsiveLayout()).thenReturn(false);
			when(nullForm.getUseCssPosition()).thenReturn(null);
			assertEquals("absolute", getFormLayoutType(nullForm), "null useCssPosition -> absolute, no NPE");
		}
	}

	// ---------------------------------------------------------------------------
	// formatOrigin(solutionName, activeSolutionName)
	// ---------------------------------------------------------------------------

	@Nested
	class FormatOrigin {
		private String formatOrigin(ServoySolutionService service, String solutionName, String activeSolutionName)
				throws Exception {
			Method m = ServoySolutionService.class.getDeclaredMethod("formatOrigin", String.class, String.class);
			m.setAccessible(true);
			return (String) m.invoke(service, solutionName, activeSolutionName);
		}

		@Test
		@DisplayName("the active solution is labelled 'active solution'")
		void active() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			assertEquals(" (in: active solution)", formatOrigin(service, "mySolution", "mySolution"));
		}

		@Test
		@DisplayName("a module is labelled by its own name")
		void module() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			assertEquals(" (in: myModule)", formatOrigin(service, "myModule", "mySolution"));
		}
	}

	// ---------------------------------------------------------------------------
	// formatValueListInfo(ValueList, origin)
	// ---------------------------------------------------------------------------

	@Nested
	class FormatValueListInfo {
		private String formatValueListInfo(ServoySolutionService service, ValueList vl, String origin)
				throws Exception {
			Method m = ServoySolutionService.class.getDeclaredMethod("formatValueListInfo", ValueList.class, String.class);
			m.setAccessible(true);
			return (String) m.invoke(service, vl, origin);
		}

		private ValueList valueList(String name, String relationName, String dataSource, String customValues) {
			ValueList vl = mock(ValueList.class);
			lenient().when(vl.getName()).thenReturn(name);
			lenient().when(vl.getRelationName()).thenReturn(relationName);
			lenient().when(vl.getDataSource()).thenReturn(dataSource);
			lenient().when(vl.getCustomValues()).thenReturn(customValues);
			return vl;
		}

		@Test
		@DisplayName("a related valuelist reports DATABASE/RELATED with the relation name")
		void related() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			String out = formatValueListInfo(service, valueList("vlRel", "rel_orders", "db:/x/y", null), "sol");
			assertTrue(out.contains("  - vlRel [sol]"), "got: " + out);
			assertTrue(out.contains("(Type: DATABASE/RELATED, Relation: rel_orders)"), "got: " + out);
			assertTrue(out.endsWith("\n"));
		}

		@Test
		@DisplayName("a table valuelist (no relation) reports DATABASE/TABLE with the datasource")
		void table() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			String out = formatValueListInfo(service, valueList("vlTbl", null, "db:/example/orders", null), "sol");
			assertTrue(out.contains("(Type: DATABASE/TABLE, DataSource: db:/example/orders)"), "got: " + out);
		}

		@Test
		@DisplayName("a custom-values valuelist reports CUSTOM")
		void custom() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			String out = formatValueListInfo(service, valueList("vlCustom", null, null, "a\nb\nc"), "sol");
			assertTrue(out.contains("(Type: CUSTOM)"), "got: " + out);
		}

		@Test
		@DisplayName("a valuelist with none of relation/datasource/custom prints only name and origin")
		void bare() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			String out = formatValueListInfo(service, valueList("vlBare", null, null, null), "sol");
			assertEquals("  - vlBare [sol]\n", out);
		}

		@Test
		@DisplayName("blank relation/datasource/custom are treated as absent")
		void blankFieldsAbsent() throws Exception {
			ServoySolutionService service = new ServoySolutionService();
			String out = formatValueListInfo(service, valueList("vlBlank", "   ", "  ", "  "), "sol");
			assertEquals("  - vlBlank [sol]\n", out, "whitespace-only fields must not trigger a Type");
		}
	}

	// ---------------------------------------------------------------------------
	// No-active-solution guards (list*, findForm, rankForms)
	// ---------------------------------------------------------------------------

	@Nested
	class ActiveSolutionGuard {
		/** A model with no active project. */
		private IDeveloperServoyModel noActiveProject() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			return model;
		}

		/** A model whose active project has no editing solution. */
		private IDeveloperServoyModel noEditingSolution() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getEditingSolution()).thenReturn(null);
			return model;
		}

		@Test
		@DisplayName("every list* and findForm guard with the same no-active-solution message when there is no project")
		void guardNoProject() {
			ServoySolutionService service = serviceWith(noActiveProject());
			assertEquals(NO_ACTIVE, service.listForms(null));
			assertEquals(NO_ACTIVE, service.listRelations(null));
			assertEquals(NO_ACTIVE, service.listValueLists(null));
			assertEquals(NO_ACTIVE, service.listStyles(null));
			assertEquals(NO_ACTIVE, service.findForm("anything"));
		}

		@Test
		@DisplayName("the same guard fires when the active project has no editing solution")
		void guardNoEditingSolution() {
			ServoySolutionService service = serviceWith(noEditingSolution());
			assertEquals(NO_ACTIVE, service.listForms(null));
			assertEquals(NO_ACTIVE, service.listRelations(null));
			assertEquals(NO_ACTIVE, service.listValueLists(null));
			assertEquals(NO_ACTIVE, service.listStyles(null));
			assertEquals(NO_ACTIVE, service.findForm("anything"));
		}

		@Test
		@DisplayName("rankForms returns an empty list (not the guard string) when there is no active solution")
		void rankFormsNoSolution() {
			assertTrue(serviceWith(noActiveProject()).rankForms("order").isEmpty());
			assertTrue(serviceWith(noEditingSolution()).rankForms("order").isEmpty());
		}
	}

	// ---------------------------------------------------------------------------
	// Empty-model formatting (list* with an active-but-empty solution)
	// ---------------------------------------------------------------------------

	@Nested
	class EmptyModelFormatting {
		/** An active solution named {@code solutionName}, no modules, with empty form/relation/valuelist iterators. */
		@SuppressWarnings("unchecked")
		private IDeveloperServoyModel emptyActiveSolution(String solutionName) {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			when(model.getActiveProject()).thenReturn(project);
			when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
			when(project.getEditingSolution()).thenReturn(solution);
			lenient().when(solution.getName()).thenReturn(solutionName);
			lenient().when(solution.getForms(null, false)).thenReturn(java.util.Collections.<Form> emptyIterator());
			lenient().when(solution.getRelations(false))
					.thenReturn(java.util.Collections.<com.servoy.j2db.persistence.Relation> emptyIterator());
			lenient().when(solution.getValueLists(false)).thenReturn(java.util.Collections.<ValueList> emptyIterator());
			return model;
		}

		@Test
		@DisplayName("listForms on an empty solution reports no forms (all scope)")
		void listFormsEmptyAll() {
			ServoySolutionService service = serviceWith(emptyActiveSolution("mySol"));
			assertEquals("No forms found in the active solution", service.listForms(null));
		}

		@Test
		@DisplayName("listForms on an empty solution names the solution in 'current' scope")
		void listFormsEmptyCurrent() {
			ServoySolutionService service = serviceWith(emptyActiveSolution("mySol"));
			assertEquals("No forms found in 'mySol'", service.listForms("current"));
		}

		@Test
		@DisplayName("listRelations on an empty solution reports no relations")
		void listRelationsEmpty() {
			ServoySolutionService service = serviceWith(emptyActiveSolution("mySol"));
			assertEquals("No relations found in the active solution", service.listRelations(null));
			assertEquals("No relations found in 'mySol'", service.listRelations("current"));
		}

		@Test
		@DisplayName("listValueLists on an empty solution reports no valuelists")
		void listValueListsEmpty() {
			ServoySolutionService service = serviceWith(emptyActiveSolution("mySol"));
			assertEquals("No valuelists found in the active solution", service.listValueLists(null));
			assertEquals("No valuelists found in 'mySol'", service.listValueLists("current"));
		}
	}

	// ---------------------------------------------------------------------------
	// Tier constants (guard against a silent reshuffle of the score() array layout)
	// ---------------------------------------------------------------------------

	@Nested
	class TierConstants {
		@Test
		@DisplayName("TIER is index 0 and WORD_MATCHES is index 1 of the score array")
		void indices() throws Exception {
			java.lang.reflect.Field tier = ServoySolutionService.class.getDeclaredField("TIER");
			java.lang.reflect.Field words = ServoySolutionService.class.getDeclaredField("WORD_MATCHES");
			tier.setAccessible(true);
			words.setAccessible(true);
			assertEquals(0, tier.getInt(null));
			assertEquals(1, words.getInt(null));
			assertFalse(tier.getInt(null) == words.getInt(null));
		}
	}
}
