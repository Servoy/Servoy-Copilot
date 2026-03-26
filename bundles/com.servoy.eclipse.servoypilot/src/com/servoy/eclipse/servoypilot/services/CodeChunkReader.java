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
 * All modes return a configurable number of lines (small=50, medium=100, large=200).
 */
public class CodeChunkReader
{
	/**
	 * Chunk size options available to the AI.
	 * SMALL (50 lines) — tight focus on a single symbol.
	 * MEDIUM (100 lines) — balanced; fits most functions with context.
	 * LARGE (200 lines) — broad view; fits entire small files in one chunk.
	 */
	public enum ChunkSize
	{
		SMALL(50),
		MEDIUM(100),
		LARGE(200);

		private final int lines;

		ChunkSize(int lines)
		{
			this.lines = lines;
		}

		public int getLines()
		{
			return lines;
		}

		public static ChunkSize fromString(String value)
		{
			if (value != null)
			{
				return switch (value.toUpperCase())
				{
					case "SMALL" -> SMALL;
					case "MEDIUM" -> MEDIUM;
					default -> LARGE;
				};
			}
			return LARGE;
		}
	}

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
	 * @param chunkSize The chunk size to use
	 * @return CodeChunk with lines up to chunkSize.getLines()
	 */
	public CodeChunk readChunk(IFile file, int chunkNumber, ChunkSize chunkSize)
	{
		if (file != null && file.exists())
		{
			try
			{
				int maxLines = chunkSize.getLines();
				List<String> lines = IOUtils.readLines(file.getContents(), StandardCharsets.UTF_8);

				int startLine = chunkNumber * maxLines;
				int endLine = Math.min(startLine + maxLines, lines.size());

				if (startLine >= lines.size())
				{
					return new CodeChunk(
						file.getFullPath().toString(),
						startLine,
						startLine,
						calculateTotalChunks(lines.size(), maxLines),
						chunkNumber,
						"",
						true,
						maxLines);
				}

				StringBuilder content = new StringBuilder();
				for (int i = startLine; i < endLine; i++)
				{
					content.append(i).append(": ").append(lines.get(i)).append("\n");
				}

				int totalChunks = calculateTotalChunks(lines.size(), maxLines);
				boolean isLast = (chunkNumber >= totalChunks - 1);

				return new CodeChunk(
					file.getFullPath().toString(),
					startLine,
					endLine - 1,
					totalChunks,
					chunkNumber,
					content.toString(),
					isLast,
					maxLines);
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
	 * Uses FileStructureService to find symbol location, then reads lines centered on it.
	 *
	 * @param file The file to read
	 * @param symbolName Symbol name to find
	 * @param chunkSize The chunk size to use
	 * @return CodeChunk containing the symbol, or null if not found
	 */
	public CodeChunk readSymbol(IFile file, String symbolName, ChunkSize chunkSize)
	{
		if (file != null && file.exists() && symbolName != null && !symbolName.isBlank())
		{
			FileStructureService structureService = FileStructureService.getInstance();
			FileStructure structure = structureService.analyzeFile(file);

			if (structure != null)
			{
				SymbolInfo symbol = structureService.findSymbol(structure, symbolName);

				if (symbol != null)
				{
					int maxLines = chunkSize.getLines();
					int symbolLine = symbol.getLineNumber() - 1;
					int startLine = Math.max(0, symbolLine - (maxLines / 2));
					int chunkNumber = startLine / maxLines;

					System.out.println("Found symbol '" + symbolName + "' at line " + symbol.getLineNumber() +
						" (0-based: " + symbolLine + "), reading chunk " + chunkNumber + " [" + chunkSize + "]");

					return readChunk(file, chunkNumber, chunkSize);
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
	 * @param chunkSize The chunk size to use
	 * @return CodeChunk with lines up to chunkSize.getLines() starting from specified line
	 */
	public CodeChunk readFromLine(IFile file, int startLine, ChunkSize chunkSize)
	{
		if (file != null && file.exists() && startLine >= 0)
		{
			try
			{
				int maxLines = chunkSize.getLines();
				List<String> lines = IOUtils.readLines(file.getContents(), StandardCharsets.UTF_8);

				if (startLine >= lines.size())
				{
					return new CodeChunk(
						file.getFullPath().toString(),
						startLine,
						startLine,
						calculateTotalChunks(lines.size(), maxLines),
						-1,
						"",
						true,
						maxLines);
				}

				int endLine = Math.min(startLine + maxLines, lines.size());

				StringBuilder content = new StringBuilder();
				for (int i = startLine; i < endLine; i++)
				{
					content.append(i).append(": ").append(lines.get(i)).append("\n");
				}

				int totalChunks = calculateTotalChunks(lines.size(), maxLines);
				boolean isLast = (endLine >= lines.size());

				return new CodeChunk(
					file.getFullPath().toString(),
					startLine,
					endLine - 1,
					totalChunks,
					-1,
					content.toString(),
					isLast,
					maxLines);
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error reading file from line " + startLine + ": " + file.getFullPath(), e);
			}
		}

		return null;
	}

	/**
	 * Calculate total number of chunks for a file given a chunk size.
	 */
	private int calculateTotalChunks(int totalLines, int maxLines)
	{
		if (totalLines <= 0)
		{
			return 0;
		}

		return (int)Math.ceil((double)totalLines / maxLines);
	}
}
