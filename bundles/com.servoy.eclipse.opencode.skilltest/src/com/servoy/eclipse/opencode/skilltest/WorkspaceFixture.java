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

package com.servoy.eclipse.opencode.skilltest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resets a Servoy solution's working tree to the state a golden baseline was
 * recorded against, JUnit {@code @Before}-style, before each replay attempt.
 * <p>
 * Two operations, both driven by the baseline's {@link Baseline.Fixture}
 * manifest and both scoped to the solution project's directory:
 * </p>
 * <ul>
 * <li><b>delete</b> &mdash; removes files the agent creates during a run (e.g.
 * {@code forms/test1_.frm}). Untracked artifacts, so a plain file delete is
 * enough; missing files are tolerated.</li>
 * <li><b>restore</b> &mdash; reverts tracked files the agent modifies (e.g.
 * {@code solution_settings.obj}) to their committed git {@code HEAD} content via
 * {@code git checkout HEAD -- &lt;path&gt;}.</li>
 * </ul>
 * <p>
 * The project directory is resolved reflectively-safe via the Eclipse resources
 * runtime, then refreshed afterwards so the IDE and Servoy model see the reset.
 * A fixture that cannot be applied throws {@link IOException}: the runner treats
 * that as an invalid test rather than replaying against a dirty workspace.
 * </p>
 */
public final class WorkspaceFixture {

	/** Max time to wait for a single {@code git} invocation. */
	private static final long GIT_TIMEOUT_SECONDS = 30;

	private final SkillTestRunner.Logger logger;

	/**
	 * @param logger sink for progress messages (may be {@code null})
	 */
	public WorkspaceFixture(SkillTestRunner.Logger logger) {
		this.logger = logger != null ? logger : msg -> {
		};
	}

	/**
	 * Applies a fixture: deletes the declared files and reverts the declared
	 * tracked files, then refreshes the project.
	 *
	 * @param fixture the fixture manifest (ignored when {@code null} or empty)
	 * @throws IOException          if the project cannot be located, a delete
	 *                              fails, or a git restore fails
	 * @throws InterruptedException if interrupted while waiting for git
	 */
	public void reset(Baseline.Fixture fixture) throws IOException, InterruptedException {
		if (fixture == null || fixture.isEmpty()) {
			return;
		}
		if (fixture.solution() == null || fixture.solution().isBlank()) {
			throw new IOException("fixture declares delete/restore paths but no 'solution' project"); //$NON-NLS-1$
		}
		File projectDir = resolveProjectDir(fixture.solution());
		if (projectDir == null || !projectDir.isDirectory()) {
			throw new IOException("fixture solution project not found or has no location: " + fixture.solution()); //$NON-NLS-1$
		}

		for (String rel : fixture.delete()) {
			deleteRelative(projectDir, rel);
		}
		for (String rel : fixture.restore()) {
			restoreFromHead(projectDir, rel);
		}
		refreshProject(fixture.solution());
		logger.log("[skilltest] fixture reset done for solution '" + fixture.solution() + "' (deleted " //$NON-NLS-1$ //$NON-NLS-2$
				+ fixture.delete().size() + ", restored " + fixture.restore().size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private void deleteRelative(File projectDir, String rel) throws IOException {
		File target = new File(projectDir, rel);
		if (!isInside(projectDir, target)) {
			throw new IOException("fixture delete path escapes the project: " + rel); //$NON-NLS-1$
		}
		if (target.exists()) {
			try {
				Files.delete(target.toPath());
				logger.log("[skilltest] fixture deleted " + rel); //$NON-NLS-1$
			} catch (IOException e) {
				throw new IOException("fixture could not delete " + rel + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	private void restoreFromHead(File projectDir, String rel) throws IOException, InterruptedException {
		File target = new File(projectDir, rel);
		if (!isInside(projectDir, target)) {
			throw new IOException("fixture restore path escapes the project: " + rel); //$NON-NLS-1$
		}
		int exit = runGit(projectDir, "checkout", "HEAD", "--", rel); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (exit != 0) {
			throw new IOException("fixture could not restore " + rel + " from git HEAD (git exit " + exit //$NON-NLS-1$ //$NON-NLS-2$
					+ "); is it a tracked file in a git checkout?"); //$NON-NLS-1$
		}
		logger.log("[skilltest] fixture restored " + rel + " from git HEAD"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Runs {@code git} in {@code workingDir}, discarding output. Package-visible so
	 * tests can override the git invocation if needed.
	 *
	 * @param workingDir the directory to run git in
	 * @param args       git arguments (after the {@code git} program)
	 * @return the process exit code
	 * @throws IOException          if git cannot be started
	 * @throws InterruptedException if interrupted while waiting
	 */
	int runGit(File workingDir, String... args) throws IOException, InterruptedException {
		runGitCapturing(workingDir, args); // reuse; ignore output
		return lastGitExit;
	}

	private int lastGitExit;

	private String runGitCapturing(File workingDir, String... args) throws IOException, InterruptedException {
		java.util.List<String> command = new java.util.ArrayList<>();
		command.add("git"); //$NON-NLS-1$
		for (String a : args) {
			command.add(a);
		}
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workingDir);
		pb.redirectErrorStream(true);
		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			throw new IOException("could not run git (is it on PATH?): " + e.getMessage(), e); //$NON-NLS-1$
		}
		String output;
		try (var in = process.getInputStream()) {
			output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("git timed out after " + GIT_TIMEOUT_SECONDS + "s: " + String.join(" ", command)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		lastGitExit = process.exitValue();
		return output;
	}

	/**
	 * The captured fixture manifest for a solution: which files a golden run
	 * created (to {@code delete} on reset) and which tracked files it modified (to
	 * {@code restore} on reset).
	 *
	 * @param delete  project-relative paths that are untracked/added
	 * @param restore project-relative paths that are tracked-but-modified
	 */
	public record CapturedManifest(List<String> delete, List<String> restore) {
	}

	/**
	 * Captures a fixture manifest from the current {@code git status} of a solution
	 * directory: untracked/added entries become {@code delete}, modified tracked
	 * entries become {@code restore}. Intended to run right after a golden session
	 * finishes (the solution's working-tree dirt then reflects exactly what the
	 * golden changed), producing a manifest to pre-fill {@code baseline.json} for
	 * human review.
	 * <p>
	 * Paths that fall outside the solution (deletions, renames of tracked files)
	 * are handled conservatively: modified/added/renamed &rarr; captured, pure
	 * deletions are skipped (nothing to delete on reset).
	 * </p>
	 *
	 * @param solutionDir the solution project's directory
	 * @return the captured manifest (possibly empty), never {@code null}
	 * @throws IOException          if git cannot be run
	 * @throws InterruptedException if interrupted while waiting for git
	 */
	public CapturedManifest captureManifest(File solutionDir) throws IOException, InterruptedException {
		List<String> delete = new java.util.ArrayList<>();
		List<String> restore = new java.util.ArrayList<>();
		if (solutionDir == null || !solutionDir.isDirectory()) {
			return new CapturedManifest(delete, restore);
		}
		// -uall lists individual untracked files rather than just their directory;
		// pathspec '.' scopes to this solution dir so paths come back relative to it.
		String out = runGitCapturing(solutionDir, "status", "--porcelain", "-uall", "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		if (lastGitExit != 0) {
			throw new IOException("git status failed (exit " + lastGitExit + ") in " + solutionDir); //$NON-NLS-1$ //$NON-NLS-2$
		}
		for (String line : out.split("\r?\n")) { //$NON-NLS-1$
			if (line.length() < 4) {
				continue;
			}
			String code = line.substring(0, 2);
			String path = line.substring(3).trim();
			if (path.isEmpty()) {
				continue;
			}
			// Strip surrounding quotes git adds for paths with special chars.
			if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2) { //$NON-NLS-1$ //$NON-NLS-2$
				path = path.substring(1, path.length() - 1);
			}
			if (code.equals("??") || code.startsWith("A")) { //$NON-NLS-1$ //$NON-NLS-2$
				delete.add(path);
			} else if (code.contains("M")) { //$NON-NLS-1$
				restore.add(path);
			}
			// Pure "D " (deleted) is skipped: there's nothing to delete on reset, and
			// restoring a golden-deleted file is out of scope for the manifest.
		}
		return new CapturedManifest(delete, restore);
	}

	private static boolean isInside(File parent, File child) {
		try {
			String p = parent.getCanonicalPath();
			String c = child.getCanonicalPath();
			return c.equals(p) || c.startsWith(p + File.separator);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Resolves a project's filesystem directory via the Eclipse resources runtime,
	 * degrading to {@code null} when that runtime is unavailable (plain unit test
	 * JVM) so this class does not hard-fail outside OSGi.
	 *
	 * @param projectName the project name
	 * @return the project directory, or {@code null} if unavailable
	 */
	private static File resolveProjectDir(String projectName) {
		try {
			org.eclipse.core.resources.IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
					.getRoot().getProject(projectName);
			if (project == null || !project.exists()) {
				return null;
			}
			org.eclipse.core.runtime.IPath location = project.getLocation();
			return location != null ? location.toFile() : null;
		} catch (IllegalStateException | LinkageError e) {
			return null;
		}
	}

	private static void refreshProject(String projectName) {
		try {
			org.eclipse.core.resources.IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
					.getRoot().getProject(projectName);
			if (project != null && project.exists()) {
				project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE,
						new org.eclipse.core.runtime.NullProgressMonitor());
			}
		} catch (org.eclipse.core.runtime.CoreException | IllegalStateException | LinkageError e) {
			// Non-fatal: the files are correct on disk even if the refresh is skipped.
		}
	}
}
