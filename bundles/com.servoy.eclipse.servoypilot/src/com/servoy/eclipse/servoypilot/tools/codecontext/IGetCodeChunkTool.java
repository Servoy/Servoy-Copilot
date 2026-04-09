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
package com.servoy.eclipse.servoypilot.tools.codecontext;

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.dto.CodeChunk;
import com.servoy.eclipse.servoypilot.services.CodeChunkReader;
import com.servoy.eclipse.servoypilot.services.CodeChunkReader.ChunkSize;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IGetCodeChunkTool
{
	@Tool("Read code chunk from file with configurable chunk size. " +
		"Mode priority: TARGETED (symbolName) > SEQUENTIAL (chunkNumber) > DIRECT (startLine). " +
		"Accepts form names, scope names, or full paths. " +
		"Choose chunk size based on what you need: " +
		"SMALL (50 lines) for tight focus on one symbol; " +
		"MEDIUM (100 lines) for a single function with context; " +
		"LARGE (200 lines) for broad view or entire small files.")
	default String getCodeChunk(
		@P("File path, form name, or scope name") String pathOrName,
		@P("Symbol name to jump to — TARGETED mode, highest priority. When provided, chunkNumber and startLine are ignored.") String symbolName,
		@P("Chunk number to read, 1-based: 1 = first chunk, 2 = second, etc. — SEQUENTIAL mode. When provided, startLine is ignored. Omit when using symbolName.") Integer chunkNumber,
		@P("Line number to start from, 0-based — DIRECT mode. Only used when neither symbolName nor chunkNumber are provided.") Integer startLine,
		@P("Chunk size: SMALL (50 lines), MEDIUM (100 lines), LARGE (200 lines). Defaults to LARGE if not specified. " +
			"Use SMALL when reading a single known symbol; MEDIUM for a function with surrounding context; LARGE for full-file exploration.") String chunkSize)
	{
		try
		{
			if (pathOrName != null && !pathOrName.isBlank())
			{
				FilePathResolver resolver = FilePathResolver.getInstance();
				IFile file = resolver.resolveFile(pathOrName);

				if (file != null && file.exists())
				{
					CodeChunkReader reader = CodeChunkReader.getInstance();
					ChunkSize size = ChunkSize.fromString(chunkSize);
					CodeChunk chunk = null;

					if (symbolName != null && !symbolName.isBlank())
					{
						chunk = reader.readSymbol(file, symbolName, size);
						if (chunk == null)
						{
							return "Error: Symbol '" + symbolName + "' not found in file";
						}
					}
					else if (chunkNumber != null && chunkNumber > 0)
					{
						int chunkNum = Math.max(0, chunkNumber - 1);
						chunk = reader.readChunk(file, chunkNum, size);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							return "Error: Chunk " + chunkNumber + " is beyond end of file";
						}
					}
					else if (startLine != null && startLine >= 0)
					{
						chunk = reader.readFromLine(file, startLine, size);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							return "Error: Start line " + startLine + " is beyond end of file";
						}
					}
					else
					{
						chunk = reader.readChunk(file, 0, size);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							return "Error: File is empty";
						}
					}

					return chunk.toFormattedString();
				}

				return resolver.buildNotFoundMessage(pathOrName);
			}

			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error reading code chunk: " + pathOrName, e);
			return "Error: " + e.getMessage();
		}
	}
}
