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
package com.servoy.eclipse.servoypilot.dto;

import java.util.Optional;

import org.eclipse.dltk.core.ISourceModule;

/**
 * Immutable data class representing a code selection or full file in the editor.
 * Contains selection metadata: file path, offset, length, and selected text.
 * When length > 0, represents a text selection. When length equals file length, represents entire file.
 */
public final class SelectionInfo
{
	private final String filePath;
	private final int offset;
	private final int length;
	private final String selectedText;
	private final ISourceModule sourceModule;
	private final int startLine;
	private final int endLine;
	private final boolean isFullFile;

	private SelectionInfo(String filePath, int offset, int length, String selectedText, ISourceModule sourceModule, int startLine, int endLine,
		boolean isFullFile)
	{
		this.filePath = filePath;
		this.offset = offset;
		this.length = length;
		this.selectedText = selectedText;
		this.sourceModule = sourceModule;
		this.startLine = startLine;
		this.endLine = endLine;
		this.isFullFile = isFullFile;
	}

	/**
	 * Creates a SelectionInfo instance.
	 * 
	 * @param filePath the full path to the file
	 * @param offset the selection start offset
	 * @param length the selection length (0 for full file)
	 * @param selectedText the actual selected text (entire file content if no selection)
	 * @param sourceModule the DLTK source module
	 * @param startLine the start line number (0-based)
	 * @param endLine the end line number (0-based)
	 * @return Optional containing SelectionInfo, or empty if invalid parameters
	 */
	public static Optional<SelectionInfo> create(String filePath, int offset, int length, String selectedText, ISourceModule sourceModule,
		int startLine, int endLine, boolean isFullFile)
	{
		if (filePath != null && !filePath.trim().isEmpty() &&
			offset >= 0 && length >= 0 &&
			startLine >= 0 && endLine >= startLine)
		{
			return Optional
				.of(new SelectionInfo(filePath, offset, length, selectedText != null ? selectedText : "", sourceModule, startLine, endLine, isFullFile));
		}
		if (offset >= 0 && length >= 0 && filePath != null && !filePath.trim().isEmpty() && sourceModule == null)
		{
			return Optional.of(new SelectionInfo(filePath, offset, length, selectedText != null ? selectedText : "", null, startLine, endLine, isFullFile));
		}
		return Optional.empty();
	}

	public String getFilePath()
	{
		return filePath;
	}

	public int getOffset()
	{
		return offset;
	}

	public int getLength()
	{
		return length;
	}

	public String getSelectedText()
	{
		return selectedText;
	}

	public ISourceModule getSourceModule()
	{
		return sourceModule;
	}

	public int getStartLine()
	{
		return startLine;
	}

	public int getEndLine()
	{
		return endLine;
	}

	public boolean hasSelection()
	{
		return length > 0 && selectedText != null && !selectedText.trim().isEmpty();
	}

	@Override
	public String toString()
	{
		return "SelectionInfo{filePath='" + filePath + "', offset=" + offset + ", length=" + length + "}";
	}

	public boolean isFullFileSelected()
	{
		return isFullFile;
	}
}
