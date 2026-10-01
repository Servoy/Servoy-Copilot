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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceFilterDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.FileInfoMatcherDescription;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Keeps the opencode codebase-index folder ({@code .opencode/index}) that the
 * index plugin writes into the project / git root out of version control and
 * out of the Eclipse workspace.
 * <p>
 * The folder is machine-local, regenerable, binary-heavy state. Every Servoy AI
 * install ends up with one under the active project's git root, so this is
 * handled automatically rather than left to each user to configure.
 * </p>
 * <p>
 * Two independent, idempotent, best-effort actions:
 * </p>
 * <ol>
 * <li><b>git</b> &mdash; ensure {@code <gitRoot>/.gitignore} contains the line
 * {@code /.opencode/index/}. Works even when the git root was never imported as
 * an Eclipse project (the common "workspace is the git root" case), where there
 * is no container to filter.</li>
 * <li><b>Eclipse</b> &mdash; when a workspace {@link IProject} physically
 * contains the {@code .opencode/index} folder, add an <i>exclude</i> resource
 * filter on it so Eclipse never builds, validates or decorates it. A resource
 * filter removes the resource from the workspace tree entirely, which is
 * stronger than {@link org.eclipse.core.resources.IResource#setDerived(boolean)
 * derived} (derived keeps the resource in the tree and only hints Team/export
 * tooling).</li>
 * </ol>
 * <p>
 * The pure helpers {@link #ensureGitignoreContent(String)} and
 * {@link #computeGitignore(java.util.List)} are package-visible so they can be
 * exercised from {@code com.servoy.eclipse.opencode.tests} without an OSGi
 * runtime.
 * </p>
 *
 * @author jcompagner
 * @since 2026.06
 */
class OpencodeIndexIgnorer {

	/** The repository-relative folder written by the opencode index plugin. */
	static final String INDEX_RELATIVE_PATH = ".opencode/index"; //$NON-NLS-1$

	/** The gitignore pattern we ensure is present (anchored to the git root). */
	static final String GITIGNORE_PATTERN = "/.opencode/index/"; //$NON-NLS-1$

	private static final String GITIGNORE_FILE = ".gitignore"; //$NON-NLS-1$

	/**
	 * Applies both the git-ignore and the Eclipse resource-filter protection for
	 * the given git root. Both steps are best-effort and never throw; failures are
	 * logged and ignored so they can never block server start-up.
	 * <p>
	 * The work is scheduled on a background {@link Job} that holds the workspace
	 * root as its scheduling rule: adding a resource filter mutates the workspace,
	 * which must not run on the UI thread and needs the workspace lock. Callers
	 * (the setup job and the view's UI thread) therefore do not have to care which
	 * thread they are on.
	 * </p>
	 *
	 * @param gitRoot the git root of the active project (may be {@code null})
	 */
	static void apply(Path gitRoot) {
		if (gitRoot == null)
			return;
		Job job = new Job("Excluding Servoy AI index from the workspace") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				applyNow(gitRoot);
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.setSystem(true);
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.schedule();
	}

	/**
	 * The actual work of {@link #apply(Path)}, run on the scheduling job. Both
	 * steps are independent and best-effort.
	 */
	static void applyNow(Path gitRoot) {
		try {
			ensureGitignore(gitRoot);
		} catch (IOException e) {
			ServoyLog.logError("OpencodeIndexIgnorer: cannot update .gitignore in " + gitRoot, e); //$NON-NLS-1$
		}
		try {
			ensureResourceFilter();
		} catch (CoreException e) {
			ServoyLog.logError("OpencodeIndexIgnorer: cannot add resource filter for " + INDEX_RELATIVE_PATH, e); //$NON-NLS-1$
		}
	}

	// -------------------------------------------------------------------------
	// git side
	// -------------------------------------------------------------------------

	/**
	 * Ensures {@code <gitRoot>/.gitignore} contains {@link #GITIGNORE_PATTERN},
	 * creating the file if absent and appending the pattern only when missing.
	 * Existing content is left untouched.
	 *
	 * @param gitRoot the git root directory
	 * @throws IOException on read/write failure
	 */
	static void ensureGitignore(Path gitRoot) throws IOException {
		Path gitignore = gitRoot.resolve(GITIGNORE_FILE);
		String existing = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : null;
		String updated = ensureGitignoreContent(existing);
		if (updated != null) {
			Files.writeString(gitignore, updated, StandardCharsets.UTF_8);
		}
	}

	/**
	 * Returns the new {@code .gitignore} content when {@link #GITIGNORE_PATTERN}
	 * must be added, or {@code null} when the pattern is already present (so the
	 * caller writes nothing).
	 * <p>
	 * The existing line delimiter style is preserved; a trailing newline is kept
	 * (or added) so the appended pattern lands on its own line.
	 * </p>
	 *
	 * @param existing the current file content, or {@code null} if the file does
	 *                 not exist yet
	 * @return the content to write, or {@code null} if no change is needed
	 */
	static String ensureGitignoreContent(String existing) {
		if (existing == null) {
			return GITIGNORE_PATTERN + "\n"; //$NON-NLS-1$
		}
		if (containsPattern(existing)) {
			return null;
		}
		boolean crlf = existing.contains("\r\n"); //$NON-NLS-1$
		String nl = crlf ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
		StringBuilder sb = new StringBuilder(existing);
		if (!existing.isEmpty() && !existing.endsWith("\n") && !existing.endsWith("\r")) { //$NON-NLS-1$ //$NON-NLS-2$
			sb.append(nl);
		}
		sb.append(GITIGNORE_PATTERN).append(nl);
		return sb.toString();
	}

	/**
	 * Returns {@code true} if any non-comment line already matches the index
	 * pattern. Tolerant of a leading/trailing slash difference and surrounding
	 * whitespace so we do not append a duplicate when the user (or a previous run)
	 * wrote a slightly different-but-equivalent spelling.
	 */
	private static boolean containsPattern(String content) {
		for (String rawLine : content.split("\r\n|\r|\n", -1)) { //$NON-NLS-1$
			String line = rawLine.strip();
			if (line.isEmpty() || line.startsWith("#")) //$NON-NLS-1$
				continue;
			String normalized = line;
			if (normalized.startsWith("/")) //$NON-NLS-1$
				normalized = normalized.substring(1);
			if (normalized.endsWith("/")) //$NON-NLS-1$
				normalized = normalized.substring(0, normalized.length() - 1);
			if (INDEX_RELATIVE_PATH.equals(normalized)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Pure variant used by the unit tests: given the current {@code .gitignore}
	 * lines, returns the resulting lines after ensuring the pattern is present.
	 */
	static List<String> computeGitignore(List<String> lines) {
		for (String rawLine : lines) {
			String line = rawLine.strip();
			if (line.isEmpty() || line.startsWith("#")) //$NON-NLS-1$
				continue;
			String normalized = line;
			if (normalized.startsWith("/")) //$NON-NLS-1$
				normalized = normalized.substring(1);
			if (normalized.endsWith("/")) //$NON-NLS-1$
				normalized = normalized.substring(0, normalized.length() - 1);
			if (INDEX_RELATIVE_PATH.equals(normalized)) {
				return new ArrayList<>(lines);
			}
		}
		List<String> result = new ArrayList<>(lines);
		result.add(GITIGNORE_PATTERN);
		return result;
	}

	// -------------------------------------------------------------------------
	// Eclipse side
	// -------------------------------------------------------------------------

	/**
	 * Adds an exclude resource filter on the {@code .opencode/index} folder for
	 * every workspace {@link IProject} that physically contains it. Idempotent: a
	 * project that already carries an equivalent filter is left alone. Projects
	 * that do not contain the folder (and the "workspace root is the git root but
	 * was never imported" case, where no project maps the location) are skipped
	 * silently &mdash; the {@code .gitignore} covers those.
	 *
	 * @throws CoreException if the workspace rejects a filter change
	 */
	static void ensureResourceFilter() throws CoreException {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		for (IProject project : root.getProjects()) {
			if (!project.isAccessible())
				continue;
			IPath location = project.getLocation();
			if (location == null)
				continue;
			Path projectDir = location.toFile().toPath();

			// Filter the index folder as soon as the project physically carries the
			// .opencode directory - we do NOT wait for .opencode/index to exist. The
			// index folder is created later, by the index plugin, after the opencode
			// server starts; by then it writes a lease/heartbeat file every few
			// seconds. If we only filtered once index/ existed we would lose that race
			// on the very first launch and Eclipse would keep refreshing on every
			// heartbeat (SVY-21507). An exclude filter on a not-yet-existing child is
			// valid and simply takes effect when the child appears.
			if (!Files.isDirectory(projectDir.resolve(".opencode"))) //$NON-NLS-1$
				continue;

			// The .opencode folder sits directly under the project root here.
			IContainer opencodeContainer = project.getFolder(".opencode"); //$NON-NLS-1$
			addExcludeFilterIfAbsent(opencodeContainer, "index"); //$NON-NLS-1$
		}
	}

	/**
	 * Adds an exclude-all resource filter matching the child named {@code name} on
	 * {@code container}, unless an equivalent filter is already present.
	 */
	private static void addExcludeFilterIfAbsent(IContainer container, String name) throws CoreException {
		if (container == null || !container.exists())
			return;

		String matcherId = "org.eclipse.core.resources.regexFilterMatcher"; //$NON-NLS-1$
		String regex = "^" + java.util.regex.Pattern.quote(name) + "$"; //$NON-NLS-1$ //$NON-NLS-2$

		for (IResourceFilterDescription existing : container.getFilters()) {
			FileInfoMatcherDescription m = existing.getFileInfoMatcherDescription();
			// The regex matcher stores the pattern (e.g. "^index$") as its argument,
			// so compare against that - not against the bare child name - otherwise a
			// re-run would never recognise its own filter and would keep adding
			// duplicates.
			if (m != null && matcherId.equals(m.getId()) && regex.equals(m.getArguments())) {
				return; // already filtered
			}
		}

		FileInfoMatcherDescription matcher = new FileInfoMatcherDescription(matcherId, regex);
		container.createFilter(
				IResourceFilterDescription.EXCLUDE_ALL | IResourceFilterDescription.FOLDERS, matcher,
				0, new NullProgressMonitor());
	}

	/** Private constructor - static utility class. */
	private OpencodeIndexIgnorer() {
	}
}
