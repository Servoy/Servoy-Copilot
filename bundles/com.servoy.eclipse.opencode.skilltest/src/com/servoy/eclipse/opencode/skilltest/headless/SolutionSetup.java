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

package com.servoy.eclipse.opencode.skilltest.headless;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.opencode.skilltest.Baseline;

/**
 * Returns the workspace to a baseline's declared clean initial state before each
 * replay, so runs never start from a dirty or ambiguous workspace (a frequent
 * cause of divergent, hanging replays).
 * <p>
 * The initial state comes from the baseline's {@code precondition.source}
 * ({@link Baseline.Source}):
 * </p>
 * <ul>
 * <li><b>git</b> - clone the repo (optionally at a ref) to a temp dir and import
 * the Servoy solution project(s) found in it,</li>
 * <li><b>folder</b> - import the Servoy solution project(s) found under a folder
 * on disk,</li>
 * <li><b>empty</b> - create a fresh empty solution.</li>
 * </ul>
 * <p>
 * Project import uses only {@code org.eclipse.core.resources} (create + open),
 * so it does not force activation of the UI-only {@code com.servoy.eclipse.core}
 * bundle. The chosen solution is activated headless via the exporter's
 * {@link ExportServoyModel#initialize(String)} (the same model activation the
 * Servoy WAR/solution exporters use in CI), so no workbench and no
 * {@code com.servoy.eclipse.core} classload is needed.
 * </p>
 */
public final class SolutionSetup {

	/**
	 * Web packages planted into an imported solution so its components resolve.
	 * <p>
	 * A solution does NOT declare the web packages it needs: its {@code rootmetadata.obj}
	 * / {@code solution_settings.obj} carry only name/uuid/type, and {@code .project}
	 * references only the resources project. Packages reach a solution either because
	 * a {@code .servoy} export package bundles them (the wizard/XML import path) or
	 * because something copies them into {@code ng_web_packages/} - which is exactly what
	 * the {@code createSolution} MCP tool does from Developer's {@code wizardpackages/}
	 * folder. A raw git/folder checkout has neither, so a form using e.g.
	 * {@code bootstrapcomponents-textbox} became an "Error Bean - Specification not found"
	 * and the structural assertions reported the component as missing (SVY-21366).
	 * </p>
	 * <p>
	 * This is the same default set {@code ServoyDevServer.NG_PACKAGES} uses, so an
	 * imported solution gets the same baseline components a newly created one does.
	 * </p>
	 */
	private static final String[] NG_PACKAGES = { "12grid", "bootstrapcomponents", "fontawesome", "servoyextra" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Max time to wait for the Servoy model to load the imported solution before activating. */
	private static final long ACTIVATE_SETTLE_TIMEOUT_MS = 60_000L;
	/** Poll interval while waiting for the solution model to materialize. */
	private static final long ACTIVATE_SETTLE_POLL_MS = 250L;

	private final SetupLogger logger;
	private final McpToolClient mcp;

	/**
	 * @param logger progress sink (may be {@code null})
	 * @param mcp    MCP tool client for the {@code empty} source (createSolution);
	 *               may be {@code null} to disable the empty-source path
	 */
	public SolutionSetup(SetupLogger logger, McpToolClient mcp) {
		this.logger = logger != null ? logger : msg -> {
		};
		this.mcp = mcp;
	}

	/**
	 * Prepares the workspace for a baseline: cleans the baseline's solution
	 * project, then materializes its declared initial state, then activates the
	 * chosen solution.
	 *
	 * @param baseline the baseline whose {@code precondition.source} + solution
	 *                 drive the setup
	 * @throws Exception if the source cannot be materialized or the solution cannot
	 *                   be activated
	 */
	public void prepare(Baseline baseline) throws Exception {
		Baseline.Source source = baseline.source();
		String solution = baseline.solution();

		// 2. Materialize the initial state.
		switch (source.type()) {
			case GIT -> {
				// A git source IS a checked-out working copy: the solution project lives
				// in the clone and is git-backed. The way to return it to a clean start
				// is 'git reset --hard' + 'clean -dfx' (revert the agent's changes,
				// including newly-added files) - NOT deleting the project with content,
				// which would destroy the checkout (SVY-21366 "you deleted the solution").
				// cloneGitRepo does exactly that: clone on first use, reset+clean on reuse.
				// So there is NO cleanBeforeMaterialize here - the clone/revert is the clean.
				File checkout = cloneGitRepo(source.location(), source.ref());
				assertSourceHasSolution(checkout, solution);
				List<String> imported = importSolutionProjects(checkout, solution);
				String toActivate = resolveActivate(solution, imported);
				ensureNgPackages(toActivate);
				settleBeforeActivate(toActivate);
				activate(toActivate);
				settleAfterActivate();
			}
			case FOLDER -> {
				File folder = new File(source.location());
				if (!folder.isDirectory()) {
					throw new IllegalArgumentException("source folder not found: " + source.location()); //$NON-NLS-1$
				}
				assertNotWorkspace(folder);
				assertSourceHasSolution(folder, solution);
				cleanBeforeMaterialize(solution, baseline);
				List<String> imported = importSolutionProjects(folder, solution);
				String toActivate = resolveActivate(solution, imported);
				ensureNgPackages(toActivate);
				settleBeforeActivate(toActivate);
				activate(toActivate);
				settleAfterActivate();
			}
			case EMPTY -> {
				cleanBeforeMaterialize(solution, baseline);
				createEmptySolution(solution);
			}
			default -> throw new IllegalStateException("unknown source type: " + source.type()); //$NON-NLS-1$
		}
	}

	/**
	 * Removes the baseline's solution project (with content) and any extra declared
	 * {@code cleanProjects}, for a known-empty start. Called only AFTER the source
	 * has been validated/cloned, so a failed materialization never leaves the user
	 * with their active solution deleted and nothing to replace it. Mirrors the
	 * integration tests' {@code deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES)}.
	 */
	private void cleanBeforeMaterialize(String solution, Baseline baseline) {
		if (solution != null && !solution.isBlank()) {
			deleteProjectWithContent(solution);
		}
		deleteProjects(baseline.cleanProjects().toArray(new String[0]));
	}

	/**
	 * Builds the just-imported projects and waits for the Servoy builder to parse
	 * them into an in-memory {@code Solution} model BEFORE activation is attempted.
	 * <p>
	 * {@code ServoyModel.setActiveProject} refuses to activate a project whose
	 * {@code getSolution()} is still {@code null} and pops a modal "Solution X cannot
	 * be activated. Please check for problems in the underlying file representation."
	 * — which, in an unattended skilltest run, blocks forever. Right after
	 * {@code importSolutionProjects} the projects exist on disk and are open, but the
	 * Servoy build that reads their {@code .frm}/{@code .obj}/metadata into the model
	 * has not necessarily run yet, so {@code getSolution()} races the activation.
	 * </p>
	 * <p>
	 * This forces a full build and joins the build jobs, then polls the target
	 * {@code ServoyProject.getSolution()} for up to {@value #ACTIVATE_SETTLE_TIMEOUT_MS}
	 * ms. If the model never materializes it fails the setup with a clear message
	 * (the checkout's file representation is genuinely broken, not a timing issue) —
	 * which the runner reports as a baseline ERROR, instead of leaving the modal to
	 * hang the run.
	 *
	 * @param solutionName the solution about to be activated
	 */
	/**
	 * Plants the default web packages into an imported solution's
	 * {@code ng_web_packages/} folder, so the components its forms use resolve to a
	 * spec instead of becoming "Error Bean - Specification not found".
	 * <p>
	 * Mirrors {@code ServoyDevServer.copyNgPackages}: the zips are read from
	 * Developer's {@code wizardpackages/} state folder (the source of truth populated
	 * at Developer startup) and copied as {@code <name>.zip}. This needs no network
	 * and no WPM round-trip. A package already present in the checkout is left as it
	 * is, so a solution that ships its own packages keeps them.
	 * </p>
	 * <p>
	 * Best-effort: a missing {@code wizardpackages/} folder or an unreadable zip is
	 * logged and skipped rather than failing the setup - the outcome checks will
	 * report the unresolved component clearly enough.
	 * </p>
	 *
	 * @param solutionName the imported solution project to provision
	 */
	private void ensureNgPackages(String solutionName) {
		if (solutionName == null || solutionName.isBlank()) {
			return;
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(solutionName);
		if (!project.exists() || !project.isOpen()) {
			return;
		}
		File wizardPackagesDir = new File(com.servoy.eclipse.ui.Activator.getDefault().getStateLocation().toFile(),
				"wizardpackages"); //$NON-NLS-1$
		if (!wizardPackagesDir.isDirectory()) {
			logger.log("[skilltest] WARN: wizardpackages folder not found at " + wizardPackagesDir //$NON-NLS-1$
					+ "; imported solution keeps whatever packages it ships"); //$NON-NLS-1$
			return;
		}
		File[] available = wizardPackagesDir.listFiles();
		if (available == null) {
			return;
		}
		try {
			org.eclipse.core.resources.IFolder ngFolder = project
					.getFolder(com.servoy.eclipse.model.repository.SolutionSerializer.NG_PACKAGES_DIR_NAME);
			if (!ngFolder.exists()) {
				ngFolder.create(true, true, new NullProgressMonitor());
			}
			List<String> planted = new ArrayList<>();
			for (String name : NG_PACKAGES) {
				org.eclipse.core.resources.IFile dest = ngFolder.getFile(name + ".zip"); //$NON-NLS-1$
				if (dest.exists()) {
					continue; // the checkout already provides this package
				}
				File source = null;
				for (File f : available) {
					if (f.isFile() && f.getName().startsWith(name + "_")) { //$NON-NLS-1$
						source = f;
						break;
					}
				}
				if (source == null) {
					logger.log("[skilltest] WARN: package not found in wizardpackages: " + name); //$NON-NLS-1$
					continue;
				}
				try (java.io.InputStream is = new java.io.FileInputStream(source)) {
					dest.create(is, true, new NullProgressMonitor());
					planted.add(name);
				} catch (IOException | CoreException e) {
					logger.log("[skilltest] WARN: could not copy package " + name + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			logger.log("[skilltest] web packages for '" + solutionName + "': planted " //$NON-NLS-1$ //$NON-NLS-2$
					+ (planted.isEmpty() ? "none (already present)" : String.join(", ", planted))); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (CoreException e) {
			logger.log("[skilltest] WARN: could not provision web packages for '" + solutionName //$NON-NLS-1$
					+ "': " + e.getMessage()); //$NON-NLS-1$
		}
	}

	private void settleBeforeActivate(String solutionName) {
		if (solutionName == null || solutionName.isBlank()) {
			return;
		}
		org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
		try {
			logger.log("[skilltest] building imported projects before activation..."); //$NON-NLS-1$
			ResourcesPlugin.getWorkspace().build(
					org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		} catch (CoreException | OperationCanceledException e) {
			logger.log("[skilltest] WARN: full build before activation failed: " + e.getMessage()); //$NON-NLS-1$
		}
		try {
			jm.join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
			jm.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new NullProgressMonitor());
		} catch (OperationCanceledException | InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.log("[skilltest] WARN: interrupted while waiting for build before activation: " //$NON-NLS-1$
					+ e.getMessage());
		}

		// Tell the Servoy model to (re)scan the workspace so the just-imported project
		// is registered and its Solution root object is loaded. This is the step that
		// actually makes getSolution() non-null for a freshly-imported project - a full
		// workspace build alone does NOT populate it. The MCP activateSolution tool only
		// refreshes when called with refreshAndWait=true, which this path does not use
		// (it calls the plain tool), so we must refresh here ourselves. Without this the
		// poll below would never see a Solution and would always time out (the regression).
		try {
			logger.log("[skilltest] refreshing Servoy projects so the import loads into the model..."); //$NON-NLS-1$
			ServoyModelFinder.getServoyModel().refreshServoyProjects();
		} catch (RuntimeException | LinkageError e) {
			logger.log("[skilltest] WARN: refreshServoyProjects failed: " + e.getMessage()); //$NON-NLS-1$
		}

		// Poll until the Servoy model has a non-null Solution for this project, so
		// activateSolution does not hit the "cannot be activated" modal.
		long deadline = System.currentTimeMillis() + ACTIVATE_SETTLE_TIMEOUT_MS;
		ServoyProject project = null;
		while (System.currentTimeMillis() < deadline) {
			project = ServoyModelFinder.getServoyModel().getServoyProject(solutionName);
			if (project != null && project.getSolution() != null) {
				logger.log("[skilltest] solution model for '" + solutionName + "' is ready to activate."); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			try {
				Thread.sleep(ACTIVATE_SETTLE_POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new IllegalStateException("solution '" + solutionName //$NON-NLS-1$
				+ "' did not load into the Servoy model after import (getSolution() is null after a full build" //$NON-NLS-1$
				+ " + refreshServoyProjects + " + (ACTIVATE_SETTLE_TIMEOUT_MS / 1000) //$NON-NLS-1$
				+ "s wait); the checked-out file representation is likely incomplete or has build errors."); //$NON-NLS-1$
	}

	/**
	 * Waits for the workspace build + Servoy background jobs (auto/manual build and
	 * the "Writing I18N files..." {@code EclipseMessages} job) to finish after
	 * activation, so the replay reads a settled persist tree and the i18n job does
	 * not race the run. Mirrors the integration tests' {@code waitForWorkspaceBuildJobs}
	 * (which {@code join}s {@code FAMILY_AUTO_BUILD}/{@code FAMILY_MANUAL_BUILD}).
	 * Best-effort: a join interruption/timeout is logged, not fatal.
	 */
	private void settleAfterActivate() {
		org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
		try {
			logger.log("[skilltest] waiting for build/i18n jobs to settle after activation..."); //$NON-NLS-1$
			jm.join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
			jm.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, new NullProgressMonitor());
		} catch (OperationCanceledException | InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.log("[skilltest] WARN: interrupted while settling jobs: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Guards against a folder source that is (or contains) the live workspace.
	 * Setup deletes the solution project with its content and re-imports it, so a
	 * source equal to - or an ancestor/descendant of - the workspace root would
	 * delete the very files it then imports. The source must be a separate,
	 * pristine copy of the solution.
	 */
	private static void assertNotWorkspace(File folder) {
		File workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
		Path src = folder.toPath().toAbsolutePath().normalize();
		Path ws = workspaceRoot.toPath().toAbsolutePath().normalize();
		if (src.equals(ws) || src.startsWith(ws) || ws.startsWith(src)) {
			throw new IllegalArgumentException(
					"folder source must not be the workspace (or contain/inside it): source=" + src //$NON-NLS-1$
							+ ", workspace=" + ws + ". Point it at a separate pristine copy of the solution."); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	// --- git ------------------------------------------------------------------

	/**
	 * Clones {@code repoUrl} (optionally checking out {@code ref}) into a fresh
	 * temp directory and returns it. A shallow clone is used for speed.
	 */
	/**
	 * Clones the repo with <b>JGit</b> (the engine EGit uses) rather than the git
	 * CLI, so it works headless and reuses the IDE's git credentials:
	 * <ul>
	 * <li>No tty is needed — the CLI's interactive credential/username prompt was
	 * the "could not read Username for https://github.com" / "User cancelled
	 * dialog" failure on CI.</li>
	 * <li>Credentials come from EGit's secure store via its credentials provider
	 * (same place the IDE's own clone/fetch uses), and SSH uses the IDE keys.</li>
	 * <li>The checkout lands under the <b>workspace</b> (a {@code .skilltest-src/}
	 * folder in the workspace root), not {@code AppData/Local/Temp}, so it sits
	 * beside the solution projects it feeds and is easy to inspect/clean.</li>
	 * </ul>
	 */
	private File cloneGitRepo(String repoUrl, String ref) throws IOException {
		if (repoUrl == null || repoUrl.isBlank()) {
			throw new IllegalArgumentException("git source requires a 'location' repo URL"); //$NON-NLS-1$
		}
		File wsRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
		File base = new File(wsRoot, ".skilltest-src"); //$NON-NLS-1$
		// Stable per-repo+ref directory (NO nanotime), so a second attempt reuses the
		// existing checkout instead of re-cloning.
		File dest = new File(base, sanitize(repoUrl) + (ref != null && !ref.isBlank() ? "@" + sanitize(ref) : "")); //$NON-NLS-1$ //$NON-NLS-2$
		if (!base.isDirectory() && !base.mkdirs()) {
			throw new IOException("cannot create clone base dir: " + base); //$NON-NLS-1$
		}

		// Already checked out: just revert it to a pristine ref state (reset --hard
		// + clean -df) rather than re-cloning. This discards all local changes the
		// previous attempt made, including newly added/untracked files.
		if (new File(dest, ".git").isDirectory()) { //$NON-NLS-1$
			logger.log("[skilltest] reusing checkout " + dest + " - reverting local changes ..."); //$NON-NLS-1$ //$NON-NLS-2$
			try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(dest)) {
				// reset --hard brings all TRACKED files back to the ref; this already undoes
				// the bulk of the agent's changes and never touches ignored files.
				git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
				// clean removes untracked files (the agent's newly-added solution content).
				// setIgnore(true): do NOT clean gitignored files. The opencode codebase-index
				// plugin writes a live .opencode/index/ (with codebase.db) into the checkout
				// root while the server runs there, and that folder is gitignored (SVY-21507).
				// Cleaning ignored files (setIgnore(false)) made JGit try to delete the open,
				// Windows-locked codebase.db and threw "process cannot access the file ... used
				// by another process", failing the whole setup. The agent's own output is
				// tracked/untracked solution content, not ignored, so it is still removed; the
				// live index DB is left alone.
				try {
					git.clean().setCleanDirectories(true).setForce(true).setIgnore(true).call();
				} catch (org.eclipse.jgit.api.errors.GitAPIException | org.eclipse.jgit.api.errors.JGitInternalException cleanEx) {
					// A leftover ignored file may still be locked on Windows (e.g. an older
					// checkout whose .opencode/index/ predates the .gitignore entry). reset --hard
					// has already restored every tracked file, so the checkout is usable; the
					// locked ignored file is not solution content. Log and keep going rather than
					// re-cloning (which would hit the same lock and leave a half-deleted checkout).
					logger.log("[skilltest] clean skipped a locked/ignored file (" //$NON-NLS-1$
							+ cleanEx.getMessage() + "); checkout was reset --hard, continuing"); //$NON-NLS-1$
				}
				logger.log("[skilltest] checkout reverted to clean " + (ref != null ? ref : "HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
				return dest;
			} catch (org.eclipse.jgit.api.errors.GitAPIException | org.eclipse.jgit.api.errors.JGitInternalException e) {
				// reset itself failed (corrupt checkout?) - fall through to a fresh clone.
				logger.log("[skilltest] revert failed (" + e.getMessage() + "), re-cloning"); //$NON-NLS-1$ //$NON-NLS-2$
				deleteRecursive(dest);
			}
		} else if (dest.exists()) {
			// Non-git leftover at the path: remove it so the clone has a clean target.
			deleteRecursive(dest);
		}

		logger.log("[skilltest] cloning " + repoUrl + (ref != null ? " @ " + ref : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ " into " + dest + " (JGit) ..."); //$NON-NLS-1$ //$NON-NLS-2$
		org.eclipse.jgit.api.CloneCommand clone = org.eclipse.jgit.api.Git.cloneRepository()
				.setURI(repoUrl)
				.setDirectory(dest)
				.setDepth(1)
				.setCredentialsProvider(resolveCredentialsProvider());
		if (ref != null && !ref.isBlank()) {
			clone.setBranch(ref);
			clone.setBranchesToClone(java.util.List.of("refs/heads/" + ref)); //$NON-NLS-1$
		}
		try (org.eclipse.jgit.api.Git git = clone.call()) {
			return dest;
		} catch (org.eclipse.jgit.api.errors.GitAPIException e) {
			throw new IOException("git clone failed: " + e.getMessage(), e); //$NON-NLS-1$
		}
	}

	/**
	 * Resolves EGit's credentials provider so HTTPS clones use the same stored
	 * credentials as the IDE's own git operations (its secure store). The class
	 * lives in an {@code internal} EGit package, so it is instantiated reflectively
	 * to avoid a hard compile dependency on internal API. Falls back to
	 * {@code null} (JGit default / SSH keys) when EGit is unavailable.
	 */
	private static org.eclipse.jgit.transport.CredentialsProvider resolveCredentialsProvider() {
		try {
			Class<?> cls = Class
					.forName("org.eclipse.egit.core.internal.credentials.EGitCredentialsProvider"); //$NON-NLS-1$
			return (org.eclipse.jgit.transport.CredentialsProvider) cls.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
			// EGit not available / internal API changed - use JGit default (SSH keys).
			return null;
		}
	}

	/**
	 * Resolves the on-disk folder of a baseline's solution project WITHOUT importing or
	 * cloning anything, so UI (e.g. the JSUnit editor's "existing test files" picker) can
	 * discover the solution's files at edit time. Returns:
	 * <ul>
	 * <li>FOLDER: {@code <location>/<solution>} when it exists, else {@code <location>},</li>
	 * <li>GIT: {@code <workspace>/.skilltest-src/<repo>@<ref>/<solution>} - the stable clone
	 * path this class uses - but ONLY if it already exists on disk (a prior run cloned it);
	 * {@code null} otherwise,</li>
	 * <li>EMPTY or unresolvable: {@code null}.</li>
	 * </ul>
	 *
	 * @param source   the baseline's declared source
	 * @param solution the baseline's solution/project name (may be blank)
	 * @return the solution folder on disk, or {@code null} when it cannot be resolved
	 */
	public static File resolveSolutionSourceFolder(Baseline.Source source, String solution) {
		if (source == null) {
			return null;
		}
		switch (source.type()) {
			case FOLDER -> {
				if (source.location() == null || source.location().isBlank()) {
					return null;
				}
				File root = new File(source.location());
				if (solution != null && !solution.isBlank()) {
					File sol = new File(root, solution);
					if (sol.isDirectory()) {
						return sol;
					}
				}
				return root.isDirectory() ? root : null;
			}
			case GIT -> {
				if (source.location() == null || source.location().isBlank()) {
					return null;
				}
				File wsRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
				File base = new File(wsRoot, ".skilltest-src"); //$NON-NLS-1$
				String ref = source.ref();
				File checkout = new File(base,
						sanitize(source.location()) + (ref != null && !ref.isBlank() ? "@" + sanitize(ref) : "")); //$NON-NLS-1$ //$NON-NLS-2$
				if (!checkout.isDirectory()) {
					return null; // not cloned yet
				}
				if (solution != null && !solution.isBlank()) {
					File sol = new File(checkout, solution);
					if (sol.isDirectory()) {
						return sol;
					}
				}
				return checkout;
			}
			default -> {
				return null; // EMPTY has no on-disk source
			}
		}
	}

	/** Makes a repo URL safe to use as a folder name. */
	private static String sanitize(String url) {
		String s = url.replaceAll("[^A-Za-z0-9._-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
		return s.length() > 60 ? s.substring(s.length() - 60) : s;
	}

	// --- project import (resources API only) ----------------------------------

	/**
	 * Asserts the source (clone/folder) actually contains a project whose name is
	 * {@code solutionName}, BEFORE the workspace is cleaned. This guards against the
	 * destructive sequence where a wrong/misspelled solution name would otherwise
	 * delete the user's existing solution and only then fail the import - which
	 * destroyed work (SVY-21366). A blank name is allowed (resolved later from a
	 * single-project source). Throws {@link IllegalStateException} listing the
	 * available projects when the declared solution is absent.
	 *
	 * @param sourceFolder the clone/folder root to search
	 * @param solutionName the declared solution name (may be blank)
	 */
	private void assertSourceHasSolution(File sourceFolder, String solutionName) throws CoreException {
		if (solutionName == null || solutionName.isBlank()) {
			return; // resolved later from a single-project source
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		List<File> projectDirs = new ArrayList<>();
		collectProjectDirs(sourceFolder, projectDirs, 0);
		java.util.Set<String> names = new java.util.LinkedHashSet<>();
		for (File dir : projectDirs) {
			IProjectDescription pd = workspace.loadProjectDescription(IPath.fromFile(new File(dir, ".project"))); //$NON-NLS-1$
			names.add(pd.getName());
		}
		if (!names.contains(solutionName)) {
			throw new IllegalStateException("declared solution '" + solutionName //$NON-NLS-1$
					+ "' not found under the source (nothing was deleted); available projects: " + names); //$NON-NLS-1$
		}
	}

	/**
	 * Imports ONLY the declared solution and the projects it transitively depends
	 * on (its resources project and modules, via each {@code .project}'s referenced
	 * projects) - not every project found in a (possibly mono-)repo. Mirrors how a
	 * user imports a solution: you get that solution + what it needs, nothing else.
	 * <p>
	 * All {@code .project} dirs under the source are discovered first (indexed by
	 * project name), then the graph is walked starting from {@code solutionName}.
	 * When {@code solutionName} is blank and there is exactly one solution project,
	 * that one is the root; otherwise it is ambiguous and the caller's
	 * {@code resolveActivate} reports it.
	 *
	 * @param sourceFolder the clone/folder root to search
	 * @param solutionName the declared solution to import (may be blank)
	 * @return the names of the projects actually imported
	 */
	private List<String> importSolutionProjects(File sourceFolder, String solutionName) throws CoreException {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();

		// Index every project dir in the source by its project name.
		List<File> projectDirs = new ArrayList<>();
		collectProjectDirs(sourceFolder, projectDirs, 0);
		if (projectDirs.isEmpty()) {
			throw new IllegalStateException("no Eclipse projects (.project) found under " + sourceFolder); //$NON-NLS-1$
		}
		java.util.Map<String, File> byName = new java.util.LinkedHashMap<>();
		for (File dir : projectDirs) {
			IProjectDescription pd = workspace.loadProjectDescription(IPath.fromFile(new File(dir, ".project"))); //$NON-NLS-1$
			byName.put(pd.getName(), dir);
		}

		// Determine the root solution to import.
		String rootName = solutionName;
		if (rootName == null || rootName.isBlank()) {
			if (byName.size() == 1) {
				rootName = byName.keySet().iterator().next();
			} else {
				throw new IllegalStateException("found " + byName.size() //$NON-NLS-1$
						+ " projects but no 'precondition.solution' set; declare which solution to import: " //$NON-NLS-1$
						+ byName.keySet());
			}
		}
		if (!byName.containsKey(rootName)) {
			throw new IllegalStateException("declared solution '" + rootName //$NON-NLS-1$
					+ "' not found under the source; available projects: " + byName.keySet()); //$NON-NLS-1$
		}

		// Walk the dependency graph (solution -> resources + modules) via each
		// .project's referencedProjects, importing only what is reachable.
		List<String> ordered = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.Deque<String> queue = new java.util.ArrayDeque<>();
		queue.add(rootName);
		while (!queue.isEmpty()) {
			String name = queue.poll();
			if (!seen.add(name)) {
				continue;
			}
			File dir = byName.get(name);
			if (dir == null) {
				// A referenced project not present in this source (e.g. a platform
				// module); skip it - it is resolved from the workspace/target if needed.
				logger.log("[skilltest] referenced project '" + name + "' not in source - skipping"); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			IProjectDescription pd = workspace.loadProjectDescription(IPath.fromFile(new File(dir, ".project"))); //$NON-NLS-1$
			ordered.add(name);
			for (IProject ref : pd.getReferencedProjects()) {
				queue.add(ref.getName());
			}
			// Also follow dynamic references (resources project is often here).
			for (IProject ref : pd.getDynamicReferences()) {
				queue.add(ref.getName());
			}
		}

		// Import resources/referenced projects before the solution so the Servoy
		// model sees them when the solution opens: reverse so leaves come first.
		java.util.Collections.reverse(ordered);
		List<String> names = new ArrayList<>();
		for (String name : ordered) {
			File dir = byName.get(name);
			IProjectDescription pd = workspace.loadProjectDescription(IPath.fromFile(new File(dir, ".project"))); //$NON-NLS-1$
			IProject project = root.getProject(pd.getName());
			java.net.URI targetLocation = dir.toURI();
			if (project.exists()) {
				// If the project already points at this very source location (a re-run
				// reusing the same clone/folder), delete only the workspace REFERENCE,
				// NOT the content - the content is the git checkout we just reverted and
				// must survive. Only when it points elsewhere do we remove content, so a
				// stale copy from a different source does not shadow this import.
				//
				// Compare as canonical FILES, not as URIs: File.toURI() appends a trailing
				// slash for a directory while IProject.getLocationURI() has none, and
				// URI.normalize() does NOT strip it - so URI equality was ALWAYS false and
				// every re-run deleted the project WITH ITS CONTENT, wiping the solution's
				// files (rootmetadata.obj, solution_settings.obj, forms/...) out of the git
				// checkout. The solution then had no file representation left, getSolution()
				// returned null and activation failed with "Solution X cannot be activated"
				// (SVY-21366).
				boolean sameLocation = isSameLocation(project, dir);
				project.delete(!sameLocation, true, new NullProgressMonitor());
			}
			// Create from the fixture's own .project so Servoy natures/builders are
			// preserved (a blank description would not register it as a solution).
			pd.setLocationURI(targetLocation);
			project.create(pd, new NullProgressMonitor());
			project.open(new NullProgressMonitor());
			names.add(pd.getName());
			logger.log("[skilltest] imported project '" + pd.getName() + "' from " + dir //$NON-NLS-1$ //$NON-NLS-2$
					+ " with natures " + java.util.Arrays.toString(pd.getNatureIds())); //$NON-NLS-1$
		}
		root.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		return names;
	}

	/**
	 * Tells whether an existing workspace project already lives at exactly
	 * {@code dir} on disk, so the import can drop only the workspace reference and
	 * keep the content (the git checkout) intact.
	 * <p>
	 * Compares canonical {@link File}s rather than {@link java.net.URI}s on purpose:
	 * {@code File.toURI()} appends a trailing slash for a directory while
	 * {@code IProject.getLocationURI()} does not, and {@code URI.normalize()} does not
	 * remove it, so URI equality never held and the caller always deleted project
	 * content. Canonical files also make the comparison case- and separator-correct on
	 * Windows and resolve any {@code .}/{@code ..}/symlink differences.
	 * </p>
	 *
	 * @param project the existing workspace project
	 * @param dir     the source directory the import wants it to point at
	 * @return {@code true} when the project's location is the same directory
	 */
	private boolean isSameLocation(IProject project, File dir) {
		org.eclipse.core.runtime.IPath location = project.getLocation();
		if (location == null) {
			return false;
		}
		try {
			return location.toFile().getCanonicalFile().equals(dir.getCanonicalFile());
		} catch (IOException e) {
			// Cannot canonicalize (unusual path / IO issue): fall back to absolute paths.
			logger.log("[skilltest] WARN: could not canonicalize project location (" + e.getMessage() //$NON-NLS-1$
					+ "); comparing absolute paths"); //$NON-NLS-1$
			return location.toFile().getAbsoluteFile().equals(dir.getAbsoluteFile());
		}
	}

	/**
	 * Recursively collects every directory that holds a {@code .project}, up to a
	 * sane depth, <b>descending even into project directories</b>. A repo is often
	 * itself an Eclipse project (a root {@code .project}) with the actual Servoy
	 * solution + resources projects nested inside it, so stopping at the first
	 * {@code .project} would miss them (the "found only [servoy_test]" failure).
	 * {@code .git} and other dot-directories are skipped.
	 */
	private static void collectProjectDirs(File dir, List<File> out, int depth) {
		if (dir == null || !dir.isDirectory() || depth > 8) {
			return;
		}
		if (new File(dir, ".project").isFile()) { //$NON-NLS-1$
			out.add(dir);
			// Keep descending: a nested solution/resources project may live below a
			// repo-root project.
		}
		File[] children = dir.listFiles(File::isDirectory);
		if (children == null) {
			return;
		}
		for (File c : children) {
			String name = c.getName();
			if (name.startsWith(".")) { //$NON-NLS-1$
				continue; // skip .git, .settings, etc.
			}
			collectProjectDirs(c, out, depth + 1);
		}
	}

	// --- activation (via the workbench-native MCP activateSolution) -----------

	private String resolveActivate(String declared, List<String> imported) {
		if (declared != null && !declared.isBlank()) {
			return declared;
		}
		// No declared solution: fall back to the sole imported project, else fail.
		List<String> candidates = imported.stream().filter(n -> !isResourcesProjectName(n)).toList();
		if (candidates.size() == 1) {
			logger.log("[skilltest] no solution declared - activating the only imported solution '" //$NON-NLS-1$
					+ candidates.get(0) + "'."); //$NON-NLS-1$
			return candidates.get(0);
		}
		throw new IllegalStateException("imported " + candidates.size() //$NON-NLS-1$
				+ " candidate solutions " + candidates //$NON-NLS-1$
				+ " but none declared; set 'precondition.solution' in baseline.json"); //$NON-NLS-1$
	}

	/** Heuristic: resources projects are commonly named "*resources" / "resources". */
	private static boolean isResourcesProjectName(String name) {
		return name != null && name.toLowerCase().contains("resources"); //$NON-NLS-1$
	}

	/**
	 * Activates {@code solutionName} headless via the exporter's
	 * {@link ExportServoyModel} - the same model the Servoy WAR/solution exporters
	 * use in CI. This loads the solution + its modules and sets it active without a
	 * workbench, so it works under a session-less CI service (unlike the UI-bound
	 * {@code com.servoy.eclipse.core} activation).
	 */
	/**
	 * Creates a fresh empty solution named {@code solutionName} (activated) via the
	 * Servoy MCP {@code servoy-dev/createSolution} tool. This works headless
	 * because the runner starts as the product/workbench, so the MCP servers are up
	 * (proven by the MCP probe). The solution project was already removed in step 1,
	 * so this starts clean.
	 *
	 * @param solutionName the solution to create; required for the empty source
	 */
	private void createEmptySolution(String solutionName) throws Exception {
		if (solutionName == null || solutionName.isBlank()) {
			throw new IllegalStateException(
					"'empty' source requires 'precondition.solution' (the name of the solution to create)"); //$NON-NLS-1$
		}
		if (mcp == null) {
			throw new IllegalStateException("'empty' source needs the MCP tool client (not available)"); //$NON-NLS-1$
		}
		logger.log("[skilltest] creating empty solution '" + solutionName + "' via MCP..."); //$NON-NLS-1$ //$NON-NLS-2$
		mcp.callDevTool("createSolution", java.util.Map.of( //$NON-NLS-1$
				"solutionName", solutionName, //$NON-NLS-1$
				"activate", "true")); //$NON-NLS-1$ //$NON-NLS-2$
		logger.log("[skilltest] empty solution '" + solutionName + "' created and activated."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Activates {@code solutionName}. The skilltest runner runs <b>inside the
	 * workbench</b> (it needs the MCP servers + JSUnit), so it activates via the
	 * MCP {@code activateSolution} tool - the workbench-native path, the same one
	 * {@code createSolution} uses. The headless {@link ExportServoyModel} path must
	 * NOT be used here: it installs/expects a {@code WorkspaceUserManager}, but a
	 * running workbench has a {@code SwitchableEclipseUserManager} (installed by
	 * the JSUnit infrastructure), and mixing them throws
	 * "SwitchableEclipseUserManager cannot be cast to WorkspaceUserManager".
	 */
	private void activate(String solutionName) {
		logger.log("[skilltest] activating solution '" + solutionName + "' via MCP..."); //$NON-NLS-1$ //$NON-NLS-2$
		if (mcp == null) {
			throw new IllegalStateException("activation needs the MCP tool client (not available)"); //$NON-NLS-1$
		}
		try {
			mcp.callDevTool("activateSolution", java.util.Map.of("solutionName", solutionName)); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (Exception e) {
			throw new IllegalStateException("failed to activate solution '" + solutionName + "': " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage(), e);
		}
		ServoyProject active = ServoyModelFinder.getServoyModel().getActiveProject();
		if (active == null || !solutionName.equals(active.getProject().getName())) {
			throw new IllegalStateException(
					"failed to activate solution '" + solutionName + "' (not active after activateSolution)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		logger.log("[skilltest] activated solution '" + solutionName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	// --- cleanup ---------------------------------------------------------------

	/**
	 * Deletes each named project from the workspace <b>including its content on
	 * disk</b>, mirroring the integration-test {@code TestUtilitiesClass.deleteProjects}
	 * pattern (which cleans the solution <i>and</i> its resources project, e.g.
	 * {@code deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES)}). Missing/blank names
	 * are skipped.
	 *
	 * @param projectNames the projects to clean (solution, resources, modules, ...)
	 */
	private void deleteProjects(String... projectNames) {
		if (projectNames == null) {
			return;
		}
		for (String name : projectNames) {
			if (name != null && !name.isBlank()) {
				deleteProjectWithContent(name);
			}
		}
	}

	/**
	 * Deletes the given project from the workspace <b>including its content on
	 * disk</b>, so a subsequent import is not blocked by leftover files. Missing
	 * projects are tolerated.
	 */
	private void deleteProjectWithContent(String projectName) {
		try {
			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
			IProject project = root.getProject(projectName);
			if (project.exists()) {
				logger.log("[skilltest] cleaning solution project '" + projectName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
				if (!project.isOpen()) {
					try {
						project.open(new NullProgressMonitor());
					} catch (CoreException ignore) {
						// deleting a closed project is fine
					}
				}
				// Remember the on-disk location BEFORE the Eclipse delete so we can
				// force-clean any residue afterwards (Windows file locks can prevent the
				// Eclipse resource delete from removing every file, leaving a ghost
				// folder that makes createSolution say "already exists" instead of
				// creating a fresh solution with default packages).
				java.io.File onDisk = project.getLocation() != null ? project.getLocation().toFile() : null;
				// The skilltest workspace is often a git repo, so the solution project may
				// be connected to EGit. EGit's move/delete team hook runs inside
				// project.delete(...) and has been seen to throw a RuntimeException
				// (GitProvider.getMoveDeleteHook NPE on a half-initialized provider) - which
				// is NOT a CoreException, so it escapes the catch below and fails the whole
				// setup. Disconnect the team provider first so the plain resource delete runs
				// without the git hook; then delete.
				disconnectTeamProvider(project);
				try {
					project.delete(true, true, new NullProgressMonitor());
				} catch (RuntimeException teamHookEx) {
					// A team/move-delete hook still blew up: fall back to deleting the
					// project from the workspace without content, then wiping the folder.
					logger.log("[skilltest] project.delete hook failed (" + teamHookEx.getMessage() //$NON-NLS-1$
							+ "); deleting description-only + on-disk"); //$NON-NLS-1$
					try {
						project.delete(false, true, new NullProgressMonitor());
					} catch (CoreException | RuntimeException ignore) {
						// best-effort: the on-disk wipe below is the real cleanup
					}
				}
				// Force-delete the on-disk folder if it survived (file-lock residue or the
				// description-only delete above left content).
				if (onDisk != null && onDisk.exists()) {
					logger.log("[skilltest] force-deleting residual on-disk folder: " + onDisk); //$NON-NLS-1$
					deleteRecursive(onDisk);
				}
			} else {
				// No Eclipse project, but the on-disk folder may still exist from a
				// prior run that crashed before cleanup. Find it next to the workspace.
				java.io.File wsRoot = root.getLocation() != null ? root.getLocation().toFile() : null;
				if (wsRoot != null) {
					java.io.File onDisk = new java.io.File(wsRoot, projectName);
					if (onDisk.isDirectory()) {
						logger.log("[skilltest] force-deleting orphan on-disk folder: " + onDisk); //$NON-NLS-1$
						deleteRecursive(onDisk);
					}
				}
			}
			root.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		} catch (CoreException e) {
			ServoyLog.logError("Failed to clean solution project '" + projectName + "'.", e); //$NON-NLS-1$ //$NON-NLS-2$
			logger.log("[skilltest] WARN: could not clean project '" + projectName + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * Disconnects any Team (EGit) provider from the project before deletion, so the
	 * provider's move/delete hook does not run inside {@code project.delete(...)}.
	 * EGit's {@code GitProvider.getMoveDeleteHook} has been seen to throw during a
	 * skilltest cleanup when the project is in a git-mapped workspace, failing the
	 * whole setup. Best-effort and tolerant: no provider, or Team API absent, is a
	 * no-op.
	 */
	private void disconnectTeamProvider(IProject project) {
		try {
			if (org.eclipse.team.core.RepositoryProvider.getProvider(project) != null) {
				org.eclipse.team.core.RepositoryProvider.unmap(project);
				logger.log("[skilltest] disconnected team provider from '" + project.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		} catch (Exception | LinkageError e) {
			// Team API not present or unmap refused - the delete fallbacks handle it.
			logger.log("[skilltest] could not disconnect team provider from '" + project.getName() //$NON-NLS-1$
					+ "': " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Recursively deletes a directory and all its contents. Best-effort: locked
	 * files are skipped (logged) rather than throwing. Used to clean up on-disk
	 * residue after {@code IProject.delete} when Windows file locks prevent it
	 * from removing everything.
	 */
	private void deleteRecursive(java.io.File file) {
		if (file.isDirectory()) {
			java.io.File[] children = file.listFiles();
			if (children != null) {
				for (java.io.File child : children) {
					deleteRecursive(child);
				}
			}
		}
		if (!file.delete() && file.exists()) {
			logger.log("[skilltest] WARN: could not delete " + file + " (locked?)"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}