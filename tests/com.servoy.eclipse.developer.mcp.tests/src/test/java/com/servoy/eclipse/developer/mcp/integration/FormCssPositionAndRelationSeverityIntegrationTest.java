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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.servoy.base.query.IBaseSQLCondition;
import com.servoy.base.query.IQueryConstants;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.builder.ServoyBuilder;
import com.servoy.eclipse.model.builder.ServoyBuilderUtils;
import com.servoy.eclipse.model.builder.ServoyRelationBuilder;
import com.servoy.eclipse.model.inmemory.MemServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractTable;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IColumnTypes;
import com.servoy.j2db.persistence.IDataProvider;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.TableNode;
import com.servoy.j2db.query.ColumnType;

/**
 * PDE plug-in integration tests for the two builder marker checks added by SVY-21356:
 * <ul>
 * <li>{@link ServoyBuilder#FORM_CSS_POSITION_NO_BODY_PART} - a CSS-position,
 * non-responsive form whose flattened form has no {@link Part#BODY} now gets an ERROR
 * marker (see {@code ServoyFormBuilder.addFormMarkers}).</li>
 * <li>{@link ServoyBuilder#RELATION_ITEM_TYPE_PROBLEM} - a relation item with a
 * {@code Relation.checkKeyTypes()} mismatch is now ERROR severity (was WARNING), with
 * {@code IMarker.PRIORITY_NORMAL} (see {@code ServoyRelationBuilder.checkRelation}).</li>
 * </ul>
 * <p>
 * Covers spec acceptance criteria (docs/SVY-21356-css-form-body-relation-severity.spec.md,
 * section 5) plus the manually-verified regression scenarios from the investigation.
 */
public class FormCssPositionAndRelationSeverityIntegrationTest extends TestUtilitiesClass
{
	private static final String TEST_SOLUTION = "test_svy21356_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private ServoyProject activeProject;
	private Solution solution;
	private IValidateName validator;

	public FormCssPositionAndRelationSeverityIntegrationTest()
	{
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeClass
	public static void deleteProjectsBeforeClass() throws Exception
	{
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@Before
	public void setUp() throws Exception
	{
		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());
		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull("Active project required", activeProject);
		solution = activeProject.getEditingSolution();
		validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
	}

	// -----------------------------------------------------------------------
	// Shared helpers
	// -----------------------------------------------------------------------

	private static String unique(String prefix)
	{
		return prefix + "_" + System.nanoTime();
	}

	private void saveAndBuild(IPersist... persists) throws Exception
	{
		activeProject.saveEditingSolutionNodes(persists, true);
		activeProject.getProject().build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		waitForWorkspaceBuildJobs();
	}

	/**
	 * Saves the relation and then directly invokes
	 * {@link ServoyRelationBuilder#checkRelation(Relation)}, bypassing
	 * {@code ServoyBuilder}'s server-missing guard.
	 * <p>
	 * The guard at {@code ServoyBuilder.java:2053} skips {@code checkRelation} for
	 * {@code mem:} datasource relations because
	 * {@link com.servoy.j2db.persistence.Relation#getPrimaryServerName()} returns
	 * {@link com.servoy.j2db.persistence.IServer#INMEM_SERVER} ("_sv_inmem") while
	 * {@code addMissingServer} only excludes
	 * {@link com.servoy.j2db.util.DataSourceUtils#INMEM_DATASOURCE} ("mem"). The two
	 * strings differ so "_sv_inmem" is added to {@code missingServers} and
	 * {@code checkRelation} is never reached from a full builder pass. Calling it
	 * directly bypasses this guard while still exercising the real marker-creation code
	 * under test.
	 */
	private void saveAndCheckRelation(Relation relation) throws Exception
	{
		activeProject.saveEditingSolutionNodes(new IPersist[] { relation }, true);
		waitForWorkspaceBuildJobs();
		ServoyRelationBuilder.deleteMarkers(relation); // clear any markers the background build may already have added
		ServoyRelationBuilder.checkRelation(relation);
	}

	private List<IMarker> findMarkersContaining(IPersist persist, String markerType, String messageSubstring)
		throws CoreException
	{
		IResource resource = ServoyBuilderUtils.getPersistResource(persist);
		IMarker[] markers = resource.findMarkers(markerType, false, IResource.DEPTH_ZERO);
		List<IMarker> matched = new ArrayList<>();
		for (IMarker marker : markers)
		{
			Object message = marker.getAttribute(IMarker.MESSAGE);
			if (message != null && message.toString().contains(messageSubstring))
			{
				matched.add(marker);
			}
		}
		return matched;
	}

	private Form createPlainCssPositionForm(String name) throws RepositoryException
	{
		Form form = solution.createNewForm(validator, null, name, null, true, new Dimension(640, 480));
		form.setUseCssPosition(Boolean.TRUE);
		return form;
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 1
	// CSS-position form, zero parts, no extendsID -> ERROR marker by name
	// -----------------------------------------------------------------------

	@Test
	public void testCssPositionFormWithoutBodyPart_getsErrorMarker() throws Exception
	{
		String formName = unique("svy21356_cssNoBody");
		Form form = createPlainCssPositionForm(formName);

		saveAndBuild(form);

		List<IMarker> markers = findMarkersContaining(form, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part");
		assertEquals("Expected exactly one formCssPositionNoBodyPart marker", 1, markers.size());
		IMarker marker = markers.get(0);
		assertEquals("formCssPositionNoBodyPart marker must be ERROR severity",
			IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, -1));
		Object message = marker.getAttribute(IMarker.MESSAGE);
		assertNotNull(message);
		assertTrue("Marker message should reference the form by name: " + message,
			message.toString().contains(formName));
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 2
	// CSS-position form that DOES have a Part.BODY -> no marker
	// -----------------------------------------------------------------------

	@Test
	public void testCssPositionFormWithBodyPart_noMarker() throws Exception
	{
		String formName = unique("svy21356_cssWithBody");
		Form form = createPlainCssPositionForm(formName);
		form.createNewPart(Part.BODY, 480);

		saveAndBuild(form);

		List<IMarker> markers = findMarkersContaining(form, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part");
		assertTrue("Form with a body part must not get the marker: " + markers, markers.isEmpty());
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 3
	// 1-level inheritance: child has no parts, super has body -> no marker
	// -----------------------------------------------------------------------

	@Test
	public void testCssPositionFormInheritingBodyFromDirectSuper_noMarker() throws Exception
	{
		String superName = unique("svy21356_super1");
		Form superForm = createPlainCssPositionForm(superName);
		superForm.createNewPart(Part.BODY, 480);
		saveAndBuild(superForm);

		String childName = unique("svy21356_child1");
		Form childForm = createPlainCssPositionForm(childName);
		childForm.setExtendsForm(superForm);
		childForm.setExtendsID(superForm.getUUID().toString());
		saveAndBuild(childForm);

		List<IMarker> markers = findMarkersContaining(childForm, ServoyBuilder.PROJECT_FORM_MARKER_TYPE,
			"no body part");
		assertTrue("Form inheriting a body part from its super form must not get the marker: " + markers,
			markers.isEmpty());
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 4
	// 2-level inheritance chain: leaf and middle have no parts, grandsuper has body
	// -----------------------------------------------------------------------

	@Test
	public void testCssPositionFormInheritingBodyThroughTwoLevels_noMarker() throws Exception
	{
		String grandSuperName = unique("svy21356_grandSuper");
		Form grandSuperForm = createPlainCssPositionForm(grandSuperName);
		grandSuperForm.createNewPart(Part.BODY, 480);
		saveAndBuild(grandSuperForm);

		String superName = unique("svy21356_super2");
		Form superForm = createPlainCssPositionForm(superName);
		superForm.setExtendsForm(grandSuperForm);
		superForm.setExtendsID(grandSuperForm.getUUID().toString());
		saveAndBuild(superForm);

		String childName = unique("svy21356_child2");
		Form childForm = createPlainCssPositionForm(childName);
		childForm.setExtendsForm(superForm);
		childForm.setExtendsID(superForm.getUUID().toString());
		saveAndBuild(childForm);

		List<IMarker> markers = findMarkersContaining(childForm, ServoyBuilder.PROJECT_FORM_MARKER_TYPE,
			"no body part");
		assertTrue("Form inheriting a body part through a 2-level extends chain must not get the marker: " + markers,
			markers.isEmpty());
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 5
	// Full chain: none of the 3 forms has a body -> ERROR on every form
	// -----------------------------------------------------------------------

	@Test
	public void testCssPositionInheritanceChainWithoutBodyAnywhere_markerOnEveryForm() throws Exception
	{
		String rootName = unique("svy21356_chainRoot");
		Form rootForm = createPlainCssPositionForm(rootName);
		saveAndBuild(rootForm);

		String midName = unique("svy21356_chainMid");
		Form midForm = createPlainCssPositionForm(midName);
		midForm.setExtendsForm(rootForm);
		midForm.setExtendsID(rootForm.getUUID().toString());
		saveAndBuild(midForm);

		String leafName = unique("svy21356_chainLeaf");
		Form leafForm = createPlainCssPositionForm(leafName);
		leafForm.setExtendsForm(midForm);
		leafForm.setExtendsID(midForm.getUUID().toString());
		saveAndBuild(leafForm);

		assertEquals("Root form in the chain should have exactly one marker", 1,
			findMarkersContaining(rootForm, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part").size());
		assertEquals("Middle form in the chain should have exactly one marker", 1,
			findMarkersContaining(midForm, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part").size());
		assertEquals("Leaf form in the chain should have exactly one marker", 1,
			findMarkersContaining(leafForm, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part").size());
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 6
	// Responsive form, zero parts -> no marker (guarded by !isResponsiveLayout())
	// -----------------------------------------------------------------------

	@Test
	public void testResponsiveFormWithoutBodyPart_noMarker() throws Exception
	{
		String formName = unique("svy21356_responsive");
		Form form = solution.createNewForm(validator, null, formName, null, true, new Dimension(640, 480));
		form.setResponsiveLayout(true);

		saveAndBuild(form);

		List<IMarker> markers = findMarkersContaining(form, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part");
		assertTrue("Responsive-layout form must not get the marker: " + markers, markers.isEmpty());
	}

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — Scenario 7
	// Plain anchored abstract form, useCssPosition unset, zero parts -> no marker
	// -----------------------------------------------------------------------

	@Test
	public void testPlainAnchoredAbstractFormWithoutBodyPart_noMarker() throws Exception
	{
		String formName = unique("svy21356_abstract");
		Form form = solution.createNewForm(validator, null, formName, null, true, new Dimension(640, 480));
		// useCssPosition left at default (false), responsive left at default (false):
		// exactly the state of a wizard-created "Abstract (no UI)" form.

		saveAndBuild(form);

		List<IMarker> markers = findMarkersContaining(form, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part");
		assertTrue("Plain anchored abstract form must not get the marker: " + markers, markers.isEmpty());
	}

	// -----------------------------------------------------------------------
	// Relation severity — helpers
	// -----------------------------------------------------------------------

	/**
	 * Creates a MemServer table with one column of the given type.
	 * <p>
	 * NOTE: the relation tests call {@link #saveAndCheckRelation(Relation)} instead of
	 * {@link #saveAndBuild(IPersist...)} — see that method's Javadoc for why.
	 */
	private ITable createMemTableWithColumn(String tableName, String columnName, int columnTypeId)
		throws RepositoryException, java.sql.SQLException
	{
		MemServer memServer = activeProject.getMemServer();
		ITable table = memServer.createNewTable(validator, tableName);
		((AbstractTable)table).createNewColumn(validator, columnName, ColumnType.getInstance(columnTypeId, 0, 0), true);
		memServer.syncTableObjWithDB(table, false, true);

		TableNode tableNode = solution.getOrCreateTableNode(table.getDataSource());
		activeProject.saveEditingSolutionNodes(new IPersist[] { tableNode }, true);
		return table;
	}

	// -----------------------------------------------------------------------
	// Relation severity — Scenario 8
	// DATETIME vs INTEGER (a genuinely incompatible pair) -> ERROR marker
	// -----------------------------------------------------------------------

	@Test
	public void testRelationWithMismatchedKeyTypes_getsErrorMarker() throws Exception
	{
		ITable primaryTable = createMemTableWithColumn(unique("svy21356_dtTable"), "dt_col", IColumnTypes.DATETIME);
		ITable foreignTable = createMemTableWithColumn(unique("svy21356_intTable"), "int_col", IColumnTypes.INTEGER);

		Relation relation = solution.createNewRelation(validator, unique("svy21356_mismatchRel"),
			primaryTable.getDataSource(), foreignTable.getDataSource(), IQueryConstants.LEFT_OUTER_JOIN);
		relation.setAllowCreationRelatedRecords(true);
		relation.createNewRelationItems(
			new IDataProvider[] { primaryTable.getColumn("dt_col") },
			new int[] { IBaseSQLCondition.EQUALS_OPERATOR },
			new Column[] { (Column)foreignTable.getColumn("int_col") });

		saveAndCheckRelation(relation);

		List<IMarker> markers = findMarkersContaining(relation, ServoyBuilder.PROJECT_RELATION_MARKER_TYPE,
			"mismatched keys");
		assertEquals("Expected exactly one relationItemTypeProblem marker", 1, markers.size());
		IMarker marker = markers.get(0);
		assertEquals("relationItemTypeProblem marker must now be ERROR severity (was WARNING)",
			IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, -1));
		assertEquals("relationItemTypeProblem marker must use PRIORITY_NORMAL",
			IMarker.PRIORITY_NORMAL, marker.getAttribute(IMarker.PRIORITY, -1));
	}

	// -----------------------------------------------------------------------
	// Relation severity — Scenario 9
	// Only correctly-typed items -> no marker
	// -----------------------------------------------------------------------

	@Test
	public void testRelationWithOnlyCorrectlyTypedItems_noMarker() throws Exception
	{
		ITable primaryTable = createMemTableWithColumn(unique("svy21356_intTableA"), "int_col_a", IColumnTypes.INTEGER);
		ITable foreignTable = createMemTableWithColumn(unique("svy21356_intTableB"), "int_col_b", IColumnTypes.INTEGER);

		Relation relation = solution.createNewRelation(validator, unique("svy21356_okRel"),
			primaryTable.getDataSource(), foreignTable.getDataSource(), IQueryConstants.LEFT_OUTER_JOIN);
		relation.setAllowCreationRelatedRecords(true);
		relation.createNewRelationItems(
			new IDataProvider[] { primaryTable.getColumn("int_col_a") },
			new int[] { IBaseSQLCondition.EQUALS_OPERATOR },
			new Column[] { (Column)foreignTable.getColumn("int_col_b") });

		saveAndCheckRelation(relation);

		List<IMarker> markers = findMarkersContaining(relation, ServoyBuilder.PROJECT_RELATION_MARKER_TYPE,
			"mismatched keys");
		assertTrue("Relation with only correctly-typed items must not get the marker: " + markers, markers.isEmpty());
	}

	// -----------------------------------------------------------------------
	// Relation severity — Scenario 10
	// One correct item + one mismatched item -> exactly ONE marker (not per-item)
	// -----------------------------------------------------------------------

	@Test
	public void testRelationWithOneCorrectAndOneMismatchedItem_exactlyOneMarker() throws Exception
	{
		ITable primaryTable = createMemTableWithColumn(unique("svy21356_mixedPrimary"), "int_col", IColumnTypes.INTEGER);
		((AbstractTable)primaryTable).createNewColumn(validator, "dt_col",
			ColumnType.getInstance(IColumnTypes.DATETIME, 0, 0), true);
		ITable foreignTable = createMemTableWithColumn(unique("svy21356_mixedForeign"), "int_col", IColumnTypes.INTEGER);
		((AbstractTable)foreignTable).createNewColumn(validator, "int_col2",
			ColumnType.getInstance(IColumnTypes.INTEGER, 0, 0), true);
		MemServer memServer = activeProject.getMemServer();
		memServer.syncTableObjWithDB(primaryTable, false, true);
		memServer.syncTableObjWithDB(foreignTable, false, true);

		Relation relation = solution.createNewRelation(validator, unique("svy21356_mixedRel"),
			primaryTable.getDataSource(), foreignTable.getDataSource(), IQueryConstants.LEFT_OUTER_JOIN);
		relation.setAllowCreationRelatedRecords(true);
		// item 0: correct (INTEGER = INTEGER), item 1: mismatched (DATETIME = INTEGER)
		relation.createNewRelationItems(
			new IDataProvider[] { primaryTable.getColumn("int_col"), primaryTable.getColumn("dt_col") },
			new int[] { IBaseSQLCondition.EQUALS_OPERATOR, IBaseSQLCondition.EQUALS_OPERATOR },
			new Column[] { (Column)foreignTable.getColumn("int_col2"), (Column)foreignTable.getColumn("int_col") });

		saveAndCheckRelation(relation);

		List<IMarker> markers = findMarkersContaining(relation, ServoyBuilder.PROJECT_RELATION_MARKER_TYPE,
			"mismatched keys");
		assertEquals(
			"A relation with one correct and one mismatched item must produce exactly one marker (not one per item): " +
				markers,
			1, markers.size());
	}

	// Scenario 11 (relation used by a related-values valuelist attached to a field
	// on a form) was NOT automated: it requires wiring a full valuelist + field + form
	// combination on top of the relation, which is disproportionate setup for a check
	// that is already exercised at the relation level by the tests above.
	// ServoyRelationBuilder.checkRelation adds the marker only to the relation's own
	// resource — it has no marker-forwarding code path for RELATION_ITEM_TYPE_PROBLEM.

	// -----------------------------------------------------------------------
	// CSS-position-no-body-part marker — AC4: preference IGNORE suppresses marker
	// -----------------------------------------------------------------------

	/**
	 * AC4: when the workspace preference for {@code formCssPositionNoBodyPart} is set
	 * to {@link com.servoy.eclipse.model.builder.ProblemSeverity#IGNORE}, the marker
	 * must NOT be produced, even for a CSS-position form that has no body part.
	 * <p>
	 * This verifies the {@code getSeverity()} + suppress-when-IGNORE contract in
	 * {@code ServoyBuilder.addMarker(IResource, String, String, int, Pair, int, String, IPersist)}.
	 */
	@Test
	public void testCssPositionFormWithoutBodyPart_ignorePref_noMarker() throws Exception
	{
		org.osgi.service.prefs.Preferences prefs = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
			.getNode(ServoyBuilder.ERROR_WARNING_PREFERENCES_NODE);
		String prefKey = ServoyBuilder.FORM_CSS_POSITION_NO_BODY_PART.getLeft();
		String originalValue = prefs.get(prefKey, null); // null means "use default"
		try
		{
			// suppress the marker at workspace level
			prefs.put(prefKey, org.eclipse.dltk.compiler.problem.ProblemSeverity.IGNORE.name());
			prefs.flush();

			String formName = unique("svy21356_cssIgnorePref");
			Form form = createPlainCssPositionForm(formName);
			saveAndBuild(form);

			List<IMarker> markers = findMarkersContaining(form, ServoyBuilder.PROJECT_FORM_MARKER_TYPE, "no body part");
			assertTrue(
				"When formCssPositionNoBodyPart preference is IGNORE, no marker should be produced: " + markers,
				markers.isEmpty());
		}
		finally
		{
			// always restore — do not leave the test workspace in a modified prefs state
			if (originalValue == null)
				prefs.remove(prefKey);
			else
				prefs.put(prefKey, originalValue);
			prefs.flush();
		}
	}

}
