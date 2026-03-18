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
package com.servoy.eclipse.servoypilot.services;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.dto.CodeChunk;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;
import com.servoy.eclipse.servoypilot.services.dto.SymbolInfo;

/**
 * Service for reading JavaScript files in manageable chunks.
 * Supports three reading modes:
 * 1. SEQUENTIAL: Read by chunk number (0, 1, 2...)
 * 2. TARGETED: Jump to specific symbol by name
 * 3. DIRECT: Start from specific line number
 * 
 * All modes return max 200 lines with line number prefixes.
 */
public class CodeChunkReader
{
	private static final int MAX_LINES_PER_CHUNK = 200;
	private static CodeChunkReader instance;

	private CodeChunkReader()
	{
		// Singleton
	}

	public static CodeChunkReader getInstance()
	{
		if (instance == null)
		{
			instance = new CodeChunkReader();
		}
		return instance;
	}

	/**
	 * Read chunk by chunk number (SEQUENTIAL mode).
	 * 
	 * @param file The file to read
	 * @param chunkNumber Chunk number (0-based)
	 * @return CodeChunk with max 200 lines
	 */
	public CodeChunk readChunk(IFile file, int chunkNumber)
	{
		if (file != null && file.exists())
		{
			try
			{
				// Read entire file
				List<String> lines = IOUtils.readLines(file.getContents(), StandardCharsets.UTF_8);

				// Calculate chunk boundaries
				int startLine = chunkNumber * MAX_LINES_PER_CHUNK;
				int endLine = Math.min(startLine + MAX_LINES_PER_CHUNK, lines.size());

				if (startLine >= lines.size())
				{
					// Beyond end of file
					return new CodeChunk(
						file.getFullPath().toString(),
						startLine,
						startLine,
						calculateTotalChunks(lines.size()),
						chunkNumber,
						"",
						true);
				}

				// Build content with line number prefixes
				StringBuilder content = new StringBuilder();
				for (int i = startLine; i < endLine; i++)
				{
					content.append(i).append(": ").append(lines.get(i)).append("\n");
				}

				int totalChunks = calculateTotalChunks(lines.size());
				boolean isLast = (chunkNumber >= totalChunks - 1);

				return new CodeChunk(
					file.getFullPath().toString(),
					startLine,
					endLine - 1,
					totalChunks,
					chunkNumber,
					content.toString(),
					isLast);
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error reading file chunk: " + file.getFullPath(), e);
			}
		}

		return null;
	}

	/**
	 * Read chunk by symbol name (TARGETED mode).
	 * Uses FileStructureService to find symbol location, then reads ~200 lines centered on it.
	 * 
	 * @param file The file to read
	 * @param symbolName Symbol name to find
	 * @return CodeChunk containing the symbol, or null if not found
	 */
	public CodeChunk readSymbol(IFile file, String symbolName)
	{
		if (file != null && file.exists() && symbolName != null && !symbolName.isBlank())
		{
			// Use FileStructureService to find symbol
			FileStructureService structureService = FileStructureService.getInstance();
			FileStructure structure = structureService.analyzeFile(file);

			if (structure != null)
			{
				SymbolInfo symbol = structureService.findSymbol(structure, symbolName);

				if (symbol != null)
				{
					// Get symbol line number (1-based from SymbolInfo, convert to 0-based)
					int symbolLine = symbol.getLineNumber() - 1;

					// Calculate start line: 100 lines before symbol (or 0)
					int startLine = Math.max(0, symbolLine - 100);

					// Calculate which chunk this falls into
					int chunkNumber = startLine / MAX_LINES_PER_CHUNK;

					System.out.println("Found symbol '" + symbolName + "' at line " + symbol.getLineNumber() +
						" (0-based: " + symbolLine + "), reading chunk " + chunkNumber);

					// Read chunk starting at calculated position
					return readChunk(file, chunkNumber);
				}

				System.out.println("Symbol '" + symbolName + "' not found in file structure");
			}
		}

		return null;
	}

	/**
	 * Read chunk starting from specific line (DIRECT mode).
	 * 
	 * @param file The file to read
	 * @param startLine Line number to start from (0-based)
	 * @return CodeChunk with max 200 lines starting from specified line
	 */
	public CodeChunk readFromLine(IFile file, int startLine)
	{
		if (file != null && file.exists() && startLine >= 0)
		{
			try
			{
				// Read entire file
				List<String> lines = IOUtils.readLines(file.getContents(), StandardCharsets.UTF_8);

				if (startLine >= lines.size())
				{
					// Start line beyond end of file
					return new CodeChunk(
						file.getFullPath().toString(),
						startLine,
						startLine,
						calculateTotalChunks(lines.size()),
						-1,
						"",
						true);
				}

				// Calculate end line (max 200 lines)
				int endLine = Math.min(startLine + MAX_LINES_PER_CHUNK, lines.size());

				// Build content with line number prefixes
				StringBuilder content = new StringBuilder();
				for (int i = startLine; i < endLine; i++)
				{
					content.append(i).append(": ").append(lines.get(i)).append("\n");
				}

				int totalChunks = calculateTotalChunks(lines.size());
				boolean isLast = (endLine >= lines.size());

				return new CodeChunk(
					file.getFullPath().toString(),
					startLine,
					endLine - 1,
					totalChunks,
					-1, // No chunk number for direct mode
					content.toString(),
					isLast);
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error reading file from line " + startLine + ": " + file.getFullPath(), e);
			}
		}

		return null;
	}

	/**
	 * Calculate total number of chunks for a file.
	 */
	private int calculateTotalChunks(int totalLines)
	{
		if (totalLines <= 0)
		{
			return 0;
		}

		return (int)Math.ceil((double)totalLines / MAX_LINES_PER_CHUNK);
	}
}
