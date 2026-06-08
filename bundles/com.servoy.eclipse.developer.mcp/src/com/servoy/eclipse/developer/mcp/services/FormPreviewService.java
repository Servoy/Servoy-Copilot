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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;
import com.servoy.eclipse.ngclient.ui.RunNPMCommand;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service for form preview and screenshot operations.
 * Provides the logic behind showFormInBrowser, screenshotForm, and checkNGClientStatus tools.
 *
 * <p>Uses the bundled Node.js from {@code com.servoy.eclipse.ngclient.ui} and installs
 * Playwright into {@code workspace/.metadata/.plugins/com.servoy.eclipse.copilot/playwright/}
 * on first use.</p>
 */
@Creatable
@SuppressWarnings("restriction")
public class FormPreviewService
{
	private static final String COPILOT_PLUGIN_DIR = "com.servoy.eclipse.copilot";
	private static final String PLAYWRIGHT_DIR = "playwright";
	private static final String PACKAGE_JSON_CONTENT = "{\n" +
		"  \"name\": \"servoy-playwright\",\n" +
		"  \"version\": \"1.0.0\",\n" +
		"  \"private\": true,\n" +
		"  \"dependencies\": {\n" +
		"    \"playwright\": \"^1.52.0\"\n" +
		"  }\n" +
		"}\n";

	public String showFormInBrowser(String formName)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project. Please open a solution.";
			}
			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			String url = "http://localhost:" + port + "/solution/" + solutionName + "/index.html?formpreview=" + formName;

			Display.getDefault().asyncExec(() -> {
				try
				{
					org.eclipse.ui.PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser()
						.openURL(new java.net.URL(url));
				}
				catch (Exception e)
				{
					ServoyLog.logError("Cannot open form in browser", e);
				}
			});

			return "Opened form '" + formName + "' in external browser: " + url;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in showFormInBrowser", e);
			return "Error: " + e.getMessage();
		}
	}

	public String screenshotForm(String formName, int waitSeconds)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project. Please open a solution.";
			}
			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			String url = "http://localhost:" + port + "/solution/" + solutionName + "/index.html?formpreview=" + formName;

			Path playwrightDir = getPlaywrightDir();
			String setupError = ensurePlaywrightInstalled(playwrightDir);
			if (setupError != null)
			{
				return setupError;
			}

			Path screenshotDir = playwrightDir.resolve("screenshots");
			Files.createDirectories(screenshotDir);
			String fileName = "screenshot_" + formName + "_" + System.currentTimeMillis() + ".png";
			Path screenshotPath = screenshotDir.resolve(fileName);

			String script = "const { chromium } = require('playwright');\n" +
				"(async () => {\n" +
				"  const browser = await chromium.launch({ headless: true });\n" +
				"  const page = await browser.newPage();\n" +
				"  await page.goto('" + url + "');\n" +
				"  await page.waitForTimeout(" + (waitSeconds * 1000) + ");\n" +
				"  await page.screenshot({ path: '" + screenshotPath.toString().replace("\\", "\\\\") + "', fullPage: true });\n" +
				"  await browser.close();\n" +
				"})();\n";

			Path scriptFile = playwrightDir.resolve("_screenshot_script.js");
			Files.writeString(scriptFile, script);

			File nodePath = getNodePath();
			if (nodePath == null)
			{
				return "Error: Bundled Node.js not available. Ensure com.servoy.eclipse.ngclient.ui is installed.";
			}

			ProcessBuilder pb = new ProcessBuilder(nodePath.getAbsolutePath(), scriptFile.toString());
			pb.directory(playwrightDir.toFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();

			StringBuilder output = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					output.append(line).append("\n");
				}
			}

			boolean finished = process.waitFor(waitSeconds + 30, TimeUnit.SECONDS);
			if (!finished)
			{
				process.destroyForcibly();
				return "Error: Screenshot timed out after " + (waitSeconds + 30) + " seconds";
			}

			Files.deleteIfExists(scriptFile);

			if (process.exitValue() != 0)
			{
				return "Error taking screenshot: " + output.toString();
			}

			if (Files.exists(screenshotPath))
			{
				return "Screenshot saved: " + screenshotPath.toString();
			}
			else
			{
				return "Error: Screenshot file was not created. Output: " + output.toString();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error in screenshotForm", e);
			return "Error: " + e.getMessage();
		}
	}

	public String checkNGClientStatus()
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "NG client status unknown: No active Servoy project.";
			}
			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			String url = "http://localhost:" + port + "/solution/" + solutionName + "/index.html";
			return "Servoy Developer is running. Form preview URL base: " + url;
		}
		catch (Exception e)
		{
			return "Error checking NG client status: " + e.getMessage();
		}
	}

	private Path getPlaywrightDir()
	{
		Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		Path metadataPlugins = workspaceRoot.getParent().resolve(".metadata").resolve(".plugins");
		return metadataPlugins.resolve(COPILOT_PLUGIN_DIR).resolve(PLAYWRIGHT_DIR);
	}

	private File getNodePath()
	{
		try
		{
			Activator ngActivator = Activator.getInstance();
			if (ngActivator == null) return null;
			ngActivator.extractNode();
			ngActivator.createNPMCommand(new File("."), List.of("--version"));
			var field = Activator.class.getDeclaredField("nodePath");
			field.setAccessible(true);
			return (File)field.get(ngActivator);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error getting bundled node path", e);
			return null;
		}
	}

	private String ensurePlaywrightInstalled(Path playwrightDir)
	{
		try
		{
			Files.createDirectories(playwrightDir);

			Path packageJson = playwrightDir.resolve("package.json");
			if (!Files.exists(packageJson))
			{
				Files.writeString(packageJson, PACKAGE_JSON_CONTENT);
			}

			Path nodeModules = playwrightDir.resolve("node_modules").resolve("playwright");
			if (Files.exists(nodeModules))
			{
				return null;
			}

			Activator ngActivator = Activator.getInstance();
			if (ngActivator == null)
			{
				return "Error: com.servoy.eclipse.ngclient.ui not available.";
			}

			IRunNPMCommand npmCommand = ngActivator.createNPMCommand(playwrightDir.toFile(), List.of("install"));
			npmCommand.setUser(false);
			npmCommand.schedule();
			npmCommand.join();

			if (npmCommand.getExitCode() != 0)
			{
				return "Error: npm install failed in " + playwrightDir + " (exit code " + npmCommand.getExitCode() + ")";
			}

			File nodePath = getNodePath();
			if (nodePath == null)
			{
				return "Error: Bundled Node.js not available for Playwright browser install.";
			}

			String npxPath = nodePath.getParent() + File.separator + "npx.cmd";
			File npxFile = new File(npxPath);
			if (!npxFile.exists())
			{
				npxPath = nodePath.getParent() + File.separator + "npx";
			}

			ProcessBuilder pb = new ProcessBuilder(npxPath, "playwright", "install", "chromium");
			pb.directory(playwrightDir.toFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();

			StringBuilder output = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					output.append(line).append("\n");
				}
			}

			boolean finished = process.waitFor(180, TimeUnit.SECONDS);
			if (!finished)
			{
				process.destroyForcibly();
				return "Error: Playwright browser install timed out after 180 seconds";
			}

			if (process.exitValue() != 0)
			{
				return "Error: Playwright browser install failed: " + output.toString();
			}

			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error ensuring Playwright is installed", e);
			return "Error setting up Playwright: " + e.getMessage();
		}
	}
}
