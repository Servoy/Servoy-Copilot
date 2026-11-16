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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.IServerManagerInternal;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;

/**
 * Plain-JUnit (Jupiter) unit tests for the <em>own</em> parse / dispatch /
 * format / error branches of {@link ServoyDevServer}'s artifact-creation tool
 * bodies - with the collaborator {@link ServoyArtifactCreationService} MOCKED
 * via reflection, and (for the model-gated bodies) the model reached through
 * the overridable {@code getServoyModel()} seam, so no live Eclipse workspace,
 * model, DLTK or app server is needed.
 * <p>
 * This class is a sibling of {@code ServoyDevServerTest} (which it does not
 * touch) and focuses <b>exclusively</b> on three tool bodies:
 * {@code createForm}, {@code createRelation} and {@code createValueList}. It
 * mirrors {@code ServoyDevServerTest}'s construction + {@code private final}
 * field-swap-via-reflection pattern: each test instantiates a
 * {@code ServoyDevServer} and reflection-swaps the {@code artifactService}
 * field for a Mockito mock, then drives the server's own branch logic by
 * varying the arguments it passes. The mock returns canned strings; assertions
 * check only the <em>server's</em> argument mapping, parse branches and
 * result/error formatting - never the collaborator's real behaviour.
 * </p>
 * <p>
 * <b>The model seam.</b> {@code createRelation} and {@code createValueList}
 * grab the model as their very first statement, but now via the overridable
 * {@code protected IDeveloperServoyModel getServoyModel()} seam rather than the
 * static {@code ServoyModelManager.getServoyModelManager().getServoyModel()}.
 * The {@link ModelSeamServer} subclass below overrides that seam to return a
 * Mockito-mocked {@link IDeveloperServoyModel}, so their own model-side
 * branches (the duplicate-name guard, the auto-detect trigger, the success
 * dispatch + result/error formatting) are now reachable as plain-JUnit tests
 * without triggering {@code ServoyModelManager.<clinit>} / a live workbench.
 * </p>
 * <p>
 * <b>What still needs the integrationpde suite.</b> The join-type mapping and
 * the valuelist <em>type</em> dispatch (custom|database|related|global_method)
 * live inside the collaborator {@code ServoyArtifactCreationService}, not in
 * these two tool bodies - the bodies pass the raw {@code joinType}/{@code type}
 * strings straight through - so those are the collaborator's own tests, not
 * this server's.
 * </p>
 * <p>
 * <b>The server-manager seam.</b> {@code autoDetectRelationColumns} used to
 * reach the static {@code ApplicationServerRegistry.get().getServerManager()},
 * which a plainjunit fork cannot satisfy. That grab now goes through the second
 * overridable seam {@code protected IServerManagerInternal getServerManager()},
 * which {@link ModelSeamServer} overrides to return a Mockito-mocked
 * {@link com.servoy.j2db.persistence.IServerManagerInternal}. That unlocks the
 * full FK auto-detect path as plain-JUnit tests (see
 * {@code CreateRelationAutoDetectBranches}): the omitted-/blank-column trigger,
 * the server/table lookups, and the three auto-detect outcomes (found by
 * {@code <table>_id}, found by the primary PK column name, and not found ->
 * helper returns {@code null} so the column stays unresolved). The real persist
 * of the resulting relation stays in {@code integrationpde}.
 * </p>
 *
 * @since 2026.11
 */
class ServoyDevServerArtifactBranchesTest
{
	// =======================================================================
	// reflection helper (no production seam / visibility widening) - mirrors
	// ServoyDevServerTest.setField, scoped to the artifactService field here.
	// =======================================================================

	private static void setField(Object target, String name, Object value)
	{
		try
		{
			Field f = ServoyDevServer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to set field '" + name + "'", e);
		}
	}

	/** A server with its {@code artifactService} swapped for the given mock. */
	private static ServoyDevServer serverWith(ServoyArtifactCreationService artifactService)
	{
		ServoyDevServer server = new ServoyDevServer();
		setField(server, "artifactService", artifactService);
		return server;
	}

	// =======================================================================
	// model seam subclass - overrides getServoyModel() to return a mock so the
	// model-gated bodies (createRelation / createValueList) run without a live
	// workbench. Mirrors the getServoyModel-override style the seam enables.
	// =======================================================================

	/**
	 * A {@link ServoyDevServer} whose {@code getServoyModel()} seam returns a
	 * caller-supplied mock, so the model-side branches of {@code createRelation}
	 * and {@code createValueList} are plain-JUnit reachable.
	 */
	private static final class ModelSeamServer extends ServoyDevServer
	{
		private final IDeveloperServoyModel model;
		private final IServerManagerInternal serverManager;

		ModelSeamServer(IDeveloperServoyModel model)
		{
			this(model, null);
		}

		ModelSeamServer(IDeveloperServoyModel model, IServerManagerInternal serverManager)
		{
			this.model = model;
			this.serverManager = serverManager;
		}

		@Override
		protected IDeveloperServoyModel getServoyModel()
		{
			return model;
		}

		@Override
		protected IServerManagerInternal getServerManager()
		{
			return serverManager;
		}
	}

	/**
	 * Builds a {@link ModelSeamServer} with the {@code artifactService} mock
	 * swapped in and a mocked model whose {@code getActiveProject()} returns the
	 * given (already-stubbed) project - or {@code null} when {@code project} is
	 * {@code null} (the "no active project" branch).
	 */
	private static ModelSeamServer modelServer(IDeveloperServoyModel model, ServoyArtifactCreationService artifactService)
	{
		ModelSeamServer server = new ModelSeamServer(model);
		setField(server, "artifactService", artifactService);
		return server;
	}

	/**
	 * Builds a {@link ModelSeamServer} with both seams overridden: the
	 * {@code artifactService} mock swapped in, a mocked model, and a mocked
	 * {@link IServerManagerInternal} returned by {@code getServerManager()} so
	 * the FK auto-detect path in {@code autoDetectRelationColumns} runs without
	 * a live app server.
	 */
	private static ModelSeamServer modelServer(IDeveloperServoyModel model, IServerManagerInternal serverManager,
		ServoyArtifactCreationService artifactService)
	{
		ModelSeamServer server = new ModelSeamServer(model, serverManager);
		setField(server, "artifactService", artifactService);
		return server;
	}

	// =======================================================================
	// createForm - events parsing (own branch logic, real SUPPORTED_EVENTS set)
	// =======================================================================

	@Nested
	class CreateFormEvents
	{
		/**
		 * Makes {@code createForm} take the no-columns branch with default
		 * width/height, so the event-parsing report is the only interesting part
		 * of the returned string. The mocked delegate returns a stable prefix.
		 */
		private ServoyArtifactCreationService mockForm(String createResult) throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createForm(anyString(), anyString(), anyInt(), anyInt(), any(), any(), any()))
				.thenReturn(createResult);
			return svc;
		}

		@Test
		@DisplayName("a bare valid event name is wired via applyEventsToForm with event->event")
		void bareValidEvent() throws Exception
		{
			ServoyArtifactCreationService svc = mockForm("FORM CREATED");
			when(svc.applyEventsToForm(eq("frm"), any())).thenReturn("wired onShow");

			String result = serverWith(svc).createForm("frm", null, null, null, null, null, "onShow", null);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
			verify(svc).applyEventsToForm(eq("frm"), captor.capture());
			assertEquals(Map.of("onShow", "onShow"), captor.getValue(), "bare event maps event name to itself");
			assertTrue(result.startsWith("FORM CREATED"), "got: " + result);
			assertTrue(result.contains("wired onShow"), "wired report appended; got: " + result);
			assertFalse(result.contains("Skipped unrecognised"), "nothing unrecognised; got: " + result);
		}

		@Test
		@DisplayName("an 'event:method' pair is wired with the given method name")
		void eventMethodPair() throws Exception
		{
			ServoyArtifactCreationService svc = mockForm("OK");
			when(svc.applyEventsToForm(eq("frm"), any())).thenReturn("");

			serverWith(svc).createForm("frm", null, null, null, null, null, "onShow:refreshData", null);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
			verify(svc).applyEventsToForm(eq("frm"), captor.capture());
			assertEquals(Map.of("onShow", "refreshData"), captor.getValue());
		}

		@Test
		@DisplayName("an unrecognised single token is reported and never wired")
		void unrecognisedSingleToken() throws Exception
		{
			ServoyArtifactCreationService svc = mockForm("OK");

			String result = serverWith(svc).createForm("frm", null, null, null, null, null, "notAnEvent", null);

			// no valid events -> applyEventsToForm is never called (eventMap empty)
			verify(svc, never()).applyEventsToForm(anyString(), any());
			assertTrue(result.contains("Skipped unrecognised events: notAnEvent"), "got: " + result);
		}

		@Test
		@DisplayName("a malformed entry with three colon parts is reported as unrecognised")
		void malformedThreeColonParts() throws Exception
		{
			ServoyArtifactCreationService svc = mockForm("OK");

			String result = serverWith(svc).createForm("frm", null, null, null, null, null, "onShow:a:b", null);

			verify(svc, never()).applyEventsToForm(anyString(), any());
			assertTrue(result.contains("Skipped unrecognised events: onShow:a:b"), "got: " + result);
		}

		@Test
		@DisplayName("blank or null events wire nothing and report nothing")
		void blankOrNullEvents() throws Exception
		{
			ServoyArtifactCreationService svcNull = mockForm("A");
			String rNull = serverWith(svcNull).createForm("frm", null, null, null, null, null, null, null);
			verify(svcNull, never()).applyEventsToForm(anyString(), any());
			assertEquals("A", rNull, "no event report for null events");

			ServoyArtifactCreationService svcBlank = mockForm("B");
			String rBlank = serverWith(svcBlank).createForm("frm", null, null, null, null, null, "   ", null);
			verify(svcBlank, never()).applyEventsToForm(anyString(), any());
			assertEquals("B", rBlank, "no event report for blank events");
		}

		@Test
		@DisplayName("a mixed events list wires the valid ones and reports the invalid ones")
		void mixedEvents() throws Exception
		{
			ServoyArtifactCreationService svc = mockForm("OK");
			when(svc.applyEventsToForm(eq("frm"), any())).thenReturn("wired 2");

			String result = serverWith(svc)
				.createForm("frm", null, null, null, null, null, "onShow, onHide:hide, bogus, onLoad:a:b", null);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
			verify(svc).applyEventsToForm(eq("frm"), captor.capture());
			assertEquals(Map.of("onShow", "onShow", "onHide", "hide"), captor.getValue(),
				"only the two valid events are wired");
			assertTrue(result.contains("Skipped unrecognised events: bogus, onLoad:a:b"), "got: " + result);
		}

		@Test
		@DisplayName("a trailing-colon entry ('onShow:') collapses to the bare valid event (String.split drops the empty tail)")
		void trailingColonCollapsesToBareEvent() throws Exception
		{
			// "onShow:".split(":") == ["onShow"] in Java (trailing empty strings dropped),
			// so the server takes the kv.length==1 branch: a supported bare event name.
			ServoyArtifactCreationService svc = mockForm("OK");
			when(svc.applyEventsToForm(eq("frm"), any())).thenReturn("wired");

			serverWith(svc).createForm("frm", null, null, null, null, null, "onShow:", null);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
			verify(svc).applyEventsToForm(eq("frm"), captor.capture());
			assertEquals(Map.of("onShow", "onShow"), captor.getValue());
		}

		@Test
		@DisplayName("an ':method' entry (empty event, two parts) is unrecognised, not wired")
		void emptyEventPair() throws Exception
		{
			ServoyArtifactCreationService svc = mockForm("OK");

			String result = serverWith(svc).createForm("frm", null, null, null, null, null, ":doStuff", null);

			verify(svc, never()).applyEventsToForm(anyString(), any());
			assertTrue(result.contains("Skipped unrecognised events: :doStuff"), "got: " + result);
		}
	}

	// =======================================================================
	// createForm - columns branch dispatch + width/height parsing
	// =======================================================================

	@Nested
	class CreateFormColumns
	{
		@Test
		@DisplayName("columns='*' delegates to createFormWithFields with a null column list and responsive default style")
		void allColumns() throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createFormWithFields(eq("frm"), eq("db:/ex/t"), eq("responsive"), isNull()))
				.thenReturn("FIELDS FORM");

			String result = serverWith(svc).createForm("frm", null, "99", "99", "db:/ex/t", null, null, "*");

			verify(svc).createFormWithFields("frm", "db:/ex/t", "responsive", null);
			// width/height are ignored on the columns branch, and createForm(..) is not called
			verify(svc, never()).createForm(anyString(), anyString(), anyInt(), anyInt(), any(), any(), any());
			assertEquals("FIELDS FORM", result, "the delegate result is passed through");
		}

		@Test
		@DisplayName("a comma-separated columns list is parsed (trimmed, blanks dropped) and the given style is kept")
		void columnList() throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createFormWithFields(eq("frm"), eq("db:/ex/t"), eq("css"), any())).thenReturn("OK");

			serverWith(svc).createForm("frm", "css", null, null, "db:/ex/t", null, null, " a : TEXT , b:INTEGER ,  ");

			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
			verify(svc).createFormWithFields(eq("frm"), eq("db:/ex/t"), eq("css"), captor.capture());
			assertEquals(List.of("a : TEXT", "b:INTEGER"), captor.getValue(),
				"entries are trimmed and the trailing blank entry is dropped");
		}

		@Test
		@DisplayName("no columns takes the createForm branch with default 640x480 and default 'css' style")
		void noColumnsDefaults() throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createForm(eq("frm"), eq("css"), eq(640), eq(480), isNull(), isNull(), isNull()))
				.thenReturn("EMPTY FORM");

			String result = serverWith(svc).createForm("frm", null, null, null, null, null, null, null);

			verify(svc).createForm("frm", "css", 640, 480, null, null, null);
			verify(svc, never()).createFormWithFields(anyString(), any(), anyString(), any());
			assertEquals("EMPTY FORM", result);
		}

		@Test
		@DisplayName("explicit width/height and style are parsed and passed through on the no-columns branch")
		void noColumnsGivenValues() throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createForm(eq("frm"), eq("responsive"), eq(800), eq(600), eq("db:/ex/t"), eq("baseForm"), isNull()))
				.thenReturn("SIZED FORM");

			String result = serverWith(svc)
				.createForm("frm", "responsive", "800", "600", "db:/ex/t", "baseForm", null, null);

			verify(svc).createForm("frm", "responsive", 800, 600, "db:/ex/t", "baseForm", null);
			assertEquals("SIZED FORM", result);
		}

		@Test
		@DisplayName("a non-numeric width on the no-columns branch is caught and returned as an 'Error:' string")
		void nonNumericWidth() throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);

			String result = serverWith(svc).createForm("frm", null, "notanumber", null, null, null, null, null);

			// Integer.parseInt throws NumberFormatException -> caught -> "Error: " + message
			assertTrue(result.startsWith("Error:"), "got: " + result);
			verify(svc, never()).createForm(anyString(), anyString(), anyInt(), anyInt(), any(), any(), any());
		}

		@Test
		@DisplayName("a delegate exception on the columns branch is caught and returned as an 'Error:' string")
		void delegateExceptionBecomesError() throws Exception
		{
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createFormWithFields(anyString(), any(), anyString(), any()))
				.thenThrow(new com.servoy.j2db.persistence.RepositoryException("boom"));

			String result = serverWith(svc).createForm("frm", null, null, null, "db:/ex/t", null, null, "*");

			assertTrue(result.startsWith("Error:") && result.contains("boom"), "got: " + result);
		}
	}

	// =======================================================================
	// createRelation - model-side branches via the getServoyModel() seam
	// =======================================================================

	@Nested
	class CreateRelationBranches
	{
		/**
		 * A project whose editing solution reports the given relation
		 * present/absent. Uses {@code doReturn().when()} so the helper never has
		 * an open {@code when(...)} stubbing when it is itself called from inside
		 * another stubbing's argument.
		 */
		private ServoyProject projectWithRelation(String name, Relation existing)
		{
			Solution solution = mock(Solution.class);
			doReturn(existing).when(solution).getRelation(name);
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			return project;
		}

		@Test
		@DisplayName("createRelation returns the duplicate-name error when the editing solution already has the relation")
		void duplicateNameRejected() throws Exception
		{
			ServoyProject project = projectWithRelation("r", mock(Relation.class));
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);

			String result = modelServer(model, svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner");

			assertEquals("Error: Relation 'r' already exists in the active solution.", result);
			// the duplicate guard returns before the collaborator is reached
			verify(svc, never()).createRelation(anyString(), anyString(), anyString(), any(), any(), any());
		}

		@Test
		@DisplayName("createRelation passes explicit primary/foreign columns and joinType straight through to the collaborator")
		void bothColumnsGivenDelegates() throws Exception
		{
			ServoyProject project = projectWithRelation("r", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner")).thenReturn("RELATION CREATED");

			String result = modelServer(model, svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner");

			assertEquals("RELATION CREATED", result, "the collaborator result is passed through unchanged");
			// both columns given => no auto-detect => the raw columns reach the collaborator
			verify(svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner");
		}

		@Test
		@DisplayName("createRelation with a null active project skips the duplicate guard and still delegates")
		void nullActiveProjectDelegates() throws Exception
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", null)).thenReturn("OK");

			String result = modelServer(model, svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", null);

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", null);
		}

		@Test
		@DisplayName("createRelation with a null editing solution skips the duplicate guard and still delegates")
		void nullEditingSolutionDelegates() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			when(project.getEditingSolution()).thenReturn(null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner")).thenReturn("OK");

			String result = modelServer(model, svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner");

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner");
		}

		@Test
		@DisplayName("createRelation catches a collaborator exception and returns it as an 'Error:' string")
		void collaboratorExceptionBecomesError() throws Exception
		{
			ServoyProject project = projectWithRelation("r", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation(anyString(), anyString(), anyString(), any(), any(), any()))
				.thenThrow(new com.servoy.j2db.persistence.RepositoryException("boom"));

			String result = modelServer(model, svc).createRelation("r", "db:/s/p", "db:/s/f", "pk", "fk", "inner");

			assertTrue(result.startsWith("Error:") && result.contains("boom"), "got: " + result);
		}
	}

	// =======================================================================
	// createRelation - FK auto-detect via the getServerManager() seam
	//
	// When primaryColumn/foreignColumn are omitted/blank, createRelation calls
	// the private autoDetectRelationColumns(primary, foreign) helper, which:
	//   - prefixes "db:/" if missing, splits the datasource on '/',
	//     needs >= 2 parts each (server, table) else returns null;
	//   - looks the primary + foreign servers up via getServerManager()
	//     .getServer(name, false, false), null-guards both;
	//   - getTable(tableName) on each, null-guards both;
	//   - takes the primary table's first row-ident (PK) column name;
	//   - tries candidates { "<primaryTable>_id", "<primaryTable>id", pkName }
	//     in order and returns "<pkName>:<candidate>" for the first that the
	//     foreign table has a column for, else null.
	// The result is split on ':' into [primaryCol, foreignCol] and the resolved
	// pair is forwarded to the collaborator; a null (any failure branch) leaves
	// the omitted column(s) unresolved (still null) when forwarded.
	// =======================================================================

	@Nested
	class CreateRelationAutoDetectBranches
	{
		/** A project whose editing solution reports the relation absent. */
		private ServoyProject projectNoRelation()
		{
			Solution solution = mock(Solution.class);
			doReturn(null).when(solution).getRelation(anyString());
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			return project;
		}

		/** A model whose active project is the given (no-duplicate) project. */
		private IDeveloperServoyModel modelWith(ServoyProject project)
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			return model;
		}

		/**
		 * A mocked {@link ITable} whose first row-ident column reports the given
		 * PK name, and whose {@code getColumn(name)} returns a non-null mocked
		 * {@link Column} exactly for the names in {@code presentColumns}.
		 */
		private ITable tableWithPkAndColumns(String pkName, String... presentColumns) throws Exception
		{
			ITable table = mock(ITable.class);
			if (pkName != null)
			{
				Column pk = mock(Column.class);
				doReturn(pkName).when(pk).getName();
				doReturn(List.of(pk)).when(table).getRowIdentColumns();
			}
			// default: unknown columns resolve to null
			doReturn(null).when(table).getColumn(anyString());
			for (String c : presentColumns)
			{
				doReturn(mock(Column.class)).when(table).getColumn(c);
			}
			return table;
		}

		/** A mocked server returning {@code table} for {@code tableName}. */
		private IServerInternal serverWithTable(String tableName, ITable table) throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			doReturn(table).when(server).getTable(tableName);
			return server;
		}

		/**
		 * A server manager whose three-arg {@code getServer(name, false, false)}
		 * returns the primary/foreign servers for their names and {@code null}
		 * otherwise.
		 */
		private IServerManagerInternal serverManager(String primaryServerName, IServerInternal primaryServer,
			String foreignServerName, IServerInternal foreignServer)
		{
			IServerManagerInternal sm = mock(IServerManagerInternal.class);
			doReturn(null).when(sm).getServer(anyString(), eq(false), eq(false));
			if (primaryServer != null) doReturn(primaryServer).when(sm).getServer(primaryServerName, false, false);
			if (foreignServer != null) doReturn(foreignServer).when(sm).getServer(foreignServerName, false, false);
			return sm;
		}

		@Test
		@DisplayName("auto-detect: the foreign table having '<primaryTable>_id' picks that FK column (pk:'<table>_id')")
		void foreignHasTableIdColumn() throws Exception
		{
			// primary db:/srv/customers (PK 'customer_id'); foreign db:/srv/orders has 'customers_id'
			ITable primaryTable = tableWithPkAndColumns("customer_id");
			ITable foreignTable = tableWithPkAndColumns(null, "customers_id");
			IServerInternal srv = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			doReturn(primaryTable).when(srv).getTable("customers");
			doReturn(foreignTable).when(srv).getTable("orders");
			IServerManagerInternal sm = serverManager("srv", srv, "srv", srv);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation("r", "db:/srv/customers", "db:/srv/orders", "customer_id", "customers_id", "left outer"))
				.thenReturn("RELATION CREATED");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/srv/customers", "db:/srv/orders", null, null, "left outer");

			assertEquals("RELATION CREATED", result);
			// pkName ':' '<primaryTable>_id' -> customer_id (primary), customers_id (foreign)
			verify(svc).createRelation("r", "db:/srv/customers", "db:/srv/orders", "customer_id", "customers_id",
				"left outer");
		}

		@Test
		@DisplayName("auto-detect: with no '<table>_id' column, the foreign table having the primary PK column name picks it")
		void foreignHasPkColumnName() throws Exception
		{
			// foreign has NEITHER 'customers_id' NOR 'customersid', but HAS 'customer_id' (the PK name)
			ITable primaryTable = tableWithPkAndColumns("customer_id");
			ITable foreignTable = tableWithPkAndColumns(null, "customer_id");
			IServerInternal srv = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			doReturn(primaryTable).when(srv).getTable("customers");
			doReturn(foreignTable).when(srv).getTable("orders");
			IServerManagerInternal sm = serverManager("srv", srv, "srv", srv);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation("r", "db:/srv/customers", "db:/srv/orders", "customer_id", "customer_id", null))
				.thenReturn("OK");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/srv/customers", "db:/srv/orders", null, null, null);

			assertEquals("OK", result);
			// both the primary and foreign resolve to the PK name 'customer_id'
			verify(svc).createRelation("r", "db:/srv/customers", "db:/srv/orders", "customer_id", "customer_id", null);
		}

		@Test
		@DisplayName("auto-detect: a supplied primaryColumn is kept; only the blank foreignColumn is auto-detected")
		void onlyBlankForeignIsAutoDetected() throws Exception
		{
			ITable primaryTable = tableWithPkAndColumns("customer_id");
			ITable foreignTable = tableWithPkAndColumns(null, "customers_id");
			IServerInternal srv = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			doReturn(primaryTable).when(srv).getTable("customers");
			doReturn(foreignTable).when(srv).getTable("orders");
			IServerManagerInternal sm = serverManager("srv", srv, "srv", srv);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			// supplied primary "pk_given" survives; foreign auto-detected to 'customers_id'
			when(svc.createRelation("r", "db:/srv/customers", "db:/srv/orders", "pk_given", "customers_id", null))
				.thenReturn("OK");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/srv/customers", "db:/srv/orders", "pk_given", "   ", null);

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/srv/customers", "db:/srv/orders", "pk_given", "customers_id", null);
		}

		@Test
		@DisplayName("auto-detect: no matching column -> helper returns null -> the omitted columns stay null when forwarded")
		void noMatchingColumnLeavesColumnsNull() throws Exception
		{
			// foreign table has none of the three candidates
			ITable primaryTable = tableWithPkAndColumns("customer_id");
			ITable foreignTable = tableWithPkAndColumns(null); // no present columns
			IServerInternal srv = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			doReturn(primaryTable).when(srv).getTable("customers");
			doReturn(foreignTable).when(srv).getTable("orders");
			IServerManagerInternal sm = serverManager("srv", srv, "srv", srv);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation(eq("r"), eq("db:/srv/customers"), eq("db:/srv/orders"), isNull(), isNull(), isNull()))
				.thenReturn("DELEGATED NULL COLS");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/srv/customers", "db:/srv/orders", null, null, null);

			assertEquals("DELEGATED NULL COLS", result);
			// null auto-result -> both omitted columns remain null (unresolved) at the collaborator
			verify(svc).createRelation("r", "db:/srv/customers", "db:/srv/orders", null, null, null);
		}

		@Test
		@DisplayName("auto-detect: the primary server not found -> helper returns null -> omitted columns stay null")
		void primaryServerNotFound() throws Exception
		{
			// manager returns a foreign server but null for the primary server name
			ITable foreignTable = tableWithPkAndColumns(null, "customers_id");
			IServerInternal foreignServer = serverWithTable("orders", foreignTable);
			IServerManagerInternal sm = serverManager("primarySrv", null, "foreignSrv", foreignServer);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation(eq("r"), eq("db:/primarySrv/customers"), eq("db:/foreignSrv/orders"), isNull(),
				isNull(), isNull())).thenReturn("OK");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/primarySrv/customers", "db:/foreignSrv/orders", null, null, null);

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/primarySrv/customers", "db:/foreignSrv/orders", null, null, null);
		}

		@Test
		@DisplayName("auto-detect: the foreign server not found -> helper returns null -> omitted columns stay null")
		void foreignServerNotFound() throws Exception
		{
			ITable primaryTable = tableWithPkAndColumns("customer_id");
			IServerInternal primaryServer = serverWithTable("customers", primaryTable);
			IServerManagerInternal sm = serverManager("primarySrv", primaryServer, "foreignSrv", null);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation(eq("r"), eq("db:/primarySrv/customers"), eq("db:/foreignSrv/orders"), isNull(),
				isNull(), isNull())).thenReturn("OK");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/primarySrv/customers", "db:/foreignSrv/orders", null, null, null);

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/primarySrv/customers", "db:/foreignSrv/orders", null, null, null);
		}

		@Test
		@DisplayName("auto-detect: an invalid datasource (< 2 '/'-parts) -> helper returns null -> omitted columns stay null, no server lookup")
		void invalidDatasourceFormat() throws Exception
		{
			// 'db:/onlyserver' has a single part after 'db:/' -> primaryParts.length < 2 -> null
			IServerManagerInternal sm = mock(IServerManagerInternal.class);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation(eq("r"), eq("db:/onlyserver"), eq("db:/srv/orders"), isNull(), isNull(), isNull()))
				.thenReturn("OK");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/onlyserver", "db:/srv/orders", null, null, null);

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/onlyserver", "db:/srv/orders", null, null, null);
			// the length guard fires before any server is looked up
			verify(sm, never()).getServer(anyString(), anyBoolean(), anyBoolean());
		}

		@Test
		@DisplayName("auto-detect: the primary table has no row-ident (PK) columns -> helper returns null -> omitted columns stay null")
		void primaryTableHasNoPkColumns() throws Exception
		{
			ITable primaryTable = mock(ITable.class);
			doReturn(List.of()).when(primaryTable).getRowIdentColumns();
			ITable foreignTable = tableWithPkAndColumns(null, "customers_id");
			IServerInternal srv = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			doReturn(primaryTable).when(srv).getTable("customers");
			doReturn(foreignTable).when(srv).getTable("orders");
			IServerManagerInternal sm = serverManager("srv", srv, "srv", srv);

			ServoyProject project = projectNoRelation();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createRelation(eq("r"), eq("db:/srv/customers"), eq("db:/srv/orders"), isNull(), isNull(), isNull()))
				.thenReturn("OK");

			String result = modelServer(modelWith(project), sm, svc)
				.createRelation("r", "db:/srv/customers", "db:/srv/orders", null, null, null);

			assertEquals("OK", result);
			verify(svc).createRelation("r", "db:/srv/customers", "db:/srv/orders", null, null, null);
		}
	}

	// =======================================================================
	// createValueList - model-side branches via the getServoyModel() seam
	// =======================================================================

	@Nested
	class CreateValueListBranches
	{
		/**
		 * A project whose editing solution reports the given valuelist
		 * present/absent. Uses {@code doReturn().when()} so the helper never has
		 * an open {@code when(...)} stubbing while it is being called.
		 */
		private ServoyProject projectWithValueList(String name, ValueList existing)
		{
			Solution solution = mock(Solution.class);
			doReturn(existing).when(solution).getValueList(name);
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			return project;
		}

		@Test
		@DisplayName("createValueList returns the duplicate-name error when the editing solution already has the valuelist")
		void duplicateNameRejected() throws Exception
		{
			ServoyProject project = projectWithValueList("vl", mock(ValueList.class));
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);

			String result = modelServer(model, svc).createValueList("vl", "custom", "A\nB", null, null, null, null);

			assertEquals("Error: ValueList 'vl' already exists in the active solution.", result);
			verify(svc, never()).createValueList(anyString(), any(), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("createValueList passes a 'custom' type's arguments straight through to the collaborator")
		void customTypeDelegates() throws Exception
		{
			ServoyProject project = projectWithValueList("vl", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList("vl", "custom", "Active\nInactive", null, null, null, null))
				.thenReturn("VALUELIST CREATED");

			String result = modelServer(model, svc)
				.createValueList("vl", "custom", "Active\nInactive", null, null, null, null);

			assertEquals("VALUELIST CREATED", result, "the collaborator result is passed through unchanged");
			verify(svc).createValueList("vl", "custom", "Active\nInactive", null, null, null, null);
		}

		@Test
		@DisplayName("createValueList passes a 'database' type's datasource/columns straight through to the collaborator")
		void databaseTypeDelegates() throws Exception
		{
			ServoyProject project = projectWithValueList("countries", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList("countries", "database", null, "db:/s/country", null, "name", "code"))
				.thenReturn("OK");

			String result = modelServer(model, svc)
				.createValueList("countries", "database", null, "db:/s/country", null, "name", "code");

			assertEquals("OK", result);
			// the server does NOT branch on type - it forwards every argument verbatim
			verify(svc).createValueList("countries", "database", null, "db:/s/country", null, "name", "code");
		}

		@Test
		@DisplayName("createValueList passes a 'related' type's relation/columns straight through to the collaborator")
		void relatedTypeDelegates() throws Exception
		{
			ServoyProject project = projectWithValueList("vl", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList("vl", "related", null, null, "orders_to_lines", "descr", "line_id"))
				.thenReturn("OK");

			String result = modelServer(model, svc)
				.createValueList("vl", "related", null, null, "orders_to_lines", "descr", "line_id");

			assertEquals("OK", result);
			verify(svc).createValueList("vl", "related", null, null, "orders_to_lines", "descr", "line_id");
		}

		@Test
		@DisplayName("createValueList forwards a null type to the collaborator (the 'custom' default is applied inside the collaborator, not here)")
		void nullTypeForwardedVerbatim() throws Exception
		{
			ServoyProject project = projectWithValueList("vl", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList(eq("vl"), isNull(), eq("A\nB"), isNull(), isNull(), isNull(), isNull()))
				.thenReturn("OK");

			String result = modelServer(model, svc).createValueList("vl", null, "A\nB", null, null, null, null);

			assertEquals("OK", result);
			verify(svc).createValueList("vl", null, "A\nB", null, null, null, null);
		}

		@Test
		@DisplayName("createValueList with a null active project skips the duplicate guard and still delegates")
		void nullActiveProjectDelegates() throws Exception
		{
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(null);
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList("vl", "custom", "A", null, null, null, null)).thenReturn("OK");

			String result = modelServer(model, svc).createValueList("vl", "custom", "A", null, null, null, null);

			assertEquals("OK", result);
			verify(svc).createValueList("vl", "custom", "A", null, null, null, null);
		}

		@Test
		@DisplayName("createValueList with a null editing solution skips the duplicate guard and still delegates")
		void nullEditingSolutionDelegates() throws Exception
		{
			ServoyProject project = mock(ServoyProject.class);
			when(project.getEditingSolution()).thenReturn(null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			when(model.getActiveProject()).thenReturn(project);
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList("vl", "custom", "A", null, null, null, null)).thenReturn("OK");

			String result = modelServer(model, svc).createValueList("vl", "custom", "A", null, null, null, null);

			assertEquals("OK", result);
			verify(svc).createValueList("vl", "custom", "A", null, null, null, null);
		}

		@Test
		@DisplayName("createValueList catches a collaborator exception and returns it as an 'Error:' string")
		void collaboratorExceptionBecomesError() throws Exception
		{
			ServoyProject project = projectWithValueList("vl", null);
			IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
			doReturn(project).when(model).getActiveProject();
			ServoyArtifactCreationService svc = mock(ServoyArtifactCreationService.class);
			when(svc.createValueList(anyString(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new com.servoy.j2db.persistence.RepositoryException("kaboom"));

			String result = modelServer(model, svc).createValueList("vl", "custom", "A", null, null, null, null);

			assertTrue(result.startsWith("Error:") && result.contains("kaboom"), "got: " + result);
		}
	}
}
