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

/**
 * Represents a single documentation item for line-based JSDoc generation.
 * 
 * Used by AI to specify:
 * - Line range to insert/replace
 * - Validation strings (start/end sentence)
 * - JSDoc to apply
 * 
 * Semantics:
 * - INSERT: startLine == endLine && startSentence.isEmpty() && endSentence.isEmpty()
 * - REPLACE: startSentence/endSentence used to validate correct location
 */
public record DocumentationItem(
	int startLine,
	int endLine,
	String startSentence,
	String endSentence,
	String jsdoc)
{
	/**
	 * Canonical constructor with validation.
	 */
	public DocumentationItem
	{
		if (startLine < 0)
		{
			throw new IllegalArgumentException("startLine cannot be negative: " + startLine);
		}
		if (endLine < startLine)
		{
			throw new IllegalArgumentException("endLine (" + endLine + ") cannot be less than startLine (" + startLine + ")");
		}
		if (startSentence == null)
		{
			throw new IllegalArgumentException("startSentence cannot be null (use empty string for insert)");
		}
		if (endSentence == null)
		{
			throw new IllegalArgumentException("endSentence cannot be null (use empty string for insert)");
		}
		if (jsdoc == null || jsdoc.isBlank())
		{
			throw new IllegalArgumentException("JSDoc cannot be null or blank");
		}
		if (!jsdoc.trim().startsWith("/**") || !jsdoc.trim().endsWith("*/"))
		{
			throw new IllegalArgumentException("JSDoc must start with /** and end with */ - got: " +
				jsdoc.trim().substring(0, Math.min(50, jsdoc.trim().length())));
		}
	}
	
	/**
	 * Check if this is an insert operation (vs replace).
	 * Insert means: same line, no validation strings.
	 */
	public boolean isInsert()
	{
		return startLine == endLine && 
			   startSentence.isEmpty() && 
			   endSentence.isEmpty();
	}
}
