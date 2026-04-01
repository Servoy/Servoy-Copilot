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

				// Prepend a note when this is the last chunk and it is much shorter
				// than a full chunk — prevents the model from dismissing it as a duplicate
				String contentStr = content.toString();
				int linesInChunk = endLine - startLine;
				if (isLast && linesInChunk < maxLines / 2)
				{
					contentStr = "NOTE: This is the final chunk. It contains only " + linesInChunk +
						" lines (file ends at line " + (lines.size() - 1) + ").\n\n" + contentStr;
				}

				return new CodeChunk(
					file.getFullPath().toString(),
					startLine,
					endLine - 1,
					totalChunks,
					chunkNumber,
					contentStr,
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
	 * Starts from the previous symbol's line so the JSDoc block above the target is included.
	 *
	 * @param file The file to read
	 * @param symbolName Symbol name to find
	 * @param chunkSize The chunk size to use
	 * @return CodeChunk containing the symbol with its JSDoc context, or null if not found
	 */
	public CodeChunk readSymbol(IFile file, String symbolName, ChunkSize chunkSize)
	{
		if (file != null && file.exists() && symbolName != null && !symbolName.isBlank())
		{
			FileStructureService structureService = FileStructureService.getInstance();
			FileStructure structure = structureService.analyzeFile(file);

			if (structure != null)
			{
				List<SymbolInfo> symbols = structure.getSymbols();
				int targetIndex = -1;
				for (int i = 0; i < symbols.size(); i++)
				{
					if (symbols.get(i).getName().equals(symbolName))
					{
						targetIndex = i;
						break;
					}
				}

				if (targetIndex >= 0)
				{
					SymbolInfo symbol = symbols.get(targetIndex);
					int symbolLine = symbol.getLineNumber() - 1; // 0-based

					// Start from the previous symbol's line to capture the JSDoc block above the target
					int startLine = 0;
					boolean hasContext = false;
					if (targetIndex > 0)
					{
						startLine = symbols.get(targetIndex - 1).getLineNumber() - 1; // 0-based
						hasContext = true;
					}

					CodeChunk chunk = readFromLine(file, startLine, chunkSize);

					if (chunk != null && hasContext)
					{
						String note = "NOTE: Output starts at line " + startLine +
							" (previous symbol boundary) to include the JSDoc block above '" + symbolName +
							"' (declared at line " + symbol.getLineNumber() + "). " +
							"Lines before line " + symbol.getLineNumber() + " are context from the previous symbol.\n\n";
						chunk = new CodeChunk(
							chunk.getFilePath(),
							chunk.getStartLine(),
							chunk.getEndLine(),
							chunk.getTotalChunks(),
							chunk.getChunkNumber(),
							note + chunk.getContent(),
							chunk.isLast(),
							chunk.getChunkSizeLines());
					}

					return chunk;
				}
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
