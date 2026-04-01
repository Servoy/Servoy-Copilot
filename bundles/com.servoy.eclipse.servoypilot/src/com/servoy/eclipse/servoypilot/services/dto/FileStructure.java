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

import java.util.Collections;
import java.util.List;

/**
 * Represents the structure of a JavaScript file with all its symbols.
 * Used by FileStructureService and CodeAnalysisTools.
 */
public class FileStructure
{
	private final String filePath;
	private final List<SymbolInfo> symbols;

	public FileStructure(String filePath, List<SymbolInfo> symbols)
	{
		this.filePath = filePath;
		this.symbols = symbols != null ? symbols : Collections.emptyList();
	}

	public String getFilePath()
	{
		return filePath;
	}

	public List<SymbolInfo> getSymbols()
	{
		return symbols;
	}

	public int getTotalSymbols()
	{
		return symbols.size();
	}

	/**
	 * Format for AI tool output.
	 */
	public String toFormattedString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("=== FILE STRUCTURE ===\n\n");
		sb.append("FILE: ").append(filePath).append("\n");
		sb.append("TOTAL SYMBOLS: ").append(getTotalSymbols()).append("\n\n");

		if (!symbols.isEmpty())
		{
			sb.append("=== SYMBOLS ===\n\n");
			for (SymbolInfo symbol : symbols)
			{
				sb.append(symbol.toString()).append("\n");
			}
		}
		else
		{
			sb.append("No symbols found in file.\n");
		}

		return sb.toString();
	}

}
