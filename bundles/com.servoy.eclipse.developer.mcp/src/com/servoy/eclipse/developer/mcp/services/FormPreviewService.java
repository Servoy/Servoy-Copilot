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
package com.servoy.eclipse.developer.mcp.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service for form preview and screenshot operations. Provides the logic behind
 * showFormInBrowser, screenshotForm, and checkNGClientStatus tools.
 *
 * <p>
 * Uses the bundled Node.js from {@code com.servoy.eclipse.ngclient.ui} and
 * installs Playwright into
 * {@code workspace/.metadata/.plugins/com.servoy.eclipse.copilot/playwright/}
 * on first use.
 * </p>
 */
@Creatable
@SuppressWarnings("restriction")
public class FormPreviewService {
	private static final String COPILOT_PLUGIN_DIR = "com.servoy.eclipse.copilot";
	private static final String PLAYWRIGHT_DIR = "playwright";
	private static final String PACKAGE_JSON_CONTENT = "{\n" + "  \"name\": \"servoy-playwright\",\n"
			+ "  \"version\": \"1.0.0\",\n" + "  \"private\": true,\n" + "  \"dependencies\": {\n"
			+ "    \"playwright\": \"^1.52.0\"\n" + "  }\n" + "}\n";

	public String showFormInBrowser(String formName) {
		return showFormInBrowser(formName, true);
	}

	public String showFormInBrowser(String formName, boolean openBrowser) {
		try {
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel()
					.getActiveProject();
			if (activeProject == null) {
				return "Error: No active Servoy project. Please open a solution.";
			}

			String validationErrors = checkFormMarkers(activeProject, formName);
			if (validationErrors != null) {
				return validationErrors;
			}

			String propertyErrors = validateFormProperties(activeProject, formName);
			if (propertyErrors != null) {
				return propertyErrors;
			}

			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			if (port <= 0) {
				return "Error: Tomcat web server is not running (port=" + port + "). Please check the Tomcat starter.";
			}
			String url = "http://localhost:" + port + "/solution/" + solutionName + "/index.html?formpreview="
					+ formName;

			String serverErrors;
			try (RuntimeErrorCapture capture = new RuntimeErrorCapture()) {
				if (openBrowser) {
					Display.getDefault().asyncExec(() -> {
						try {
							org.eclipse.ui.PlatformUI.getWorkbench().getBrowserSupport()
									.createBrowser(
											org.eclipse.ui.browser.IWorkbenchBrowserSupport.LOCATION_BAR
													| org.eclipse.ui.browser.IWorkbenchBrowserSupport.NAVIGATION_BAR
													| org.eclipse.ui.browser.IWorkbenchBrowserSupport.AS_EXTERNAL,
											"servoy.formpreview", null, null)
									.openURL(new java.net.URL(url));
						} catch (Exception e) {
							ServoyLog.logError("Cannot open form in browser", e);
						}
					});
				}

				Thread.sleep(5000);
				serverErrors = capture.formatCapturedErrors();
			}

			String result = "Opened form '" + formName + "' in external browser: " + url;
			if (serverErrors != null) {
				result += "\n\nWarning: Form '" + formName + "' rendered with server-side runtime errors:\n"
						+ serverErrors;
			}
			return result;
		} catch (Exception e) {
			ServoyLog.logError("Error in showFormInBrowser", e);
			return "Error: " + e.getMessage();
		}
	}

	public String screenshotForm(String formName, int waitSeconds) {
		try {
			if (formName == null || formName.trim().isEmpty()) {
				return "Error: Form name must not be null or empty.";
			}

			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel()
					.getActiveProject();
			if (activeProject == null) {
				return "Error: No active Servoy project. Please open a solution.";
			}

			if (activeProject.getEditingSolution().getForm(formName) == null) {
				return "Error: Form '" + formName + "' does not exist in solution '"
						+ activeProject.getSolution().getName() + "'.";
			}

			String validationErrors = checkFormMarkers(activeProject, formName);
			if (validationErrors != null) {
				return validationErrors;
			}

			String propertyErrors = validateFormProperties(activeProject, formName);
			if (propertyErrors != null) {
				return propertyErrors;
			}

			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			if (port <= 0) {
				return "Error: Tomcat web server is not running (port=" + port + "). Please check the Tomcat starter.";
			}
			String url = "http://localhost:" + port + "/solution/" + solutionName + "/index.html?formpreview="
					+ formName;

			Path playwrightDir = getPlaywrightDir();
			String setupError = ensurePlaywrightInstalled(playwrightDir);
			if (setupError != null) {
				return setupError;
			}

			Path screenshotDir = playwrightDir.resolve("screenshots");
			Files.createDirectories(screenshotDir);
			String fileName = "screenshot_" + formName + "_" + System.currentTimeMillis() + ".png";
			Path screenshotPath = screenshotDir.resolve(fileName);

			String script = "const { chromium } = require('playwright');\n" + "(async () => {\n"
					+ "  const errors = [];\n" + "  const browser = await chromium.launch({ headless: true });\n"
					+ "  const page = await browser.newPage();\n"
					+ "  page.on('pageerror', err => errors.push('[PageError] ' + err.message));\n"
					+ "  page.on('console', msg => { if (msg.type() === 'error') errors.push('[ConsoleError] ' + msg.text()); });\n"
					+ "  await page.goto('" + url + "');\n" + "  await page.waitForTimeout(" + (waitSeconds * 1000)
					+ ");\n" + "  await page.screenshot({ path: '" + screenshotPath.toString().replace("\\", "\\\\")
					+ "', fullPage: true });\n" + "  await browser.close();\n"
					+ "  if (errors.length > 0) { console.log('__ERRORS_START__'); errors.forEach(e => console.log(e)); console.log('__ERRORS_END__'); }\n"
					+ "})();\n";
			Path scriptFile = playwrightDir.resolve("_screenshot_script.js");
			Files.writeString(scriptFile, script);

			File nodePath = getNodePath();
			if (nodePath == null) {
				return "Error: Bundled Node.js not available. Ensure com.servoy.eclipse.ngclient.ui is installed.";
			}

			String serverErrors;
			try (RuntimeErrorCapture capture = new RuntimeErrorCapture()) {
				ProcessBuilder pb = new ProcessBuilder(nodePath.getAbsolutePath(), scriptFile.toString());
				pb.directory(playwrightDir.toFile());
				pb.redirectErrorStream(true);
				Process process = pb.start();

				StringBuilder output = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						output.append(line).append("\n");
					}
				}

				boolean finished = process.waitFor(waitSeconds + 30, TimeUnit.SECONDS);
				if (!finished) {
					process.destroyForcibly();
					return "Error: Screenshot timed out after " + (waitSeconds + 30) + " seconds";
				}

				Files.deleteIfExists(scriptFile);

				if (process.exitValue() != 0) {
					return "Error taking screenshot: " + output.toString();
				}

				if (!Files.exists(screenshotPath)) {
					return "Error: Screenshot file was not created. Output: " + output.toString();
				}

				serverErrors = capture.formatCapturedErrors();
				String outputStr = output.toString();
				String consoleErrors = extractConsoleErrors(outputStr);

				List<String> allErrors = new ArrayList<>();
				if (serverErrors != null) allErrors.add(serverErrors);
				if (consoleErrors != null) allErrors.add(consoleErrors);

				if (!allErrors.isEmpty()) {
					return "Warning: Form '" + formName + "' rendered with runtime errors:\n"
							+ String.join("\n", allErrors) + "\nScreenshot saved: " + screenshotPath.toString();
				}
			}
			return "Screenshot saved: " + screenshotPath.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error in screenshotForm", e);
			return "Error: " + e.getMessage();
		}
	}

	private String extractConsoleErrors(String output) {
		int startIdx = output.indexOf("__ERRORS_START__");
		if (startIdx < 0) {
			return null;
		}
		int endIdx = output.indexOf("__ERRORS_END__");
		if (endIdx < 0) {
			return null;
		}
		String errorBlock = output.substring(startIdx + "__ERRORS_START__".length(), endIdx).trim();
		if (errorBlock.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (String line : errorBlock.split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				sb.append("- ").append(trimmed).append("\n");
			}
		}
		return sb.length() > 0 ? sb.toString().trim() : null;
	}

	public String checkNGClientStatus() {
		try {
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel()
					.getActiveProject();
			if (activeProject == null) {
				return "NG client status unknown: No active Servoy project.";
			}
			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			String url = "http://localhost:" + port + "/solution/" + solutionName + "/index.html";
			return "Servoy Developer is running. Form preview URL base: " + url;
		} catch (Exception e) {
			return "Error checking NG client status: " + e.getMessage();
		}
	}

	private String checkFormMarkers(ServoyProject activeProject, String formName) {
		try {
			IProject project = activeProject.getProject();
			List<String> errors = new ArrayList<>();

			IFile frmFile = project.getFile("forms/" + formName + ".frm");
			if (frmFile.exists()) {
				collectErrorMarkers(frmFile, IResource.DEPTH_ZERO, errors);
			}

			IFolder formFolder = project.getFolder("forms/" + formName);
			if (formFolder.exists()) {
				collectErrorMarkers(formFolder, IResource.DEPTH_INFINITE, errors);
			}

			IFile jsFile = project.getFile("forms/" + formName + ".js");
			if (jsFile.exists()) {
				collectErrorMarkers(jsFile, IResource.DEPTH_ZERO, errors);
			}

			if (!errors.isEmpty()) {
				StringBuilder sb = new StringBuilder();
				sb.append("Error: Form '").append(formName)
						.append("' has validation errors. Fix these before taking a screenshot:\n");
				for (String error : errors) {
					sb.append(error).append("\n");
				}
				return sb.toString().trim();
			}
		} catch (CoreException e) {
			ServoyLog.logError("Error checking form markers", e);
		}
		return null;
	}

	private void collectErrorMarkers(IResource resource, int depth, List<String> errors) throws CoreException {
		IMarker[] markers = resource.findMarkers(IMarker.PROBLEM, true, depth);
		for (IMarker marker : markers) {
			int severity = marker.getAttribute(IMarker.SEVERITY, -1);
			if (severity == IMarker.SEVERITY_ERROR) {
				String message = marker.getAttribute(IMarker.MESSAGE, "Unknown error");
				int lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
				if (lineNumber > 0) {
					errors.add("- [ERROR] " + message + " (line " + lineNumber + ")");
				} else {
					errors.add("- [ERROR] " + message);
				}
			}
		}
	}

	private String validateFormProperties(ServoyProject activeProject, String formName) {
		try {
			com.servoy.eclipse.core.IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager()
					.getServoyModel();
			com.servoy.j2db.FlattenedSolution fs = servoyModel
					.getEditingFlattenedSolution(activeProject.getEditingSolution());
			if (fs == null) return null;

			com.servoy.j2db.persistence.Form form = fs.getForm(formName);
			if (form == null) return null;

			org.sablo.specification.SpecProviderState specState = org.sablo.specification.WebComponentSpecProvider
					.getSpecProviderState();
			if (specState == null) return null;

			List<String> errors = new ArrayList<>();
			validateWebComponents(form.getWebComponents(), specState, errors);
			validateLayoutContainers(form.getLayoutContainers(), specState, errors);

			if (errors.isEmpty()) return null;

			StringBuilder sb = new StringBuilder();
			sb.append("Error: Form '").append(formName)
					.append("' has property type mismatches that will cause runtime errors:\n");
			for (String error : errors) {
				sb.append(error).append("\n");
			}
			return sb.toString().trim();
		} catch (Exception e) {
			ServoyLog.logError("Error validating form properties", e);
			return null;
		}
	}

	private void validateWebComponents(java.util.Iterator<com.servoy.j2db.persistence.WebComponent> webComponents,
			org.sablo.specification.SpecProviderState specState, List<String> errors) {
		while (webComponents.hasNext()) {
			com.servoy.j2db.persistence.WebComponent wc = webComponents.next();
			String typeName = wc.getTypeName();
			if (typeName == null) continue;

			org.sablo.specification.WebObjectSpecification spec = specState.getWebObjectSpecification(typeName);
			if (spec == null) continue;

			String componentName = wc.getName() != null ? wc.getName() : typeName;
			org.json.JSONObject json = wc.getFlattenedJson();
			if (json == null) continue;

			java.util.Map<String, org.sablo.specification.PropertyDescription> properties = spec.getProperties();
			for (java.util.Map.Entry<String, org.sablo.specification.PropertyDescription> entry : properties
					.entrySet()) {
				String propName = entry.getKey();
				if (!json.has(propName)) continue;

				org.sablo.specification.PropertyDescription pd = entry.getValue();
				Object value = json.opt(propName);
				String typeMismatch = checkTypeMismatch(pd, value);
				if (typeMismatch != null) {
					errors.add("- [TYPE MISMATCH] Component '" + componentName + "', property '" + propName
							+ "': " + typeMismatch);
				}
			}
		}
	}

	private void validateLayoutContainers(
			java.util.Iterator<com.servoy.j2db.persistence.LayoutContainer> containers,
			org.sablo.specification.SpecProviderState specState, List<String> errors) {
		while (containers.hasNext()) {
			com.servoy.j2db.persistence.LayoutContainer container = containers.next();
			validateWebComponents(container.getWebComponents(), specState, errors);
			validateLayoutContainers(container.getLayoutContainers(), specState, errors);
		}
	}

	private String checkTypeMismatch(org.sablo.specification.PropertyDescription pd, Object value) {
		if (value == null || value == org.json.JSONObject.NULL) return null;
		if (value instanceof org.json.JSONObject || value instanceof org.json.JSONArray) return null;

		Object defaultValue = pd.getDefaultValue();
		if (defaultValue instanceof Boolean && !(value instanceof Boolean)) {
			return "expected boolean but found " + value.getClass().getSimpleName() + " \"" + value + "\"";
		}
		if (defaultValue instanceof Number && value instanceof String) {
			return "expected number but found String \"" + value + "\"";
		}

		org.sablo.specification.property.IPropertyType<?> type = pd.getType();
		if (type == null) return null;
		String typeName = type.getName();

		if (("boolean".equals(typeName) || "enabled".equals(typeName) || "visible".equals(typeName)
				|| "protected".equals(typeName)) && !(value instanceof Boolean)) {
			return "expected boolean but found " + value.getClass().getSimpleName() + " \"" + value + "\"";
		}
		if (("int".equals(typeName) || "integer".equals(typeName)) && value instanceof String) {
			return "expected integer but found String \"" + value + "\"";
		}
		return null;
	}


	private Path getPlaywrightDir() {
		Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		Path metadataPlugins = workspaceRoot.getParent().resolve(".metadata").resolve(".plugins");
		return metadataPlugins.resolve(COPILOT_PLUGIN_DIR).resolve(PLAYWRIGHT_DIR);
	}

	private File getNodePath() {
		try {
			Activator ngActivator = Activator.getInstance();
			if (ngActivator == null)
				return null;
			ngActivator.extractNode();
			ngActivator.createNPMCommand(new File("."), List.of("--version"));
			var field = Activator.class.getDeclaredField("nodePath");
			field.setAccessible(true);
			return (File) field.get(ngActivator);
		} catch (Exception e) {
			ServoyLog.logError("Error getting bundled node path", e);
			return null;
		}
	}

	private String ensurePlaywrightInstalled(Path playwrightDir) {
		try {
			Files.createDirectories(playwrightDir);

			Path packageJson = playwrightDir.resolve("package.json");
			if (!Files.exists(packageJson)) {
				Files.writeString(packageJson, PACKAGE_JSON_CONTENT);
			}

			Path nodeModules = playwrightDir.resolve("node_modules").resolve("playwright");
			if (!Files.exists(nodeModules)) {
				Activator ngActivator = Activator.getInstance();
				if (ngActivator == null) {
					return "Error: com.servoy.eclipse.ngclient.ui not available.";
				}

				IRunNPMCommand npmCommand = ngActivator.createNPMCommand(playwrightDir.toFile(), List.of("install"));
				npmCommand.setUser(false);
				npmCommand.schedule();
				npmCommand.join();

				if (npmCommand.getExitCode() != 0) {
					return "Error: npm install failed in " + playwrightDir + " (exit code " + npmCommand.getExitCode()
							+ ")";
				}
			}

			Path browsersInstalledMarker = playwrightDir.resolve(".browsers_installed");
			if (Files.exists(browsersInstalledMarker)) {
				return null;
			}

			File nodePath = getNodePath();
			if (nodePath == null) {
				return "Error: Bundled Node.js not available for Playwright browser install.";
			}

			String npxPath = nodePath.getParent() + File.separator + "npx.cmd";
			File npxFile = new File(npxPath);
			if (!npxFile.exists()) {
				npxPath = nodePath.getParent() + File.separator + "npx";
			}

			ProcessBuilder pb = new ProcessBuilder(npxPath, "playwright", "install", "chromium");
			pb.directory(playwrightDir.toFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();

			StringBuilder output = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					output.append(line).append("\n");
				}
			}

			boolean finished = process.waitFor(180, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return "Error: Playwright browser install timed out after 180 seconds";
			}

			if (process.exitValue() != 0) {
				return "Error: Playwright browser install failed: " + output.toString();
			}

			Files.writeString(browsersInstalledMarker, "installed");
			return null;
		} catch (Exception e) {
			ServoyLog.logError("Error ensuring Playwright is installed", e);
			return "Error setting up Playwright: " + e.getMessage();
		}
	}
}
