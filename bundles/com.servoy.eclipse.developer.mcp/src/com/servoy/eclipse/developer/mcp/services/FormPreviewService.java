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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service for form preview and screenshot operations.
 * Provides the logic behind showFormInBrowser, screenshotForm, and checkNGClientStatus tools.
 */
@Creatable
@SuppressWarnings("restriction")
public class FormPreviewService
{
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

			Path screenshotDir = Path.of(System.getProperty("user.home"), ".servoy", "formtesting", "screenshots");
			Files.createDirectories(screenshotDir);
			String fileName = "screenshot_" + formName + "_" + System.currentTimeMillis() + ".png";
			Path screenshotPath = screenshotDir.resolve(fileName);

			Path playwrightDir = Path.of(System.getProperty("user.home"), ".servoy", "formtesting", "playwright");

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

			ProcessBuilder pb = new ProcessBuilder("node", scriptFile.toString());
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

			boolean finished = process.waitFor(waitSeconds + 15, TimeUnit.SECONDS);
			if (!finished)
			{
				process.destroyForcibly();
				return "Error: Screenshot timed out after " + (waitSeconds + 15) + " seconds";
			}

			Files.deleteIfExists(scriptFile);

			if (process.exitValue() != 0)
			{
				return "Error taking screenshot (v2 - script at " + scriptFile + "): " + output.toString();
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
}
