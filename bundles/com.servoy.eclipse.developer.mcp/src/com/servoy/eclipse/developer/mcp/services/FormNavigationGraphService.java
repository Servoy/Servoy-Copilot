package com.servoy.eclipse.developer.mcp.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.json.JSONArray;
import org.json.JSONObject;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.FlattenedSolution;
import com.servoy.j2db.persistence.AbstractContainer;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.LayoutContainer;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.Tab;
import com.servoy.j2db.persistence.TabPanel;
import com.servoy.j2db.persistence.WebComponent;
import com.servoy.j2db.server.ngclient.property.types.FormPropertyType;

import org.sablo.specification.PropertyDescription;
import org.sablo.specification.SpecProviderState;
import org.sablo.specification.WebComponentSpecProvider;
import org.sablo.specification.WebObjectSpecification;
import org.sablo.specification.property.ICustomType;
import org.sablo.specification.property.IPropertyType;

@Creatable
public class FormNavigationGraphService {
	private static final Pattern FORM_PROPERTY_ASSIGNMENT = Pattern
			.compile("elements\\.(\\w+)\\.(\\w+)\\s*=\\s*(?:forms\\.(\\w+)|['\"]([\\w]+)['\"])", Pattern.MULTILINE);

	private static final Pattern SHOW_FORM_IN_DIALOG = Pattern
			.compile("application\\.createWindow\\s*\\([^)]*\\)\\.show\\s*\\(\\s*forms\\.(\\w+)", Pattern.MULTILINE);

	private static final Pattern WINDOW_VAR_ASSIGNMENT = Pattern
			.compile("(\\w+)\\s*=\\s*application\\.createWindow\\s*\\(", Pattern.MULTILINE);

	private static final Pattern SHOW_FORM_POPUP = Pattern
			.compile("plugins\\.window\\.showFormPopup\\s*\\(\\s*[^,]*,\\s*forms\\.(\\w+)", Pattern.MULTILINE);



	// any call ending in navigateToForm(forms.X) â matches regardless of scope chain prefix
	// e.g. scopes.navigation.navigateToForm(forms.X), myNav.navigateToForm(forms.X), navigateToForm(forms.X)
	private static final Pattern NAVIGATE_TO_FORM = Pattern
			.compile("navigateToForm\\s*\\(\\s*forms\\.(\\w+)", Pattern.MULTILINE);

	// JSForm.NAMES.formName â always a form reference, regardless of context
	// covers: navigateToForm(JSForm.NAMES.X), someVar = JSForm.NAMES.X, JSForm.NAMES.appDetails, etc.
	private static final Pattern JSFORM_NAMES_REF = Pattern
			.compile("JSForm\\.NAMES\\.(\\w+)", Pattern.MULTILINE);


	public NavigationGraph buildFullGraph() {
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();
		if (servoyProject == null || servoyProject.getEditingSolution() == null) {
			return new NavigationGraph();
		}

		FlattenedSolution fs = servoyModel.getEditingFlattenedSolution(servoyProject.getEditingSolution());
		if (fs == null) {
			return new NavigationGraph();
		}

		NavigationGraph graph = new NavigationGraph();
		buildStaticGraph(fs, graph);
		augmentWithScriptAnalysis(servoyModel, graph);
		return graph;
	}

	public String getMainFormName() {
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = servoyModel.getActiveProject();
		if (servoyProject == null || servoyProject.getEditingSolution() == null) {
			return null;
		}

		Solution solution = servoyProject.getEditingSolution();
		String firstFormId = solution.getFirstFormID();
		if (firstFormId == null || firstFormId.isEmpty())
			return null;

		FlattenedSolution fs = servoyModel.getEditingFlattenedSolution(solution);
		if (fs == null)
			return null;

		Form firstForm = fs.getForm(firstFormId);
		return firstForm != null ? firstForm.getName() : null;
	}

	private void buildStaticGraph(FlattenedSolution fs, NavigationGraph graph) {
		Iterator<Form> forms = fs.getForms(false);
		while (forms.hasNext()) {
			Form form = forms.next();
			String formName = form.getName();

			processTabPanels(fs, form, formName, graph);
			processWebComponents(fs, form, formName, graph);
			processNavigator(fs, form, formName, graph);
			processLayoutContainers(fs, form, formName, graph);
		}
	}

	private void processTabPanels(FlattenedSolution fs, Form form, String formName, NavigationGraph graph) {
		Iterator<TabPanel> tabPanels = form.getTabPanels();
		while (tabPanels.hasNext()) {
			TabPanel tabPanel = tabPanels.next();
			String containerName = tabPanel.getName();
			String containerType = resolveTabPanelType(tabPanel);

			int tabIndex = 0;
			Iterator<IPersist> tabs = tabPanel.getTabs();
			while (tabs.hasNext()) {
				IPersist persist = tabs.next();
				if (persist instanceof Tab tab) {
					String containsFormId = tab.getContainsFormID();
					if (containsFormId != null && !containsFormId.isEmpty()) {
						Form targetForm = fs.getForm(containsFormId);
						if (targetForm != null) {
							graph.addEdge(new NavigationEdge.Builder().from(formName).to(targetForm.getName())
									.containerName(containerName).containerType(containerType)
									.propertyName("containsFormID").tabName(tab.getName()).tabIndex(tabIndex)
									.relationName(tab.getRelationName()).confidence("static").build());
						}
					}
					tabIndex++;
				}
			}
		}
	}

	private void processWebComponents(FlattenedSolution fs, Form form, String formName, NavigationGraph graph) {
		Iterator<WebComponent> webComponents = form.getWebComponents();
		while (webComponents.hasNext()) {
			WebComponent wc = webComponents.next();
			processWebComponentFormProperties(fs, wc, formName, graph);
		}
	}

	private void processWebComponentFormProperties(FlattenedSolution fs, WebComponent wc, String formName,
			NavigationGraph graph) {
		String typeName = wc.getTypeName();
		if (typeName == null)
			return;

		WebComponentSpecProvider specProvider = WebComponentSpecProvider.getInstance();
		if (specProvider == null)
			return;

		SpecProviderState specState = WebComponentSpecProvider.getSpecProviderState();
		if (specState == null)
			return;

		WebObjectSpecification spec = specState.getWebObjectSpecification(typeName);
		if (spec == null)
			return;

		String containerName = wc.getName();
		JSONObject json = wc.getFlattenedJson();

		Map<String, PropertyDescription> properties = spec.getProperties();
		for (Map.Entry<String, PropertyDescription> entry : properties.entrySet()) {
			String propName = entry.getKey();
			PropertyDescription pd = entry.getValue();
			checkAndAddFormEdge(fs, json, propName, pd, formName, containerName, "formcomponent", graph);
		}

		Map<String, ICustomType<?>> customTypes = spec.getDeclaredCustomObjectTypes();
		if (customTypes != null) {
			for (Map.Entry<String, ICustomType<?>> typeEntry : customTypes.entrySet()) {
				PropertyDescription typeDef = typeEntry.getValue().getCustomJSONTypeDefinition();
				if (typeDef == null)
					continue;

				Map<String, PropertyDescription> typeProps = typeDef.getProperties();
				for (Map.Entry<String, PropertyDescription> propEntry : typeProps.entrySet()) {
					String propName = propEntry.getKey();
					PropertyDescription pd = propEntry.getValue();
					if (isFormPropertyType(pd)) {
						extractFormRefsFromJsonArrays(fs, json, propName, formName, containerName, graph);
					}
				}
			}
		}
	}

	private void checkAndAddFormEdge(FlattenedSolution fs, JSONObject json, String propName, PropertyDescription pd,
			String formName, String containerName, String containerType, NavigationGraph graph) {
		if (!isFormPropertyType(pd))
			return;
		if (json == null || !json.has(propName))
			return;

		Object value = json.opt(propName);
		String targetFormName = resolveFormReference(fs, value);
		if (targetFormName != null) {
			graph.addEdge(new NavigationEdge.Builder().from(formName).to(targetFormName).containerName(containerName)
					.containerType(containerType).propertyName(propName).confidence("static").build());
		}
	}

	private void extractFormRefsFromJsonArrays(FlattenedSolution fs, JSONObject json, String propName, String formName,
			String containerName, NavigationGraph graph) {
		if (json == null)
			return;

		for (String key : json.keySet()) {
			Object val = json.opt(key);
			if (val instanceof JSONArray arr) {
				for (int i = 0; i < arr.length(); i++) {
					Object item = arr.opt(i);
					if (item instanceof JSONObject obj && obj.has(propName)) {
						String targetFormName = resolveFormReference(fs, obj.opt(propName));
						if (targetFormName != null) {
							graph.addEdge(new NavigationEdge.Builder().from(formName).to(targetFormName)
									.containerName(containerName).containerType("formcomponent").propertyName(propName)
									.tabIndex(i).confidence("static").build());
						}
					}
				}
			} else if (val instanceof JSONObject obj && obj.has(propName)) {
				String targetFormName = resolveFormReference(fs, obj.opt(propName));
				if (targetFormName != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formName).to(targetFormName)
							.containerName(containerName).containerType("formcomponent").propertyName(propName)
							.confidence("static").build());
				}
			}
		}
	}

	private boolean isFormPropertyType(PropertyDescription pd) {
		if (pd == null)
			return false;
		IPropertyType<?> type = pd.getType();
		if (type == null)
			return false;
		return FormPropertyType.TYPE_NAME.equals(type.getName());
	}

	private String resolveFormReference(FlattenedSolution fs, Object value) {
		if (value == null)
			return null;
		// Unwrap JSONObject form references (e.g. {"svyFormId": "uuid"})
		if (value instanceof JSONObject jsonObj) {
			if (jsonObj.has("svyFormId")) {
				value = jsonObj.opt("svyFormId");
			} else {
				// Try the first string value in the object
				for (String key : jsonObj.keySet()) {
					Object v = jsonObj.opt(key);
					if (v instanceof String) {
						value = v;
						break;
					}
				}
			}
			if (value == null)
				return null;
		}
		String strValue = value.toString().trim();
		if (strValue.isEmpty())
			return null;

		Form form = fs.getForm(strValue);
		if (form != null)
			return form.getName();
		return null;
	}

	private void processNavigator(FlattenedSolution fs, Form form, String formName, NavigationGraph graph) {
		String navigatorId = form.getNavigatorID();
		if (navigatorId == null || navigatorId.isEmpty())
			return;
		if ("0".equals(navigatorId) || "-1".equals(navigatorId))
			return;

		Form navigatorForm = fs.getForm(navigatorId);
		if (navigatorForm != null) {
			graph.addEdge(new NavigationEdge.Builder().from(formName).to(navigatorForm.getName())
					.containerType("navigator").propertyName("navigatorID").confidence("static").build());
		}
	}

	private void processLayoutContainers(FlattenedSolution fs, Form form, String formName, NavigationGraph graph) {
		if (!form.isResponsiveLayout())
			return;

		Iterator<LayoutContainer> containers = form.getLayoutContainers();
		while (containers.hasNext()) {
			LayoutContainer container = containers.next();
			processLayoutContainerWebComponents(fs, container, formName, graph);
		}
	}

	private void processLayoutContainerWebComponents(FlattenedSolution fs, AbstractContainer container, String formName,
			NavigationGraph graph) {
		Iterator<WebComponent> wcs = container.getWebComponents();
		while (wcs.hasNext()) {
			WebComponent wc = wcs.next();
			processWebComponentFormProperties(fs, wc, formName, graph);
		}

		Iterator<LayoutContainer> nested = container.getLayoutContainers();
		while (nested.hasNext()) {
			processLayoutContainerWebComponents(fs, nested.next(), formName, graph);
		}
	}

	private String resolveTabPanelType(TabPanel tabPanel) {
		int orientation = tabPanel.getTabOrientation();
		return switch (orientation) {
		case TabPanel.HIDE -> "tabless";
		case TabPanel.SPLIT_HORIZONTAL, TabPanel.SPLIT_VERTICAL -> "splitpane";
		case TabPanel.ACCORDION_PANEL -> "accordion";
		default -> "tabpanel";
		};
	}

	private void augmentWithScriptAnalysis(IDeveloperServoyModel servoyModel, NavigationGraph graph) {
		ServoyProject servoyProject = servoyModel.getActiveProject();
		if (servoyProject == null)
			return;

		scanProjectScripts(servoyProject.getProject(), graph);

		for (ServoyProject module : servoyModel.getModulesOfActiveProject()) {
			if (module != null && !module.equals(servoyProject)) {
				scanProjectScripts(module.getProject(), graph);
			}
		}
	}

	private void scanProjectScripts(IProject project, NavigationGraph graph) {
		if (project == null || !project.isAccessible())
			return;

		try {
			project.accept(new IResourceVisitor() {
				@Override
				public boolean visit(IResource resource) throws CoreException {
					if (resource.getType() == IResource.FILE && resource.getName().endsWith(".js")
							&& !resource.getName().endsWith(".spec.cy.js")) {
						analyzeScriptFile((IFile) resource, graph);
					}
					return true;
				}
			});
		} catch (CoreException e) {
			ServoyLog.logError("Error scanning project scripts for navigation graph", e);
		}
	}

	private void analyzeScriptFile(IFile file, NavigationGraph graph) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
			String scriptPath = file.getProjectRelativePath().toString();
			StringBuilder content = new StringBuilder();
			List<int[]> functionOffsets = new ArrayList<>();
			List<String> functionNames = new ArrayList<>();
			String line;
			Pattern funcPattern = Pattern.compile("function\\s+(\\w+)");

			while ((line = reader.readLine()) != null) {
				if (line.contains("function ")) {
					Matcher funcMatcher = funcPattern.matcher(line);
					if (funcMatcher.find()) {
						functionNames.add(funcMatcher.group(1));
						functionOffsets.add(new int[] { content.length(), -1 });
						if (functionOffsets.size() > 1) {
							functionOffsets.get(functionOffsets.size() - 2)[1] = content.length() - 1;
						}
					}
				}
				content.append(line).append('\n');
			}
			if (!functionOffsets.isEmpty()) {
				functionOffsets.get(functionOffsets.size() - 1)[1] = content.length();
			}

			String fullContent = content.toString();
			String formContext = extractFormContext(scriptPath);

			Matcher assignMatcher = FORM_PROPERTY_ASSIGNMENT.matcher(fullContent);
			while (assignMatcher.find()) {
				String elementName = assignMatcher.group(1);
				String propertyName = assignMatcher.group(2);
				String targetForm = assignMatcher.group(3) != null ? assignMatcher.group(3) : assignMatcher.group(4);

				if (formContext != null && targetForm != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(targetForm)
							.containerName(elementName).containerType("formcomponent").propertyName(propertyName)
							.trigger(elementName + "." + propertyName).confidence("dynamic").build());
				}
			}

			Matcher dialogMatcher = SHOW_FORM_IN_DIALOG.matcher(fullContent);
			while (dialogMatcher.find()) {
				String enclosingMethod = findEnclosingMethod(dialogMatcher.start(), functionOffsets, functionNames);
				String triggerValue = scriptPath + (enclosingMethod != null ? "." + enclosingMethod : "");
				if (formContext != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(dialogMatcher.group(1))
							.containerType("dialog").trigger(triggerValue).confidence("dynamic").build());
				}
			}

			Matcher popupMatcher = SHOW_FORM_POPUP.matcher(fullContent);

			// Two-pass: detect variable-stored window pattern
			// e.g. var w = application.createWindow("name", JSWindow.DIALOG);
			// w.show(forms.myForm);
			Set<String> windowVarNames = new HashSet<>();
			Matcher windowVarMatcher = WINDOW_VAR_ASSIGNMENT.matcher(fullContent);
			while (windowVarMatcher.find()) {
				windowVarNames.add(windowVarMatcher.group(1));
			}
			for (String varName : windowVarNames) {
				Pattern varShowPattern = Pattern.compile(Pattern.quote(varName) + "\\.show\\s*\\(\\s*forms\\.(\\w+)",
						Pattern.MULTILINE);
				Matcher varShowMatcher = varShowPattern.matcher(fullContent);
				while (varShowMatcher.find()) {
					String enclosingMethod = findEnclosingMethod(varShowMatcher.start(), functionOffsets,
							functionNames);
					String triggerValue = scriptPath + (enclosingMethod != null ? "." + enclosingMethod : "");
					if (formContext != null) {
						graph.addEdge(new NavigationEdge.Builder().from(formContext).to(varShowMatcher.group(1))
								.containerType("dialog").trigger(triggerValue).confidence("dynamic").build());
					}
				}
			}

			while (popupMatcher.find()) {
				String enclosingMethod = findEnclosingMethod(popupMatcher.start(), functionOffsets, functionNames);
				String triggerValue = scriptPath + (enclosingMethod != null ? "." + enclosingMethod : "");
				if (formContext != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(popupMatcher.group(1))
							.containerType("popup").trigger(triggerValue).confidence("dynamic").build());
				}
			}


			// navigateToForm(forms.X) â any prefix, any navigation module
			Matcher navigateMatcher = NAVIGATE_TO_FORM.matcher(fullContent);
			while (navigateMatcher.find()) {
				String targetForm = navigateMatcher.group(1);
				String enclosingMethod = findEnclosingMethod(navigateMatcher.start(), functionOffsets, functionNames);
				String triggerValue = scriptPath + (enclosingMethod != null ? "." + enclosingMethod : "");
				if (formContext != null && targetForm != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(targetForm)
							.containerType("navigation").trigger(triggerValue).confidence("dynamic").build());
				}
			}

			// JSForm.NAMES.formName â always a form reference regardless of context
			Matcher jsFormNamesMatcher = JSFORM_NAMES_REF.matcher(fullContent);
			while (jsFormNamesMatcher.find()) {
				String targetForm = jsFormNamesMatcher.group(1);
				String enclosingMethod = findEnclosingMethod(jsFormNamesMatcher.start(), functionOffsets, functionNames);
				String triggerValue = scriptPath + (enclosingMethod != null ? "." + enclosingMethod : "");
				if (formContext != null && targetForm != null) {
					graph.addEdge(new NavigationEdge.Builder().from(formContext).to(targetForm)
							.containerType("navigation").trigger(triggerValue).confidence("dynamic").build());
				}
			}
		} catch (Exception e) {
			ServoyLog.logError("Error analyzing script file: " + file.getName(), e);
		}
	}

	private String findEnclosingMethod(int offset, List<int[]> functionOffsets, List<String> functionNames) {
		for (int i = 0; i < functionOffsets.size(); i++) {
			int[] range = functionOffsets.get(i);
			if (offset >= range[0] && offset <= range[1]) {
				return functionNames.get(i);
			}
		}
		return null;
	}

	/**
	 * Generates the content of a Cypress .cy.js test file for the given navigation path.
	 * Navigation steps are derived from the graph; the scenario is embedded as a comment
	 * so the AI caller can add assertions after the file is created.
	 */
	public String generateCypressTestContent(String solutionName, String baseUrl, String startForm,
			String targetForm, String scenario, java.util.List<NavigationEdge> path) {
		StringBuilder sb = new StringBuilder();
		// Relative URL: baseUrl is defined in cypress.config.js, so cy.visit uses a relative path
		// Include ?svy_testmode=true to ensure data-cy attributes are rendered
		String relativeUrl = "/solution/" + solutionName + "/index.html?svy_testmode=true";

		sb.append("// Generated by Kiro - Servoy Cypress E2E scaffold\n");
		sb.append("// Scenario: ").append(scenario != null ? scenario : "").append("\n");
		sb.append("// Navigation: ").append(startForm);
		for (NavigationEdge edge : path) {
			sb.append(" -> ").append(edge.getTo());
		}
		sb.append("\n\n");

		sb.append("describe('").append(escapeJsString(scenario != null ? scenario : targetForm)).append("', () => {\n");
		sb.append("\n");
		sb.append("  beforeEach(() => {\n");
		sb.append("    cy.visit('").append(relativeUrl).append("');\n");
		sb.append("  });\n");
		sb.append("\n");
		sb.append("  it('").append(escapeJsString(scenario != null ? scenario : "navigates to " + targetForm))
				.append("', () => {\n");

		if (path.isEmpty()) {
			sb.append("    // WARNING: No navigation path found from '").append(startForm).append("' to '")
					.append(targetForm).append("'.\n");
			sb.append("    // The target form may be the start form or unreachable via static/script analysis.\n");
		} else {
			sb.append("    // --- Navigation steps ---\n");
			for (NavigationEdge edge : path) {
				String selector = edge.getCypressSelector();
				String confidence = edge.getConfidence();
				String containerType = edge.getContainerType();

				sb.append("    // Navigate to ").append(edge.getTo()).append(" via ").append(containerType);
				if ("dynamic".equals(confidence))
					sb.append(" (dynamic - triggered by script)");
				sb.append("\n");

				if (selector != null) {
					if ("tabpanel".equals(containerType) || "tabless".equals(containerType)
							|| "accordion".equals(containerType)) {
						sb.append("    cy.get('").append(selector).append("').click();\n");
					} else if ("dialog".equals(containerType) || "popup".equals(containerType)) {
						sb.append("    cy.get('").append(selector).append("').click();\n");
						sb.append("    // TODO: wait for ").append(edge.getTo()).append(" dialog/popup to appear\n");
					} else {
						sb.append("    cy.get('").append(selector).append("').click();\n");
					}
				} else {
					sb.append("    // TODO: no selector available for this edge - add navigation manually\n");
				}
			}
		}

		sb.append("\n");
		sb.append("    // --- Assertions ---\n");
		sb.append("    // TODO: add assertions for the scenario:\n");
		sb.append("    // ").append(scenario != null ? scenario : "").append("\n");
		sb.append("  });\n");
		sb.append("});\n");

		return sb.toString();
	}

	private static String escapeJsString(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("'", "\\'");
	}

	private String extractFormContext(String scriptPath) {
		if (scriptPath.startsWith("forms/")) {
			String afterForms = scriptPath.substring("forms/".length());
			int lastSlash = afterForms.lastIndexOf('/');
			String fileName = lastSlash >= 0 ? afterForms.substring(lastSlash + 1) : afterForms;
			int dotIndex = fileName.lastIndexOf('.');
			if (dotIndex > 0) {
				return fileName.substring(0, dotIndex);
			}
		}
		// For non-form scripts (scopes, root-level .js files) derive context from the file name.
		// This allows navigateToForm calls in scope scripts to be recorded in the graph.
		// Spec/test files are excluded to avoid noise.
		int lastSlash = scriptPath.lastIndexOf('/');
		String scopeFileName = lastSlash >= 0 ? scriptPath.substring(lastSlash + 1) : scriptPath;
		if (scopeFileName.endsWith(".spec.cy.js") || scopeFileName.startsWith("test_")) {
			return null;
		}
		int dotIndex = scopeFileName.lastIndexOf('.');
		if (dotIndex > 0) {
			return scopeFileName.substring(0, dotIndex);
		}
		return null;
	}
}
