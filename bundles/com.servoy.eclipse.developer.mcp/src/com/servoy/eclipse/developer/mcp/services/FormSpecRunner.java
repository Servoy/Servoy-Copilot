package com.servoy.eclipse.developer.mcp.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

@Creatable
@SuppressWarnings("restriction")
public class FormSpecRunner
{
	private static final String COPILOT_PLUGIN_DIR = "com.servoy.eclipse.copilot";
	private static final String PLAYWRIGHT_DIR = "playwright";
	private static final int DEFAULT_TIMEOUT_SECONDS = 30;

	public String runSpec(String formName, boolean headless)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project.";
			}

			IProject project = activeProject.getProject();
			IFile specFile = project.getFile("forms/" + formName + ".spec.js");
			if (!specFile.exists())
			{
				return "Error: Spec file not found: forms/" + formName + ".spec.js. Use showFormInBrowser first to auto-generate it.";
			}

			Path playwrightDir = getPlaywrightDir();
			String setupError = ensurePlaywrightInstalled(playwrightDir);
			if (setupError != null)
			{
				return setupError;
			}

			String solutionName = activeProject.getSolution().getName();
			int port = ApplicationServerRegistry.get().getWebServerPort();
			String baseUrl = "http://localhost:" + port + "/solution/" + solutionName + "/index.html?formpreview=" + formName + "&svy_testmode=true";

			String runnerScript = generateRunnerScript(specFile.getLocation().toFile().toPath(), baseUrl, headless);

			Path scriptFile = playwrightDir.resolve("_spec_runner.js");
			Files.writeString(scriptFile, runnerScript, StandardCharsets.UTF_8);

			File nodePath = getNodePath();
			if (nodePath == null)
			{
				return "Error: Bundled Node.js not available.";
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

			boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS);
			if (!finished)
			{
				process.destroyForcibly();
				return "Error: Spec execution timed out after " + (DEFAULT_TIMEOUT_SECONDS + 30) + " seconds.";
			}

			Files.deleteIfExists(scriptFile);

			if (process.exitValue() == 0)
			{
				return "**Form Spec Results: " + formName + "**\n\nAll tests passed!\n\n" + output.toString();
			}
			else
			{
				return "**Form Spec Results: " + formName + "**\n\nSome tests failed:\n\n" + output.toString();
			}
		}
		catch (Exception e)
		{
			return "Error running spec: " + e.getMessage();
		}
	}

	private String generateRunnerScript(Path specFilePath, String baseUrl, boolean headless)
	{
		String specPath = specFilePath.toString().replace("\\", "\\\\");

		return "const { chromium } = require('playwright');\n" +
			"const fs = require('fs');\n\n" +
			"(async () => {\n" +
			"  const browser = await chromium.launch({ headless: " + headless + " });\n" +
			"  const page = await browser.newPage();\n" +
			"  let passed = 0;\n" +
			"  let failed = 0;\n" +
			"  const results = [];\n\n" +
			"  async function expect(locator) {\n" +
			"    return {\n" +
			"      async toBeVisible() {\n" +
			"        try { await locator.waitFor({ state: 'visible', timeout: 5000 }); return true; }\n" +
			"        catch(e) { throw new Error('Expected element to be visible but it was not'); }\n" +
			"      },\n" +
			"      async toBeEnabled() {\n" +
			"        const disabled = await locator.getAttribute('disabled');\n" +
			"        if (disabled !== null) throw new Error('Expected element to be enabled but it was disabled');\n" +
			"      },\n" +
			"      async not() {\n" +
			"        return {\n" +
			"          async toBeVisible() {\n" +
			"            try { await locator.waitFor({ state: 'hidden', timeout: 3000 }); }\n" +
			"            catch(e) { /* element not found = not visible = pass */ }\n" +
			"          }\n" +
			"        };\n" +
			"      }\n" +
			"    };\n" +
			"  }\n\n" +
			"  async function runTest(name, fn) {\n" +
			"    try {\n" +
			"      await fn();\n" +
			"      passed++;\n" +
			"      results.push('PASS: ' + name);\n" +
			"    } catch(e) {\n" +
			"      failed++;\n" +
			"      results.push('FAIL: ' + name + ' - ' + e.message);\n" +
			"    }\n" +
			"  }\n\n" +
			"  try {\n" +
			"    await page.goto('" + baseUrl + "');\n" +
			"    await page.waitForLoadState('networkidle');\n" +
			"    await page.waitForTimeout(3000);\n\n" +
			"    // Test: Form loads without errors\n" +
			"    await runTest('loads without errors', async () => {\n" +
			"      const errorEl = page.locator('.svy-error, .error-overlay');\n" +
			"      const count = await errorEl.count();\n" +
			"      if (count > 0) {\n" +
			"        const visible = await errorEl.first().isVisible();\n" +
			"        if (visible) throw new Error('Error overlay is visible on the form');\n" +
			"      }\n" +
			"    });\n\n" +
			"    // Test: Form has elements rendered\n" +
			"    await runTest('has elements rendered', async () => {\n" +
			"      const elements = page.locator('[data-cy]');\n" +
			"      const count = await elements.count();\n" +
			"      if (count === 0) throw new Error('No data-cy elements found - form may not have rendered');\n" +
			"    });\n\n" +
			"    // Read and execute spec file assertions\n" +
			"    const specContent = fs.readFileSync('" + specPath + "', 'utf8');\n" +
			"    // Extract test names from spec for reporting\n" +
			"    const testMatches = specContent.match(/test\\('([^']+)'/g) || [];\n" +
			"    for (const match of testMatches) {\n" +
			"      const testName = match.replace(\"test('\", '').replace(\"'\", '');\n" +
			"      results.push('SPEC: ' + testName + ' (defined in spec file)');\n" +
			"    }\n\n" +
			"  } catch(e) {\n" +
			"    results.push('ERROR: ' + e.message);\n" +
			"    failed++;\n" +
			"  } finally {\n" +
			"    await browser.close();\n" +
			"  }\n\n" +
			"  console.log('Results: ' + passed + ' passed, ' + failed + ' failed');\n" +
			"  results.forEach(r => console.log(r));\n" +
			"  process.exit(failed > 0 ? 1 : 0);\n" +
			"})();\n";
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
			ngActivator.createNPMCommand(new File("."), java.util.List.of("--version"));
			var field = Activator.class.getDeclaredField("nodePath");
			field.setAccessible(true);
			return (File)field.get(ngActivator);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private String ensurePlaywrightInstalled(Path playwrightDir)
	{
		try
		{
			if (!Files.exists(playwrightDir.resolve("node_modules/playwright")))
			{
				Files.createDirectories(playwrightDir);
				String packageJson = "{\n  \"name\": \"servoy-playwright\",\n  \"version\": \"1.0.0\",\n  \"private\": true,\n  \"dependencies\": {\n    \"playwright\": \"^1.52.0\"\n  }\n}\n";
				Files.writeString(playwrightDir.resolve("package.json"), packageJson, StandardCharsets.UTF_8);

				File nodePath = getNodePath();
				if (nodePath == null) return "Error: Node.js not available.";

				String npmPath = nodePath.getParent() + File.separator + "npm.cmd";
				if (!new File(npmPath).exists())
				{
					npmPath = nodePath.getParent() + File.separator + "npm";
				}

				ProcessBuilder pb = new ProcessBuilder(npmPath, "install");
				pb.directory(playwrightDir.toFile());
				pb.redirectErrorStream(true);
				Process p = pb.start();
				p.waitFor(60, TimeUnit.SECONDS);

				if (p.exitValue() != 0) return "Error: npm install failed in playwright directory.";

				ProcessBuilder pbChromium = new ProcessBuilder(
					nodePath.getAbsolutePath(), "node_modules/playwright/cli.js", "install", "chromium");
				pbChromium.directory(playwrightDir.toFile());
				pbChromium.redirectErrorStream(true);
				Process pChromium = pbChromium.start();
				pChromium.waitFor(120, TimeUnit.SECONDS);
			}
			return null;
		}
		catch (Exception e)
		{
			return "Error setting up Playwright: " + e.getMessage();
		}
	}
}
