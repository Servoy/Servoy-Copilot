package com.servoy.eclipse.servoypilot.context.dto;

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

	private SelectionInfo(String filePath, int offset, int length, String selectedText, ISourceModule sourceModule)
	{
		this.filePath = filePath;
		this.offset = offset;
		this.length = length;
		this.selectedText = selectedText;
		this.sourceModule = sourceModule;
	}

	/**
	 * Creates a SelectionInfo instance.
	 * 
	 * @param filePath the full path to the file
	 * @param offset the selection start offset
	 * @param length the selection length (0 for full file)
	 * @param selectedText the actual selected text (entire file content if no selection)
	 * @param sourceModule the DLTK source module
	 * @return Optional containing SelectionInfo, or empty if invalid parameters
	 */
	public static Optional<SelectionInfo> create(String filePath, int offset, int length, String selectedText, ISourceModule sourceModule)
	{
		if (filePath != null && !filePath.trim().isEmpty() && 
			offset >= 0 && length >= 0 && 
			sourceModule != null)
		{
			return Optional.of(new SelectionInfo(filePath, offset, length, selectedText != null ? selectedText : "", sourceModule));
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
