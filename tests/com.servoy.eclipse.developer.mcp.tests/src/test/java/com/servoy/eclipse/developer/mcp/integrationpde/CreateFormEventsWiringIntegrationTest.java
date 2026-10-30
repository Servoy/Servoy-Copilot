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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IPersistVisitor;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.WebComponent;

/**
 * Integration tests for the {@code createForm} MCP tool's event-handler wiring
 * (SVY-21596), covering {@link ServoyDevServer#createForm} and the new
 * {@link com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService#applyEventsToForm}
 * delegation to {@link com.servoy.eclipse.core.ai.AiBridge#applyEventMethod}.
 * <p>
 * These exercise the full create + wire round-trip against a live workbench and
 * active Servoy solution (the tool resolves the just-created form from the
 * active project's editing flattened solution, creates real
 * {@code ScriptMethod} persists, and sets the form's event-method IDs), so they
 * must run under the PDE harness - hence the {@code integrationpde} package,
 * the {@link TestUtilitiesClassCopy} base, and the {@code *IntegrationTest}
 * suffix.
 * </p>
 * <p>
 * Each acceptance criterion from the spec is covered: events honoured together
 * with {@code columns}; events-without-columns (no regression); a bare event
 * name wiring with the verbatim-event-name default; unrecognised/malformed
 * events reported in the result string while valid events in the same call
 * still wire; per-event method skeletons carrying the correct parameter list;
 * and the three previously-drifted events ({@code onBeforeRecordSelection},
 * {@code onElementFocusGained}, {@code onElementFocusLost}) now wiring via
 * {@code AiBridge}.
 * </p>
 */
public class CreateFormEventsWiringIntegrationTest extends TestUtilitiesClassCopy {

	private static final String TEST_SOLUTION = "test_createform_events_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public CreateFormEventsWiringIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Resolves the form from the active project's editing flattened solution - the
	 * resolution the tool itself uses (and the only one that also sees forms living
	 * in modules), per the project convention.
	 */
	private Form resolveForm(String formName) {
		FlattenedSolution fs = activeProject.getEditingFlattenedSolution();
		assertNotNull(fs, "editing flattened solution must be resolvable");
		return fs.getForm(formName);
	}

	/**
	 * Finds a {@link ScriptMethod} declared on the form by name, or {@code null}
	 * when absent.
	 */
	private ScriptMethod findMethod(Form form, String methodName) {
		Iterator<ScriptMethod> it = form.getScriptMethods(false);
		while (it.hasNext()) {
			ScriptMethod m = it.next();
			if (methodName.equals(m.getName()))
				return m;
		}
		return null;
	}

	/**
	 * Asserts the given form event is wired to a real method with the expected
	 * name: the form's event-method UUID is non-blank, a method of that name exists
	 * on the form, and the two identify the same persist.
	 *
	 * @param eventMethodUUID the UUID the form stores for the event (e.g.
	 *                        {@code form.getOnShowMethodID()})
	 */
	private ScriptMethod assertWired(Form form, String eventMethodUUID, String methodName) {
		assertNotNull(eventMethodUUID, "event-method UUID must be set on the form for a wired handler");
		assertFalse(eventMethodUUID.isBlank(), "event-method UUID must not be blank for a wired handler");
		assertNotEqualsNoneSentinel(eventMethodUUID);

		ScriptMethod method = findMethod(form, methodName);
		assertNotNull(method, "the backing method '" + methodName + "' must have been created on the form");
		assertEquals(method.getUUID().toString(), eventMethodUUID,
				"the form's event-method UUID must point at the created method '" + methodName + "'");
		return method;
	}

	/**
	 * A set event-method ID must be a real UUID reference, never the "-1"/"0"
	 * none-sentinel some persist int-IDs historically used.
	 */
	private void assertNotEqualsNoneSentinel(String eventMethodUUID) {
		assertFalse("-1".equals(eventMethodUUID) || "0".equals(eventMethodUUID),
				"event-method reference must be a real UUID, not a none-sentinel: " + eventMethodUUID);
	}

	private int countWebComponents(Form form) {
		int[] count = { 0 };
		form.acceptVisitor(o -> {
			if (o instanceof WebComponent)
				count[0]++;
			return IPersistVisitor.CONTINUE_TRAVERSAL;
		});
		return count[0];
	}

	/**
	 * Creates an in-memory table with the given columns and returns its datasource.
	 */
	private String createMemTable(String tableName, String columnsSpec) {
		String result = devServer.createTable(null, tableName, "true", columnsSpec);
		assertNotNull(result);
		assertTrue(result.contains("created") || result.contains("already exists"),
				"mem table creation should succeed or already exist: " + result);
		return "mem:" + tableName;
	}

	private String uniqueName(String prefix) {
		return prefix + "_" + System.nanoTime();
	}

	// -----------------------------------------------------------------------
	// AC: columns + events in a single call -> fields AND wired handlers
	// -----------------------------------------------------------------------

	@Nested
	class ColumnsAndEvents {

		@Test
		@org.junit.jupiter.api.DisplayName("columns + events: form has fields AND each requested handler wired + backed by a created method, in one call")
		void columnsAndEvents_wiresHandlersAndCreatesFields() {
			String tableName = uniqueName("evcols_tbl");
			String dataSource = createMemTable(tableName, "name:TEXT:100,email:TEXT:200");
			String formName = uniqueName("evcolsForm");

			String result = devServer.createForm(formName, "responsive", null, null, dataSource, null,
					"onShow:refreshData,onLoad:initForm", "*");

			assertNotNull(result);
			assertFalse(result.startsWith("Error"), "single-call create should succeed: " + result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist in the active (flattened) solution");

			assertAll(
					() -> assertTrue(countWebComponents(form) > 0,
							"the columns branch must still have generated label+field web components: webComponents="
									+ countWebComponents(form)),
					() -> assertWired(form, form.getOnShowMethodID(), "refreshData"),
					() -> assertWired(form, form.getOnLoadMethodID(), "initForm"));
		}
	}

	// -----------------------------------------------------------------------
	// AC: events with no columns -> handlers wired (no regression)
	// -----------------------------------------------------------------------

	@Nested
	class EventsWithoutColumns {

		@Test
		@org.junit.jupiter.api.DisplayName("events without columns: handlers wired and methods created (no regression)")
		void eventsOnly_wiresHandlers() {
			String formName = uniqueName("evOnlyForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null,
					"onLoad:initForm,onShow:refreshData", null);

			assertNotNull(result);
			assertFalse(result.startsWith("Error"), "create should succeed: " + result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");

			assertAll(() -> assertWired(form, form.getOnLoadMethodID(), "initForm"),
					() -> assertWired(form, form.getOnShowMethodID(), "refreshData"));
		}
	}

	// -----------------------------------------------------------------------
	// AC: bare event name -> default method name = event name verbatim
	// -----------------------------------------------------------------------

	@Nested
	class BareEventName {

		@Test
		@org.junit.jupiter.api.DisplayName("bare event name 'onShow' wires the handler and creates method named 'onShow'")
		void bareEventName_usesEventNameAsMethodName() {
			String formName = uniqueName("bareEvForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null, "onShow", null);

			assertNotNull(result);
			assertFalse(result.startsWith("Error"), "create should succeed: " + result);
			assertFalse(result.contains("Skipped unrecognised events"),
					"a supported bare event name must NOT be reported as unrecognised: " + result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");

			// default method-name rule: the event name verbatim
			assertWired(form, form.getOnShowMethodID(), "onShow");
		}
	}

	// -----------------------------------------------------------------------
	// AC: unrecognised/unsupported events reported, not silently dropped;
	// valid events in the same call still wire.
	// -----------------------------------------------------------------------

	@Nested
	class UnrecognisedEvents {

		@Test
		@org.junit.jupiter.api.DisplayName("unsupported event 'onBogus:foo' is reported in the result string, not silently dropped")
		void unsupportedEvent_isReported() {
			String formName = uniqueName("bogusEvForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null, "onBogus:foo", null);

			assertNotNull(result);
			assertTrue(result.contains("Skipped unrecognised events"),
					"an unsupported event must be reported back: " + result);
			assertTrue(result.contains("onBogus:foo"), "the reported note must name the offending entry: " + result);
		}

		@Test
		@org.junit.jupiter.api.DisplayName("malformed event 'a:b:c' is reported in the result string, not silently dropped")
		void malformedEvent_isReported() {
			String formName = uniqueName("malformedEvForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null, "a:b:c", null);

			assertNotNull(result);
			assertTrue(result.contains("Skipped unrecognised events"),
					"a malformed (multi-colon) entry must be reported back: " + result);
			assertTrue(result.contains("a:b:c"), "the reported note must name the malformed entry: " + result);
		}

		@Test
		@org.junit.jupiter.api.DisplayName("valid events in the same call still wire even when another entry is unrecognised")
		void validEventsStillWire_whenOneUnrecognised() {
			String formName = uniqueName("mixedEvForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null,
					"onShow:refreshData,onBogus:foo", null);

			assertNotNull(result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");

			assertAll(
					() -> assertTrue(result.contains("Skipped unrecognised events") && result.contains("onBogus:foo"),
							"the unrecognised entry must still be reported: " + result),
					() -> assertWired(form, form.getOnShowMethodID(), "refreshData"));
		}
	}

	// -----------------------------------------------------------------------
	// AC: per-event method skeleton matches the event's real parameter list.
	// -----------------------------------------------------------------------

	@Nested
	class PerEventSkeletons {

		@Test
		@org.junit.jupiter.api.DisplayName("onShow skeleton has an (event) parameter list")
		void onShow_skeletonHasEventParam() {
			String formName = uniqueName("skelShowForm");

			devServer.createForm(formName, "css", "640", "480", null, null, "onShow:handleShow", null);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");
			ScriptMethod method = findMethod(form, "handleShow");
			assertNotNull(method, "the onShow method must have been created");

			String decl = method.getDeclaration();
			assertNotNull(decl, "the created method must carry a skeleton declaration");
			assertTrue(decl.contains("function handleShow(event)"),
					"onShow skeleton must take a single (event) parameter: " + decl);
			assertTrue(decl.contains("@param {JSEvent} event"),
					"onShow skeleton must document the {JSEvent} event param: " + decl);
		}

		@Test
		@org.junit.jupiter.api.DisplayName("onRecordEditStop skeleton has (record, event) and returns Boolean")
		void onRecordEditStop_skeletonHasRecordAndEventAndBooleanReturn() {
			String formName = uniqueName("skelRESForm");

			devServer.createForm(formName, "css", "640", "480", null, null, "onRecordEditStop:handleStop", null);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");
			ScriptMethod method = findMethod(form, "handleStop");
			assertNotNull(method, "the onRecordEditStop method must have been created");

			String decl = method.getDeclaration();
			assertNotNull(decl, "the created method must carry a skeleton declaration");
			assertAll(
					() -> assertTrue(decl.contains("function handleStop(record, event)"),
							"onRecordEditStop skeleton must take (record, event): " + decl),
					() -> assertTrue(decl.contains("@param {JSRecord} record"),
							"onRecordEditStop skeleton must document the {JSRecord} record param: " + decl),
					() -> assertTrue(decl.contains("@param {JSEvent} event"),
							"onRecordEditStop skeleton must document the {JSEvent} event param: " + decl),
					() -> assertTrue(decl.contains("@return {Boolean}"),
							"onRecordEditStop skeleton must declare a {Boolean} return: " + decl));
		}

		@Test
		@org.junit.jupiter.api.DisplayName("onElementDataChange skeleton has (oldValue, newValue, event) and returns Boolean")
		void onElementDataChange_skeletonHasThreeParamsAndBooleanReturn() {
			String formName = uniqueName("skelEDCForm");

			devServer.createForm(formName, "css", "640", "480", null, null, "onElementDataChange:handleChange", null);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");
			ScriptMethod method = findMethod(form, "handleChange");
			assertNotNull(method, "the onElementDataChange method must have been created");

			String decl = method.getDeclaration();
			assertNotNull(decl, "the created method must carry a skeleton declaration");
			assertAll(
					() -> assertTrue(decl.contains("function handleChange(oldValue, newValue, event)"),
							"onElementDataChange skeleton must take (oldValue, newValue, event): " + decl),
					() -> assertTrue(decl.contains("@param {Object} oldValue"),
							"onElementDataChange skeleton must document the oldValue param: " + decl),
					() -> assertTrue(decl.contains("@param {Object} newValue"),
							"onElementDataChange skeleton must document the newValue param: " + decl),
					() -> assertTrue(decl.contains("@return {Boolean}"),
							"onElementDataChange skeleton must declare a {Boolean} return: " + decl));
		}
	}

	// -----------------------------------------------------------------------
	// AC: the three previously-drifted events now wire via AiBridge.
	// -----------------------------------------------------------------------

	@Nested
	class PreviouslyDriftedEvents {

		@Test
		@org.junit.jupiter.api.DisplayName("onBeforeRecordSelection wires via AiBridge (was missing from the drifted copy)")
		void onBeforeRecordSelection_wires() {
			String formName = uniqueName("driftBRSForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null,
					"onBeforeRecordSelection:beforeSelect", null);

			assertNotNull(result);
			assertFalse(result.contains("Skipped unrecognised events"),
					"onBeforeRecordSelection must be a supported event, not reported as unrecognised: " + result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");
			assertWired(form, form.getOnBeforeRecordSelectionMethodID(), "beforeSelect");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("onElementFocusGained wires via AiBridge (was missing from the drifted copy)")
		void onElementFocusGained_wires() {
			String formName = uniqueName("driftFGForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null,
					"onElementFocusGained:focusGained", null);

			assertNotNull(result);
			assertFalse(result.contains("Skipped unrecognised events"),
					"onElementFocusGained must be a supported event, not reported as unrecognised: " + result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");
			assertWired(form, form.getOnElementFocusGainedMethodID(), "focusGained");
		}

		@Test
		@org.junit.jupiter.api.DisplayName("onElementFocusLost wires via AiBridge (was missing from the drifted copy)")
		void onElementFocusLost_wires() {
			String formName = uniqueName("driftFLForm");

			String result = devServer.createForm(formName, "css", "640", "480", null, null,
					"onElementFocusLost:focusLost", null);

			assertNotNull(result);
			assertFalse(result.contains("Skipped unrecognised events"),
					"onElementFocusLost must be a supported event, not reported as unrecognised: " + result);

			Form form = resolveForm(formName);
			assertNotNull(form, "form should exist");
			assertWired(form, form.getOnElementFocusLostMethodID(), "focusLost");
		}
	}
}
