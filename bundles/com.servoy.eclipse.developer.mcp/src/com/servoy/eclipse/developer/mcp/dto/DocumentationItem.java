/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.dto;

/**
 * Represents a single documentation item for line-based JSDoc generation.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.dto.DocumentationItem}.
 * </p>
 * <p>
 * Semantics:
 * <ul>
 *   <li>INSERT: {@code startLine == endLine && startSentence.isEmpty() && endSentence.isEmpty()}</li>
 *   <li>REPLACE: {@code startSentence}/{@code endSentence} validate correct location</li>
 * </ul>
 * </p>
 */
public record DocumentationItem(
	int startLine,
	int endLine,
	String startSentence,
	String endSentence,
	String jsdoc)
{
	public DocumentationItem
	{
		if (startLine < 0)
			throw new IllegalArgumentException("startLine cannot be negative: " + startLine);
		if (endLine < startLine)
			throw new IllegalArgumentException("endLine (" + endLine + ") cannot be less than startLine (" + startLine + ")");
		if (startSentence == null)
			throw new IllegalArgumentException("startSentence cannot be null (use empty string for insert)");
		if (endSentence == null)
			throw new IllegalArgumentException("endSentence cannot be null (use empty string for insert)");
		if (jsdoc == null || jsdoc.isBlank())
			throw new IllegalArgumentException("JSDoc cannot be null or blank");
		if (!jsdoc.trim().startsWith("/**") || !jsdoc.trim().endsWith("*/"))
			throw new IllegalArgumentException("JSDoc must start with /** and end with */ - got: " +
				jsdoc.trim().substring(0, Math.min(50, jsdoc.trim().length())));
	}

	public boolean isInsert()
	{
		return startLine == endLine && startSentence.isEmpty() && endSentence.isEmpty();
	}
}
