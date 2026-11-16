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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.servoy.base.query.IQueryConstants;
import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.util.UUID;

/**
 * Plain-JUnit (Jupiter) unit tests for {@link ServoyArtifactCreationService}'s
 * <em>own</em> building / validation / dispatch / result-formatting logic,
 * driven through the overridable {@code getServoyModel()} seam with a mocked
 * {@link IDeveloperServoyModel}.
 * <p>
 * The service grabs the model as the very first statement of
 * {@code createForm}, {@code createRelation}, {@code createValueList} and
 * {@code createFormWithFields} via the seam, so overriding it with a Mockito
 * mock reaches every model-side branch without a live workbench / app server /
 * DLTK. The Servoy core the model hands back ({@link ServoyProject},
 * {@link Solution}, {@link Form}, {@link Relation}, {@link ValueList}) is only
 * stubbed to return canned objects at the boundary so the service's own
 * branches run; the core's real behaviour is NOT asserted here (that belongs in
 * servoy-eclipse and in the {@code CreateArtifactsIntegrationTest} happy-path).
 * </p>
 * <p>
 * <b>What stays integration.</b> The {@code .dbi}/table lookups that go through
 * the static {@code ServoyModelFinder.getServoyModel()} (column-mapping on
 * {@code db:/} relations, {@code createFormWithFields}'s real column walk,
 * {@code ensureDbiForDataSource}) are not seamed and need the live app server -
 * so success here uses {@code mem:}/null datasources that skip those statics,
 * and the full table-backed paths are left to the integration suite. The
 * {@code AiBridge.applyEventMethod} wiring is a static the form success path
 * does not reach unless an event is requested, so the event tests use the
 * resolve-or-create skeleton helper directly (reflection) rather than the full
 * {@code createForm} events loop.
 * </p>
 *
 * @since 2026.11
 */
class ServoyArtifactCreationServiceModelTest {
	// =======================================================================
	// seam subclass + builders
	// =======================================================================

	/** A service whose {@code getServoyModel()} seam returns the supplied mock. */
	private static ServoyArtifactCreationService serviceWith(IDeveloperServoyModel model) {
		return new ServoyArtifactCreationService() {
			@Override
			protected IDeveloperServoyModel getServoyModel() {
				return model;
			}
		};
	}

	/** A model whose active project is the given one, with a null-returning validator. */
	private static IDeveloperServoyModel modelWith(ServoyProject project) {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		doReturn(project).when(model).getActiveProject();
		doReturn(mock(IValidateName.class)).when(model).getNameValidator();
		return model;
	}

	// -----------------------------------------------------------------------
	// createForm
	// -----------------------------------------------------------------------

	@Nested
	class CreateFormBranches {
		/**
		 * A project whose editing solution has no form of the given name and
		 * whose {@code createNewForm} returns the supplied mocked {@link Form};
		 * the editing flattened solution reports the given parent form (or none).
		 */
		private ServoyProject projectCreatingForm(Form created, FlattenedSolution fs) throws Exception {
			Solution solution = mock(Solution.class);
			doReturn(null).when(solution).getForm(anyString());
			doReturn(created).when(solution).createNewForm(any(), any(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
				any());
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			doReturn(fs).when(project).getEditingFlattenedSolution();
			return project;
		}

		@Test
		@DisplayName("createForm throws when there is no active project")
		void noActiveProjectThrows() {
			IDeveloperServoyModel model = modelWith(null);
			RepositoryException ex = assertThrows(RepositoryException.class,
				() -> serviceWith(model).createForm("frm", null, 0, 0, null, null, null));
			assertEquals("No active Servoy solution project found", ex.getMessage());
		}

		@Test
		@DisplayName("createForm throws when the active project has no editing solution")
		void noEditingSolutionThrows() {
			ServoyProject project = mock(ServoyProject.class);
			doReturn(null).when(project).getEditingSolution();
			RepositoryException ex = assertThrows(RepositoryException.class,
				() -> serviceWith(modelWith(project)).createForm("frm", null, 0, 0, null, null, null));
			assertEquals("No active Servoy solution project found", ex.getMessage());
		}

		@Test
		@DisplayName("createForm requires a non-blank name")
		void blankNameThrows() {
			Solution solution = mock(Solution.class);
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			ServoyArtifactCreationService svc = serviceWith(modelWith(project));
			assertEquals("Form name is required",
				assertThrows(RepositoryException.class, () -> svc.createForm("  ", null, 0, 0, null, null, null)).getMessage());
			assertEquals("Form name is required",
				assertThrows(RepositoryException.class, () -> svc.createForm(null, null, 0, 0, null, null, null)).getMessage());
		}

		@Test
		@DisplayName("createForm returns the already-exists message when the editing solution has the form")
		void duplicateFormReported() throws Exception {
			Solution solution = mock(Solution.class);
			doReturn(mock(Form.class)).when(solution).getForm("frm");
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();

			String result = serviceWith(modelWith(project)).createForm("frm", null, 0, 0, null, null, null);

			assertEquals("Form 'frm' already exists in the active solution.", result);
		}

		@Test
		@DisplayName("createForm (css) builds a BODY part, css-position form and reports the css size with defaults")
		void cssFormBuildsPartAndReportsSize() throws Exception {
			Form form = mock(Form.class);
			ServoyProject project = projectCreatingForm(form, null);

			String result = serviceWith(modelWith(project)).createForm("frm", null, 0, 0, null, null, null);

			// NAVIGATOR_NONE always set; css branch creates a BODY part + sets css position
			verify(form).setNavigatorID(Form.NAVIGATOR_NONE);
			verify(form).createNewPart(eq(com.servoy.j2db.persistence.Part.BODY), eq(480));
			verify(form).setUseCssPosition(Boolean.TRUE);
			verify(form, never()).setResponsiveLayout(org.mockito.ArgumentMatchers.anyBoolean());
			verify(project).saveEditingSolutionNodes(any(IPersist[].class), eq(true));

			assertTrue(result.startsWith("Form 'frm' created successfully"), result);
			assertTrue(result.contains("(css, 640x480)"), "default size + css label; got: " + result);
			assertFalse(result.contains("DataSource:"), "no datasource line when none given; got: " + result);
			assertFalse(result.contains("Extends:"), "no extends line when none given; got: " + result);
		}

		@Test
		@DisplayName("createForm (responsive) sets responsive layout, creates no part, and reports the responsive label + size")
		void responsiveFormSetsResponsiveLayout() throws Exception {
			Form form = mock(Form.class);
			ServoyProject project = projectCreatingForm(form, null);

			String result = serviceWith(modelWith(project)).createForm("frm", "responsive", 800, 600, null, null, null);

			verify(form).setNavigatorID(Form.NAVIGATOR_NONE);
			verify(form).setResponsiveLayout(true);
			verify(form, never()).createNewPart(anyInt(), anyInt());
			verify(form, never()).setUseCssPosition(any());
			assertTrue(result.contains("(responsive, 800x600)"), "explicit size + responsive label; got: " + result);
		}

		@Test
		@DisplayName("createForm wires a parent form resolved from the editing flattened solution")
		void extendsFormResolvedFromFlattenedSolution() throws Exception {
			Form form = mock(Form.class);
			Form parent = mock(Form.class);
			UUID parentUuid = UUID.randomUUID();
			doReturn(parentUuid).when(parent).getUUID();
			FlattenedSolution fs = mock(FlattenedSolution.class);
			doReturn(parent).when(fs).getForm("baseForm");
			ServoyProject project = projectCreatingForm(form, fs);

			String result = serviceWith(modelWith(project)).createForm("frm", null, 0, 0, "mem:t", "baseForm", null);

			verify(form).setExtendsForm(parent);
			verify(form).setExtendsID(parentUuid.toString());
			assertTrue(result.contains("DataSource: mem:t"), "datasource line present; got: " + result);
			assertTrue(result.contains("Extends: baseForm"), "extends line present; got: " + result);
		}

		@Test
		@DisplayName("createForm with an unresolved parent form skips inheritance but still succeeds")
		void extendsFormNotFoundSkipsInheritance() throws Exception {
			Form form = mock(Form.class);
			FlattenedSolution fs = mock(FlattenedSolution.class);
			doReturn(null).when(fs).getForm("missing");
			ServoyProject project = projectCreatingForm(form, fs);

			String result = serviceWith(modelWith(project)).createForm("frm", null, 0, 0, null, "missing", null);

			verify(form, never()).setExtendsForm(any());
			verify(form, never()).setExtendsID(anyString());
			assertTrue(result.startsWith("Form 'frm' created successfully"), result);
			assertTrue(result.contains("Extends: missing"), "the requested extends is still reported; got: " + result);
		}
	}

	// -----------------------------------------------------------------------
	// createRelation
	// -----------------------------------------------------------------------

	@Nested
	class CreateRelationBranches {
		/** A project creating the given relation, no existing relation of that name. */
		private ServoyProject projectCreatingRelation(Relation created) throws Exception {
			Solution solution = mock(Solution.class);
			doReturn(created).when(solution).createNewRelation(any(), anyString(), anyString(), anyString(), anyInt());
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			return project;
		}

		@Test
		@DisplayName("createRelation throws without an active project / editing solution")
		void noActiveProjectThrows() {
			assertEquals("No active Servoy solution project found", assertThrows(RepositoryException.class,
				() -> serviceWith(modelWith(null)).createRelation("r", "db:/s/p", "db:/s/f", null, null, null)).getMessage());
		}

		@Test
		@DisplayName("createRelation validates name, primaryDataSource and foreignDataSource")
		void requiredArgsValidated() throws Exception {
			Solution solution = mock(Solution.class);
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			ServoyArtifactCreationService svc = serviceWith(modelWith(project));

			assertEquals("Relation name is required", assertThrows(RepositoryException.class,
				() -> svc.createRelation(" ", "db:/s/p", "db:/s/f", null, null, null)).getMessage());
			assertEquals("primaryDataSource is required", assertThrows(RepositoryException.class,
				() -> svc.createRelation("r", "  ", "db:/s/f", null, null, null)).getMessage());
			assertEquals("foreignDataSource is required", assertThrows(RepositoryException.class,
				() -> svc.createRelation("r", "db:/s/p", null, null, null, null)).getMessage());
		}

		@Test
		@DisplayName("createRelation maps joinType 'inner' to INNER_JOIN, enables related-record creation, and reports INNER")
		void innerJoinMappedAndReported() throws Exception {
			Relation relation = mock(Relation.class);
			ServoyProject project = projectCreatingRelation(relation);

			String result = serviceWith(modelWith(project)).createRelation("r", "db:/s/p", "db:/s/f", null, null, "inner");

			verify(project.getEditingSolution()).createNewRelation(any(), eq("r"), eq("db:/s/p"), eq("db:/s/f"),
				eq(IQueryConstants.INNER_JOIN));
			verify(relation).setAllowCreationRelatedRecords(true);
			verify(project).saveEditingSolutionNodes(any(IPersist[].class), eq(true));
			assertTrue(result.startsWith("Relation 'r' created successfully"), result);
			assertTrue(result.contains("Primary: db:/s/p") && result.contains("Foreign: db:/s/f"), result);
			assertTrue(result.contains("Join: INNER"), "inner join label; got: " + result);
			assertFalse(result.contains("Mapping:"), "no mapping line when no columns given; got: " + result);
			assertFalse(result.contains("WARNING:"), "no warning when mapping not attempted; got: " + result);
		}

		@Test
		@DisplayName("createRelation maps any non-'inner' joinType to LEFT OUTER")
		void defaultJoinIsLeftOuter() throws Exception {
			Relation relation = mock(Relation.class);
			ServoyProject project = projectCreatingRelation(relation);

			String result = serviceWith(modelWith(project)).createRelation("r", "db:/s/p", "db:/s/f", null, null, null);

			verify(project.getEditingSolution()).createNewRelation(any(), eq("r"), eq("db:/s/p"), eq("db:/s/f"),
				eq(IQueryConstants.LEFT_OUTER_JOIN));
			assertTrue(result.contains("Join: LEFT OUTER"), "default join label; got: " + result);
		}

		@Test
		@DisplayName("createRelation auto-corrects a bare server/table datasource to db:/ before creating")
		void bareDatasourceCorrected() throws Exception {
			Relation relation = mock(Relation.class);
			ServoyProject project = projectCreatingRelation(relation);

			String result = serviceWith(modelWith(project)).createRelation("r", "s/p", "s/f", null, null, "inner");

			// the bare "s/p" / "s/f" are prefixed to "db:/s/p" / "db:/s/f" by correctDataSource
			verify(project.getEditingSolution()).createNewRelation(any(), eq("r"), eq("db:/s/p"), eq("db:/s/f"), anyInt());
			assertTrue(result.contains("Primary: db:/s/p"), "corrected primary datasource reported; got: " + result);
		}
	}

	// -----------------------------------------------------------------------
	// createValueList
	// -----------------------------------------------------------------------

	@Nested
	class CreateValueListBranches {
		/** A project creating the given valuelist. */
		private ServoyProject projectCreatingValueList(ValueList created) throws Exception {
			Solution solution = mock(Solution.class);
			doReturn(created).when(solution).createNewValueList(any(), anyString());
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			return project;
		}

		@Test
		@DisplayName("createValueList throws without an active project")
		void noActiveProjectThrows() {
			assertEquals("No active Servoy solution project found", assertThrows(RepositoryException.class,
				() -> serviceWith(modelWith(null)).createValueList("vl", "custom", "A", null, null, null, null)).getMessage());
		}

		@Test
		@DisplayName("createValueList requires a non-blank name")
		void blankNameThrows() {
			Solution solution = mock(Solution.class);
			ServoyProject project = mock(ServoyProject.class);
			doReturn(solution).when(project).getEditingSolution();
			assertEquals("ValueList name is required", assertThrows(RepositoryException.class,
				() -> serviceWith(modelWith(project)).createValueList(" ", "custom", "A", null, null, null, null)).getMessage());
		}

		@Test
		@DisplayName("createValueList 'custom' sets CUSTOM_VALUES (0) + custom values and reports the custom type")
		void customTypeSetsCustomValues() throws Exception {
			ValueList vl = mock(ValueList.class);
			ServoyProject project = projectCreatingValueList(vl);

			String result = serviceWith(modelWith(project)).createValueList("vl", "custom", "A\nB", null, null, null, null);

			verify(vl).setValueListType(0);
			verify(vl).setCustomValues("A\nB");
			verify(project).saveEditingSolutionNodes(any(IPersist[].class), eq(true));
			assertEquals("ValueList 'vl' created successfully (type: custom)", result);
		}

		@Test
		@DisplayName("createValueList 'related' sets DATABASE_VALUES (1), the relation name and display/return providers")
		void relatedTypeSetsRelationAndProviders() throws Exception {
			ValueList vl = mock(ValueList.class);
			ServoyProject project = projectCreatingValueList(vl);

			String result = serviceWith(modelWith(project))
				.createValueList("vl", "related", null, null, "orders_to_lines", "descr", "line_id");

			verify(vl).setValueListType(1);
			verify(vl).setRelationName("orders_to_lines");
			verify(vl).setDataProviderID1("descr");
			verify(vl).setDataProviderID2("line_id");
			assertEquals("ValueList 'vl' created successfully (type: related)", result);
		}

		@Test
		@DisplayName("createValueList 'global_method' sets GLOBAL_METHOD_VALUES (4) and the custom values")
		void globalMethodTypeSetsType4() throws Exception {
			ValueList vl = mock(ValueList.class);
			ServoyProject project = projectCreatingValueList(vl);

			String result = serviceWith(modelWith(project))
				.createValueList("vl", "global_method", "globals.myVlProvider", null, null, null, null);

			verify(vl).setValueListType(4);
			verify(vl).setCustomValues("globals.myVlProvider");
			assertEquals("ValueList 'vl' created successfully (type: global_method)", result);
		}

		@Test
		@DisplayName("createValueList with no type / datasource / relation falls back to the custom type")
		void defaultFallsBackToCustom() throws Exception {
			ValueList vl = mock(ValueList.class);
			ServoyProject project = projectCreatingValueList(vl);

			String result = serviceWith(modelWith(project)).createValueList("vl", null, "X\nY", null, null, null, null);

			verify(vl).setValueListType(0);
			verify(vl).setCustomValues("X\nY");
			assertEquals("ValueList 'vl' created successfully (type: custom)", result);
		}

		@Test
		@DisplayName("createValueList does NOT set the return provider when it equals the display provider")
		void returnEqualDisplayNotSet() throws Exception {
			ValueList vl = mock(ValueList.class);
			ServoyProject project = projectCreatingValueList(vl);

			serviceWith(modelWith(project)).createValueList("vl", "related", null, null, "rel", "same", "same");

			verify(vl).setDataProviderID1("same");
			verify(vl, never()).setDataProviderID2(anyString());
		}
	}

	// -----------------------------------------------------------------------
	// private helpers via reflection (no visibility widening)
	// -----------------------------------------------------------------------

	@Nested
	class SkeletonForEvent {
		private String skeleton(String eventName, String methodName) throws Exception {
			Method m = ServoyArtifactCreationService.class.getDeclaredMethod("skeletonForEvent", String.class, String.class);
			m.setAccessible(true);
			return (String) m.invoke(new ServoyArtifactCreationService(), eventName, methodName);
		}

		@Test
		@DisplayName("onRecordEditStop -> (record, event) with @return {Boolean}")
		void recordEditStop() throws Exception {
			String s = skeleton("onRecordEditStop", "doStop");
			assertTrue(s.contains("function doStop(record, event)"), s);
			assertTrue(s.contains("@param {JSRecord} record") && s.contains("@return {Boolean}"), s);
		}

		@Test
		@DisplayName("onElementDataChange -> (oldValue, newValue, event)")
		void elementDataChange() throws Exception {
			String s = skeleton("onElementDataChange", "chg");
			assertTrue(s.contains("function chg(oldValue, newValue, event)"), s);
			assertTrue(s.contains("@param {Object} oldValue") && s.contains("@param {Object} newValue"), s);
		}

		@Test
		@DisplayName("onSort -> (dataProviderID, asc, event), no @return")
		void sort() throws Exception {
			String s = skeleton("onSort", "srt");
			assertTrue(s.contains("function srt(dataProviderID, asc, event)"), s);
			assertFalse(s.contains("@return"), "onSort has no @return; got: " + s);
		}

		@Test
		@DisplayName("onBeforeHide -> (event) with @return {Boolean}")
		void beforeHide() throws Exception {
			String s = skeleton("onBeforeHide", "bh");
			assertTrue(s.contains("function bh(event)") && s.contains("@return {Boolean}"), s);
		}

		@Test
		@DisplayName("an unlisted event falls back to a plain (event) skeleton")
		void defaultEvent() throws Exception {
			String s = skeleton("onShow", "show");
			assertTrue(s.contains("function show(event)"), s);
			assertTrue(s.contains("@param {JSEvent} event"), s);
			assertTrue(s.contains("// TODO: implement"), s);
		}
	}

	@Nested
	class ResolveOrCreateMethod {
		private String resolve(ServoyArtifactCreationService svc, Form form, String methodName, String eventName,
			IValidateName validator) throws Throwable {
			Method m = ServoyArtifactCreationService.class.getDeclaredMethod("resolveOrCreateMethod", Form.class, String.class,
				String.class, IValidateName.class);
			m.setAccessible(true);
			try {
				return (String) m.invoke(svc, form, methodName, eventName, validator);
			} catch (InvocationTargetException ite) {
				throw ite.getCause();
			}
		}

		@Test
		@DisplayName("an existing method of the same name is reused (no new method created)")
		void reusesExistingMethod() throws Throwable {
			UUID uuid = UUID.randomUUID();
			ScriptMethod existing = mock(ScriptMethod.class);
			doReturn("doIt").when(existing).getName();
			doReturn(uuid).when(existing).getUUID();
			Form form = mock(Form.class);
			doReturn(Collections.singletonList(existing).iterator()).when(form).getScriptMethods(false);

			String result = resolve(new ServoyArtifactCreationService(), form, "doIt", "onShow", mock(IValidateName.class));

			assertEquals(uuid.toString(), result);
			verify(form, never()).createNewScriptMethod(any(), anyString());
		}

		@Test
		@DisplayName("a missing method is created with the per-event skeleton as its declaration")
		void createsNewMethodWithSkeleton() throws Throwable {
			UUID uuid = UUID.randomUUID();
			ScriptMethod created = mock(ScriptMethod.class);
			doReturn(uuid).when(created).getUUID();
			Form form = mock(Form.class);
			doReturn(Collections.<ScriptMethod> emptyList().iterator()).when(form).getScriptMethods(false);
			doReturn(created).when(form).createNewScriptMethod(any(), eq("newM"));

			String result = resolve(new ServoyArtifactCreationService(), form, "newM", "onSort", mock(IValidateName.class));

			assertEquals(uuid.toString(), result);
			ArgumentCaptor<String> decl = ArgumentCaptor.forClass(String.class);
			verify(created).setDeclaration(decl.capture());
			assertTrue(decl.getValue().contains("function newM(dataProviderID, asc, event)"),
				"the onSort skeleton is used; got: " + decl.getValue());
		}
	}

	@Nested
	class FormatColumnLabel {
		private String format(String columnName) throws Exception {
			Method m = ServoyArtifactCreationService.class.getDeclaredMethod("formatColumnLabel", String.class);
			m.setAccessible(true);
			return (String) m.invoke(new ServoyArtifactCreationService(), columnName);
		}

		@Test
		@DisplayName("underscores/hyphens become spaces and each word is capitalized")
		void humanizesColumnNames() throws Exception {
			assertEquals("Customer Id", format("customer_id"));
			assertEquals("First Name", format("first-name"));
			assertEquals("Name", format("name"));
		}
	}

	@Nested
	class CreateCssPosition {
		private String position(int left, int right, int top, int bottom, int width, int height) throws Exception {
			Method m = ServoyArtifactCreationService.class.getDeclaredMethod("createCssPosition", int.class, int.class, int.class,
				int.class, int.class, int.class);
			m.setAccessible(true);
			return (String) m.invoke(new ServoyArtifactCreationService(), left, right, top, bottom, width, height);
		}

		@Test
		@DisplayName("non-negative coordinates become '<n>px' and negatives become '-1', in top,right,bottom,left,width,height order")
		void formatsCssPositionString() throws Exception {
			// serialized order is top,right,bottom,left,width,height
			// args are (left=10, right=-1, top=20, bottom=-1, width=150, height=30)
			assertEquals("20px,-1,-1,10px,150px,30px", position(10, -1, 20, -1, 150, 30));
			assertEquals("0px,0px,0px,0px,0px,0px", position(0, 0, 0, 0, 0, 0));
		}
	}

	// A small assertion that the seam itself is in place (behaviour-preserving
	// contract: the default service exposes a protected getServoyModel()).
	@Test
	@DisplayName("the service exposes a getServoyModel() seam that subclasses can override")
	void seamIsOverridable() throws Exception {
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		ServoyArtifactCreationService svc = serviceWith(model);
		Method m = ServoyArtifactCreationService.class.getDeclaredMethod("getServoyModel");
		m.setAccessible(true);
		assertSame(model, m.invoke(svc), "the override is used by the seam");
	}
}
