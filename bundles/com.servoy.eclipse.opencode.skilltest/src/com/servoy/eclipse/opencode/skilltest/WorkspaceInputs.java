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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.servoy.eclipse.opencode.OpenCodeUtil;

/**
 * Stages a baseline's declared input files into the agent's working directory
 * before each replay attempt, so a prompt that refers to a file "in this
 * workspace" (e.g. a UI mockup under {@code specs/}) finds it regardless of the
 * machine or the baseline's {@code source} kind - including {@code empty},
 * which has no checkout to carry such a file (SVY-21366).
 * <p>
 * The inputs are owned by the <b>baseline</b>, not the solution, exactly like
 * the JSUnit verify scripts: each {@link Baseline.Input} names a file under the
 * baseline folder ({@code from}) and a destination relative to the agent's
 * working directory ({@code to}). The working directory is the active project's
 * git root - the same CWD opencode runs in ({@link OpenCodeUtil#getActiveProjectPath()}) -
 * so a relative prompt path resolves to the staged file.
 * </p>
 * <p>
 * Both ends are validated to stay inside their roots (no {@code ../} escape),
 * mirroring {@link WorkspaceFixture}. A missing source file or an unresolvable
 * working directory throws {@link IOException}: the runner treats that as an
 * invalid test rather than replaying against a prompt whose input is absent.
 * </p>
 */
public final class WorkspaceInputs {

	private final SkillTestRunner.Logger logger;

	/**
	 * @param logger sink for progress messages (may be {@code null})
	 */
	public WorkspaceInputs(SkillTestRunner.Logger logger) {
		this.logger = logger != null ? logger : msg -> {
		};
	}

	/**
	 * Copies each declared input from the baseline folder into the agent's working
	 * directory, creating parent folders as needed and overwriting any file left by
	 * a previous attempt.
	 *
	 * @param inputs         the baseline's declared inputs (ignored when
	 *                       {@code null}/empty)
	 * @param baselineFolder the baseline's on-disk folder (the {@code from} root);
	 *                       required when {@code inputs} is non-empty
	 * @throws IOException if the working directory cannot be resolved, a source file
	 *                     is missing, a path escapes its root, or a copy fails
	 */
	public void stage(List<Baseline.Input> inputs, File baselineFolder) throws IOException {
		if (inputs == null || inputs.isEmpty()) {
			return;
		}
		if (baselineFolder == null || !baselineFolder.isDirectory()) {
			throw new IOException("baseline declares inputs but its folder is not available: " + baselineFolder); //$NON-NLS-1$
		}
		File workingDir = resolveWorkingDir();
		if (workingDir == null || !workingDir.isDirectory()) {
			throw new IOException(
					"cannot stage inputs: no active project working directory (is a solution active?)"); //$NON-NLS-1$
		}
		for (Baseline.Input input : inputs) {
			stageOne(input, baselineFolder, workingDir);
		}
	}

	private void stageOne(Baseline.Input input, File baselineFolder, File workingDir) throws IOException {
		File source = new File(baselineFolder, input.from());
		if (!isInside(baselineFolder, source)) {
			throw new IOException("input 'from' path escapes the baseline folder: " + input.from()); //$NON-NLS-1$
		}
		if (!source.isFile()) {
			throw new IOException("input source file not found in baseline: " + input.from()); //$NON-NLS-1$
		}
		File dest = new File(workingDir, input.to());
		if (!isInside(workingDir, dest)) {
			throw new IOException("input 'to' path escapes the working directory: " + input.to()); //$NON-NLS-1$
		}
		File parent = dest.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("cannot create input destination folder: " + parent); //$NON-NLS-1$
		}
		Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		logger.log("[skilltest] staged input " + input.from() + " -> " + input.to()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The agent's working directory: the active project's git root, the same CWD
	 * opencode runs in. Degrades to {@code null} outside OSGi / with no active
	 * solution so this class does not hard-fail in a plain unit-test JVM.
	 */
	private static File resolveWorkingDir() {
		try {
			String path = OpenCodeUtil.getActiveProjectPath();
			return path != null ? new File(path) : null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static boolean isInside(File parent, File child) {
		try {
			Path p = parent.getCanonicalFile().toPath();
			Path c = child.getCanonicalFile().toPath();
			return c.startsWith(p);
		} catch (IOException e) {
			return false;
		}
	}
}
