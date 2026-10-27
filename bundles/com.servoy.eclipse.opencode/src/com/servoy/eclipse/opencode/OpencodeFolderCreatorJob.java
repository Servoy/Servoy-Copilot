/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ngclient.ui.IRunNPMCommand;

/**
 * One-shot Eclipse Job that ensures the opencode npm package is installed in
 * the plugin's state directory, then schedules {@link RunOpencodeCommand} to
 * start the server.
 * <p>
 * Follows the same version-sentinel pattern as {@code NodeFolderCreatorJob} in
 * {@code com.servoy.eclipse.ngclient.ui}: a {@code package_copy.json} file
 * records the last installed version; if it differs from the bundle's
 * {@code /opencode/package.json}, a clean re-install is triggered.
 * </p>
 * <p>
 * The helper methods {@link #needsInstall(File, File, String)},
 * {@link #readUrlContent(URL)}, {@link #deleteDirectory(Path)} and
 * {@link #isPlaceholderBinary(File)} are package-visible so they can be
 * exercised by {@code com.servoy.eclipse.opencode.tests} without an OSGi
 * runtime.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
public class OpencodeFolderCreatorJob extends Job {
	public OpencodeFolderCreatorJob() {
		super("Setting up Servoy AI");
		setUser(false);
		setSystem(true);
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		// Servoy AI requires both GENAI_API_KEY and SERVOY_SKILLS_ZIP to be configured.
		// If either is absent the view will show a "not enabled" page; skip setup
		// entirely.
		if (System.getProperty(ProviderConfigWriter.ENV_API_KEY) == null
				|| System.getProperty(ProviderConfigWriter.ENV_API_KEY).isBlank()
				|| SkillsZipExtractor.getSkillsZipSource() == null) {
			ServoyLog
					.logInfo("Servoy AI: not configured (GENAI_API_KEY or SERVOY_SKILLS_ZIP missing), skipping setup."); //$NON-NLS-1$
			return Status.OK_STATUS;
		}

		com.servoy.eclipse.ngclient.ui.Activator ngActivator = com.servoy.eclipse.ngclient.ui.Activator.getInstance();
		if (ngActivator == null) {
			ServoyLog.logError(
					"OpencodeFolderCreatorJob: ngclient.ui Activator not available - Node.js may not be installed.",
					null);
			return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Node.js Activator not available");
		}

		Activator activator = Activator.getInstance();
		File stateLocation = activator.getStateLocation().toFile();
		File opencodeDir = new File(stateLocation, "opencode");
		File markerFile = new File(stateLocation, ".fullygenerated");
		File sentinelFile = new File(opencodeDir, "package_copy.json");

		URL bundlePkgUrl = activator.getBundle().getEntry("/opencode/package.json");
		if (bundlePkgUrl == null) {
			ServoyLog.logError("OpencodeFolderCreatorJob: /opencode/package.json not found in bundle.", null);
			return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "/opencode/package.json missing from bundle");
		}

		String bundleContent;
		try {
			bundleContent = readUrlContent(bundlePkgUrl);
		} catch (IOException e) {
			ServoyLog.logError("OpencodeFolderCreatorJob: cannot read bundle package.json", e);
			return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Cannot read bundle package.json: " + e.getMessage());
		}

		boolean needsInstall = needsInstall(markerFile, sentinelFile, bundleContent);

		ServoyLog.logInfo(
				"Servoy AI setup: " + (needsInstall ? "installing opencode..." : "opencode already up to date."));

		if (needsInstall) {
			// Clean slate
			if (opencodeDir.exists()) {
				try {
					deleteDirectory(opencodeDir.toPath());
				} catch (IOException e) {
					ServoyLog.logError("OpencodeFolderCreatorJob: cannot delete opencode dir", e);
				}
			}
			opencodeDir.mkdirs();
			markerFile.delete();

			// Write package.json + sentinel
			try {
				Files.writeString(new File(opencodeDir, "package.json").toPath(), bundleContent,
						StandardCharsets.UTF_8);
				Files.writeString(sentinelFile.toPath(), bundleContent, StandardCharsets.UTF_8);
			} catch (IOException e) {
				ServoyLog.logError("OpencodeFolderCreatorJob: cannot write package.json", e);
				return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Cannot write package.json: " + e.getMessage());
			}

			// Copy the committed package-manager build-approval config into the managed
			// install dir so @opencode/cli's postinstall (which materializes the real
			// native opencode binary) runs under both pnpm (strictDepBuilds + the
			// version-scoped allowBuilds in pnpm-workspace.yaml) and npm
			// (ignore-scripts=false in .npmrc). See SVY-21679. Copied on every clean
			// reinstall so the files are always present, current and version-matched
			// before the install runs. A missing bundle entry is logged but non-fatal so
			// a packaging slip is diagnosable rather than a hard failure.
			copyBundleResource(activator, "/opencode/pnpm-workspace.yaml", opencodeDir);
			copyBundleResource(activator, "/opencode/.npmrc", opencodeDir);
		}

		// The second clause is defensive only: markerFile lives in stateLocation
		// (not inside opencodeDir), so a missing marker always makes needsInstall()
		// return true. There is therefore no path where the install runs but the
		// build-approval config copied in the needsInstall block above was skipped.
		if (needsInstall || !markerFile.exists()) {
			// createNPMCommand internally waits for Node.js extraction (via
			// waitForNodeExtraction)
			IRunNPMCommand install = ngActivator.createNPMCommand(opencodeDir, List.of("install"));
			install.setOutputStream(activator.getConsole().outputStream());
			try {
				install.runCommand(monitor);
			} catch (IOException | InterruptedException e) {
				activator.logToConsole("npm install failed: " + e.getMessage());
				return new Status(IStatus.WARNING, Activator.PLUGIN_ID, "npm install failed: " + e.getMessage());
			}

			if (install.getExitCode() == 0) {
				try {
					markerFile.createNewFile();
					activator.logToConsole("opencode installed successfully.");
				} catch (IOException e) {
					ServoyLog.logError("OpencodeFolderCreatorJob: cannot create marker file", e);
				}

				// Diagnostic: verify the install produced a real native binary rather than
				// the ~229-byte placeholder shell script shipped inside the package. When
				// the package manager skips @opencode/cli's postinstall (e.g. an
				// outdated/missing allowBuilds approval under pnpm), resolveOpencodeExecutable
				// later finds the placeholder and the launch fails opaquely on Windows with
				// CreateProcess error=216. Reporting it loudly here (named log + WARNING
				// status, consistent with the non-zero-exit path below) makes a
				// build-approval misconfiguration diagnosable at install time. See SVY-21679.
				File opencodeBin = RunOpencodeCommand.resolveOpencodeExecutable(opencodeDir);
				if (isPlaceholderBinary(opencodeBin)) {
					String binMsg = "Servoy AI: opencode install did not produce a real native binary (found "
							+ (opencodeBin == null ? "no executable" : "placeholder " + opencodeBin)
							+ "). The @opencode/cli postinstall script was likely skipped by the package manager;"
							+ " the server will fail to launch. Verify the committed build-approval config"
							+ " (opencode/pnpm-workspace.yaml allowBuilds / opencode/.npmrc) matches the pinned version.";
					activator.logToConsole(binMsg);
					ServoyLog.logWarning(binMsg, null);
					return new Status(IStatus.WARNING, Activator.PLUGIN_ID, binMsg);
				}
			} else {
				String msg = "npm install for opencode exited with code " + install.getExitCode();
				activator.logToConsole(msg);
				return new Status(IStatus.WARNING, Activator.PLUGIN_ID, msg);
			}
		}
		// NOTE: no `npm update @opencode/cli` here on purpose. The version is
		// PINNED to an exact version in the bundle's opencode/package.json because
		// opencode ships breaking API changes in patch releases (e.g. the prompt
		// attachment field was renamed url->uri in 2.0.26). Auto-updating silently
		// changed the server API under the webui and broke features. To move to a
		// newer opencode, bump the exact version in opencode/package.json: that
		// changes the sentinel, so needsInstall() triggers a clean reinstall of the
		// pinned version on the next start. Updates are therefore deliberate and
		// testable, never automatic.

		// Write / merge the opencode.json MCP config
		Path servoyOpencodeCfgDir = Path.of(System.getProperty("user.home"), ".servoy", "opencode"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		// 1. Extract skills zip (if available)
		boolean providerFromZip = false;
		boolean skipExtraction = SkillsZipExtractor.shouldSkipExtraction();
		String skillsZipSource = SkillsZipExtractor.getSkillsZipSource();
		if (skipExtraction) {
			ServoyLog.logInfo("Servoy AI: SERVOY_SKILL_OVERWRITE=false, skipping skills extraction."); //$NON-NLS-1$
		} else if (skillsZipSource != null) {
			byte[] zipBytes;
			try (InputStream is = SkillsZipExtractor.openZipStream(skillsZipSource)) {
				zipBytes = is.readAllBytes();
			} catch (IOException e) {
				ServoyLog.logError("OpencodeFolderCreatorJob: failed to open skills zip", e); //$NON-NLS-1$
				zipBytes = null;
			}
			if (zipBytes != null) {
				try {
					providerFromZip = SkillsZipExtractor.extractToConfigDir(new ByteArrayInputStream(zipBytes),
							servoyOpencodeCfgDir);
				} catch (IOException e) {
					ServoyLog.logError("OpencodeFolderCreatorJob: failed to extract skills zip", e); //$NON-NLS-1$
				}

				String projectRoot = OpenCodeUtil.getActiveProjectPath();
				if (projectRoot != null) {
					try {
						SkillsZipExtractor.writeOrUpdateAgentsMd(new ByteArrayInputStream(zipBytes),
								Path.of(projectRoot));
					} catch (IOException e) {
						ServoyLog.logError("OpencodeFolderCreatorJob: failed to write AGENTS.MD", e); //$NON-NLS-1$
					}
				} else {
					ServoyLog.logInfo("OpencodeFolderCreatorJob: no active project, AGENTS.MD skipped."); //$NON-NLS-1$
				}
			}
		}

		// 2. Merge MCP endpoints (always)
		List<IMcpEndpointProvider> providers = McpConfigWriter.collectProviders();
		try {
			McpConfigWriter.mergeConfig(providers, servoyOpencodeCfgDir.resolve("opencode.json")); //$NON-NLS-1$
		} catch (IOException e) {
			ServoyLog.logError("OpencodeFolderCreatorJob: failed to write opencode.json", e); //$NON-NLS-1$
			// non-fatal: opencode starts without the Servoy MCP endpoints configured
		}
		Map<String, String> mcpEnvVars = McpConfigWriter.buildEnvVars(providers);

		// 3. Provider config fallback (only if zip did not supply opencode.json)
		if (!providerFromZip) {
			try {
				ProviderConfigWriter.mergeProviderConfig(servoyOpencodeCfgDir.resolve("opencode.json")); //$NON-NLS-1$
			} catch (IOException e) {
				ServoyLog.logError("OpencodeFolderCreatorJob: failed to write provider config", e); //$NON-NLS-1$
				// non-fatal: opencode starts without Servoy GenAI provider configured
			}
		}

		Map<String, String> allEnvVars = new HashMap<>(mcpEnvVars);
		allEnvVars.putAll(ProviderConfigWriter.buildProviderEnvVars());

		// Keep the opencode codebase-index folder (.opencode/index) out of git and
		// out of the Eclipse workspace. The index plugin writes it into the project /
		// git root once opencode runs there; it is machine-local, regenerable state.
		String activeProjectPath = OpenCodeUtil.getActiveProjectPath();
		if (activeProjectPath != null) {
			OpencodeIndexIgnorer.apply(Path.of(activeProjectPath));
		}

		// Start the opencode server
		new RunOpencodeCommand(opencodeDir, Collections.unmodifiableMap(allEnvVars)).schedule();
		return Status.OK_STATUS;
	}

	// --- package-visible helpers (accessible from test fragment) ---

	/**
	 * Determines whether {@code npm install} needs to run.
	 * <p>
	 * Returns {@code true} when any of:
	 * <ul>
	 * <li>the {@code .fullygenerated} marker is absent,</li>
	 * <li>the {@code package_copy.json} sentinel is absent, or</li>
	 * <li>the sentinel content differs from {@code bundleContent} (meaning the
	 * shipped version changed).</li>
	 * </ul>
	 * </p>
	 *
	 * @param markerFile    the {@code .fullygenerated} marker
	 * @param sentinelFile  the {@code package_copy.json} sentinel
	 * @param bundleContent the content of {@code /opencode/package.json} from the
	 *                      bundle
	 * @return {@code true} if a (re-)install is required
	 */
	public static boolean needsInstall(File markerFile, File sentinelFile, String bundleContent) {
		if (!markerFile.exists() || !sentinelFile.exists())
			return true;
		try {
			String installedContent = Files.readString(sentinelFile.toPath(), StandardCharsets.UTF_8);
			return !installedContent.equals(bundleContent);
		} catch (IOException e) {
			ServoyLog.logError("OpencodeFolderCreatorJob: cannot read sentinel file", e);
			return true; // defensive: re-install on read error
		}
	}

	/**
	 * Copies a committed bundle resource (e.g.
	 * {@code /opencode/pnpm-workspace.yaml}) into {@code targetDir}, keeping its
	 * file name. A missing bundle entry is logged and ignored (non-fatal) so a
	 * packaging slip surfaces as a clear log line rather than a hard failure.
	 *
	 * @param activator  the plugin activator used to resolve the bundle entry
	 * @param bundlePath the bundle-relative resource path, e.g.
	 *                   {@code "/opencode/.npmrc"}
	 * @param targetDir  the directory to write the resource into
	 */
	static void copyBundleResource(Activator activator, String bundlePath, File targetDir) {
		URL entry = activator.getBundle().getEntry(bundlePath);
		if (entry == null) {
			ServoyLog.logWarning("OpencodeFolderCreatorJob: " + bundlePath + " not found in bundle, skipping copy.",
					null);
			return;
		}
		String fileName = bundlePath.substring(bundlePath.lastIndexOf('/') + 1);
		try {
			String content = readUrlContent(entry);
			Files.writeString(new File(targetDir, fileName).toPath(), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			ServoyLog.logError("OpencodeFolderCreatorJob: cannot copy bundle resource " + bundlePath, e);
		}
	}

	/**
	 * Size (bytes) below which a resolved opencode binary is assumed to be the
	 * placeholder shell script rather than the real native executable. The shipped
	 * placeholder is ~229 bytes; the real binary is MB-scale.
	 */
	public static final long PLACEHOLDER_MAX_SIZE_BYTES = 10 * 1024L;

	/**
	 * Heuristically decides whether {@code binFile} is the placeholder shell script
	 * shipped inside {@code @opencode/cli} rather than the real native opencode
	 * executable.
	 * <p>
	 * When the package manager skips {@code @opencode/cli}'s {@code postinstall}
	 * (e.g. under pnpm's {@code strictDepBuilds} without a matching
	 * {@code allowBuilds} approval), the installed {@code bin/opencode(.exe)} is a
	 * ~229-byte text file whose first non-blank line starts with
	 * {@code echo "Error: @opencode/cli's postinstall script was not run."}. A real
	 * binary is orders of magnitude larger and does not start with that marker.
	 * </p>
	 * <p>
	 * The heuristic is deliberately loose (no hardcoded exact size): a file is
	 * treated as a placeholder when it is missing, below
	 * {@link #PLACEHOLDER_MAX_SIZE_BYTES}, or its first non-blank line starts with
	 * the {@code echo "Error:} marker. A file that is already past the size gate
	 * but cannot be read as UTF-8 text (as a genuine binary often cannot) is
	 * treated as a real binary.
	 * </p>
	 *
	 * @param binFile the resolved opencode executable, may be {@code null}
	 * @return {@code true} if the file is missing or looks like the placeholder
	 */
	public static boolean isPlaceholderBinary(File binFile) {
		if (binFile == null || !binFile.isFile()) {
			return true;
		}
		if (binFile.length() < PLACEHOLDER_MAX_SIZE_BYTES) {
			return true;
		}
		// Inspect only the first non-blank line; never slurp a (potentially large)
		// real binary into memory.
		try (BufferedReader reader = Files.newBufferedReader(binFile.toPath(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.strip();
				if (trimmed.isEmpty()) {
					continue;
				}
				return trimmed.startsWith("echo \"Error:");
			}
			return false; // large file, no content lines -> assume real
		} catch (IOException e) {
			// A genuine native binary is not valid UTF-8 and may fail to read as lines;
			// it is already past the size gate, so treat an unreadable, sufficiently
			// large file as a real binary.
			ServoyLog.logInfo("OpencodeFolderCreatorJob: could not read " + binFile
					+ " as text while checking for placeholder; assuming real binary.");
			return false;
		}
	}

	/**
	 * Reads the full content of the given URL as a UTF-8 string.
	 */
	public static String readUrlContent(URL url) throws IOException {
		try (InputStream is = url.openStream()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Recursively deletes {@code dir} and all its contents.
	 */
	public static void deleteDirectory(Path dir) throws IOException {
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (IOException e) {
					ServoyLog.logError("OpencodeFolderCreatorJob: cannot delete " + path, e);
				}
			});
		}
	}
}
