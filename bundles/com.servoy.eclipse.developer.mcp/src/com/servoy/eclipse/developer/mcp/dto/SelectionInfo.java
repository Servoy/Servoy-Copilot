/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.dto;

import java.util.Optional;

import org.eclipse.dltk.core.ISourceModule;

/**
 * Immutable data class representing a code selection or full file in the editor.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.dto.SelectionInfo}.
 * Contains selection metadata: file path, offset, length, and selected text.
 * When length > 0, represents a text selection. When length equals file length, represents entire file.
 * </p>
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

	public String getFilePath() { return filePath; }
	public int getOffset() { return offset; }
	public int getLength() { return length; }
	public String getSelectedText() { return selectedText; }
	public ISourceModule getSourceModule() { return sourceModule; }
	public int getStartLine() { return startLine; }
	public int getEndLine() { return endLine; }
	public boolean isFullFileSelected() { return isFullFile; }

	public boolean hasSelection()
	{
		return length > 0 && selectedText != null && !selectedText.trim().isEmpty();
	}

	@Override
	public String toString()
	{
		return "SelectionInfo{filePath='" + filePath + "', offset=" + offset + ", length=" + length + "}";
	}
}
