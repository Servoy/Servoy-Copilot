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

package com.servoy.eclipse.servoypilot.tools.dto;

import java.util.Objects;

public record SourceEdit(
	String filePath,
	int lineStart,
	int lineEnd,
	int stringStart,
	int stringEnd,
	String replacement)
{

	public SourceEdit
	{
		if (lineStart < 0)
		{
			throw new IllegalArgumentException("lineStart cannot be negative: " + lineStart);
		}
		if (lineEnd < lineStart)
		{
			throw new IllegalArgumentException("lineEnd (" + lineEnd + ") cannot be less than lineStart (" + lineStart + ")");
		}
		if (stringStart < 0)
		{
			throw new IllegalArgumentException("stringStart cannot be null (use empty string for insert)");
		}
		if (stringEnd < 0)
		{
			throw new IllegalArgumentException("stringEnd cannot be null (use empty string for insert)");
		}
	}

	public boolean isInsert()
	{
		return stringStart == stringEnd && replacement != null && !replacement.isEmpty();
	}

	public boolean isReplacement()
	{
		return stringEnd > stringStart && replacement != null && !replacement.isEmpty();
	}

	public boolean isDelete()
	{
		return stringEnd > stringStart && (replacement == null || replacement.isEmpty());
	}

	public boolean affectsSingleLine()
	{
		return lineStart == lineEnd;
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
			", lines=" + lineStart + "-" + lineEnd +
			", chars=" + stringStart + "-" + stringEnd +
			", replacement=" + Objects.toString(replacement) +
			"]";
	}
}