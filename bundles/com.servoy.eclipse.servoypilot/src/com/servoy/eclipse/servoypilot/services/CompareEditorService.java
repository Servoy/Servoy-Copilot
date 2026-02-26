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
package com.servoy.eclipse.servoypilot.services;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileCompareEditorInput;

/**
 * Service for opening Eclipse compare editors.
 * Provides reusable compare functionality accessible to all agents.
 */
public class CompareEditorService
{
	private static CompareEditorService instance;

	public static synchronized CompareEditorService getInstance()
	{
		if (instance == null)
		{
			instance = new CompareEditorService();
		}
		return instance;
	}

	private CompareEditorService()
	{
		// Singleton
	}

	/**
	 * Opens Eclipse compare editor showing original vs modified content.
	 * 
	 * @param fileName the file name (for display)
	 * @param originalContent the original content (left side)
	 * @param modifiedContent the modified content (right side)
	 * @return true if compare editor opened successfully, false otherwise
	 */
	public boolean openCompareEditor(String fileName, String originalContent, String modifiedContent)
	{
		if (fileName == null || originalContent == null || modifiedContent == null)
		{
			ServoyLog.logError("CompareEditorService: Invalid parameters - fileName, originalContent, and modifiedContent are required", null);
			return false;
		}

		try
		{
			// Create compare editor input
			FileCompareEditorInput compareInput = new FileCompareEditorInput(
				fileName,
				originalContent,
				modifiedContent);

			// Open compare editor
			org.eclipse.compare.CompareUI.openCompareEditor(compareInput);
			
			ServoyLog.logInfo("Compare editor opened for file: " + fileName);
			return true;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error opening compare editor for file: " + fileName, e);
			return false;
		}
	}
}
