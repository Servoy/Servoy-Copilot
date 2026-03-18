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
package com.servoy.eclipse.servoypilot.tools;

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.CodeChunkReader;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.FileStructureService;
import com.servoy.eclipse.servoypilot.services.dto.CodeChunk;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * AI tools for code analysis (shared across all assistants).
 * 
 * Provides tools to:
 * 1. Analyze file structure and extract symbols
 * 2. Read code adaptively (chunks, targeted, direct)
 * 3. Resolve identifier types using DLTK type inference
 * 
 * These tools are registered globally for ALL assistants (VibeCoding, Documentation, Explain, QuickFix)
 * but only provide analysis capabilities - not documentation operations.
 */
public class CodeAnalysisTools
{
	@Tool("Analyze file structure and extract all symbols with JSDoc status (FAST - uses DLTK caching). " +
		"Accepts form names (e.g., 'testCustomers'), scope names (e.g., 'utils'), or full paths.")
	public String analyzeFileStructure(
		@P("File path, form name, or scope name (e.g., 'testCustomers', 'utils', '/ProjectName/forms/customers/customers.js')") String pathOrName)
	{
		System.out.println("\n=== CodeAnalysisTools.analyzeFileStructure() called ===");
		System.out.println("Input parameter: '" + pathOrName + "'");
		
		try
		{
			if (pathOrName != null && !pathOrName.isBlank())
			{
				// Use FilePathResolver for intelligent file resolution
				FilePathResolver resolver = FilePathResolver.getInstance();
				IFile file = resolver.resolveFile(pathOrName);

				if (file != null && file.exists())
				{
					System.out.println("File resolved successfully: " + file.getFullPath());
					
					// Analyze file structure
					FileStructureService service = FileStructureService.getInstance();
					FileStructure structure = service.analyzeFile(file);

					// Return formatted output
					String result = structure.toFormattedString();
					System.out.println("Analysis complete - returning " + structure.getTotalSymbols() + " symbols");
					System.out.println("\n--- ANALYSIS RESULT (returned to AI) ---");
					System.out.println(result);
					System.out.println("--- END ANALYSIS RESULT ---\n");
					System.out.println("=== End CodeAnalysisTools.analyzeFileStructure() ===\n");
					return result;
				}

				// File not found - provide helpful message
				String errorMsg = resolver.buildNotFoundMessage(pathOrName);
				System.out.println("File NOT resolved - returning error message");
				System.out.println("=== End CodeAnalysisTools.analyzeFileStructure() ===\n");
				return errorMsg;
			}

			System.out.println("Error: Empty file path provided");
			System.out.println("=== End CodeAnalysisTools.analyzeFileStructure() ===\n");
			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error analyzing file structure: " + pathOrName, e);
			System.out.println("EXCEPTION occurred: " + e.getMessage());
			System.out.println("=== End CodeAnalysisTools.analyzeFileStructure() ===\n");
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
		System.out.println("\n=== CodeAnalysisTools.getCodeChunk() called ===");
		System.out.println("Input: pathOrName='" + pathOrName + "', symbolName='" + symbolName +
			"', chunkNumber=" + chunkNumber + ", startLine=" + startLine);

		try
		{
			if (pathOrName != null && !pathOrName.isBlank())
			{
				// Use FilePathResolver for intelligent file resolution
				FilePathResolver resolver = FilePathResolver.getInstance();
				IFile file = resolver.resolveFile(pathOrName);

				if (file != null && file.exists())
				{
					System.out.println("File resolved successfully: " + file.getFullPath());

					CodeChunkReader reader = CodeChunkReader.getInstance();
					CodeChunk chunk = null;

					// MODE 1: TARGETED - Jump to specific symbol
					if (symbolName != null && !symbolName.isBlank())
					{
						System.out.println("Using TARGETED mode: jumping to symbol '" + symbolName + "'");
						chunk = reader.readSymbol(file, symbolName);

						if (chunk == null)
						{
							String error = "Error: Symbol '" + symbolName + "' not found in file";
							System.out.println(error);
							System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
							return error;
						}
					}
					// MODE 2: DIRECT - Start from specific line
					else if (startLine != null && startLine >= 0)
					{
						System.out.println("Using DIRECT mode: starting from line " + startLine);
						chunk = reader.readFromLine(file, startLine);

						if (chunk == null || chunk.getContent().isEmpty())
						{
							String error = "Error: Start line " + startLine + " is beyond end of file";
							System.out.println(error);
							System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
							return error;
						}
					}
					// MODE 3: SEQUENTIAL - Read by chunk number
					else
					{
						int chunkNum = (chunkNumber != null) ? chunkNumber : 0;
						System.out.println("Using SEQUENTIAL mode: reading chunk " + chunkNum);
						chunk = reader.readChunk(file, chunkNum);

						if (chunk == null || chunk.getContent().isEmpty())
						{
							String error = "Error: Chunk " + chunkNum + " is beyond end of file";
							System.out.println(error);
							System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
							return error;
						}
					}

					// Return formatted output
					String result = chunk.toFormattedString();
					System.out.println("Read complete - returning lines " + chunk.getStartLine() + "-" + chunk.getEndLine());
					System.out.println("\n--- CODE CHUNK RESULT (returned to AI) ---");
					System.out.println(result);
					System.out.println("--- END CODE CHUNK RESULT ---\n");
					System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
					return result;
				}

				// File not found - provide helpful message
				String errorMsg = resolver.buildNotFoundMessage(pathOrName);
				System.out.println("File NOT resolved - returning error message");
				System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
				return errorMsg;
			}

			System.out.println("Error: Empty file path provided");
			System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error reading code chunk: " + pathOrName, e);
			System.out.println("EXCEPTION occurred: " + e.getMessage());
			System.out.println("=== End CodeAnalysisTools.getCodeChunk() ===\n");
			return "Error: " + e.getMessage();
		}
	}
}
