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

package com.servoy.eclipse.servoypilot.dto;

import java.util.Objects;

/**
 * Represents a single source code edit produced by an AI assistant.
 * <p>
 * This is a POJO (not a record) so that Jackson can use setter-based deserialization.
 * This allows duplicate JSON keys (which LLMs sometimes produce) to be handled gracefully
 * via last-value-wins semantics, instead of crashing with
 * "No fallback setter/field defined for creator property".
 */
public class SourceEdit
{
	private String filePath;
	private int startLine;
	private int endLine;
	private String startSentence;
	private String endSentence;
	private String replacement;
	private boolean forceEndLineUse;

	public SourceEdit()
	{
	}

	public SourceEdit(String filePath, int startLine, int endLine, String startSentence, String endSentence, String replacement, boolean forceEndLineUse)
	{
		this.filePath = filePath;
		this.startLine = startLine;
		this.endLine = endLine;
		this.startSentence = startSentence;
		this.endSentence = endSentence;
		this.replacement = replacement;
		this.forceEndLineUse = forceEndLineUse;
	}

	public String filePath()
	{
		return filePath;
	}

	public void setFilePath(String filePath)
	{
		this.filePath = filePath;
	}

	public int startLine()
	{
		return startLine;
	}

	public void setStartLine(int startLine)
	{
		this.startLine = startLine;
	}

	public int endLine()
	{
		return endLine;
	}

	public void setEndLine(int endLine)
	{
		this.endLine = endLine;
	}

	public String startSentence()
	{
		return startSentence;
	}

	public void setStartSentence(String startSentence)
	{
		this.startSentence = startSentence;
	}

	public String endSentence()
	{
		return endSentence;
	}

	public void setEndSentence(String endSentence)
	{
		this.endSentence = endSentence;
	}

	public String replacement()
	{
		return replacement;
	}

	public void setReplacement(String replacement)
	{
		this.replacement = replacement;
	}

	public boolean forceEndLineUse()
	{
		return forceEndLineUse;
	}

	public void setForceEndLineUse(boolean forceEndLineUse)
	{
		this.forceEndLineUse = forceEndLineUse;
	}

	public void validate()
	{
		if (filePath == null || filePath.isBlank())
		{
			throw new IllegalArgumentException("filePath cannot be null or blank");
		}

		if (startLine < 0)
		{
			throw new IllegalArgumentException("startLine cannot be negative: " + startLine);
		}

		if (endLine < startLine)
		{
			throw new IllegalArgumentException(
				"endLine (" + endLine + ") cannot be less than startLine (" + startLine + ")");
		}

		if (startSentence == null)
		{
			throw new IllegalArgumentException(
				"startSentence cannot be null (use empty string for insert)");
		}

		if (endSentence == null)
		{
			throw new IllegalArgumentException(
				"endSentence cannot be null (use empty string for insert)");
		}
	}

	public boolean isInsert()
	{
		return startLine == endLine &&
			(startSentence == null || startSentence.isEmpty()) &&
			(endSentence == null || endSentence.isEmpty()) &&
			replacement != null &&
			!replacement.isEmpty();
	}

	public boolean isReplacement()
	{
		return ((startSentence != null && !startSentence.isEmpty()) || (endSentence != null && !endSentence.isEmpty())) &&
			replacement != null &&
			!replacement.isEmpty();
	}

	public boolean isDelete()
	{
		return ((startSentence != null && !startSentence.isEmpty()) || (endSentence != null && !endSentence.isEmpty())) &&
			(replacement == null || replacement.isEmpty());
	}

	public boolean affectsSingleLine()
	{
		return startLine == endLine;
	}

	public boolean hasReplacement()
	{
		return replacement != null && !replacement.isEmpty();
	}

	@Override
	public String toString()
	{
		return "SourceEdit[" +
			"file=" + filePath +
			", lines=" + startLine + "-" + endLine +
			", startSentence=" + startSentence +
			", endSentence=" + endSentence +
			", replacement=" + Objects.toString(replacement) +
			"]";
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		SourceEdit that = (SourceEdit)o;
		return startLine == that.startLine &&
			endLine == that.endLine &&
			forceEndLineUse == that.forceEndLineUse &&
			Objects.equals(filePath, that.filePath) &&
			Objects.equals(startSentence, that.startSentence) &&
			Objects.equals(endSentence, that.endSentence) &&
			Objects.equals(replacement, that.replacement);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(filePath, startLine, endLine, startSentence, endSentence, replacement, forceEndLineUse);
	}
}
