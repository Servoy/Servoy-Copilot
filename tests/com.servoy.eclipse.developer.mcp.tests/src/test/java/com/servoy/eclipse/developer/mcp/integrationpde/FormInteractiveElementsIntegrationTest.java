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
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyTestingServer;
import com.servoy.eclipse.developer.mcp.services.ComponentSpecService;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsFormatter;
import com.servoy.eclipse.developer.mcp.services.FormInteractiveElementsService;
import com.servoy.eclipse.developer.mcp.services.HandlerBinding;
import com.servoy.eclipse.developer.mcp.services.InteractiveElement;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.Field;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.GraphicalComponent;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.LayoutContainer;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.Portal;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.TabPanel;
import com.servoy.j2db.persistence.WebComponent;
import com.servoy.j2db.util.ServoyJSONObject;
import com.servoy.j2db.util.UUID;

/**
 * Integration test for {@code getFormInteractiveElements}, {@code locateForm}
 * and the unchanged markdown of {@code findForm} (SVY-21573). Needs a live
 * workbench with an active Servoy solution, a module, real forms, script
 * methods and the web component spec providers, so it runs as a JUnit Plug-in
 * test.
 * <p>
 * The seeded model uses legacy persists (GraphicalComponent, Field, TabPanel)
 * for the bound handlers so the result does not depend on which web component
 * packages are installed, plus one web component of a spec that is certainly
 * not installed for the {@code specMissing} case.
 * </p>
 */
public class FormInteractiveElementsIntegrationTest extends TestUtilitiesClassCopy {
	private static final String TEST_SOLUTION = "test_formelems_suite";
	private static final String TEST_MODULE = "test_formelems_module";
	private static final String SERVOY_RESOURCES = "servoy_resources";
	private static final String SCOPE = "fivscope";

	private static final String MAIN = "fiv_main";
	private static final String DETAILS = "fiv_cockpit_details";
	private static final String OVERVIEW = "fiv_cockpit_overview";
	private static final String UNNAMED = "fiv_unnamed";
	private static final String BASE = "fiv_base";
	private static final String CHILD = "fiv_child";
	private static final String RESPONSIVE = "fiv_responsive";
	private static final String SPEC_MISSING = "fiv_specmissing";
	private static final String AMB_ONE = "fiv_amb_one";
	private static final String AMB_TWO = "fiv_amb_two";
	private static final String MODULE_FORM = "fiv_module_form";
	private static final String WEB_COMP_FORM = "fiv_webcomp";
	private static final String EDGE_FORM = "fiv_handler_edge";
	private static final String PORTAL_FORM = "fiv_portal";
	private static final String ISLAND_PARENT = "fiv_island_parent";
	private static final String ISLAND_CHILD = "fiv_island_child";

	/** a well formed Servoy UUID that no persist in the seeded solution has */
	private static final String UNRESOLVED_UUID = "6f1c2b3a-9d4e-4f5a-8b7c-0a1b2c3d4e5f";

	private static final String FAKE_WIDGET = "fiv-fake-widget";
	private static final String FAKE_TABPANEL = "fiv-fake-tabpanel";

	/**
	 * A component spec that is really installed in the running workspace and one of
	 * its handlers, discovered in seed() so the test does not depend on which
	 * component packages the launch contains. Null when no installed component
	 * declares a handler; the tests that need it then fail with an explicit
	 * message.
	 */
	private static String realSpec;
	private static String realHandler;
	private static String loadedSpecsSummary = "";

	/**
	 * handler names the fake widget spec declares; only onCustomEvent is bound in
	 * the seeded component JSON
	 */
	private static final Map<String, Set<String>> FAKE_HANDLERS = Map.of(FAKE_WIDGET,
			Set.of("onCustomEvent", "onDeclaredButAbsent", "onDisabledEvent"), FAKE_TABPANEL, Set.of());
	private static final Map<String, Set<String>> FAKE_DATAPROVIDERS = Map.of(FAKE_WIDGET, Set.of("dataProviderID"),
			FAKE_TABPANEL, Set.of());

	private static final String FAKE_COMPONENT = "fiv-nonexistent-component";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static boolean seeded;
	private static Throwable seedFailure;

	private ServoyTestingServer testingServer;

	public FormInteractiveElementsIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, TEST_MODULE, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
		seeded = false;
		seedFailure = null;
	}

	@BeforeEach
	public void setUp() throws Exception {
		testingServer = new ServoyTestingServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureSolutionInWorkspace(TEST_MODULE, UUID.randomUUID().toString(), SERVOY_RESOURCES, null, null);
		ensureSolutionReadyAndOptionallyActive(TEST_MODULE, false);
		ensureTestSolutionInWorkspace(new String[] { TEST_MODULE }, null);
		ensureActiveProject();

		if (seedFailure != null) {
			fail("Seeding failed in an earlier test of this class; not seeding again on top of partial data",
					seedFailure);
		}
		if (!seeded) {
			try {
				seed();
				seeded = true;
			} catch (Exception | AssertionError e) {
				seedFailure = e;
				throw e;
			}
		}
	}

	// -----------------------------------------------------------------------
	// seeding
	// -----------------------------------------------------------------------

	private static IDeveloperServoyModel model() {
		return ServoyModelManager.getServoyModelManager().getServoyModel();
	}

	@SuppressWarnings("deprecation")
	private void seed() throws Exception {
		ServoyProject active = model().getActiveProject();
		assertNotNull(active, "Active project required");
		Solution solution = active.getEditingSolution();
		IValidateName validator = model().getNameValidator();
		ServoyArtifactCreationService creator = new ServoyArtifactCreationService();

		for (String name : List.of(MAIN, DETAILS, OVERVIEW, UNNAMED, BASE, CHILD, SPEC_MISSING, AMB_ONE, AMB_TWO,
				WEB_COMP_FORM, EDGE_FORM, PORTAL_FORM, ISLAND_PARENT, ISLAND_CHILD)) {
			creator.createForm(name, "css", 640, 480, null, null, null);
		}
		creator.createForm(RESPONSIVE, "responsive", 640, 480, null, null, null);

		Form main = solution.getForm(MAIN);
		Form details = solution.getForm(DETAILS);
		Form overview = solution.getForm(OVERVIEW);
		Form unnamed = solution.getForm(UNNAMED);
		Form base = solution.getForm(BASE);
		Form child = solution.getForm(CHILD);
		Form responsive = solution.getForm(RESPONSIVE);
		Form specMissing = solution.getForm(SPEC_MISSING);
		Form ambOne = solution.getForm(AMB_ONE);
		Form ambTwo = solution.getForm(AMB_TWO);
		Form webForm = solution.getForm(WEB_COMP_FORM);
		Form edgeForm = solution.getForm(EDGE_FORM);
		Form portalForm = solution.getForm(PORTAL_FORM);
		Form islandParent = solution.getForm(ISLAND_PARENT);
		Form islandChild = solution.getForm(ISLAND_CHILD);

		// titles and comments used by the findForm characterization
		main.setTitleText("Orders Cockpit");
		main.setComment("Seeded main form");
		details.setTitleText("Orders Cockpit Details");
		details.setComment("Seeded details form");
		overview.setTitleText("Cockpit Overview");
		ambOne.setTitleText("Ambiguous Twin");
		ambTwo.setTitleText("Ambiguous Twin");

		// the solution's main form
		solution.setFirstFormID(main.getUUID().toString());

		// scope method used as onDataChange handler
		ScriptMethod scopeMethod = solution.createNewGlobalScriptMethod(validator, SCOPE, "onFivChange");
		scopeMethod.setDeclaration("function onFivChange(oldValue, newValue, event) {\n\treturn true;\n}\n");

		// --- fiv_main: button (form method), input (scope method), "-1" handler, tab
		// panel, static label
		ScriptMethod newOrder = formMethod(main, "newOrder", validator);
		ScriptMethod onRightClick = formMethod(main, "showMenu", validator);
		button(main, "btn_new", newOrder, 10);

		Field customer = main.createNewField(new Point(10, 50));
		customer.setName("fld_customer");
		customer.setDataProviderID("customerid");
		customer.setOnDataChangeMethodID(scopeMethod.getUUID().toString());

		GraphicalComponent mixed = main.createNewGraphicalComponent(new Point(10, 90));
		mixed.setName("btn_mixed");
		mixed.setText("mixed");
		mixed.setOnActionMethodID("-1");
		mixed.setOnRightClickMethodID(onRightClick.getUUID().toString());

		TabPanel tabs = main.createNewTabPanel("tabs");
		tabs.createNewTab("Details", null, details);

		GraphicalComponent label = main.createNewGraphicalComponent(new Point(10, 130));
		label.setName("lbl_static");
		label.setText("static");

		// --- navigation target with an element
		ScriptMethod showDetails = formMethod(details, "showDetails", validator);
		button(details, "btn_details", showDetails, 10);

		// --- unnamed element
		ScriptMethod doIt = formMethod(unnamed, "doIt", validator);
		button(unnamed, null, doIt, 10);

		// --- inheritance
		ScriptMethod baseAction = formMethod(base, "baseAction", validator);
		button(base, "base_btn", baseAction, 10);
		child.setExtendsForm(base);
		child.setExtendsID(base.getUUID().toString());
		ScriptMethod childAction = formMethod(child, "childAction", validator);
		button(child, "child_btn", childAction, 50);

		// --- layout container
		ScriptMethod inContainer = formMethod(responsive, "containerAction", validator);
		LayoutContainer container = responsive.createNewLayoutContainer();
		container.setName("panel_toolbar");
		GraphicalComponent inside = container.createNewGraphicalComponent(new Point(0, 0));
		inside.setName("btn_in_container");
		inside.setText("inside");
		inside.setOnActionMethodID(inContainer.getUUID().toString());

		// --- web component of a spec that is not installed
		specMissing.createNewWebComponent("fake_comp", FAKE_COMPONENT);

		// --- web components: a made-up type whose handler key does not follow the
		// onXxxMethodID pattern, a made-up
		// "tabpanel" type, and one component of an installed spec
		ScriptMethod customAction = formMethod(webForm, "customAction", validator);
		WebComponent widget = webForm.createNewWebComponent("fake_widget", FAKE_WIDGET);
		widget.setJson(new ServoyJSONObject(Map.<String, Object>of("onCustomEvent", customAction.getUUID().toString(),
				"onDisabledEvent", "-1", "dataProviderID", "customerid")));
		webForm.createNewWebComponent("fake_tabs", FAKE_TABPANEL);
		discoverInstalledComponent();
		if (realSpec != null) {
			WebComponent realComponent = webForm.createNewWebComponent("real_component", realSpec);
			realComponent.setJson(
					new ServoyJSONObject(Map.<String, Object>of(realHandler, customAction.getUUID().toString())));
		}

		// --- handlers that cannot be resolved (well formed but unknown UUID, garbage)
		// and handlers that must not be listed ("0", blank)
		GraphicalComponent unresolvedUuid = edgeForm.createNewGraphicalComponent(new Point(10, 10));
		unresolvedUuid.setName("btn_unresolved");
		unresolvedUuid.setText("unresolved");
		unresolvedUuid.setOnActionMethodID(UNRESOLVED_UUID);
		GraphicalComponent garbage = edgeForm.createNewGraphicalComponent(new Point(10, 50));
		garbage.setName("btn_garbage");
		garbage.setText("garbage");
		garbage.setOnActionMethodID("not-a-uuid");
		GraphicalComponent zeroAndBlank = edgeForm.createNewGraphicalComponent(new Point(10, 90));
		zeroAndBlank.setName("btn_zero");
		zeroAndBlank.setText("zero");
		zeroAndBlank.setOnActionMethodID("0");
		zeroAndBlank.setOnRightClickMethodID(" ");

		// --- portal with a child element
		ScriptMethod portalAction = formMethod(portalForm, "portalAction", validator);
		Portal portal = portalForm.createNewPortal("portal_orders", new Point(10, 10));
		GraphicalComponent inPortal = portal.createNewGraphicalComponent(new Point(0, 0));
		inPortal.setName("btn_in_portal");
		inPortal.setText("in portal");
		inPortal.setOnActionMethodID(portalAction.getUUID().toString());

		// --- two forms linked by a tab panel, but not reachable from the main form
		TabPanel islandTabs = islandParent.createNewTabPanel("island_tabs");
		islandTabs.createNewTab("Child", null, islandChild);

		active.saveEditingSolutionNodes(
				new IPersist[] { main, details, overview, unnamed, base, child, responsive, specMissing, ambOne, ambTwo,
						webForm, edgeForm, portalForm, islandParent, islandChild, scopeMethod, solution },
				true);

		// --- a form living only in the module
		ServoyProject moduleProject = model().getServoyProject(TEST_MODULE);
		assertNotNull(moduleProject, "Module project required");
		Solution moduleSolution = moduleProject.getEditingSolution();
		Form moduleForm = moduleSolution.createNewForm(validator, null, MODULE_FORM, null, true,
				new Dimension(640, 480));
		moduleForm.createNewPart(Part.BODY, 480);
		moduleForm.setUseCssPosition(Boolean.TRUE);
		moduleForm.setTitleText("Module Screen");
		ScriptMethod moduleAction = formMethod(moduleForm, "moduleAction", validator);
		button(moduleForm, "btn_mod", moduleAction, 10);
		moduleProject.saveEditingSolutionNodes(new IPersist[] { moduleForm }, true);

		waitForWorkspaceBuildJobs();
		pumpEventsUntil(10_000, () -> {
			FlattenedSolution editingFs = active.getEditingFlattenedSolution();
			assertNotNull(editingFs.getScriptMethod(scopeMethod.getUUID().toString()),
					"Scope method must resolve in the flattened solution (first saveEditingSolutionNodes should have finished)");
			assertNotNull(editingFs.getForm(MODULE_FORM), "Module form must resolve in the flattened solution (the second saveEditingSolutionNodes should have finished)");
		});
	}

	private static void discoverInstalledComponent() {
		realSpec = null;
		realHandler = null;
		ComponentSpecService service = new ComponentSpecService();
		JSONArray components = service.listObjects(null, false);
		loadedSpecsSummary = components.length() + " component specs loaded";
		for (int i = 0; i < components.length(); i++) {
			JSONObject summary = components.getJSONObject(i);
			if (summary.optInt("handlerCount", 0) == 0)
				continue;
			JSONObject full = service.getObjectSpec(summary.getString("name"), false);
			if (full == null || full.getJSONArray("handlers").length() == 0)
				continue;
			realSpec = summary.getString("name");
			realHandler = full.getJSONArray("handlers").getJSONObject(0).getString("name");
			return;
		}
	}

	private static String requireRealSpec() {
		assertNotNull(realSpec, "No installed component spec with a handler was found in the PDE runtime ("
				+ loadedSpecsSummary + "); the launch must include component bundles");
		return realSpec;
	}

	private static ScriptMethod formMethod(Form form, String name, IValidateName validator) throws Exception {
		ScriptMethod method = form.createNewScriptMethod(validator, name);
		method.setDeclaration("function " + name + "(event) {\n}\n");
		return method;
	}

	private static GraphicalComponent button(Form form, String name, ScriptMethod onAction, int y) throws Exception {
		GraphicalComponent button = form.createNewGraphicalComponent(new Point(10, y));
		if (name != null)
			button.setName(name);
		button.setText(name != null ? name : "unnamed");
		button.setOnActionMethodID(onAction.getUUID().toString());
		return button;
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private static JsonNode json(String result) throws Exception {
		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "Expected JSON but got: " + result);
		return MAPPER.readTree(result);
	}

	private static JsonNode element(JsonNode payload, String name) {
		for (JsonNode element : payload.get("elements")) {
			if (element.get("name").isTextual() && name.equals(element.get("name").asText()))
				return element;
		}
		throw new AssertionError("Element '" + name + "' not found in " + payload);
	}

	private static List<String> elementNames(JsonNode payload) {
		List<String> names = new ArrayList<>();
		for (JsonNode element : payload.get("elements")) {
			names.add(element.get("name").asText());
		}
		return names;
	}

	private String elements(String form, boolean includeStatic) {
		return testingServer.getFormInteractiveElements(form, includeStatic, 100);
	}

	// -----------------------------------------------------------------------
	// getFormInteractiveElements
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a named button bound to a form method is a control with the resolved method, owner and data-cy")
	void namedButton_isControlWithResolvedHandler() throws Exception {
		JsonNode root = json(elements(MAIN, false));
		JsonNode button = element(root, "btn_new");
		JsonNode handler = button.get("handlers").get(0);

		assertAll(() -> assertEquals(MAIN, root.get("form").asText()),
				() -> assertEquals("Orders Cockpit", root.get("title").asText()),
				() -> assertEquals(TEST_SOLUTION, root.get("module").asText()),
				() -> assertEquals("css", root.get("layout").asText()),
				() -> assertEquals("control", button.get("kind").asText()),
				() -> assertEquals("GraphicalComponent", button.get("type").asText()),
				() -> assertEquals("fiv_main.btn_new", button.get("dataCy").asText()),
				() -> assertEquals("[data-cy=\"fiv_main.btn_new\"]", button.get("selector").asText()),
				() -> assertFalse(button.get("inherited").asBoolean()),
				() -> assertEquals(1, button.get("handlers").size()),
				() -> assertEquals("onActionMethodID", handler.get("handler").asText()),
				() -> assertEquals("onAction", handler.get("event").asText()),
				() -> assertEquals("newOrder", handler.get("method").asText()),
				() -> assertEquals("forms.fiv_main", handler.get("owner").asText()),
				() -> assertFalse(handler.has("unresolved")));
	}

	@Test
	@DisplayName("a named textbox with a dataprovider and a scope onDataChange is an input owned by the scope")
	void namedTextbox_isInputWithScopeHandler() throws Exception {
		JsonNode input = element(json(elements(MAIN, false)), "fld_customer");
		JsonNode handler = input.get("handlers").get(0);

		assertAll(() -> assertEquals("input", input.get("kind").asText()),
				() -> assertEquals("customerid", input.get("dataProvider").asText()),
				() -> assertEquals("fiv_main.fld_customer", input.get("dataCy").asText()),
				() -> assertEquals(1, input.get("handlers").size()),
				() -> assertEquals("onDataChangeMethodID", handler.get("handler").asText()),
				() -> assertEquals("onDataChange", handler.get("event").asText()),
				() -> assertEquals("onFivChange", handler.get("method").asText()),
				() -> assertEquals("scopes.fivscope", handler.get("owner").asText()));
	}

	@Test
	@DisplayName("a handler value of -1 is not listed")
	void minusOneHandler_isNotListed() throws Exception {
		JsonNode mixed = element(json(elements(MAIN, false)), "btn_mixed");

		assertAll(() -> assertEquals(1, mixed.get("handlers").size()),
				() -> assertEquals("onRightClick", mixed.get("handlers").get(0).get("event").asText()),
				() -> assertEquals("showMenu", mixed.get("handlers").get(0).get("method").asText()));
	}

	@Test
	@DisplayName("a tab panel is a container element")
	void tabPanel_isContainer() throws Exception {
		JsonNode tabs = element(json(elements(MAIN, false)), "tabs");

		assertAll(() -> assertEquals("container", tabs.get("kind").asText()),
				() -> assertEquals("TabPanel", tabs.get("type").asText()),
				() -> assertEquals("fiv_main.tabs", tabs.get("dataCy").asText()));
	}

	@Test
	@DisplayName("includeStatic=false hides the named static label, true lists it as static and counts it")
	void includeStatic_togglesStaticElements() throws Exception {
		JsonNode without = json(elements(MAIN, false));
		JsonNode with = json(elements(MAIN, true));

		assertAll(() -> assertEquals(4, without.get("totalElements").asInt()),
				() -> assertEquals(Set.of("btn_new", "fld_customer", "btn_mixed", "tabs"),
						Set.copyOf(elementNames(without))),
				() -> assertEquals(5, with.get("totalElements").asInt()),
				() -> assertFalse(elementNames(without).contains("lbl_static")),
				() -> assertEquals("static", element(with, "lbl_static").get("kind").asText()),
				() -> assertEquals(0, element(with, "lbl_static").get("handlers").size()));
	}

	@Test
	@DisplayName("an unnamed element has null dataCy and selector and is counted in the notes")
	void unnamedElement_isReportedInNotes() throws Exception {
		JsonNode root = json(elements(UNNAMED, false));
		JsonNode only = root.get("elements").get(0);

		assertAll(() -> assertEquals(1, root.get("totalElements").asInt()), () -> assertTrue(only.get("name").isNull()),
				() -> assertTrue(only.get("dataCy").isNull()), () -> assertTrue(only.get("selector").isNull()),
				() -> assertEquals("doIt", only.get("handlers").get(0).get("method").asText()),
				() -> assertEquals(1, root.get("notes").size()),
				() -> assertEquals("1 element has no name and cannot be targeted by data-cy: GraphicalComponent",
						root.get("notes").get(0).asText()));
	}

	@Test
	@DisplayName("an element inside a layout container carries the container name")
	void elementInLayoutContainer_hasContainer() throws Exception {
		JsonNode root = json(elements(RESPONSIVE, false));
		JsonNode inside = element(root, "btn_in_container");

		assertAll(() -> assertEquals("responsive", root.get("layout").asText()),
				() -> assertEquals(1, root.get("totalElements").asInt()),
				() -> assertEquals("panel_toolbar", inside.get("container").asText()),
				() -> assertEquals("fiv_responsive.btn_in_container", inside.get("dataCy").asText()),
				() -> assertEquals("containerAction", inside.get("handlers").get(0).get("method").asText()));
	}

	@Test
	@DisplayName("elements of the super form are included as inherited and use the requested form name for data-cy")
	void inheritedElement_isMarkedAndUsesChildFormName() throws Exception {
		JsonNode root = json(elements(CHILD, false));
		JsonNode inherited = element(root, "base_btn");
		JsonNode own = element(root, "child_btn");

		assertAll(() -> assertEquals(2, root.get("totalElements").asInt()),
				() -> assertTrue(inherited.get("inherited").asBoolean()),
				() -> assertEquals("fiv_child.base_btn", inherited.get("dataCy").asText()),
				() -> assertEquals("baseAction", inherited.get("handlers").get(0).get("method").asText()),
				() -> assertEquals("forms.fiv_base", inherited.get("handlers").get(0).get("owner").asText()),
				() -> assertFalse(own.get("inherited").asBoolean()),
				() -> assertEquals("fiv_child.child_btn", own.get("dataCy").asText()),
				() -> assertEquals("forms.fiv_child", own.get("handlers").get(0).get("owner").asText()));
	}

	@Test
	@DisplayName("the super form itself reports its own element as not inherited")
	void baseForm_elementIsNotInherited() throws Exception {
		JsonNode base = element(json(elements(BASE, false)), "base_btn");

		assertAll(() -> assertFalse(base.get("inherited").asBoolean()),
				() -> assertEquals("fiv_base.base_btn", base.get("dataCy").asText()));
	}

	@Test
	@DisplayName("a form living only in the module resolves and reports the module")
	void moduleOnlyForm_resolves() throws Exception {
		JsonNode root = json(elements(MODULE_FORM, false));
		JsonNode button = element(root, "btn_mod");

		assertAll(() -> assertEquals(MODULE_FORM, root.get("form").asText()),
				() -> assertEquals(TEST_MODULE, root.get("module").asText()),
				() -> assertEquals("Module Screen", root.get("title").asText()),
				() -> assertEquals("fiv_module_form.btn_mod", button.get("dataCy").asText()),
				() -> assertEquals("moduleAction", button.get("handlers").get(0).get("method").asText()),
				() -> assertEquals("forms.fiv_module_form", button.get("handlers").get(0).get("owner").asText()));
	}

	@Test
	@DisplayName("an unknown form returns the exact error text")
	void unknownForm_returnsExactError() throws Exception {
		assertEquals(
				"Error: Form 'no_such_form_xyz' not found in the active solution or its modules. "
						+ "Use findForm to resolve a description or UI title to the internal form name.",
				elements("no_such_form_xyz", false));
	}

	@Test
	@DisplayName("a web component of an uninstalled spec is reported as specMissing without an exception")
	void uninstalledSpec_isSpecMissing() throws Exception {
		JsonNode root = json(elements(SPEC_MISSING, false));
		JsonNode fake = element(root, "fake_comp");

		assertAll(() -> assertEquals(1, root.get("totalElements").asInt()),
				() -> assertEquals(FAKE_COMPONENT, fake.get("type").asText()),
				() -> assertTrue(fake.get("specMissing").asBoolean()),
				() -> assertEquals(0, fake.get("handlers").size()),
				() -> assertEquals(
						List.of("1 element uses a component whose spec is not installed (handlers cannot be listed): "
								+ FAKE_COMPONENT),
						List.of(root.get("notes").get(0).asText())));
	}

	@Test
	@DisplayName("the tool truncates with maxElements=1 and keeps the first element of the full result")
	void maxElements_truncates() throws Exception {
		JsonNode full = json(testingServer.getFormInteractiveElements(MAIN, false, 100));
		JsonNode truncated = json(testingServer.getFormInteractiveElements(MAIN, false, 1));

		assertAll(() -> assertEquals(4, full.get("totalElements").asInt()),
				() -> assertFalse(full.get("truncated").asBoolean()),
				() -> assertEquals(4, truncated.get("totalElements").asInt()),
				() -> assertEquals(1, truncated.get("returned").asInt()),
				() -> assertTrue(truncated.get("truncated").asBoolean()),
				() -> assertEquals(1, truncated.get("elements").size()),
				() -> assertEquals(full.get("elements").get(0).get("name").asText(),
						truncated.get("elements").get(0).get("name").asText()));
	}

	@Test
	@DisplayName("a blank form name is rejected before the model is touched, with a loaded solution too")
	void blankFormName_isRejected() throws Exception {
		assertEquals("Error: formName parameter is required",
				testingServer.getFormInteractiveElements("  ", false, 100));
	}

	// -----------------------------------------------------------------------
	// findForm characterization (guards the ranking refactor)
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("findForm markdown for a multi-hit query is verbatim: heading, bullets and relevance order")
	void findForm_characterization_orderAndText() throws Exception {
		String expected = "# Forms matching 'Orders Cockpit' (3 found)\n\n" //
				+ "## fiv_main\n" //
				+ "- **title:** Orders Cockpit\n" //
				+ "- **internal name:** `fiv_main`\n" //
				+ "- **module:** test_formelems_suite (active)\n" //
				+ "- **layout:** css\n" //
				+ "- **comment:** Seeded main form\n" //
				+ "\n" //
				+ "## fiv_cockpit_details\n" //
				+ "- **title:** Orders Cockpit Details\n" //
				+ "- **internal name:** `fiv_cockpit_details`\n" //
				+ "- **module:** test_formelems_suite (active)\n" //
				+ "- **layout:** css\n" //
				+ "- **comment:** Seeded details form\n" //
				+ "\n" //
				+ "## fiv_cockpit_overview\n" //
				+ "- **title:** Cockpit Overview\n" //
				+ "- **internal name:** `fiv_cockpit_overview`\n" //
				+ "- **module:** test_formelems_suite (active)\n" //
				+ "- **layout:** css\n" //
				+ "\n";

		assertEquals(expected, new ServoySolutionService().findForm("Orders Cockpit"));
	}

	@Test
	@DisplayName("findForm markdown for a module form is verbatim and has no (active) marker")
	void findForm_characterization_moduleForm() throws Exception {
		String expected = "# Forms matching 'Module Screen' (1 found)\n\n" //
				+ "## fiv_module_form\n" //
				+ "- **title:** Module Screen\n" //
				+ "- **internal name:** `fiv_module_form`\n" //
				+ "- **module:** test_formelems_module\n" //
				+ "- **layout:** css\n" //
				+ "\n";

		assertEquals(expected, new ServoySolutionService().findForm("Module Screen"));
	}

	@Test
	@DisplayName("findForm without a match returns the verbatim no-result text")
	void findForm_characterization_noMatch() throws Exception {
		assertEquals("No forms found matching 'zzqqxx'.\n" //
				+ "Tip: use listForms to see all available forms, or try a shorter/different search term.",
				new ServoySolutionService().findForm("zzqqxx"));
	}

	// -----------------------------------------------------------------------
	// web components, unresolved / empty handlers, portal children (review
	// findings)
	// -----------------------------------------------------------------------

	private static FlattenedSolution flattened() {
		return model().getActiveProject().getEditingFlattenedSolution();
	}

	private static InteractiveElement byName(List<InteractiveElement> elements, String name) {
		for (InteractiveElement element : elements) {
			if (name.equals(element.name()))
				return element;
		}
		throw new AssertionError("Element '" + name + "' not found in " + elements);
	}

	/**
	 * Drives the web component branch of the collector with fake spec lookups
	 * against the seeded fiv_webcomp form.
	 */
	private static List<InteractiveElement> collectWithFakeSpecs() {
		FlattenedSolution fs = flattened();
		Form form = fs.getForm(WEB_COMP_FORM);
		assertNotNull(form, "Seeded form " + WEB_COMP_FORM + " must resolve");
		return new FormInteractiveElementsService().collect(form, fs, type -> FAKE_HANDLERS.get(type),
				type -> FAKE_DATAPROVIDERS.getOrDefault(type, Set.of()), true);
	}

	@Test
	@DisplayName("a web component handler whose key is not onXxxMethodID is listed under its exact key with event, method and owner")
	void webComponent_customHandlerKey_isListedWithResolvedMethod() throws Exception {
		List<InteractiveElement> elements = collectWithFakeSpecs();
		InteractiveElement widget = byName(elements, "fake_widget");
		HandlerBinding binding = widget.handlers().get(0);
		JsonNode jsonWidget = element(
				FormInteractiveElementsFormatter.toElementsNode(WEB_COMP_FORM, elements, true, 100), "fake_widget");
		JsonNode jsonHandler = jsonWidget.get("handlers").get(0);

		assertAll(() -> assertEquals(FAKE_WIDGET, widget.type()), () -> assertFalse(widget.specMissing()),
				() -> assertEquals(1, widget.handlers().size()), () -> assertEquals("onCustomEvent", binding.handler()),
				() -> assertEquals("customAction", binding.method()),
				() -> assertEquals("forms.fiv_webcomp", binding.owner()), () -> assertFalse(binding.unresolved()),
				() -> assertEquals("onCustomEvent", jsonHandler.get("handler").asText()),
				() -> assertEquals("onCustomEvent", jsonHandler.get("event").asText()),
				() -> assertEquals("customAction", jsonHandler.get("method").asText()),
				() -> assertEquals("forms.fiv_webcomp", jsonHandler.get("owner").asText()));
	}

	@Test
	@DisplayName("a web component with a dataprovider-typed property is an input carrying that dataprovider")
	void webComponent_dataProviderProperty_isInput() throws Exception {
		InteractiveElement widget = byName(collectWithFakeSpecs(), "fake_widget");

		assertAll(() -> assertEquals(InteractiveElement.KIND_INPUT, widget.kind()),
				() -> assertEquals("customerid", widget.dataProvider()));
	}

	@Test
	@DisplayName("a web component type name containing tabpanel is a container")
	void webComponent_tabpanelTypeName_isContainer() throws Exception {
		InteractiveElement tabs = byName(collectWithFakeSpecs(), "fake_tabs");

		assertAll(() -> assertEquals(InteractiveElement.KIND_CONTAINER, tabs.kind()),
				() -> assertEquals(FAKE_TABPANEL, tabs.type()), () -> assertEquals(0, tabs.handlers().size()));
	}

	@Test
	@DisplayName("a handler declared by the spec but absent or -1 in the component JSON is not listed")
	void webComponent_absentAndMinusOneHandlers_areNotListed() throws Exception {
		InteractiveElement widget = byName(collectWithFakeSpecs(), "fake_widget");
		List<String> handlerKeys = new ArrayList<>();
		for (HandlerBinding binding : widget.handlers()) {
			handlerKeys.add(binding.handler());
		}

		assertEquals(List.of("onCustomEvent"), handlerKeys,
				"onDeclaredButAbsent (absent) and onDisabledEvent (-1) must not be listed");
	}

	@Test
	@DisplayName("a web component of an installed spec is collected with the handlers of the real spec")
	void webComponent_installedSpec_listsRealHandler() throws Exception {
		String spec = requireRealSpec();
		FlattenedSolution fs = flattened();
		Form form = fs.getForm(WEB_COMP_FORM);
		assertNotNull(form);
		InteractiveElement component = byName(new FormInteractiveElementsService().collect(form, fs, true),
				"real_component");

		assertAll(() -> assertEquals(spec, component.type()), () -> assertFalse(component.specMissing()),
				() -> assertEquals(1, component.handlers().size()),
				() -> assertEquals(realHandler, component.handlers().get(0).handler()),
				() -> assertEquals("customAction", component.handlers().get(0).method()),
				() -> assertEquals("forms.fiv_webcomp", component.handlers().get(0).owner()));
	}

	@Test
	@DisplayName("ComponentSpecService.getHandlerNames returns the real handlers of an installed spec and an empty set for unknown or null")
	void componentSpecService_getHandlerNames() throws Exception {
		String spec = requireRealSpec();
		ComponentSpecService service = new ComponentSpecService();
		Set<String> handlers = service.getHandlerNames(spec);
		Set<String> expected = new java.util.HashSet<>();
		JSONArray declared = service.getObjectSpec(spec, false).getJSONArray("handlers");
		for (int i = 0; i < declared.length(); i++) {
			expected.add(declared.getJSONObject(i).getString("name"));
		}

		assertAll(() -> assertFalse(expected.isEmpty(), "spec " + spec + " declares no handlers"),
				() -> assertEquals(expected, handlers), () -> assertTrue(handlers.contains(realHandler)),
				() -> assertEquals(Set.of(), service.getHandlerNames(FAKE_COMPONENT)),
				() -> assertEquals(Set.of(), service.getHandlerNames(null)));
	}

	@Test
	@DisplayName("unresolvable handler ids (unknown UUID, garbage) have no method, are flagged unresolved with the raw id and are counted in the notes")
	void unresolvedHandlers_areFlaggedWithRawIdAndNote() throws Exception {
		JsonNode root = json(elements(EDGE_FORM, false));
		JsonNode unknownUuid = element(root, "btn_unresolved").get("handlers").get(0);
		JsonNode garbage = element(root, "btn_garbage").get("handlers").get(0);

		assertAll(() -> assertEquals(2, root.get("totalElements").asInt()),
				() -> assertEquals("onActionMethodID", unknownUuid.get("handler").asText()),
				() -> assertEquals("onAction", unknownUuid.get("event").asText()),
				() -> assertTrue(unknownUuid.get("method").isNull()),
				() -> assertTrue(unknownUuid.get("unresolved").asBoolean()),
				() -> assertEquals(UNRESOLVED_UUID, unknownUuid.get("methodId").asText()),
				() -> assertFalse(unknownUuid.has("owner")), () -> assertTrue(garbage.get("method").isNull()),
				() -> assertTrue(garbage.get("unresolved").asBoolean()),
				() -> assertEquals("not-a-uuid", garbage.get("methodId").asText()),
				() -> assertEquals(
						List.of("2 handlers could not be resolved to a method name (methodId holds the raw value)"),
						List.of(root.get("notes").get(0).asText())),
				() -> assertEquals(1, root.get("notes").size()));
	}

	@Test
	@DisplayName("handler values 0 and blank are not listed, so the element has no handlers and only shows up as static")
	void zeroAndBlankHandlers_areNotListed() throws Exception {
		JsonNode withoutStatic = json(elements(EDGE_FORM, false));
		JsonNode withStatic = json(elements(EDGE_FORM, true));
		JsonNode zero = element(withStatic, "btn_zero");

		assertAll(() -> assertFalse(elementNames(withoutStatic).contains("btn_zero")),
				() -> assertEquals(3, withStatic.get("totalElements").asInt()),
				() -> assertEquals("static", zero.get("kind").asText()),
				() -> assertEquals(0, zero.get("handlers").size()));
	}

	@Test
	@DisplayName("children of a portal carry the portal name as container")
	void portalChild_hasPortalNameAsContainer() throws Exception {
		JsonNode root = json(elements(PORTAL_FORM, false));
		JsonNode child = element(root, "btn_in_portal");

		assertAll(() -> assertEquals(1, root.get("totalElements").asInt()),
				() -> assertEquals("portal_orders", child.get("container").asText()),
				() -> assertEquals("fiv_portal.btn_in_portal", child.get("dataCy").asText()),
				() -> assertEquals("portalAction", child.get("handlers").get(0).get("method").asText()));
	}

	// -----------------------------------------------------------------------
	// findForm ties and blank query (characterization)
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("findForm keeps two forms of equal score in collection order (creation order of the seeded forms)")
	void findForm_characterization_tieKeepsCollectionOrder() throws Exception {
		String expected = "# Forms matching 'Ambiguous Twin' (2 found)\n\n" //
				+ "## fiv_amb_one\n" //
				+ "- **title:** Ambiguous Twin\n" //
				+ "- **internal name:** `fiv_amb_one`\n" //
				+ "- **module:** test_formelems_suite (active)\n" //
				+ "- **layout:** css\n" //
				+ "\n" //
				+ "## fiv_amb_two\n" //
				+ "- **title:** Ambiguous Twin\n" //
				+ "- **internal name:** `fiv_amb_two`\n" //
				+ "- **module:** test_formelems_suite (active)\n" //
				+ "- **layout:** css\n" //
				+ "\n";

		assertEquals(expected, new ServoySolutionService().findForm("Ambiguous Twin"));
	}

	@Test
	@DisplayName("findForm with a blank or null query uses the (all) heading with the exact count of listed forms")
	void findForm_characterization_blankQueryListsAll() throws Exception {
		String blank = new ServoySolutionService().findForm("   ");
		String none = new ServoySolutionService().findForm(null);
		Matcher heading = Pattern.compile("^# Forms matching '\\(all\\)' \\((\\d+) found\\)\n\\n").matcher(blank);

		assertTrue(heading.find(), "Unexpected heading: " + blank.lines().findFirst().orElse(""));
		int listed = (int) blank.lines().filter(line -> line.startsWith("## ")).count();
		assertAll(() -> assertEquals(listed, Integer.parseInt(heading.group(1))), () -> assertEquals(blank, none));
		for (String name : List.of(MAIN, DETAILS, OVERVIEW, UNNAMED, BASE, CHILD, RESPONSIVE, SPEC_MISSING, AMB_ONE,
				AMB_TWO, WEB_COMP_FORM, EDGE_FORM, PORTAL_FORM, ISLAND_PARENT, ISLAND_CHILD, MODULE_FORM)) {
			assertTrue(blank.contains("\n## " + name + "\n"), "Form " + name + " missing from the (all) listing");
		}
	}

	// -----------------------------------------------------------------------
	// getFormNavigationGraph reachability guard
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("getFormNavigationGraph omits reachability when the subgraph has edges (full and summary)")
	void navigationGraph_reachabilityAbsentWhenSubgraphHasEdges() throws Exception {
		JsonNode full = json(testingServer.getFormNavigationGraph(DETAILS, false));
		JsonNode summary = json(testingServer.getFormNavigationGraph(DETAILS, true));

		assertAll(() -> assertTrue(full.get("edgeCount").asInt() > 0), () -> assertFalse(full.has("reachability")),
				() -> assertTrue(full.get("targetFormExists").asBoolean()),
				() -> assertEquals(1, full.get("pathTo").size()),
				() -> assertTrue(summary.get("edgeCount").asInt() > 0), () -> assertFalse(summary.has("reachability")),
				() -> assertEquals(1, summary.get("pathTo").size()));
	}

	@Test
	@DisplayName("getFormNavigationGraph reports reachability=unreachable for a form in the graph that the main form cannot reach (full and summary)")
	void navigationGraph_reachabilityUnreachable() throws Exception {
		JsonNode full = json(testingServer.getFormNavigationGraph(ISLAND_CHILD, false));
		JsonNode summary = json(testingServer.getFormNavigationGraph(ISLAND_CHILD, true));

		assertAll(() -> assertEquals(0, full.get("edgeCount").asInt()),
				() -> assertTrue(full.get("targetFormExists").asBoolean()),
				() -> assertEquals("unreachable", full.get("reachability").asText()),
				() -> assertEquals(0, full.get("pathTo").size()),
				() -> assertEquals(0, summary.get("edgeCount").asInt()),
				() -> assertEquals("unreachable", summary.get("reachability").asText()));
	}

	@Test
	@DisplayName("getFormNavigationGraph reports reachability=isMainForm for the main form and unknownForm for a name the graph has never seen")
	void navigationGraph_reachabilityMainAndUnknown() throws Exception {
		JsonNode main = json(testingServer.getFormNavigationGraph(MAIN, false));
		JsonNode unknown = json(testingServer.getFormNavigationGraph("no_such_form_xyz", false));

		assertAll(() -> assertEquals("isMainForm", main.get("reachability").asText()),
				() -> assertEquals(0, main.get("pathTo").size()),
				() -> assertEquals("unknownForm", unknown.get("reachability").asText()),
				() -> assertFalse(unknown.get("targetFormExists").asBoolean()));
	}

	// -----------------------------------------------------------------------
	// locateForm (Increment B)
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("locateForm resolves a unique best match with the tab path from the main form and its elements")
	void locateForm_resolved_withPathAndElements() throws Exception {
		JsonNode root = json(testingServer.locateForm("Orders Cockpit Details", true));
		JsonNode step = root.get("pathTo").get(0);

		assertAll(() -> assertEquals("resolved", root.get("status").asText()),
				() -> assertEquals("Orders Cockpit Details", root.get("query").asText()),
				() -> assertEquals(DETAILS, root.get("form").get("name").asText()),
				() -> assertEquals("Orders Cockpit Details", root.get("form").get("title").asText()),
				() -> assertEquals(TEST_SOLUTION, root.get("form").get("module").asText()),
				() -> assertEquals("css", root.get("form").get("layout").asText()),
				() -> assertEquals(MAIN, root.get("mainForm").asText()),
				() -> assertEquals("reachable", root.get("reachability").asText()),
				() -> assertEquals(1, root.get("pathTo").size()), () -> assertEquals(MAIN, step.get("from").asText()),
				() -> assertEquals(DETAILS, step.get("to").asText()),
				() -> assertEquals("tabs", step.get("containerName").asText()),
				() -> assertEquals(DETAILS, root.get("candidates").get(0).get("name").asText()),
				() -> assertEquals(3, root.get("candidates").get(0).get("tier").asInt()),
				() -> assertEquals("btn_details", root.get("elements").get("elements").get(0).get("name").asText()),
				() -> assertEquals("fiv_cockpit_details.btn_details",
						root.get("elements").get("elements").get(0).get("dataCy").asText()),
				() -> assertEquals(1, root.get("elements").get("totalElements").asInt()),
				() -> assertFalse(root.get("elements").has("form")));
	}

	@Test
	@DisplayName("locateForm with includeElements omitted (null) defaults to true and returns the elements")
	void locateForm_includeElementsDefaultsToTrue() throws Exception {
		JsonNode root = json(testingServer.locateForm("Orders Cockpit Details", null));

		assertAll(() -> assertEquals("resolved", root.get("status").asText()), () -> assertTrue(root.has("elements")),
				() -> assertEquals("btn_details", root.get("elements").get("elements").get(0).get("name").asText()));
	}

	@Test
	@DisplayName("locateForm with includeElements=false has no elements key")
	void locateForm_includeElementsFalse_hasNoElements() throws Exception {
		JsonNode root = json(testingServer.locateForm("Orders Cockpit Details", false));

		assertAll(() -> assertEquals("resolved", root.get("status").asText()),
				() -> assertEquals(DETAILS, root.get("form").get("name").asText()),
				() -> assertFalse(root.has("elements")),
				() -> assertEquals("reachable", root.get("reachability").asText()),
				() -> assertEquals(1, root.get("pathTo").size()));
	}

	@Test
	@DisplayName("locateForm for the main form itself reports isMainForm with an empty path")
	void locateForm_mainForm_isMainForm() throws Exception {
		JsonNode root = json(testingServer.locateForm("Orders Cockpit", false));

		assertAll(() -> assertEquals("resolved", root.get("status").asText()),
				() -> assertEquals(MAIN, root.get("form").get("name").asText()),
				() -> assertEquals("isMainForm", root.get("reachability").asText()),
				() -> assertEquals(0, root.get("pathTo").size()));
	}

	@Test
	@DisplayName("getNavigationPath from the main form to a tabbed form returns mainForm, pathTo and the serialized step")
	void getNavigationPath_mainFormToTab_returnsSteps() throws Exception {
		JsonNode root = json(testingServer.getNavigationPath(DETAILS, null));
		JsonNode step = root.get("steps").get(0);

		assertAll(() -> assertEquals(MAIN, root.get("mainForm").asText()),
				() -> assertEquals(DETAILS, root.get("pathTo").asText()),
				() -> assertEquals(1, root.get("steps").size()), () -> assertEquals(MAIN, step.get("from").asText()),
				() -> assertEquals(DETAILS, step.get("to").asText()),
				() -> assertEquals("tabs", step.get("containerName").asText()),
				() -> assertEquals("tabpanel", step.get("containerType").asText()),
				() -> assertEquals("static", step.get("confidence").asText()),
				() -> assertEquals("[data-cy=\"fiv_main.tabs\"]", step.get("cypressSelector").asText()));
	}

	@Test
	@DisplayName("getNavigationPath with an explicit fromForm starts there instead of at the main form")
	void getNavigationPath_explicitFromForm() throws Exception {
		JsonNode root = json(testingServer.getNavigationPath(DETAILS, "  " + MAIN + "  "));

		assertAll(() -> assertEquals(MAIN, root.get("mainForm").asText()),
				() -> assertEquals(1, root.get("steps").size()),
				() -> assertEquals(DETAILS, root.get("steps").get(0).get("to").asText()));
	}

	@Test
	@DisplayName("getNavigationPath to a form that no edge reaches returns the plain no-path error naming both forms")
	void getNavigationPath_unreachableTarget_returnsNoPathError() throws Exception {
		String result = testingServer.getNavigationPath(SPEC_MISSING, null);

		assertTrue(result.startsWith("Error: No navigation path found from '" + MAIN + "' to '" + SPEC_MISSING + "'. "),
				result);
	}

	@Test
	@DisplayName("getNavigationPath from an unreachable fromForm to a form the graph cannot get to returns the no-path error")
	void getNavigationPath_explicitFromForm_withoutPath_returnsNoPathError() throws Exception {
		String result = testingServer.getNavigationPath(MAIN, DETAILS);

		assertTrue(result.startsWith("Error: No navigation path found from '" + DETAILS + "' to '" + MAIN + "'. "),
				result);
	}

	@Test
	@DisplayName("locateForm for an existing form that no navigation edge mentions reports unreachable, not unknownForm")
	void locateForm_formWithoutEdges_isUnreachable() throws Exception {
		JsonNode graph = json(testingServer.getFormNavigationGraph(SPEC_MISSING, true));
		JsonNode root = json(testingServer.locateForm(SPEC_MISSING, false));

		assertAll(
				() -> assertFalse(graph.get("targetFormExists").asBoolean(),
						"precondition: the graph has never seen " + SPEC_MISSING),
				() -> assertEquals("resolved", root.get("status").asText()),
				() -> assertEquals(SPEC_MISSING, root.get("form").get("name").asText()),
				() -> assertEquals("unreachable", root.get("reachability").asText()),
				() -> assertEquals(0, root.get("pathTo").size()));
	}

	@Test
	@DisplayName("locateForm with two forms of the same title is ambiguous: two candidates, no form, no path, no elements")
	void locateForm_ambiguous() throws Exception {
		JsonNode root = json(testingServer.locateForm("Ambiguous Twin", true));
		Set<String> candidates = Set.of(root.get("candidates").get(0).get("name").asText(),
				root.get("candidates").get(1).get("name").asText());

		assertAll(() -> assertEquals("ambiguous", root.get("status").asText()),
				() -> assertEquals(2, root.get("candidates").size()),
				() -> assertEquals(Set.of(AMB_ONE, AMB_TWO), candidates),
				() -> assertEquals(3, root.get("candidates").get(0).get("tier").asInt()),
				() -> assertFalse(root.has("form")), () -> assertFalse(root.has("pathTo")),
				() -> assertFalse(root.has("elements")), () -> assertFalse(root.has("reachability")));
	}

	@Test
	@DisplayName("locateForm without any match is notFound with no candidates")
	void locateForm_notFound() throws Exception {
		JsonNode root = json(testingServer.locateForm("zzqqxx nothing", true));

		assertAll(() -> assertEquals("notFound", root.get("status").asText()),
				() -> assertEquals(0, root.get("candidates").size()), () -> assertFalse(root.has("form")),
				() -> assertFalse(root.has("pathTo")), () -> assertFalse(root.has("elements")));
	}
}
