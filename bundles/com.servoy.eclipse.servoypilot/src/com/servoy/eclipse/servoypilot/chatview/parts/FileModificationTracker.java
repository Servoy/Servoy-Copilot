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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.chatview.parts;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Singleton tracker for modified files in AI-assisted development.
 * Stores original file content for undo capability and notifies UI when files are modified.
 * Thread-safe implementation for use by AI tools running on background threads.
 * 
 * @author Servoy AI Team
 * @since 2026.02
 */
public class FileModificationTracker
{
	private static FileModificationTracker instance;
	private static final Object LOCK = new Object();

	private final Map<String, String> modifiedFiles = new LinkedHashMap<>();
	private FileModificationListener listener;

	private FileModificationTracker()
	{
		// Private constructor for singleton
	}

	/**
	 * Gets the singleton instance of FileModificationTracker.
	 * Thread-safe with double-checked locking.
	 */
	public static FileModificationTracker getInstance()
	{
		if (instance == null)
		{
			synchronized (LOCK)
			{
				if (instance == null)
				{
					instance = new FileModificationTracker();
					ServoyLog.logInfo("FileModificationTracker initialized");
				}
			}
		}
		return instance;
	}

	/**
	 * Notifies tracker that a file is about to be modified.
	 * Stores original content for undo capability.
	 * 
	 * @param filePath workspace-relative path (e.g., "/ProjectName/path/file.js")
	 * @param originalContent original file content before modification
	 */
	public synchronized void notifyFileModified(String filePath, String originalContent)
	{
		if (filePath != null && !filePath.trim().isEmpty())
		{
			if (!modifiedFiles.containsKey(filePath))
			{
				modifiedFiles.put(filePath, originalContent);
				ServoyLog.logInfo("File tracked for modifications: " + filePath);

				if (listener != null)
				{
					Display.getDefault().asyncExec(() -> listener.onFileModified(filePath));
				}
			}
		}
	}

	/**
	 * Gets all currently modified files.
	 * 
	 * @return map of file paths to original content (LinkedHashMap preserves insertion order)
	 */
	public synchronized Map<String, String> getModifiedFiles()
	{
		return new LinkedHashMap<>(modifiedFiles);
	}

	/**
	 * Gets the original content of a modified file.
	 * 
	 * @param filePath workspace-relative path
	 * @return original content or null if file not tracked
	 */
	public synchronized String getOriginalContent(String filePath)
	{
		return modifiedFiles.get(filePath);
	}

	/**
	 * Checks if any files are currently tracked.
	 * 
	 * @return true if at least one file is tracked
	 */
	public synchronized boolean hasModifiedFiles()
	{
		return !modifiedFiles.isEmpty();
	}

	/**
	 * Removes a file from tracking (user kept changes).
	 * 
	 * @param filePath workspace-relative path
	 */
	public synchronized void keepFile(String filePath)
	{
		if (modifiedFiles.remove(filePath) != null)
		{
			ServoyLog.logInfo("File kept: " + filePath);

			if (modifiedFiles.isEmpty() && listener != null)
			{
				Display.getDefault().asyncExec(() -> listener.onFilesCleared());
			}
			else if (listener != null)
			{
				Display.getDefault().asyncExec(() -> listener.onFileModified(filePath));
			}
		}
	}

	/**
	 * Removes a file from tracking without action (user dismissed tracking).
	 * File stays in its current modified state.
	 * 
	 * @param filePath workspace-relative path
	 */
	public synchronized void removeFile(String filePath)
	{
		if (modifiedFiles.remove(filePath) != null)
		{
			ServoyLog.logInfo("File dismissed from tracking: " + filePath);

			if (modifiedFiles.isEmpty() && listener != null)
			{
				Display.getDefault().asyncExec(() -> listener.onFilesCleared());
			}
			else if (listener != null)
			{
				Display.getDefault().asyncExec(() -> listener.onFileModified(filePath));
			}
		}
	}

	/**
	 * Clears all tracked files (user kept all changes).
	 */
	public synchronized void keepAll()
	{
		if (!modifiedFiles.isEmpty())
		{
			int count = modifiedFiles.size();
			modifiedFiles.clear();
			ServoyLog.logInfo("All files kept: " + count + " files");

			if (listener != null)
			{
				Display.getDefault().asyncExec(() -> listener.onFilesCleared());
			}
		}
	}

	/**
	 * Clears all tracked files without notification (internal cleanup).
	 * Used when switching solutions or assistants.
	 */
	public synchronized void clear()
	{
		if (!modifiedFiles.isEmpty())
		{
			int count = modifiedFiles.size();
			modifiedFiles.clear();
			ServoyLog.logInfo("Tracking cleared: " + count + " files");

			if (listener != null)
			{
				Display.getDefault().asyncExec(() -> listener.onFilesCleared());
			}
		}
	}

	/**
	 * Sets the listener for file modification events.
	 * 
	 * @param listener listener to notify on file changes
	 */
	public void setListener(FileModificationListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Listener interface for file modification events.
	 */
	public interface FileModificationListener
	{
		/**
		 * Called when a file is modified or removed from tracking.
		 * 
		 * @param filePath workspace-relative path of modified file
		 */
		void onFileModified(String filePath);

		/**
		 * Called when all files are cleared from tracking.
		 */
		void onFilesCleared();
	}
}
