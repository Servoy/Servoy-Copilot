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

package com.servoy.eclipse.servoypilot.tools;

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.CodeChunkReader;
import com.servoy.eclipse.servoypilot.services.CodeChunkReader.ChunkSize;
import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.FileStructureService;
import com.servoy.eclipse.servoypilot.services.dto.CodeChunk;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Provides AI tools for code analysis operations.
 * These tools allow the language model to inspect source code structure, read code chunks, and resolve types.
 * 
 * @author emera
 */
public class CodeAnalysisTools
{
	@Tool("Analyze file structure and extract all symbols with JSDoc status (FAST - uses DLTK caching). " +
		"Accepts form names (e.g., 'testCustomers'), scope names (e.g., 'utils'), or full paths.")
	public String analyzeFileStructure(
		@P("File path, form name, or scope name (e.g., 'testCustomers', 'utils', '/ProjectName/forms/customers/customers.js')") String pathOrName)
	{
		System.out.println("[analyzeFileStructure] ===== TOOL CALLED ===== pathOrName=" + pathOrName);
		try
		{
			if (pathOrName != null && !pathOrName.isBlank())
			{
				FilePathResolver resolver = FilePathResolver.getInstance();
				IFile file = resolver.resolveFile(pathOrName);

				if (file != null && file.exists())
				{
					System.out.println("[analyzeFileStructure] Resolved → " + file.getFullPath());
					FileStructureService service = FileStructureService.getInstance();
					FileStructure structure = service.analyzeFile(file);
					String result = structure.toFormattedString();
					System.out.println("[analyzeFileStructure] Result:\n" + result);
					return result;
				}

				String notFound = resolver.buildNotFoundMessage(pathOrName);
				System.out.println("[analyzeFileStructure] File not found: " + notFound);
				return notFound;
			}

			System.out.println("[analyzeFileStructure] Error: pathOrName is null or blank");
			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			System.out.println("[analyzeFileStructure] Exception: " + e.getMessage());
			ServoyLog.logError("Error analyzing file structure: " + pathOrName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Read code chunk from file with configurable chunk size. " +
		"Supports three modes: TARGETED (jump to symbol), DIRECT (start from line), SEQUENTIAL (read by chunk number). " +
		"Accepts form names, scope names, or full paths. " +
		"Choose chunk size based on what you need: " +
		"SMALL (50 lines) for tight focus on one symbol; " +
		"MEDIUM (100 lines) for a single function with context; " +
		"LARGE (200 lines) for broad view or entire small files.")
	public String getCodeChunk(
		@P("File path, form name, or scope name") String pathOrName,
		@P("Symbol name to find (optional - for TARGETED mode)") String symbolName,
		@P("Chunk number for sequential reading (0-based, optional - for SEQUENTIAL mode)") Integer chunkNumber,
		@P("Start line number (0-based, optional - for DIRECT mode)") Integer startLine,
		@P("Chunk size: SMALL (50 lines), MEDIUM (100 lines), LARGE (200 lines). Default: LARGE. " +
			"Use SMALL when reading a single known symbol; MEDIUM for a function with surrounding context; LARGE for full-file exploration.") String chunkSize)
	{
		System.out.println("[getCodeChunk] ===== TOOL CALLED ===== pathOrName=" + pathOrName +
			", symbolName=" + symbolName + ", chunkNumber=" + chunkNumber +
			", startLine=" + startLine + ", chunkSize=" + chunkSize);
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

					// MODE 1: TARGETED - Jump to specific symbol
					if (symbolName != null && !symbolName.isBlank())
					{
						System.out.println("[getCodeChunk] Mode: TARGETED, symbol=" + symbolName);
						chunk = reader.readSymbol(file, symbolName, size);
						if (chunk == null)
						{
							System.out.println("[getCodeChunk] Symbol not found: " + symbolName);
							return "Error: Symbol '" + symbolName + "' not found in file";
						}
					}
					// MODE 2: DIRECT - Start from specific line
					else if (startLine != null && startLine >= 0)
					{
						System.out.println("[getCodeChunk] Mode: DIRECT, startLine=" + startLine);
						chunk = reader.readFromLine(file, startLine, size);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							System.out.println("[getCodeChunk] Start line beyond EOF: " + startLine);
							return "Error: Start line " + startLine + " is beyond end of file";
						}
					}
					// MODE 3: SEQUENTIAL - Read by chunk number
					else
					{
						int chunkNum = (chunkNumber != null) ? chunkNumber : 0;
						System.out.println("[getCodeChunk] Mode: SEQUENTIAL, chunkNum=" + chunkNum);
						chunk = reader.readChunk(file, chunkNum, size);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							System.out.println("[getCodeChunk] Chunk beyond EOF: " + chunkNum);
							return "Error: Chunk " + chunkNum + " is beyond end of file";
						}
					}

					System.out.println("[getCodeChunk] Returning chunk: startLine=" + chunk.getStartLine() +
						", endLine=" + chunk.getEndLine() + ", totalChunks=" + chunk.getTotalChunks());
					return chunk.toFormattedString();
				}

				String notFound = resolver.buildNotFoundMessage(pathOrName);
				System.out.println("[getCodeChunk] File not found: " + notFound);
				return notFound;
			}

			System.out.println("[getCodeChunk] Error: pathOrName is null or blank");
			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			System.out.println("[getCodeChunk] Exception: " + e.getMessage());
			ServoyLog.logError("Error reading code chunk: " + pathOrName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Resolve the type of an identifier in a JavaScript file. " +
		"Returns concise type information (not full code context). " +
		"Accepts form names, scope names, or full file paths.")
	public String resolveIdentifierType(
		@P("Identifier name to resolve (e.g., 'foundset', 'fs', 'record', 'customerName')") String identifier,
		@P("File path, form name, or scope name (e.g., 'myForm', 'utils', 'forms/myForm.js')") String pathOrName)
	{
		System.out.println("[resolveIdentifierType] ===== TOOL CALLED ===== identifier=" + identifier + ", pathOrName=" + pathOrName);
		if (identifier != null && !identifier.isBlank() && pathOrName != null && !pathOrName.isBlank())
		{
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(pathOrName);

			if (file != null && file.exists())
			{
				String result = CodeContextService.getInstance().resolveIdentifierType(identifier, file);
				System.out.println("[resolveIdentifierType] Result: " + result);
				return result;
			}

			String notFound = resolver.buildNotFoundMessage(pathOrName);
			System.out.println("[resolveIdentifierType] File not found: " + notFound);
			return notFound;
		}

		System.out.println("[resolveIdentifierType] Error: identifier or pathOrName is null/blank");
		return "Error: Identifier and file path are required";
	}
}
