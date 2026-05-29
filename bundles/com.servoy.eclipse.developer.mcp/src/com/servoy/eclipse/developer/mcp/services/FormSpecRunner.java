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

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Runs Playwright test specs (.spec.pw.js) against Servoy forms using the real
 * Playwright Test runner (npx playwright test). Spec files live in
 * workspace/pwtests/{solutionName}/ to avoid DLTK parser issues.
 */
@Creatable
@SuppressWarnings("restriction")
public class FormSpecRunner
{
	private static final String COPILOT_PLUGIN_DIR = "com.servoy.eclipse.copilot";
	private static final String PLAYWRIGHT_DIR = "playwright";
	private static final int DEFAULT_TIMEOUT_SECONDS = 60;

	private final FormSpecGenerator specGenerator = new FormSpecGenerator();

	/**
	 * Runs the Playwright spec for the given form using 'npx playwright test'.
	 *
	 * @param formName the form whose .spec.pw.js to run
	 * @param headless true for headless (CI), false for headed (debugging)
	 * @return test results output
	 */
	public String runSpec(String formName, boolean headless)
	{
		try
		{
			ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
			if (activeProject == null)
			{
				return "Error: No active Servoy project.";
			}

			String solutionName = activeProject.getSolution().getName();
			Path pwTestsDir = specGenerator.getPwTestsDir(solutionName);
			Path specFilePath = pwTestsDir.resolve(formName + ".spec.pw.js");

			if (!Files.exists(specFilePath))
			{
				return "Error: Spec file not found: medias/pwtests/" + formName + ".spec.pw.js. Use showFormInBrowser first to auto-generate it.";
			}

			Path playwrightDir = getPlaywrightDir();
			String setupError = ensurePlaywrightInstalled(playwrightDir);
			if (setupError != null)
			{
				return setupError;
			}

			// Write playwright.config.js in medias/pwtests/ (same dir as spec files)
			int port = ApplicationServerRegistry.get().getWebServerPort();
			String baseUrl = "http://localhost:" + port + "/solution/" + solutionName + "/index.html";
			ensurePlaywrightConfig(pwTestsDir, baseUrl, pwTestsDir, headless);

			// Run: npx playwright test {formName}.spec.pw.js --config=... --reporter=list
			File nodePath = getNodePath();
			if (nodePath == null)
			{
				return "Error: Bundled Node.js not available.";
			}

			String npxPath = nodePath.getParent() + File.separator + "npx.cmd";
			if (!new File(npxPath).exists())
			{
				npxPath = nodePath.getParent() + File.separator + "npx";
			}

			List<String> command = new ArrayList<>();
			command.add(npxPath);
			command.add("playwright");
			command.add("test");
			command.add(formName + ".spec.pw.js");
			command.add("--config=" + pwTestsDir.resolve("playwright.config.js").toString());
			command.add("--reporter=list");
			if (!headless)
			{
				command.add("--headed");
			}

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(activeProject.getProject().getLocation().toFile());
			pb.redirectErrorStream(true);
			// NODE_PATH: so spec files can require('@playwright/test')
			pb.environment().put("NODE_PATH", playwrightDir.resolve("node_modules").toString());
			// PLAYWRIGHT_BROWSERS_PATH: so it finds the installed chromium
			pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", playwrightDir.resolve("browsers").toString());
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

			boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished)
			{
				process.destroyForcibly();
				return "Error: Playwright test timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.";
			}

			String rawOutput = output.toString();

			if (process.exitValue() == 0)
			{
				return "**Form Spec Results: " + formName + "**\n\nAll tests passed!\n\n" + rawOutput;
			}
			else
			{
				return "**Form Spec Results: " + formName + "**\n\nSome tests failed:\n\n" + rawOutput;
			}
		}
		catch (Exception e)
		{
			return "Error running spec: " + e.getMessage();
		}
	}

	/**
	 * Writes a playwright.config.js in the medias/pwtests/ directory.
	 * Configures baseURL, testDir, testMatch, headless mode, and timeouts.
	 */
	private void ensurePlaywrightConfig(Path pwTestsDir, String baseUrl, Path testDir, boolean headless)
	{
		try
		{
			Files.createDirectories(pwTestsDir);
			String testDirEscaped = testDir.toString().replace("\\", "/");
			Path configFile = pwTestsDir.resolve("playwright.config.js");
			String config = "// Auto-generated by Servoy MCP - do not edit manually\n" +
				"const { defineConfig } = require('@playwright/test');\n\n" +
				"module.exports = defineConfig({\n" +
				"  testDir: '" + testDirEscaped + "',\n" +
				"  testMatch: '*.spec.pw.js',\n" +
				"  timeout: 60000,\n" +
				"  retries: 0,\n" +
				"  workers: 1,\n" +
				"  use: {\n" +
				"    baseURL: '" + baseUrl + "',\n" +
				"    headless: " + headless + ",\n" +
				"    viewport: { width: 1280, height: 720 },\n" +
				"    actionTimeout: 10000,\n" +
				"    navigationTimeout: 30000,\n" +
				"  },\n" +
				"  reporter: [['list']],\n" +
				"});\n";
			Files.writeString(configFile, config, StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			// Non-fatal - playwright will use defaults
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

	/**
	 * Ensures @playwright/test is installed (not just 'playwright' library).
	 * Also installs Chromium browser if not present.
	 */
	private String ensurePlaywrightInstalled(Path playwrightDir)
	{
		try
		{
			boolean needsInstall = !Files.exists(playwrightDir.resolve("node_modules/@playwright/test"));

			if (needsInstall)
			{
				Files.createDirectories(playwrightDir);
				String packageJson = "{\n" +
					"  \"name\": \"servoy-playwright\",\n" +
					"  \"version\": \"1.0.0\",\n" +
					"  \"private\": true,\n" +
					"  \"dependencies\": {\n" +
					"    \"@playwright/test\": \"^1.52.0\"\n" +
					"  }\n" +
					"}\n";
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
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
				{
					while (reader.readLine() != null) { /* drain */ }
				}
				p.waitFor(120, TimeUnit.SECONDS);

				if (p.exitValue() != 0) return "Error: npm install failed in playwright directory.";

				// Install Chromium browser
				ProcessBuilder pbBrowser = new ProcessBuilder(
					nodePath.getAbsolutePath(), "node_modules/@playwright/test/cli.js", "install", "chromium");
				pbBrowser.directory(playwrightDir.toFile());
				pbBrowser.redirectErrorStream(true);
				pbBrowser.environment().put("PLAYWRIGHT_BROWSERS_PATH", playwrightDir.resolve("browsers").toString());
				Process pBrowser = pbBrowser.start();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(pBrowser.getInputStream(), StandardCharsets.UTF_8)))
				{
					while (reader.readLine() != null) { /* drain */ }
				}
				pBrowser.waitFor(180, TimeUnit.SECONDS);
			}
			return null;
		}
		catch (Exception e)
		{
			return "Error setting up Playwright: " + e.getMessage();
		}
	}
}
