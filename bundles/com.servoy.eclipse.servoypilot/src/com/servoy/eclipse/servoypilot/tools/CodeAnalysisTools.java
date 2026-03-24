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
		try
		{
			if (pathOrName != null && !pathOrName.isBlank())
			{
				FilePathResolver resolver = FilePathResolver.getInstance();
				IFile file = resolver.resolveFile(pathOrName);

				if (file != null && file.exists())
				{
					FileStructureService service = FileStructureService.getInstance();
					FileStructure structure = service.analyzeFile(file);
					return structure.toFormattedString();
				}

				return resolver.buildNotFoundMessage(pathOrName);
			}

			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error analyzing file structure: " + pathOrName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Read code chunk from file (max 200 lines per chunk). " +
		"Supports three modes: TARGETED (jump to symbol), DIRECT (start from line), SEQUENTIAL (read by chunk number). " +
		"Accepts form names, scope names, or full paths.")
	public String getCodeChunk(
		@P("File path, form name, or scope name") String pathOrName,
		@P("Symbol name to find (optional - for TARGETED mode)") String symbolName,
		@P("Chunk number for sequential reading (0-based, optional - for SEQUENTIAL mode)") Integer chunkNumber,
		@P("Start line number (0-based, optional - for DIRECT mode)") Integer startLine)
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
					CodeChunk chunk = null;

					// MODE 1: TARGETED - Jump to specific symbol
					if (symbolName != null && !symbolName.isBlank())
					{
						chunk = reader.readSymbol(file, symbolName);
						if (chunk == null)
						{
							return "Error: Symbol '" + symbolName + "' not found in file";
						}
					}
					// MODE 2: DIRECT - Start from specific line
					else if (startLine != null && startLine >= 0)
					{
						chunk = reader.readFromLine(file, startLine);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							return "Error: Start line " + startLine + " is beyond end of file";
						}
					}
					// MODE 3: SEQUENTIAL - Read by chunk number
					else
					{
						int chunkNum = (chunkNumber != null) ? chunkNumber : 0;
						chunk = reader.readChunk(file, chunkNum);
						if (chunk == null || chunk.getContent().isEmpty())
						{
							return "Error: Chunk " + chunkNum + " is beyond end of file";
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

	@Tool("Resolve the type of an identifier in a JavaScript file. " +
		"Returns concise type information (not full code context). " +
		"Accepts form names, scope names, or full file paths.")
	public String resolveIdentifierType(
		@P("Identifier name to resolve (e.g., 'foundset', 'fs', 'record', 'customerName')") String identifier,
		@P("File path, form name, or scope name (e.g., 'myForm', 'utils', 'forms/myForm.js')") String pathOrName)
	{
		if (identifier != null && !identifier.isBlank() && pathOrName != null && !pathOrName.isBlank())
		{
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(pathOrName);

			if (file != null && file.exists())
			{
				return CodeContextService.getInstance().resolveIdentifierType(identifier, file);
			}

			return resolver.buildNotFoundMessage(pathOrName);
		}

		return "Error: Identifier and file path are required";
	}
}
