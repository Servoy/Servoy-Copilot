package com.servoy.eclipse.developer.mcp.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.Activator;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Runs Cypress test specs (.spec.cy.js) against Servoy forms using npx cypress run.
 * Cypress is installed locally in .metadata/.plugins/com.servoy.eclipse.copilot/cypress/.
 * Spec files live next to the .frm file in forms/ directory.
 */
@Creatable
@SuppressWarnings("restriction")
public class FormSpecRunner
{
	private static final String MCP_PLUGIN_DIR = "com.servoy.eclipse.developer.mcp";
	private static final String CYPRESS_DIR = "cypress";
	private static final int DEFAULT_TIMEOUT_SECONDS = 60;

	private final FormSpecGenerator specGenerator = new FormSpecGenerator();

	/**
	 * Runs the Cypress spec for the given form using 'npx cypress run'.
	 *
	 * @param formName the form whose .spec.cy.js to run
	 * @param headless true for headless (default), false for headed (debugging)
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

			Path specFilePath = specGenerator.getSpecFilePath(formName);
			if (specFilePath == null || !Files.exists(specFilePath))
			{
				return "Error: Spec file not found: jenkins-custom/e2e-test-scripts/cypress/cy-form/" + formName +
					".spec.cy.js. Use showFormInBrowser first to auto-generate it.";
			}

			// The form spec lives under the e2e-test-scripts project tree, so Cypress MUST
			// be run with that folder as its project root (a spec outside the project root
			// is reported as "no spec files were found"). Reuse the same project-local
			// Cypress install + config that runE2ESpec uses, rather than the bundled
			// .metadata Cypress whose root is elsewhere.
			Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
			Path scriptsRoot = workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts");
			Path configFile = Files.exists(scriptsRoot.resolve("cypress.config.ts"))
				? scriptsRoot.resolve("cypress.config.ts")
				: scriptsRoot.resolve("cypress.config.js");

			List<String> command = new ArrayList<>();
			Path scriptsNodeModulesBin = scriptsRoot.resolve("node_modules").resolve(".bin");
			String localCypressCmd = scriptsNodeModulesBin.resolve("cypress.cmd").toFile().exists()
				? scriptsNodeModulesBin.resolve("cypress.cmd").toString()
				: scriptsNodeModulesBin.resolve("cypress").toFile().exists()
					? scriptsNodeModulesBin.resolve("cypress").toString()
					: null;

			File nodePath = getNodePath();

			if (localCypressCmd != null)
			{
				// use project-local cypress directly â no npm/npx lookup needed
				command.add(localCypressCmd);
				command.add("run");
			}
			else
			{
				// fall back to internal .metadata installation
				Path cypressDir = getCypressDir();
				String setupError = ensureCypressInstalled(cypressDir);
				if (setupError != null)
				{
					return setupError;
				}
				if (nodePath == null)
				{
					return "Error: Bundled Node.js not available and no local Cypress found in " + scriptsNodeModulesBin;
				}
				String npxPath = nodePath.getParent() + File.separator + "npx.cmd";
				if (!new File(npxPath).exists())
				{
					npxPath = nodePath.getParent() + File.separator + "npx";
				}
				command.add(npxPath);
				command.add("cypress");
				command.add("run");
			}
			command.add("--spec");
			command.add(specFilePath.toString());
			if (Files.exists(configFile))
			{
				command.add("--config-file");
				command.add(configFile.toString());
			}
			if (!headless)
			{
				command.add("--headed");
			}

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(scriptsRoot.toFile());
			pb.redirectErrorStream(true);
			// NODE_PATH: use the project-local node_modules if present, otherwise the internal cypress dir
			Path effectiveNodeModules = Files.exists(scriptsRoot.resolve("node_modules"))
				? scriptsRoot.resolve("node_modules")
				: getCypressDir().resolve("node_modules");
			pb.environment().put("NODE_PATH", effectiveNodeModules.toString());
			String existingPath = System.getenv("PATH");
			String prependPath = scriptsNodeModulesBin.toString();
			if (nodePath != null) prependPath = nodePath.getParent() + File.pathSeparator + prependPath;
			pb.environment().put("PATH", prependPath + File.pathSeparator + (existingPath != null ? existingPath : ""));
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
				return "Error: Cypress test timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.";
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
	 * Writes cypress.config.js with baseUrl for the running Servoy solution.
	 */
	private void ensureCypressConfig(Path cypressDir, String baseUrl)
	{
		try
		{
			Path configFile = cypressDir.resolve("cypress.config.js");
			String config = "// Auto-generated by Servoy MCP - do not edit manually\n" +
				"const { defineConfig } = require('cypress');\n\n" +
				"module.exports = defineConfig({\n" +
				"  e2e: {\n" +
				"    baseUrl: '" + baseUrl + "',\n" +
				"    supportFile: false,\n" +
				"    specPattern: '**/*.spec.cy.js',\n" +
				"    testIsolation: true,\n" +
				"    defaultCommandTimeout: 10000,\n" +
				"    pageLoadTimeout: 30000,\n" +
				"    video: false,\n" +
				"    screenshotOnRunFailure: false,\n" +
				"  },\n" +
				"});\n";
			Files.writeString(configFile, config, StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			// Non-fatal
		}
	}

	private Path getCypressDir()
	{
		Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		Path metadataPlugins = workspaceRoot.getParent().resolve(".metadata").resolve(".plugins");
		return metadataPlugins.resolve(MCP_PLUGIN_DIR).resolve(CYPRESS_DIR);
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

	public String executeTestSetup(String serverName, String tableName, java.util.Map<String, Object> columnValues)
	{
		if (serverName == null || tableName == null || columnValues == null || columnValues.isEmpty())
			return "Error: serverName, tableName, and columnValues are required.";

		try
		{
			if (!com.servoy.j2db.server.shared.ApplicationServerRegistry.exists())
				return "Error: Servoy application server is not running.";

			IServerInternal server = (IServerInternal)ApplicationServerRegistry.get().getServerManager().getServer(serverName, false, false);
			if (server == null) return "Error: Database server '" + serverName + "' not found.";

			StringBuilder cols = new StringBuilder();
			StringBuilder placeholders = new StringBuilder();
			List<Object> values = new ArrayList<>();

			for (java.util.Map.Entry<String, Object> entry : columnValues.entrySet())
			{
				if (cols.length() > 0)
				{
					cols.append(", ");
					placeholders.append(", ");
				}
				cols.append(entry.getKey());
				placeholders.append("?");
				values.add(entry.getValue());
			}

			String sql = "INSERT INTO " + tableName + " (" + cols + ") VALUES (" + placeholders + ")";

			try (Connection conn = server.getRawConnection())
			{
				try (PreparedStatement ps = conn.prepareStatement(sql))
				{
					for (int i = 0; i < values.size(); i++)
					{
						ps.setObject(i + 1, values.get(i));
					}
					ps.executeUpdate();
				}
				if (!conn.getAutoCommit()) conn.commit();
			}

			return "Test setup: inserted 1 row into " + serverName + "." + tableName;
		}
		catch (Exception e)
		{
			return "Error in test setup: " + e.getMessage();
		}
	}

	public String executeTestTeardown(String serverName, String tableName, String whereColumn, Object whereValue)
	{
		if (serverName == null || tableName == null || whereColumn == null || whereValue == null)
			return "Error: serverName, tableName, whereColumn, and whereValue are required.";

		try
		{
			if (!com.servoy.j2db.server.shared.ApplicationServerRegistry.exists())
				return "Error: Servoy application server is not running.";

			IServerInternal server = (IServerInternal)ApplicationServerRegistry.get().getServerManager().getServer(serverName, false, false);
			if (server == null) return "Error: Database server '" + serverName + "' not found.";

			String sql = "DELETE FROM " + tableName + " WHERE " + whereColumn + " = ?";
			int deleted = 0;

			try (Connection conn = server.getRawConnection())
			{
				try (PreparedStatement ps = conn.prepareStatement(sql))
				{
					ps.setObject(1, whereValue);
					deleted = ps.executeUpdate();
				}
				if (!conn.getAutoCommit()) conn.commit();
			}

			return "Test teardown: deleted " + deleted + " row(s) from " + serverName + "." + tableName + " where " + whereColumn + " = '" + whereValue + "'";
		}
		catch (Exception e)
		{
			return "Error in test teardown: " + e.getMessage();
		}
	}

	/**
	 * Runs the Cypress E2E spec for the given form from jenkins-custom/e2e-test-scripts/cypress/e2e/&lt;solutionName&gt;/.
	 * Falls back to a recursive search across all solution subdirectories.
	 *
	 * @param targetForm the form name whose .cy.js to run (e.g. 'order_detail' → 'order_detail.cy.js')
	 * @param headless true for headless (default), false for headed (debugging)
	 * @return test results output
	 */
	public String runE2ESpec(String targetForm, boolean headless)
	{
		try
		{
			Path workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
			Path e2eBaseDir = workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts").resolve("cypress").resolve("e2e");

			// resolve active solution name to look in solution-specific subdirectory first
			com.servoy.eclipse.model.nature.ServoyProject servoyProject = com.servoy.eclipse.core.ServoyModelManager
					.getServoyModelManager().getServoyModel().getActiveProject();
			String solutionName = (servoyProject != null && servoyProject.getProject() != null)
					? servoyProject.getProject().getName()
					: null;
			Path e2eDir = (solutionName != null) ? e2eBaseDir.resolve(solutionName) : e2eBaseDir;
			// prefer cypress.config.ts (TypeScript project), fall back to cypress.config.js
			Path scriptsRoot = workspaceRoot.resolve("jenkins-custom").resolve("e2e-test-scripts");
			Path configFile = Files.exists(scriptsRoot.resolve("cypress.config.ts"))
				? scriptsRoot.resolve("cypress.config.ts")
				: scriptsRoot.resolve("cypress.config.js");

			// find spec file:
			// 1. exact match in solution subdir (supports relative paths like "applications/environment/queryPerformance.cy.ts")
			// 2. <targetForm>.cy.js in solution subdir
			// 3. <targetForm>.cy.ts in solution subdir
			// 4. recursive search under e2eBaseDir (all solutions)
			Path specFilePath = e2eDir.resolve(targetForm);
			if (!Files.exists(specFilePath))
			{
				specFilePath = e2eDir.resolve(targetForm + ".cy.js");
			}
			if (!Files.exists(specFilePath))
			{
				specFilePath = e2eDir.resolve(targetForm + ".cy.ts");
			}
			if (!Files.exists(specFilePath) && Files.exists(e2eBaseDir))
			{
				// recursive walk: find first file whose base name (without .cy.js/.cy.ts) matches targetForm
				String baseName = targetForm.replaceAll("\\.cy\\.(js|ts)$", "");
				try (java.util.stream.Stream<Path> walk = Files.walk(e2eBaseDir))
				{
					specFilePath = walk
						.filter(p -> {
							String name = p.getFileName().toString();
							return name.equals(baseName + ".cy.js") || name.equals(baseName + ".cy.ts");
						})
						.findFirst()
						.orElse(e2eDir.resolve(targetForm + ".cy.js")); // keep as missing path for error message
				}
			}
			if (!Files.exists(specFilePath))
			{
				return "Error: E2E spec file not found for '" + targetForm + "'. " +
					"Searched recursively under " + e2eBaseDir + " for '" + targetForm + ".cy.js' or '" + targetForm + ".cy.ts'. " +
					"Use generateCypressE2ETest to create a new one, or pass the relative path (e.g. 'applications/environment/queryPerformance.cy.ts').";
			}
			if (!Files.exists(configFile))
			{
				return "Error: cypress.config.ts/js not found at " + scriptsRoot + ". Use generateCypressE2ETest first to scaffold the E2E test structure.";
			}

			// scriptsRoot already resolved above (for configFile detection)
			Path scriptsDir = scriptsRoot;

			// Prefer the project-local Cypress binary (node_modules/.bin/cypress) if present â
			// this is the case for e2e-test-scripts repos that already have Cypress installed.
			// Fall back to the internal .metadata-bundled installation only if not found.
			Path scriptsNodeModulesBin = scriptsDir.resolve("node_modules").resolve(".bin");
			String localCypressCmd = scriptsNodeModulesBin.resolve("cypress.cmd").toFile().exists()
				? scriptsNodeModulesBin.resolve("cypress.cmd").toString()
				: scriptsNodeModulesBin.resolve("cypress").toFile().exists()
					? scriptsNodeModulesBin.resolve("cypress").toString()
					: null;

			List<String> command = new ArrayList<>();
			if (localCypressCmd != null)
			{
				// use project-local cypress directly â no npm/npx lookup needed
				command.add(localCypressCmd);
				command.add("run");
			}
			else
			{
				// fall back to internal .metadata installation
				Path cypressDir = getCypressDir();
				String setupError = ensureCypressInstalled(cypressDir);
				if (setupError != null)
				{
					return setupError;
				}

				File nodePath = getNodePath();
				if (nodePath == null)
				{
					return "Error: Bundled Node.js not available and no local Cypress found in " + scriptsNodeModulesBin;
				}

				String npxPath = nodePath.getParent() + File.separator + "npx.cmd";
				if (!new File(npxPath).exists())
				{
					npxPath = nodePath.getParent() + File.separator + "npx";
				}
				command.add(npxPath);
				command.add("cypress");
				command.add("run");
			}
			command.add("--spec");
			command.add(specFilePath.toString());
			command.add("--config-file");
			command.add(configFile.toString());
			if (!headless)
			{
				command.add("--headed");
			}

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(scriptsDir.toFile());
			pb.redirectErrorStream(true);
			// NODE_PATH: use the project-local node_modules if present, otherwise the internal cypress dir
			Path effectiveNodeModules = Files.exists(scriptsDir.resolve("node_modules"))
				? scriptsDir.resolve("node_modules")
				: getCypressDir().resolve("node_modules");
			pb.environment().put("NODE_PATH", effectiveNodeModules.toString());
			String existingPath = System.getenv("PATH");
			// Prepend the project-local .bin to PATH so cypress.cmd is found; also add system node
			String prependPath = scriptsDir.resolve("node_modules").resolve(".bin").toString();
			File sysNode = getNodePath();
			if (sysNode != null) prependPath = sysNode.getParent() + File.pathSeparator + prependPath;
			pb.environment().put("PATH", prependPath + File.pathSeparator + (existingPath != null ? existingPath : ""));
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
				return "Error: Cypress E2E test timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.";
			}

			String rawOutput = output.toString();

			if (process.exitValue() == 0)
			{
				return "**E2E Spec Results: " + targetForm + "**\n\nAll tests passed!\n\n" + rawOutput;
			}
			else
			{
				return "**E2E Spec Results: " + targetForm + "**\n\nSome tests failed:\n\n" + rawOutput;
			}
		}
		catch (Exception e)
		{
			return "Error running E2E spec: " + e.getMessage();
		}
	}

	/**
	 * Ensures Cypress is installed locally in the .metadata plugins directory.
	 */
	private String ensureCypressInstalled(Path cypressDir)
	{
		try
		{
			boolean needsInstall = !Files.exists(cypressDir.resolve("node_modules/cypress"));

			if (needsInstall)
			{
				System.out.println("[Servoy MCP] Cypress not found at " + cypressDir + " - installing via npm (this may take a few minutes)...");
				Files.createDirectories(cypressDir);
				String packageJson = "{\n" +
					"  \"name\": \"servoy-cypress\",\n" +
					"  \"version\": \"1.0.0\",\n" +
					"  \"private\": true,\n" +
					"  \"dependencies\": {\n" +
					"    \"cypress\": \"^13.0.0\"\n" +
					"  }\n" +
					"}\n";
				Files.writeString(cypressDir.resolve("package.json"), packageJson, StandardCharsets.UTF_8);

				File nodePath = getNodePath();
				if (nodePath == null) return "Error: Node.js not available.";

				String npmPath = nodePath.getParent() + File.separator + "npm.cmd";
				if (!new File(npmPath).exists())
				{
					npmPath = nodePath.getParent() + File.separator + "npm";
				}

				ProcessBuilder pb = new ProcessBuilder(npmPath, "install");
				pb.directory(cypressDir.toFile());
				pb.redirectErrorStream(true);
				// Ensure the bundled node is on PATH so npm (a node script) can find its runtime
				String existingPath = System.getenv("PATH");
				pb.environment().put("PATH",
					nodePath.getParent() + File.pathSeparator + (existingPath != null ? existingPath : ""));
				Process p = pb.start();
				StringBuilder npmOutput = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
				{
					String line;
					while ((line = reader.readLine()) != null)
					{
						npmOutput.append(line).append("\n");
					}
				}
				p.waitFor(180, TimeUnit.SECONDS);

				if (p.exitValue() != 0)
				{
					System.err.println("[Servoy MCP] npm install FAILED. Output:\n" + npmOutput);
					return "Error: npm install failed in cypress directory.\n" + npmOutput;
				}
				System.out.println("[Servoy MCP] Cypress installed successfully.");
			}
			return null;
		}
		catch (Exception e)
		{
			return "Error setting up Cypress: " + e.getMessage();
		}
	}
}
