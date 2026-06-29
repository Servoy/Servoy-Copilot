package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.j2db.FlattenedSolution;
import org.json.JSONObject;

@DisplayName("FormNavigationGraphService")
class FormNavigationGraphServiceTest {
	private FormNavigationGraphService service;
	private Pattern formPropertyAssignment;
	private Pattern showFormInDialog;
	private Pattern showFormPopup;
	private Pattern navigateToForm;
	private Pattern jsFormNamesRef;

	@BeforeEach
	void setUp() throws Exception {
		service = new FormNavigationGraphService();
		formPropertyAssignment = getPatternField("FORM_PROPERTY_ASSIGNMENT");
		showFormInDialog = getPatternField("SHOW_FORM_IN_DIALOG");
		showFormPopup = getPatternField("SHOW_FORM_POPUP");
		navigateToForm = getPatternField("NAVIGATE_TO_FORM");
		jsFormNamesRef = getPatternField("JSFORM_NAMES_REF");
	}

	private Pattern getPatternField(String fieldName) throws Exception {
		Field field = FormNavigationGraphService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return (Pattern) field.get(null);
	}

	@Nested
	@DisplayName("FORM_PROPERTY_ASSIGNMENT pattern")
	class FormPropertyAssignmentPatternTests {
		@Test
		@DisplayName("matches elements.name.property = forms.formName")
		void matchesFormsReference() {
			String script = "elements.myTabless.containedForm = forms.orderDetail";
			Matcher m = formPropertyAssignment.matcher(script);
			assertTrue(m.find());
			assertAll(() -> assertEquals("myTabless", m.group(1)), () -> assertEquals("containedForm", m.group(2)),
					() -> assertEquals("orderDetail", m.group(3)));
		}

		@Test
		@DisplayName("matches elements.name.property = 'formName'")
		void matchesSingleQuotedString() {
			String script = "elements.panel1.containedForm = 'customerList'";
			Matcher m = formPropertyAssignment.matcher(script);
			assertTrue(m.find());
			assertAll(() -> assertEquals("panel1", m.group(1)), () -> assertEquals("containedForm", m.group(2)),
					() -> assertNull(m.group(3)), () -> assertEquals("customerList", m.group(4)));
		}

		@Test
		@DisplayName("matches elements.name.property = \"formName\"")
		void matchesDoubleQuotedString() {
			String script = "elements.sidenav.headerForm = \"myHeaderForm\"";
			Matcher m = formPropertyAssignment.matcher(script);
			assertTrue(m.find());
			assertAll(() -> assertEquals("sidenav", m.group(1)), () -> assertEquals("headerForm", m.group(2)),
					() -> assertEquals("myHeaderForm", m.group(4)));
		}

		@Test
		@DisplayName("matches various form-typed property names")
		void matchesVariousPropertyNames() {
			String[] scripts = { "elements.nav.footerForm = forms.footer", "elements.grid.editForm = forms.editRow",
					"elements.split.containsFormId = forms.leftPanel" };
			for (String script : scripts) {
				Matcher m = formPropertyAssignment.matcher(script);
				assertTrue(m.find(), "Should match: " + script);
			}
		}

		@Test
		@DisplayName("does not match unrelated assignments")
		void doesNotMatchUnrelated() {
			String script = "var x = forms.someForm";
			Matcher m = formPropertyAssignment.matcher(script);
			assertFalse(m.find(), "Should not match: " + script);
		}
	}

	@Nested
	@DisplayName("SHOW_FORM_IN_DIALOG pattern")
	class ShowFormInDialogPatternTests {
		@Test
		@DisplayName("matches application.createWindow(...).show(forms.name)")
		void matchesCreateWindowShow() {
			String script = "var w = application.createWindow('myDialog', JSWindow.DIALOG).show(forms.orderEditor)";
			Matcher m = showFormInDialog.matcher(script);
			assertTrue(m.find());
			assertEquals("orderEditor", m.group(1));
		}

		@Test
		@DisplayName("matches with multiline content in createWindow args")
		void matchesMultilineArgs() {
			String script = "application.createWindow('dlg',\nJSWindow.MODAL_DIALOG).show(forms.settingsForm)";
			Matcher m = showFormInDialog.matcher(script);
			assertTrue(m.find());
			assertEquals("settingsForm", m.group(1));
		}

		@Test
		@DisplayName("does not match unrelated window calls")
		void doesNotMatchUnrelated() {
			String script = "application.showForm(forms.someForm)";
			Matcher m = showFormInDialog.matcher(script);
			assertFalse(m.find(), "Should not match: " + script);
		}
	}

	@Nested
	@DisplayName("SHOW_FORM_POPUP pattern")
	class ShowFormPopupPatternTests {
		@Test
		@DisplayName("matches plugins.window.showFormPopup(null, forms.name, ...)")
		void matchesShowFormPopup() {
			String script = "plugins.window.showFormPopup(null, forms.popupEditor, 'scope', 'method')";
			Matcher m = showFormPopup.matcher(script);
			assertTrue(m.find());
			assertEquals("popupEditor", m.group(1));
		}

		@Test
		@DisplayName("matches with element reference as first arg")
		void matchesWithElementFirstArg() {
			String script = "plugins.window.showFormPopup(elements.btn, forms.quickEdit, null, null)";
			Matcher m = showFormPopup.matcher(script);
			assertTrue(m.find());
			assertEquals("quickEdit", m.group(1));
		}

		@Test
		@DisplayName("does not match unrelated plugin calls")
		void doesNotMatchUnrelated() {
			String script = "plugins.window.closeFormPopup()";
			Matcher m = showFormPopup.matcher(script);
			assertFalse(m.find(), "Should not match: " + script);
		}
	}

	@Nested
	@DisplayName("NAVIGATE_TO_FORM pattern")
	class NavigateToFormPatternTests {
		@Test
		@DisplayName("matches bare navigateToForm(forms.X) call")
		void matchesBareCall() {
			String script = "navigateToForm(forms.appDetails)";
			Matcher m = navigateToForm.matcher(script);
			assertTrue(m.find());
			assertEquals("appDetails", m.group(1));
		}

		@Test
		@DisplayName("matches scopes.navigation.navigateToForm(forms.X) prefix")
		void matchesScopesPrefix() {
			String script = "scopes.navigation.navigateToForm(forms.environmentQueryPerfOverview)";
			Matcher m = navigateToForm.matcher(script);
			assertTrue(m.find());
			assertEquals("environmentQueryPerfOverview", m.group(1));
		}

		@Test
		@DisplayName("matches arbitrary module prefix myNav.navigateToForm(forms.X)")
		void matchesArbitraryModulePrefix() {
			String script = "myNav.navigateToForm(forms.ordersList)";
			Matcher m = navigateToForm.matcher(script);
			assertTrue(m.find());
			assertEquals("ordersList", m.group(1));
		}

		@Test
		@DisplayName("matches with whitespace inside the call")
		void matchesWithWhitespace() {
			String script = "navigateToForm(  forms.detailForm )";
			Matcher m = navigateToForm.matcher(script);
			assertTrue(m.find());
			assertEquals("detailForm", m.group(1));
		}

		@Test
		@DisplayName("does not match unrelated navigation calls")
		void doesNotMatchUnrelated() {
			String script = "navigateToUrl('http://example.com')";
			Matcher m = navigateToForm.matcher(script);
			assertFalse(m.find(), "Should not match: " + script);
		}
	}

	@Nested
	@DisplayName("JSFORM_NAMES_REF pattern")
	class JsFormNamesRefPatternTests {
		@Test
		@DisplayName("matches navigateToForm(JSForm.NAMES.X) argument")
		void matchesInsideNavigateToForm() {
			String script = "navigateToForm(JSForm.NAMES.appDetails)";
			Matcher m = jsFormNamesRef.matcher(script);
			assertTrue(m.find());
			assertEquals("appDetails", m.group(1));
		}

		@Test
		@DisplayName("matches assignment someVar = JSForm.NAMES.X")
		void matchesAssignment() {
			String script = "var target = JSForm.NAMES.ordersList";
			Matcher m = jsFormNamesRef.matcher(script);
			assertTrue(m.find());
			assertEquals("ordersList", m.group(1));
		}

		@Test
		@DisplayName("matches standalone JSForm.NAMES.X expression")
		void matchesStandaloneExpression() {
			String script = "JSForm.NAMES.environmentDetails";
			Matcher m = jsFormNamesRef.matcher(script);
			assertTrue(m.find());
			assertEquals("environmentDetails", m.group(1));
		}

		@Test
		@DisplayName("does not match unrelated JSForm references")
		void doesNotMatchUnrelated() {
			String script = "JSForm.SELECTION_MODE_SINGLE";
			Matcher m = jsFormNamesRef.matcher(script);
			// SELECTION_MODE_SINGLE is not preceded by NAMES., so the NAMES.(\\w+) group must not match it
			assertFalse(m.find(), "Should not match: " + script);
		}
	}

	@Nested
	@DisplayName("extractFormContext")
	class ExtractFormContextTests {
		private String invokeExtractFormContext(String scriptPath) throws Exception {
			Method method = FormNavigationGraphService.class.getDeclaredMethod("extractFormContext", String.class);
			method.setAccessible(true);
			return (String) method.invoke(service, scriptPath);
		}

		@Test
		@DisplayName("extracts form name from forms/formName/formName.js")
		void extractsFromStandardPath() throws Exception {
			assertEquals("orderDetail", invokeExtractFormContext("forms/orderDetail/orderDetail.js"));
		}

		@Test
		@DisplayName("extracts form name from forms/formName.js (flat structure)")
		void extractsFromFlatPath() throws Exception {
			assertEquals("main_form", invokeExtractFormContext("forms/main_form.js"));
		}

		@Test
		@DisplayName("falls back to file base name for non-forms path")
		void fallsBackToBaseNameForNonFormsPath() throws Exception {
			assertEquals("globals", invokeExtractFormContext("globals/globals.js"));
		}

		@Test
		@DisplayName("falls back to file base name for scopes path so navigateToForm calls are captured")
		void fallsBackToBaseNameForScopesPath() throws Exception {
			assertEquals("myScope", invokeExtractFormContext("scopes/myScope.js"));
		}

		@Test
		@DisplayName("excludes Cypress spec files (*.spec.cy.js)")
		void excludesCypressSpecFiles() throws Exception {
			assertNull(invokeExtractFormContext("cypress/e2e/orders.spec.cy.js"));
		}

		@Test
		@DisplayName("excludes test files (test_*.js)")
		void excludesTestFiles() throws Exception {
			assertNull(invokeExtractFormContext("scopes/test_helpers.js"));
		}

		@Test
		@DisplayName("returns null for empty input")
		void returnsNullForEmptyInput() throws Exception {
			assertNull(invokeExtractFormContext(""));
		}

		@Test
		@DisplayName("throws NullPointerException for null input")
		void throwsNpeForNullInput() throws Exception {
			Method method = FormNavigationGraphService.class.getDeclaredMethod("extractFormContext", String.class);
			method.setAccessible(true);
			try {
				method.invoke(service, (Object) null);
			} catch (java.lang.reflect.InvocationTargetException e) {
				assertTrue(e.getCause() instanceof NullPointerException);
				return;
			}
			assertTrue(false, "Expected NullPointerException");
		}
	}

	@Nested
	@DisplayName("findEnclosingMethod")
	class FindEnclosingMethodTests {
		private String invokeFindEnclosingMethod(int offset, List<int[]> offsets, List<String> names) throws Exception {
			Method method = FormNavigationGraphService.class.getDeclaredMethod("findEnclosingMethod", int.class,
					List.class, List.class);
			method.setAccessible(true);
			return (String) method.invoke(service, offset, offsets, names);
		}

		@Test
		@DisplayName("returns function name when offset is inside range")
		void returnsNameWhenInsideRange() throws Exception {
			List<int[]> offsets = new ArrayList<>();
			offsets.add(new int[] { 0, 100 });
			offsets.add(new int[] { 101, 200 });
			List<String> names = new ArrayList<>();
			names.add("onLoad");
			names.add("onClick");

			assertEquals("onLoad", invokeFindEnclosingMethod(50, offsets, names));
			assertEquals("onClick", invokeFindEnclosingMethod(150, offsets, names));
		}

		@Test
		@DisplayName("returns null when offset is outside all ranges")
		void returnsNullWhenOutsideRanges() throws Exception {
			List<int[]> offsets = new ArrayList<>();
			offsets.add(new int[] { 10, 50 });
			List<String> names = new ArrayList<>();
			names.add("onLoad");

			assertNull(invokeFindEnclosingMethod(60, offsets, names));
		}

		@Test
		@DisplayName("returns null for empty lists")
		void returnsNullForEmptyLists() throws Exception {
			assertNull(invokeFindEnclosingMethod(10, new ArrayList<>(), new ArrayList<>()));
		}
	}

	@Nested
	@DisplayName("resolveTabPanelType")
	class ResolveTabPanelTypeTests {
		private String invokeResolveTabPanelType(int orientation) throws Exception {
			java.lang.reflect.Constructor<?> ctor = com.servoy.j2db.persistence.TabPanel.class
					.getDeclaredConstructors()[0];
			ctor.setAccessible(true);
			com.servoy.j2db.persistence.TabPanel tabPanel = (com.servoy.j2db.persistence.TabPanel) ctor
					.newInstance(null, com.servoy.j2db.util.UUID.randomUUID());
			tabPanel.setTabOrientation(orientation);

			Method method = FormNavigationGraphService.class.getDeclaredMethod("resolveTabPanelType",
					com.servoy.j2db.persistence.TabPanel.class);
			method.setAccessible(true);
			return (String) method.invoke(service, tabPanel);
		}

		@Test
		@DisplayName("orientation HIDE (-1) returns tabless")
		void hide_returnsTabless() throws Exception {
			assertEquals("tabless", invokeResolveTabPanelType(com.servoy.j2db.persistence.TabPanel.HIDE));
		}

		@Test
		@DisplayName("orientation SPLIT_HORIZONTAL (-2) returns splitpane")
		void splitHorizontal_returnsSplitpane() throws Exception {
			assertEquals("splitpane", invokeResolveTabPanelType(com.servoy.j2db.persistence.TabPanel.SPLIT_HORIZONTAL));
		}

		@Test
		@DisplayName("orientation SPLIT_VERTICAL (-3) returns splitpane")
		void splitVertical_returnsSplitpane() throws Exception {
			assertEquals("splitpane", invokeResolveTabPanelType(com.servoy.j2db.persistence.TabPanel.SPLIT_VERTICAL));
		}

		@Test
		@DisplayName("orientation ACCORDION_PANEL (-4) returns accordion")
		void accordion_returnsAccordion() throws Exception {
			assertEquals("accordion", invokeResolveTabPanelType(com.servoy.j2db.persistence.TabPanel.ACCORDION_PANEL));
		}

		@Test
		@DisplayName("orientation DEFAULT_ORIENTATION (0) returns tabpanel")
		void defaultOrientation_returnsTabpanel() throws Exception {
			assertEquals("tabpanel",
					invokeResolveTabPanelType(com.servoy.j2db.persistence.TabPanel.DEFAULT_ORIENTATION));
		}

		@Test
		@DisplayName("positive orientation (1 = TOP) returns tabpanel")
		void positiveOrientation_returnsTabpanel() throws Exception {
			assertEquals("tabpanel", invokeResolveTabPanelType(1));
		}
	}

	@Nested
	@DisplayName("script analysis edge construction")
	class ScriptAnalysisEdgeConstructionTests {
		private NavigationGraph buildEdgesFromScript(String scriptContent, String formContext) throws Exception {
			NavigationGraph graph = new NavigationGraph();
			if (formContext == null)
				return graph;

			java.util.regex.Matcher dialogMatcher = showFormInDialog.matcher(scriptContent);
			while (dialogMatcher.find()) {
				graph.addEdge(new NavigationEdge.Builder().from(formContext).to(dialogMatcher.group(1))
						.containerType("dialog").trigger("test.js").confidence("dynamic").build());
			}

			java.util.regex.Matcher popupMatcher = showFormPopup.matcher(scriptContent);
			while (popupMatcher.find()) {
				graph.addEdge(new NavigationEdge.Builder().from(formContext).to(popupMatcher.group(1))
						.containerType("popup").trigger("test.js").confidence("dynamic").build());
			}

			java.util.regex.Matcher assignMatcher = formPropertyAssignment.matcher(scriptContent);
			while (assignMatcher.find()) {
				String elementName = assignMatcher.group(1);
				String propertyName = assignMatcher.group(2);
				String targetForm = assignMatcher.group(3) != null ? assignMatcher.group(3) : assignMatcher.group(4);
				if (targetForm != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(targetForm)
							.containerName(elementName).containerType("formcomponent").propertyName(propertyName)
							.trigger(elementName + "." + propertyName).confidence("dynamic").build());
				}
			}

			java.util.regex.Matcher navigateMatcher = navigateToForm.matcher(scriptContent);
			while (navigateMatcher.find()) {
				graph.addEdge(new NavigationEdge.Builder().from(formContext).to(navigateMatcher.group(1))
						.containerType("navigation").trigger("test.js").confidence("dynamic").build());
			}

			java.util.regex.Matcher jsFormNamesMatcher = jsFormNamesRef.matcher(scriptContent);
			while (jsFormNamesMatcher.find()) {
				graph.addEdge(new NavigationEdge.Builder().from(formContext).to(jsFormNamesMatcher.group(1))
						.containerType("navigation").trigger("test.js").confidence("dynamic").build());
			}

			return graph;
		}

		@Test
		@DisplayName("createWindow().show(forms.x) produces dialog edge with dynamic confidence")
		void dialogEdge_hasCorrectContainerTypeAndConfidence() throws Exception {
			String script = "var w = application.createWindow(\"x\", JSWindow.DIALOG).show(forms.myDialog)";
			NavigationGraph graph = buildEdgesFromScript(script, "mainForm");

			List<NavigationEdge> edges = graph.getEdgesFrom("mainForm");
			assertEquals(1, edges.size());
			assertAll(() -> assertEquals("myDialog", edges.get(0).getTo()),
					() -> assertEquals("dialog", edges.get(0).getContainerType()),
					() -> assertEquals("dynamic", edges.get(0).getConfidence()));
		}

		@Test
		@DisplayName("showFormPopup produces popup edge with dynamic confidence")
		void popupEdge_hasCorrectContainerTypeAndConfidence() throws Exception {
			String script = "plugins.window.showFormPopup(null, forms.popupForm, 'scope', 'method')";
			NavigationGraph graph = buildEdgesFromScript(script, "mainForm");

			List<NavigationEdge> edges = graph.getEdgesFrom("mainForm");
			assertEquals(1, edges.size());
			assertAll(() -> assertEquals("popupForm", edges.get(0).getTo()),
					() -> assertEquals("popup", edges.get(0).getContainerType()),
					() -> assertEquals("dynamic", edges.get(0).getConfidence()));
		}

		@Test
		@DisplayName("form property assignment produces formcomponent edge with dynamic confidence")
		void formPropertyEdge_hasCorrectContainerTypeAndConfidence() throws Exception {
			String script = "elements.myTabless.containedForm = forms.orderDetail";
			NavigationGraph graph = buildEdgesFromScript(script, "mainForm");

			List<NavigationEdge> edges = graph.getEdgesFrom("mainForm");
			assertEquals(1, edges.size());
			assertAll(() -> assertEquals("orderDetail", edges.get(0).getTo()),
					() -> assertEquals("formcomponent", edges.get(0).getContainerType()),
					() -> assertEquals("dynamic", edges.get(0).getConfidence()),
					() -> assertEquals("myTabless", edges.get(0).getContainerName()));
		}

		@Test
		@DisplayName("navigateToForm(forms.X) produces navigation edge with dynamic confidence")
		void navigateToFormEdge_hasCorrectContainerTypeAndConfidence() throws Exception {
			String script = "scopes.navigation.navigateToForm(forms.environmentQueryPerfOverview)";
			NavigationGraph graph = buildEdgesFromScript(script, "navigation");

			List<NavigationEdge> edges = graph.getEdgesFrom("navigation");
			assertEquals(1, edges.size());
			assertAll(() -> assertEquals("environmentQueryPerfOverview", edges.get(0).getTo()),
					() -> assertEquals("navigation", edges.get(0).getContainerType()),
					() -> assertEquals("dynamic", edges.get(0).getConfidence()));
		}

		@Test
		@DisplayName("JSForm.NAMES.X produces navigation edge with dynamic confidence")
		void jsFormNamesEdge_hasCorrectContainerTypeAndConfidence() throws Exception {
			String script = "navigateToForm(JSForm.NAMES.appDetails)";
			NavigationGraph graph = buildEdgesFromScript(script, "appReportsBaseWidget");

			// Both NAVIGATE_TO_FORM (no match, arg is JSForm.NAMES not forms.) and JSFORM_NAMES_REF apply;
			// only JSForm.NAMES.appDetails yields a form reference here.
			List<NavigationEdge> edges = graph.getEdgesFrom("appReportsBaseWidget");
			assertEquals(1, edges.size());
			assertAll(() -> assertEquals("appDetails", edges.get(0).getTo()),
					() -> assertEquals("navigation", edges.get(0).getContainerType()),
					() -> assertEquals("dynamic", edges.get(0).getConfidence()));
		}

		@Test
		@DisplayName("mixed script produces multiple edges with correct types")
		void mixedScript_producesMultipleEdges() throws Exception {
			String script = "application.createWindow(\"x\", JSWindow.DIALOG).show(forms.myDialog)\n"
					+ "plugins.window.showFormPopup(null, forms.popupForm, null, null)\n"
					+ "elements.panel.containedForm = forms.childForm\n";
			NavigationGraph graph = buildEdgesFromScript(script, "parentForm");

			List<NavigationEdge> edges = graph.getEdgesFrom("parentForm");
			assertEquals(3, edges.size());

			long dialogCount = edges.stream().filter(e -> "dialog".equals(e.getContainerType())).count();
			long popupCount = edges.stream().filter(e -> "popup".equals(e.getContainerType())).count();
			long formcompCount = edges.stream().filter(e -> "formcomponent".equals(e.getContainerType())).count();
			assertAll(() -> assertEquals(1, dialogCount), () -> assertEquals(1, popupCount),
					() -> assertEquals(1, formcompCount));
			assertTrue(edges.stream().allMatch(e -> "dynamic".equals(e.getConfidence())));
		}
	}

	@Nested
	@DisplayName("class structure")
	class ClassStructureTests {
		@Test
		@DisplayName("has @Creatable annotation")
		void hasCreatableAnnotation() {
			assertNotNull(
					FormNavigationGraphService.class.getAnnotation(org.eclipse.e4.core.di.annotations.Creatable.class));
		}

		@Test
		@DisplayName("has buildFullGraph method")
		void hasBuildFullGraphMethod() throws NoSuchMethodException {
			Method m = FormNavigationGraphService.class.getMethod("buildFullGraph");
			assertEquals(NavigationGraph.class, m.getReturnType());
		}

		@Test
		@DisplayName("has getMainFormName method")
		void hasGetMainFormNameMethod() throws NoSuchMethodException {
			Method m = FormNavigationGraphService.class.getMethod("getMainFormName");
			assertEquals(String.class, m.getReturnType());
		}
	}

	@Nested
	@DisplayName("NavigationEdge Builder")
	class NavigationEdgeBuilderTests {
		@Test
		@DisplayName("builds edge with all properties")
		void buildsWithAllProperties() {
			NavigationEdge edge = new NavigationEdge.Builder().from("formA").to("formB").containerName("tabs_1")
					.containerType("tabpanel").propertyName("containsFormID").tabName("Orders").tabIndex(0)
					.relationName("orders_to_details").trigger("button_1.onAction").confidence("static").build();

			assertAll(() -> assertEquals("formA", edge.getFrom()), () -> assertEquals("formB", edge.getTo()),
					() -> assertEquals("tabs_1", edge.getContainerName()),
					() -> assertEquals("tabpanel", edge.getContainerType()),
					() -> assertEquals("containsFormID", edge.getPropertyName()),
					() -> assertEquals("Orders", edge.getTabName()), () -> assertEquals(0, edge.getTabIndex()),
					() -> assertEquals("orders_to_details", edge.getRelationName()),
					() -> assertEquals("button_1.onAction", edge.getTrigger()),
					() -> assertEquals("static", edge.getConfidence()));
		}

		@Test
		@DisplayName("default confidence is static")
		void defaultConfidenceIsStatic() {
			NavigationEdge edge = new NavigationEdge.Builder().from("a").to("b").build();

			assertEquals("static", edge.getConfidence());
		}

		@Test
		@DisplayName("default tabIndex is -1")
		void defaultTabIndexIsMinusOne() {
			NavigationEdge edge = new NavigationEdge.Builder().from("a").to("b").build();

			assertEquals(-1, edge.getTabIndex());
		}
	}

	@Nested
	@DisplayName("WindowVarPattern - variable-stored window detection")
	class WindowVarPatternTests {

		private Pattern windowVarAssignment;

		@BeforeEach
		void setUp() throws Exception {
			windowVarAssignment = getPatternField("WINDOW_VAR_ASSIGNMENT");
		}

		@Test
		@DisplayName("WINDOW_VAR_ASSIGNMENT matches 'var w = application.createWindow(...)'")
		void patternMatchesVarAssignment() {
			String script = "var w = application.createWindow(\"myName2\", JSWindow.DIALOG);";
			Matcher m = windowVarAssignment.matcher(script);
			assertTrue(m.find());
			assertEquals("w", m.group(1));
		}

		@Test
		@DisplayName("WINDOW_VAR_ASSIGNMENT captures variable name before '='")
		void patternCapturesVarName() {
			String script = "var myWindow = application.createWindow(\"x\", JSWindow.DIALOG);";
			Matcher m = windowVarAssignment.matcher(script);
			assertTrue(m.find());
			assertEquals("myWindow", m.group(1));
		}

		@Test
		@DisplayName("two-pass detection: var assignment + .show(forms.X) produces dialog edge with dynamic confidence")
		void twoPassDetectsDialogEdge() throws Exception {
			// Script uses variable-stored window pattern
			String script = "var myWindow = application.createWindow(\"myName2\", JSWindow.DIALOG);\n"
					+ "myWindow.show(forms.dialogform2);\n";

			NavigationGraph graph = new NavigationGraph();
			String formContext = "hostForm";

			// Replicate the two-pass logic using the patterns
			java.util.Set<String> windowVarNames = new java.util.HashSet<>();
			Matcher windowVarMatcher = windowVarAssignment.matcher(script);
			while (windowVarMatcher.find()) {
				windowVarNames.add(windowVarMatcher.group(1));
			}
			for (String varName : windowVarNames) {
				java.util.regex.Pattern varShowPattern = java.util.regex.Pattern.compile(
						java.util.regex.Pattern.quote(varName) + "\\.show\\s*\\(\\s*forms\\.(\\w+)",
						java.util.regex.Pattern.MULTILINE);
				Matcher varShowMatcher = varShowPattern.matcher(script);
				while (varShowMatcher.find()) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(varShowMatcher.group(1))
							.containerType("dialog").trigger("test.js").confidence("dynamic").build());
				}
			}

			List<NavigationEdge> edges = graph.getEdgesFrom("hostForm");
			assertEquals(1, edges.size());
			assertAll(() -> assertEquals("dialogform2", edges.get(0).getTo()),
					() -> assertEquals("dialog", edges.get(0).getContainerType()),
					() -> assertEquals("dynamic", edges.get(0).getConfidence()));
		}

		@Test
		@DisplayName("regression: chained createWindow().show(forms.x) still produces dialog edge")
		void chainedPatternStillWorks() throws Exception {
			String script = "application.createWindow(\"x\", JSWindow.DIALOG).show(forms.orderEditor)";
			Matcher m = showFormInDialog.matcher(script);
			assertTrue(m.find());
			assertEquals("orderEditor", m.group(1));
		}
	}

	@Nested
	@DisplayName("generateCypressTestContent")
	class GenerateCypressTestContentTests {

		@Test
		@DisplayName("empty path produces WARNING comment")
		void emptyPath_producesWarningComment() {
			String content = service.generateCypressTestContent("mySolution", "http://localhost:8080", "main_form",
					"target_form", "my scenario", java.util.List.of());
			assertTrue(content.contains("WARNING"), "Expected WARNING in output for empty path");
		}

		@Test
		@DisplayName("URL is correctly constructed as relative solutionName/solution/solutionName/index.html")
		void urlIsCorrectlyConstructed() {
			String content = service.generateCypressTestContent("mySolution", "http://localhost:8080", "main_form",
					"target_form", null, java.util.List.of());
			assertTrue(content.contains("mySolution/solution/mySolution/index.html"),
					"Expected relative constructed URL in output (baseUrl lives in cypress.config.js)");
		}

		@Test
		@DisplayName("null scenario uses targetForm as describe title")
		void nullScenario_usesTargetFormAsTitle() {
			String content = service.generateCypressTestContent("sol", "http://host", "main", "target_form", null,
					java.util.List.of());
			assertTrue(content.contains("target_form"), "Expected targetForm in describe title when scenario is null");
		}

		@Test
		@DisplayName("non-null scenario is used in describe title")
		void scenario_usedInTitle() {
			String content = service.generateCypressTestContent("sol", "http://host", "main", "target_form",
					"Order flow test", java.util.List.of());
			assertTrue(content.contains("Order flow test"), "Expected scenario in describe title");
		}

		@Test
		@DisplayName("tabpanel edge with tabName produces cy.get().click() command")
		void tabpanelEdge_producesClickCommand() {
			NavigationEdge edge = new NavigationEdge.Builder().from("main_form").to("orders").containerName("tabs_1")
					.containerType("tabpanel").tabName("Orders").tabIndex(0).confidence("static").build();

			String content = service.generateCypressTestContent("sol", "http://host", "main_form", "orders", null,
					java.util.List.of(edge));

			assertTrue(content.contains("cy.get("), "Expected cy.get() click command");
			assertTrue(content.contains(".click();"), "Expected .click() in output");
			assertTrue(content.contains("data-cy"), "Expected data-cy selector");
			assertTrue(content.contains("Orders"), "Expected tab name in selector");
		}

		@Test
		@DisplayName("dialog edge produces click plus TODO wait comment")
		void dialogEdge_producesClickPlusTodoWait() {
			NavigationEdge edge = new NavigationEdge.Builder().from("main_form").to("edit_dialog")
					.containerName("btn_edit").containerType("dialog").trigger("btn_edit.onAction").confidence("dynamic")
					.build();

			String content = service.generateCypressTestContent("sol", "http://host", "main_form", "edit_dialog", null,
					java.util.List.of(edge));

			assertTrue(content.contains("cy.get(") && content.contains(".click();"),
					"Expected click command for dialog");
			assertTrue(content.contains("TODO: wait for") && content.contains("edit_dialog"),
					"Expected TODO wait comment for dialog");
		}

		@Test
		@DisplayName("popup edge produces click plus TODO wait comment")
		void popupEdge_producesClickPlusTodoWait() {
			NavigationEdge edge = new NavigationEdge.Builder().from("main_form").to("popup_form")
					.containerName("btn_popup").containerType("popup").trigger("btn_popup.onAction").confidence("dynamic")
					.build();

			String content = service.generateCypressTestContent("sol", "http://host", "main_form", "popup_form", null,
					java.util.List.of(edge));

			assertTrue(content.contains("cy.get(") && content.contains(".click();"),
					"Expected click command for popup");
			assertTrue(content.contains("TODO: wait for") && content.contains("popup_form"),
					"Expected TODO wait comment for popup");
		}

		@Test
		@DisplayName("dynamic confidence adds annotation in comment")
		void dynamicConfidence_addsAnnotation() {
			NavigationEdge edge = new NavigationEdge.Builder().from("main_form").to("child_form")
					.containerName("panel").containerType("formcomponent").trigger("btn.onAction").confidence("dynamic")
					.build();

			String content = service.generateCypressTestContent("sol", "http://host", "main_form", "child_form", null,
					java.util.List.of(edge));

			assertTrue(content.contains("dynamic - triggered by script"),
					"Expected dynamic annotation in navigation comment");
		}

		@Test
		@DisplayName("edge with no selector produces TODO manual navigation comment")
		void noSelector_producesTodoManualComment() {
			NavigationEdge edge = new NavigationEdge.Builder().from("main_form").to("orphan_form")
					.containerType("formcomponent").confidence("static").build();
			// No containerName, no trigger, no tabName -> getCypressSelector() returns null

			String content = service.generateCypressTestContent("sol", "http://host", "main_form", "orphan_form", null,
					java.util.List.of(edge));

			assertTrue(content.contains("TODO: no selector available"),
					"Expected TODO comment when no selector is available");
		}

		@Test
		@DisplayName("output always contains assertions TODO section")
		void outputContainsAssertionsSection() {
			String content = service.generateCypressTestContent("sol", "http://host", "main_form", "target_form", null,
					java.util.List.of());

			assertTrue(content.contains("Assertions"), "Expected Assertions section in output");
			assertTrue(content.contains("TODO: add assertions"), "Expected TODO assertions comment");
		}

		@Test
		@DisplayName("navigation path comment shows from -> to chain")
		void navigationCommentShowsChain() {
			NavigationEdge e1 = new NavigationEdge.Builder().from("main").to("orders").containerType("tabpanel")
					.confidence("static").build();
			NavigationEdge e2 = new NavigationEdge.Builder().from("orders").to("detail").containerType("tabless")
					.confidence("static").build();

			String content = service.generateCypressTestContent("sol", "http://host", "main", "detail", null,
					java.util.List.of(e1, e2));

			assertTrue(content.contains("main") && content.contains("->") && content.contains("orders")
					&& content.contains("detail"), "Expected navigation chain comment");
		}

		@Test
		@DisplayName("tabless edge produces click command (same as tabpanel)")
		void tablessEdge_producesClickCommand() {
			NavigationEdge edge = new NavigationEdge.Builder().from("orders").to("detail")
					.containerName("tabless_panel").containerType("tabless").confidence("static").build();

			String content = service.generateCypressTestContent("sol", "http://host", "orders", "detail", null,
					java.util.List.of(edge));

			assertTrue(content.contains("cy.get(") && content.contains(".click();"),
					"Expected click command for tabless edge");
		}
	}

	@Nested
	@DisplayName("resolveFormReference - defensive JSONObject handling")
	class ResolveFormReferenceJsonTests {

		/**
		 * Minimal FlattenedSolution stub that records the last string passed to
		 * getForm() so tests can assert correct unwrapping without needing a live
		 * Servoy model.
		 */
		private class CapturingFlattenedSolution extends FlattenedSolution {
			String lastLookupKey = null;

			@Override
			public com.servoy.j2db.persistence.Form getForm(String nameOrUUID) {
				lastLookupKey = nameOrUUID;
				return null; // no real model - just capture the argument
			}
		}

		private String invokeResolveFormReference(FlattenedSolution fs, Object value) throws Exception {
			Method method = FormNavigationGraphService.class.getDeclaredMethod("resolveFormReference",
					FlattenedSolution.class, Object.class);
			method.setAccessible(true);
			try {
				return (String) method.invoke(service, fs, value);
			} catch (InvocationTargetException e) {
				throw (Exception) e.getCause();
			}
		}

		@Test
		@DisplayName("null input returns null without touching fs")
		void nullValueReturnsNull() throws Exception {
			assertNull(invokeResolveFormReference(null, null));
		}

		@Test
		@DisplayName("JSONObject with svyFormId key is unwrapped and passed to getForm")
		void jsonObjectWithSvyFormIdIsUnwrapped() throws Exception {
			CapturingFlattenedSolution stub = new CapturingFlattenedSolution();
			JSONObject json = new JSONObject();
			json.put("svyFormId", "test-uuid-1234");
			assertNull(invokeResolveFormReference(stub, json));
			assertEquals("test-uuid-1234", stub.lastLookupKey);
		}

		@Test
		@DisplayName("plain string value still works (regression)")
		void plainStringPassedDirectlyToGetForm() throws Exception {
			CapturingFlattenedSolution stub = new CapturingFlattenedSolution();
			assertNull(invokeResolveFormReference(stub, "myFormName"));
			assertEquals("myFormName", stub.lastLookupKey);
		}

		@Test
		@DisplayName("JSONObject without svyFormId falls back to first string value")
		void jsonObjectWithoutSvyFormIdFallsBackToFirstStringValue() throws Exception {
			CapturingFlattenedSolution stub = new CapturingFlattenedSolution();
			JSONObject json = new JSONObject();
			json.put("otherKey", "otherFormName");
			assertNull(invokeResolveFormReference(stub, json));
			assertEquals("otherFormName", stub.lastLookupKey);
		}
	}
}
