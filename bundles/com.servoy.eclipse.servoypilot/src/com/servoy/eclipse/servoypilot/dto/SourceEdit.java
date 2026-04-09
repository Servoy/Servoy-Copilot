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

public record SourceEdit(
	String filePath,
	int startLine,
	int endLine,
	String startSentence,
	String endSentence,
	String replacement)
{

	public SourceEdit
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

	/**
	 * Insert means:
	 * - same line
	 * - no validation strings
	 */
	public boolean isInsert()
	{
		return startLine == endLine &&
			startSentence.isEmpty() &&
			endSentence.isEmpty() &&
			replacement != null &&
			!replacement.isEmpty();
	}

	/**
	 * Replacement means:
	 * - At least one anchor exists (start or end)
	 * - replacement text exists
	 */
	public boolean isReplacement()
	{
		// A replacement is valid if we have something to find (either start or end)
		// AND we have something to put in its place.
		return (!startSentence.isEmpty() || !endSentence.isEmpty()) &&
			replacement != null &&
			!replacement.isEmpty();
	}

	/**
	 * Delete means:
	 * - At least one anchor exists
	 * - replacement is empty or null
	 */
	public boolean isDelete()
	{
		return (!startSentence.isEmpty() || !endSentence.isEmpty()) &&
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
}