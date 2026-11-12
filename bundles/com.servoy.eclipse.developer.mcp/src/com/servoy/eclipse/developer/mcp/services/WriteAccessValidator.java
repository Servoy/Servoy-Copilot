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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Gives the workspace's team provider a chance to make files writable before an editing tool writes
 * them.
 * <p>
 * Under a pessimistic version control system - Perforce, ClearCase, SVN with needs-lock - a file
 * that is not checked out is read-only, and the provider only checks it out when asked through
 * {@link IWorkspace#validateEdit}. Writing with {@link org.eclipse.core.resources.IResource#FORCE}
 * does not help: {@code FORCE} overrides the out-of-sync check, not the read-only flag, so the write
 * simply fails with an opaque error.
 * </p>
 * <p>
 * Every file an operation writes is validated in one call, so a provider that prompts asks once
 * rather than once per file. Files that are already writable never reach the provider, which is the
 * ordinary case on git - an optimistic system that does not mark files read-only - so this costs
 * nothing there.
 * </p>
 * <p>
 * Ported from AssistAI's {@code WriteAccessValidator} (upstream commit {@code b720591}) under
 * SVY-21303. Differences: a refusal is reported through this bundle's prose/exception convention by
 * the caller rather than as a structured {@code EditResult.rejected(...)} with a
 * {@code DiagnosticCode}, and the upstream log line is omitted.
 * </p>
 */
public final class WriteAccessValidator
{
	private WriteAccessValidator()
	{
	}

	/**
	 * Asks the workspace to make whichever of the given files are read-only writable.
	 *
	 * @param files the files about to be written; {@code null} entries are ignored
	 * @return {@link Status#OK_STATUS} when nothing is read-only, otherwise the status
	 *         {@link IWorkspace#validateEdit} returned
	 */
	public static IStatus ensureWritable(IFile... files)
	{
		if (files == null || files.length == 0)
		{
			return Status.OK_STATUS;
		}

		List<IFile> readOnlyFiles = new ArrayList<>();
		for (IFile file : files)
		{
			if (file != null && file.isReadOnly())
			{
				readOnlyFiles.add(file);
			}
		}
		if (readOnlyFiles.isEmpty())
		{
			return Status.OK_STATUS;
		}

		return ResourcesPlugin.getWorkspace().validateEdit(
			readOnlyFiles.toArray(IFile[]::new), IWorkspace.VALIDATE_PROMPT);
	}

	/** {@link #ensureWritable(IFile...)} for a collected set of files. */
	public static IStatus ensureWritable(Collection<IFile> files)
	{
		return files == null ? Status.OK_STATUS : ensureWritable(files.toArray(IFile[]::new));
	}

	/**
	 * Validates the files and throws when the team provider refuses, so a caller that reports failures
	 * as exceptions can guard a write in one line.
	 *
	 * @throws RuntimeException when any file could not be made writable
	 */
	public static void requireWritable(IFile... files)
	{
		IStatus status = ensureWritable(files);
		if (!status.isOK())
		{
			throw new RuntimeException("Error: The file is read-only and could not be checked out: " +
				status.getMessage());
		}
	}

	/**
	 * {@link #requireWritable(IFile...)} for a collected set of files, so a folder operation validates
	 * every file it is about to write in a single call.
	 *
	 * @throws RuntimeException when any file could not be made writable
	 */
	public static void requireWritable(Collection<IFile> files)
	{
		IStatus status = ensureWritable(files);
		if (!status.isOK())
		{
			throw new RuntimeException("Error: The files are read-only and could not be checked out: " +
				status.getMessage());
		}
	}
}
