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
package com.servoy.eclipse.servoypilot.services.dto;

import org.eclipse.dltk.core.IModelElement;

/**
 * Represents a single symbol (function or variable) in a JavaScript file.
 * Used by FileStructureService to report symbol information to AI.
 */
public class SymbolInfo
{
	public enum SymbolType
	{
		FUNCTION, VARIABLE
	}

	private final String name;
	private final SymbolType type;
	private final int startOffset;
	private final int endOffset;
	private final int lineNumber; // Line number (1-based) where symbol appears
	private final boolean hasJSDoc;

	public SymbolInfo(String name, int elementType, int startOffset, int endOffset, int lineNumber, boolean hasJSDoc)
	{
		this.name = name;
		this.type = (elementType == IModelElement.METHOD) ? SymbolType.FUNCTION : SymbolType.VARIABLE;
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.lineNumber = lineNumber;
		this.hasJSDoc = hasJSDoc;
	}

	public String getName()
	{
		return name;
	}

	public SymbolType getType()
	{
		return type;
	}

	public int getStartOffset()
	{
		return startOffset;
	}

	public int getEndOffset()
	{
		return endOffset;
	}

	public int getLineNumber()
	{
		return lineNumber;
	}

	public boolean hasJSDoc()
	{
		return hasJSDoc;
	}

	@Override
	public String toString()
	{
		return String.format("- %s (%s) at line %d %s", name, type, lineNumber, hasJSDoc ? "[DOCUMENTED]" : "[NEEDS DOCS]");
	}
}
