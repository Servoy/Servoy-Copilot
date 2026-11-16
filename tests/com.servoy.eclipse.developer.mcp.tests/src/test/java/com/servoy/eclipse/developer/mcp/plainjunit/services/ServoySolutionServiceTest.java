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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.EclipseRepository;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.util.UUID;

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

	// ---------------------------------------------------------------------------
	// Shared mock-model builders for the populated-solution listing/finding tests
	// ---------------------------------------------------------------------------

	/**
	 * A {@link Form} mock named {@code name}, whose {@code getRootObject()} returns a {@link Solution}
	 * named {@code originSolution} (so {@code getSolutionName} resolves the origin), with the given
	 * data source and layout flags.
	 */
	private static Form form(String name, String originSolution, String dataSource,
			boolean responsive, Boolean useCssPosition) {
		Form form = mock(Form.class);
		Solution root = mock(Solution.class);
		lenient().when(root.getName()).thenReturn(originSolution);
		lenient().when(form.getRootObject()).thenReturn(root);
		lenient().when(form.getName()).thenReturn(name);
		lenient().when(form.getDataSource()).thenReturn(dataSource);
		lenient().when(form.isResponsiveLayout()).thenReturn(responsive);
		lenient().when(form.getUseCssPosition()).thenReturn(useCssPosition);
		lenient().when(form.getTitleText()).thenReturn(null);
		lenient().when(form.getComment()).thenReturn(null);
		return form;
	}

	/** A {@link Relation} mock with the given name, datasources and join type (1 = INNER, else LEFT OUTER). */
	private static Relation relation(String name, String originSolution, String primaryDs, String foreignDs,
			int joinType) {
		Relation rel = mock(Relation.class);
		Solution root = mock(Solution.class);
		lenient().when(root.getName()).thenReturn(originSolution);
		lenient().when(rel.getRootObject()).thenReturn(root);
		lenient().when(rel.getName()).thenReturn(name);
		lenient().when(rel.getPrimaryDataSource()).thenReturn(primaryDs);
		lenient().when(rel.getForeignDataSource()).thenReturn(foreignDs);
		lenient().when(rel.getJoinType()).thenReturn(joinType);
		return rel;
	}

	/**
	 * Builds a model with one active project + editing solution named {@code solutionName} and no
	 * modules. Each per-type iterator is stubbed lazily by the individual test so only the list*
	 * under test needs its content.
	 */
	private static IDeveloperServoyModel populatedModel(String solutionName, Solution solution,
			ServoyProject project) {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		lenient().when(model.getActiveProject()).thenReturn(project);
		lenient().when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
		lenient().when(project.getEditingSolution()).thenReturn(solution);
		lenient().when(solution.getName()).thenReturn(solutionName);
		return model;
	}

	// ---------------------------------------------------------------------------
	// listForms happy path (populated solution, all + current scope)
	// ---------------------------------------------------------------------------

	@Nested
	class ListFormsPopulated {
		@Test
		@DisplayName("listForms lists every form with origin, datasource and layout type (all scope)")
		void listsFormsAllScope() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = populatedModel("mySol", solution, project);
			Form responsiveForm = form("frmA", "mySol", "db:/ex/orders", true, null);
			Form absoluteForm = form("frmB", "mySol", null, false, Boolean.FALSE);
			when(solution.getForms(null, false)).thenReturn(Arrays.asList(responsiveForm, absoluteForm).iterator());

			String out = serviceWith(model).listForms(null);
			assertTrue(out.startsWith("Forms in solution 'mySol' and modules (2):"), "header; got: " + out);
			assertTrue(out.contains("1. frmA (in: active solution) - DataSource: db:/ex/orders (responsive)"),
					"first form; got: " + out);
			assertTrue(out.contains("2. frmB (in: active solution) (absolute)"), "second form; got: " + out);
		}

		@Test
		@DisplayName("listForms with 'current' scope names the active solution and uses the css layout label")
		void listsFormsCurrentScope() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = populatedModel("mySol", solution, project);
			Form cssForm = form("frmCss", "mySol", null, false, Boolean.TRUE);
			when(solution.getForms(null, false)).thenReturn(Collections.singletonList(cssForm).iterator());

			String out = serviceWith(model).listForms("current");
			assertTrue(out.startsWith("Forms in 'mySol' (1):"), "header; got: " + out);
			assertTrue(out.contains("1. frmCss (in: active solution) (css)"), "got: " + out);
		}
	}

	// ---------------------------------------------------------------------------
	// listRelations happy path (join-type labels, datasources)
	// ---------------------------------------------------------------------------

	@Nested
	class ListRelationsPopulated {
		@Test
		@DisplayName("listRelations lists each relation with its datasources and join-type label")
		void listsRelations() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = populatedModel("mySol", solution, project);
			Relation inner = relation("rel_inner", "mySol", "db:/ex/a", "db:/ex/b", 1);
			Relation outer = relation("rel_outer", "mySol", "db:/ex/c", "db:/ex/d", 0);
			when(solution.getRelations(false)).thenReturn(Arrays.asList(inner, outer).iterator());

			String out = serviceWith(model).listRelations("current");
			assertTrue(out.startsWith("Relations in 'mySol' (2):"), "header; got: " + out);
			assertTrue(out.contains("1. rel_inner (in: active solution)"), "got: " + out);
			assertTrue(out.contains("Primary: db:/ex/a"), "got: " + out);
			assertTrue(out.contains("Foreign: db:/ex/b"), "got: " + out);
			assertTrue(out.contains("(INNER JOIN)"), "inner join label; got: " + out);
			assertTrue(out.contains("(LEFT OUTER JOIN)"), "outer join label; got: " + out);
		}
	}

	// ---------------------------------------------------------------------------
	// listValueLists happy path (formatValueListInfo + header)
	// ---------------------------------------------------------------------------

	@Nested
	class ListValueListsPopulated {
		@Test
		@DisplayName("listValueLists prints the count header and one formatted line per valuelist (current scope)")
		void listsValueLists() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = populatedModel("mySol", solution, project);
			ValueList custom = mock(ValueList.class);
			lenient().when(custom.getName()).thenReturn("vlCustom");
			lenient().when(custom.getCustomValues()).thenReturn("a\nb");
			when(solution.getValueLists(false)).thenReturn(Collections.singletonList(custom).iterator());

			String out = serviceWith(model).listValueLists("current");
			assertTrue(out.startsWith("ValueLists (1):"), "header; got: " + out);
			assertTrue(out.contains("  - vlCustom [mySol] (Type: CUSTOM)"), "got: " + out);
		}

		@Test
		@DisplayName("listValueLists in 'all' scope walks the active solution and its modules")
		void listsValueListsAcrossModules() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			ServoyProject moduleProject = mock(ServoyProject.class);
			Solution moduleSolution = mock(Solution.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[] { moduleProject });
			when(project.getEditingSolution()).thenReturn(solution);
			when(solution.getName()).thenReturn("mySol");
			when(moduleProject.getEditingSolution()).thenReturn(moduleSolution);
			when(moduleSolution.getName()).thenReturn("myModule");

			ValueList a = mock(ValueList.class);
			lenient().when(a.getName()).thenReturn("vlA");
			lenient().when(a.getDataSource()).thenReturn("db:/ex/t");
			ValueList b = mock(ValueList.class);
			lenient().when(b.getName()).thenReturn("vlB");
			when(solution.getValueLists(false)).thenReturn(Collections.singletonList(a).iterator());
			when(moduleSolution.getValueLists(false)).thenReturn(Collections.singletonList(b).iterator());

			String out = serviceWith(model).listValueLists(null);
			assertTrue(out.startsWith("ValueLists (2):"), "header counts both solutions; got: " + out);
			assertTrue(out.contains("  - vlA [mySol] (Type: DATABASE/TABLE, DataSource: db:/ex/t)"), "got: " + out);
			assertTrue(out.contains("  - vlB [myModule]"), "module valuelist tagged with its own solution; got: " + out);
		}
	}

	// ---------------------------------------------------------------------------
	// listStyles happy path (.less existence per solution)
	// ---------------------------------------------------------------------------

	@Nested
	class ListStyles {
		/** Wires project.getProject().getFile("medias/<sol>.less").exists() to return {@code exists}. */
		private void wireLessFile(ServoyProject project, String solutionName, boolean exists) {
			IProject eclipseProject = mock(IProject.class);
			IFile lessFile = mock(IFile.class);
			when(project.getProject()).thenReturn(eclipseProject);
			when(eclipseProject.getFile("medias/" + solutionName + ".less")).thenReturn(lessFile);
			when(lessFile.exists()).thenReturn(exists);
		}

		@Test
		@DisplayName("listStyles reports the .less file as existing when the medias file is present")
		void reportsExistingLess() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = populatedModel("mySol", solution, project);
			wireLessFile(project, "mySol", true);

			String out = serviceWith(model).listStyles("current");
			assertTrue(out.startsWith("Styles:"), "header; got: " + out);
			assertTrue(out.contains("  - mySol: mySol.less (exists)\n"), "got: " + out);
		}

		@Test
		@DisplayName("listStyles reports no .less file when the medias file is absent")
		void reportsNoLess() {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = populatedModel("mySol", solution, project);
			wireLessFile(project, "mySol", false);

			String out = serviceWith(model).listStyles("current");
			assertTrue(out.contains("  - mySol: no .less file\n"), "got: " + out);
		}
	}

	// ---------------------------------------------------------------------------
	// findForm / rankForms over the flattened solution
	// ---------------------------------------------------------------------------

	@Nested
	class FindAndRankForms {
		/**
		 * A model whose editing flattened solution ({@code getEditingFlattenedSolution()}) returns the
		 * given forms, with the active editing solution named {@code solutionName}.
		 */
		private IDeveloperServoyModel modelWithFlatForms(String solutionName, Form... forms) {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			FlattenedSolution fs = mock(FlattenedSolution.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			when(project.getEditingSolution()).thenReturn(solution);
			lenient().when(solution.getName()).thenReturn(solutionName);
			when(project.getEditingFlattenedSolution()).thenReturn(fs);
			when(fs.getForms(false)).thenReturn(Arrays.asList(forms).iterator());
			return model;
		}

		@Test
		@DisplayName("findForm renders a markdown block per matching form, best match first")
		void findFormMatches() {
			Form exact = form("orders", "mySol", "db:/ex/orders", false, Boolean.FALSE);
			Form contains = form("ordersDetail", "mySol", null, false, Boolean.FALSE);
			ServoySolutionService service = serviceWith(modelWithFlatForms("mySol", contains, exact));

			String out = service.findForm("orders");
			assertTrue(out.startsWith("# Forms matching 'orders' (2 found)"), "header; got: " + out);
			assertTrue(out.contains("## orders"), "got: " + out);
			assertTrue(out.contains("## ordersDetail"), "got: " + out);
			// exact (tier 3) must be rendered before the contains match (tier 2)
			assertTrue(out.indexOf("## orders\n") < out.indexOf("## ordersDetail"), "best match first; got: " + out);
			assertTrue(out.contains("- **module:** mySol (active)"), "active module label; got: " + out);
			assertTrue(out.contains("- **dataSource:** db:/ex/orders"), "got: " + out);
			assertTrue(out.contains("- **layout:** absolute"), "got: " + out);
		}

		@Test
		@DisplayName("findForm reports no match with the listForms tip when nothing scores")
		void findFormNoMatch() {
			Form unrelated = form("customers", "mySol", null, false, Boolean.FALSE);
			ServoySolutionService service = serviceWith(modelWithFlatForms("mySol", unrelated));

			String out = service.findForm("zzzzz");
			assertTrue(out.startsWith("No forms found matching 'zzzzz'."), "got: " + out);
			assertTrue(out.contains("use listForms to see all available forms"), "tip; got: " + out);
		}

		@Test
		@DisplayName("rankForms returns the matching candidates best-first, each with its origin and tier")
		void rankFormsOrders() {
			Form exact = form("orders", "mySol", null, false, Boolean.FALSE);
			Form contains = form("ordersList", "mySol", null, false, Boolean.FALSE);
			ServoySolutionService service = serviceWith(modelWithFlatForms("mySol", contains, exact));

			List<ServoySolutionService.FormCandidate> ranked = service.rankForms("orders");
			assertEquals(2, ranked.size(), "both forms match 'orders'");
			assertEquals("orders", ranked.get(0).form().getName(), "exact match ranks first");
			assertEquals(3, ranked.get(0).tier(), "exact name -> tier 3");
			assertEquals("mySol", ranked.get(0).module());
			assertEquals(2, ranked.get(1).tier(), "contains -> tier 2");
		}
	}

	// ---------------------------------------------------------------------------
	// deleteArtifacts routing / result formatting (model mutation fully mocked)
	// ---------------------------------------------------------------------------

	@Nested
	class DeleteArtifacts {
		/**
		 * A model whose active project resolves {@code existingName} to a {@link Form} (found via
		 * {@code editingSolution.getForm}) and everything else to null. The repository + save calls are
		 * mocked so the delete routing and result formatting run without any real mutation.
		 */
		private IDeveloperServoyModel modelWithDeletableForm(String existingName, EclipseRepository repository,
				Form form) {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			lenient().when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
			when(project.getEditingSolution()).thenReturn(solution);
			lenient().when(solution.getName()).thenReturn("mySol");
			lenient().when(solution.getForm(existingName)).thenReturn(form);
			lenient().when(solution.getRepository()).thenReturn(repository);
			UUID uuid = UUID.randomUUID();
			lenient().when(form.getUUID()).thenReturn(uuid);
			lenient().when(project.getEditingPersist(uuid)).thenReturn(form);
			return model;
		}

		@Test
		@DisplayName("deleteForms deletes a found form and reports it, saving the editing node")
		void deletesFoundForm() throws Exception {
			EclipseRepository repository = mock(EclipseRepository.class);
			Form form = mock(Form.class);
			IDeveloperServoyModel model = modelWithDeletableForm("frmA", repository, form);
			ServoySolutionService service = serviceWith(model);

			String out = service.deleteForms(Collections.singletonList("frmA"));
			assertEquals("Successfully deleted 1 form(s): frmA", out);
			verify(repository).deleteObject(any(IPersist.class));
			verify(model.getActiveProject()).saveEditingSolutionNodes(any(IPersist[].class), anyBoolean());
		}

		@Test
		@DisplayName("deleteForms reports a form that is nowhere in the solution as not found")
		void reportsNotFound() {
			EclipseRepository repository = mock(EclipseRepository.class);
			Form form = mock(Form.class);
			IDeveloperServoyModel model = modelWithDeletableForm("frmA", repository, form);
			ServoySolutionService service = serviceWith(model);

			String out = service.deleteForms(Collections.singletonList("ghost"));
			assertEquals("Not found (1): ghost", out);
		}

		@Test
		@DisplayName("deleteForms mixes a deleted form and a missing one into one summary")
		void deletedAndNotFound() {
			EclipseRepository repository = mock(EclipseRepository.class);
			Form form = mock(Form.class);
			IDeveloperServoyModel model = modelWithDeletableForm("frmA", repository, form);
			ServoySolutionService service = serviceWith(model);

			String out = service.deleteForms(Arrays.asList("frmA", "ghost"));
			assertTrue(out.contains("Successfully deleted 1 form(s): frmA"), "got: " + out);
			assertTrue(out.contains("Not found (1): ghost"), "got: " + out);
		}

		@Test
		@DisplayName("deleteForms with only blank/null names reports nothing specified and never touches the repository")
		void onlyBlankNames() throws Exception {
			EclipseRepository repository = mock(EclipseRepository.class);
			Form form = mock(Form.class);
			IDeveloperServoyModel model = modelWithDeletableForm("frmA", repository, form);
			ServoySolutionService service = serviceWith(model);

			String out = service.deleteForms(Arrays.asList(null, "   "));
			assertEquals("No forms specified for deletion", out);
			verify(repository, never()).deleteObject(any(IPersist.class));
		}

		@Test
		@DisplayName("deleteForms guards with the no-active-solution message before any lookup")
		void guardNoActiveSolution() {
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			assertEquals(NO_ACTIVE, serviceWith(model).deleteForms(Collections.singletonList("frmA")));
		}

		@Test
		@DisplayName("deleteRelations routes through the relation finder and reports the deletion")
		void deletesRelation() throws Exception {
			ServoyProject project = mock(ServoyProject.class);
			Solution solution = mock(Solution.class);
			EclipseRepository repository = mock(EclipseRepository.class);
			Relation rel = mock(Relation.class);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			lenient().when(model.getModulesOfActiveProject()).thenReturn(new ServoyProject[0]);
			when(project.getEditingSolution()).thenReturn(solution);
			lenient().when(solution.getRelation("rel_a")).thenReturn(rel);
			lenient().when(solution.getRepository()).thenReturn(repository);
			UUID uuid = UUID.randomUUID();
			lenient().when(rel.getUUID()).thenReturn(uuid);
			lenient().when(project.getEditingPersist(uuid)).thenReturn(rel);

			String out = serviceWith(model).deleteRelations(Collections.singletonList("rel_a"));
			assertEquals("Successfully deleted 1 relation(s): rel_a", out);
			verify(repository, times(1)).deleteObject(any(IPersist.class));
		}
	}
}
