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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.codeassist.ISelectionRequestor;
import org.eclipse.dltk.compiler.env.IModuleSource;
import org.eclipse.dltk.compiler.env.ModuleSource;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.ILocalVariable;
import org.eclipse.dltk.core.IMethod;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.dltk.core.ModelException;
import org.eclipse.dltk.internal.core.SourceRefElement;
import org.eclipse.dltk.javascript.ast.FunctionStatement;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.dltk.javascript.internal.core.codeassist.JavaScriptSelectionEngine2;
import org.eclipse.dltk.javascript.typeinfo.IRElement;
import org.eclipse.dltk.javascript.typeinfo.IRMember;
import org.eclipse.dltk.javascript.typeinfo.IRMethod;
import org.eclipse.dltk.javascript.typeinfo.model.Element;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.model.repository.SolutionSerializer;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.CodeChunkReader;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.FileStructureService;
import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.eclipse.servoypilot.services.dto.CodeChunk;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.util.PersistHelper;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Provides AI tools that allow the language model to inspect source code within the workspace.
 * @author emera
 */
public class CodeContextTools
{
	// configurable limits
	private static final int CONTEXT_LINES_AROUND_ERROR = 10;
	private static final int MAX_FULL_FUNCTION_LINES = 40;

	@Tool("Analyze file structure and extract all symbols with JSDoc status (FAST - uses DLTK caching). " +
		"Accepts form names (e.g., 'testCustomers'), scope names (e.g., 'utils'), or full paths.")
	public String analyzeFileStructure(
		@P("File path, form name, or scope name (e.g., 'testCustomers', 'utils', '/ProjectName/forms/customers/customers.js')") String pathOrName)
	{
		System.out.println("\n=== CodeContextTools.analyzeFileStructure() called ===");
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
					System.out.println("=== End CodeContextTools.analyzeFileStructure() ===\n");
					return result;
				}

				// File not found - provide helpful message
				String errorMsg = resolver.buildNotFoundMessage(pathOrName);
				System.out.println("File NOT resolved - returning error message");
				System.out.println("=== End CodeContextTools.analyzeFileStructure() ===\n");
				return errorMsg;
			}

			System.out.println("Error: Empty file path provided");
			System.out.println("=== End CodeContextTools.analyzeFileStructure() ===\n");
			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error analyzing file structure: " + pathOrName, e);
			System.out.println("EXCEPTION occurred: " + e.getMessage());
			System.out.println("=== End CodeContextTools.analyzeFileStructure() ===\n");
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
		System.out.println("\n=== CodeContextTools.getCodeChunk() called ===");
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
							System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
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
							System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
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
							System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
							return error;
						}
					}

					// Return formatted output
					String result = chunk.toFormattedString();
					System.out.println("Read complete - returning lines " + chunk.getStartLine() + "-" + chunk.getEndLine());
					System.out.println("\n--- CODE CHUNK RESULT (returned to AI) ---");
					System.out.println(result);
					System.out.println("--- END CODE CHUNK RESULT ---\n");
					System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
					return result;
				}

				// File not found - provide helpful message
				String errorMsg = resolver.buildNotFoundMessage(pathOrName);
				System.out.println("File NOT resolved - returning error message");
				System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
				return errorMsg;
			}

			System.out.println("Error: Empty file path provided");
			System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error reading code chunk: " + pathOrName, e);
			System.out.println("EXCEPTION occurred: " + e.getMessage());
			System.out.println("=== End CodeContextTools.getCodeChunk() ===\n");
			return "Error: " + e.getMessage();
		}
	}

	@Tool("Resolve the type of an identifier in a JavaScript file. " +
		"Returns concise type information (not full code context). " +
		"Accepts form names, scope names, or full file paths.")
	public String resolveIdentifierType(
		@P("Identifier name to resolve (e.g., 'foundset', 'fs', 'record', 'customerName')") String identifier,
		@P("File path, form name, or scope name (e.g., 'myForm', 'utils', 'forms/myForm.js')") String pathOrName) throws Exception
	{
		System.out.println("\n=== CodeContextTools.resolveIdentifierType() called ===");
		System.out.println("Input: identifier='" + identifier + "', pathOrName='" + pathOrName + "'");

		if (identifier != null && !identifier.isBlank() && pathOrName != null && !pathOrName.isBlank())
		{
			// Use FilePathResolver for intelligent file resolution
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(pathOrName);

			if (file != null && file.exists())
			{
				System.out.println("File resolved successfully: " + file.getFullPath());
				String filePath = file.getFullPath().toString();

				// Read file content and create document
				String fileContent = readWorkspaceFile(filePath);
				IDocument document = new Document(fileContent);

				// Find identifier offset in source
				int offset = findIdentifierOffset(fileContent, identifier);
				if (offset != -1)
				{
					// Calculate line number from offset (0-based)
					int lineNumber = document.getLineOfOffset(offset);
					System.out.println("Found identifier at offset " + offset + ", line " + (lineNumber + 1));

					// Get model elements directly (standalone - not via codeContext)
					SelectionResult selectedElements = getModelElements(filePath, lineNumber, offset);

					if (selectedElements != null)
					{
			// Format type information (focused output, not full context)
			String result = formatTypeInfo(selectedElements, identifier, filePath, lineNumber + 1, fileContent, offset);
						System.out.println("\n--- TYPE RESOLUTION RESULT (returned to AI) ---");
						System.out.println(result);
						System.out.println("--- END TYPE RESOLUTION RESULT ---\n");
						System.out.println("=== End CodeContextTools.resolveIdentifierType() ===\n");
						return result;
					}

					String error = "Error: No type information available for identifier '" + identifier + "'";
					System.out.println(error);
					System.out.println("=== End CodeContextTools.resolveIdentifierType() ===\n");
					return error;
				}

				String error = "Error: Identifier '" + identifier + "' not found in file: " + pathOrName;
				System.out.println(error);
				System.out.println("=== End CodeContextTools.resolveIdentifierType() ===\n");
				return error;
			}

			String errorMsg = resolver.buildNotFoundMessage(pathOrName);
			System.out.println("File NOT resolved - returning error message");
			System.out.println("=== End CodeContextTools.resolveIdentifierType() ===\n");
			return errorMsg;
		}

		System.out.println("Error: Missing required parameters");
		System.out.println("=== End CodeContextTools.resolveIdentifierType() ===\n");
		return "Error: Identifier and file path are required";
	}

	/**
	 * Format focused type information from SelectionResult.
	 * Returns concise type details, not full code context.
	 */
	private String formatTypeInfo(SelectionResult selectedElements, String identifier, String filePath, int lineNumber, 
		String fileContent, int offset)
	{
		StringBuilder result = new StringBuilder();
		result.append("=== TYPE RESOLUTION ===\n\n");
		result.append("IDENTIFIER: ").append(identifier).append("\n");

		// Extract from model elements (LocalVariable, etc.)
		for (IModelElement element : selectedElements.modelElements)
		{
			if (element.getElementName().equals(identifier))
			{
				if (element instanceof ILocalVariable localVariable)
				{
					String type = localVariable.getType();
					if (type != null && !type.isBlank())
					{
						result.append("TYPE: ").append(type).append("\n");
						result.append("SOURCE: Local variable\n");
						result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
						return result.toString();
					}
				}
				
				if (element instanceof IMethod method)
				{
					result.append("TYPE: Function\n");
					result.append("SOURCE: Method declaration\n");
					result.append("PARAMETERS: (");
					try
					{
						result.append(Arrays.stream(method.getParameters())
							.map(p -> p.getName() + ":" + p.getType())
							.collect(Collectors.joining(", ")));
					}
					catch (Exception e)
					{
						result.append("unknown");
					}
					result.append(")\n");
					result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
					return result.toString();
				}

				// Other model element types - try JSDoc fallback before returning
				System.out.println("  Model element found but no type extracted, trying JSDoc fallback...");
				String jsDocType = extractJSDocType(fileContent, offset);
				if (jsDocType != null)
				{
					result.append("TYPE: ").append(jsDocType).append("\n");
					result.append("SOURCE: JSDoc @type annotation\n");
					result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
					return result.toString();
				}

				result.append("TYPE: ").append(element.getClass().getSimpleName()).append("\n");
				result.append("SOURCE: Model element\n");
				result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return result.toString();
			}
		}

		// Extract from foreign elements (Servoy API types)
		for (IRElement element : selectedElements.foreignElements)
		{
			String type = element.getName();
			if (type != null && !type.isBlank())
			{
				result.append("TYPE: ").append(type).append("\n");
				result.append("SOURCE: Servoy API type\n");
				
				if (element instanceof IRMethod method)
				{
					result.append("PARAMETERS: (");
					result.append(method.getParameters().stream()
						.map(p -> p.getName() + ":" + p.getType())
						.collect(Collectors.joining(", ")));
					result.append(")\n");
				}
				
				result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return result.toString();
			}
		}

		// Fallback: Try to extract type from JSDoc @type annotation
		System.out.println("  No type found in model/foreign elements, trying JSDoc fallback...");
		String jsDocType = extractJSDocType(fileContent, offset);
		if (jsDocType != null)
		{
			result.append("TYPE: ").append(jsDocType).append("\n");
			result.append("SOURCE: JSDoc @type annotation\n");
			result.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
			return result.toString();
		}

		// No type information found - return error message
		System.out.println("  No type information found - returning error");
		return "Error: Could not resolve type for identifier '" + identifier + "' in file: " + filePath + " at line " + lineNumber;
	}

	/**
	 * Extract type from JSDoc @type annotation in preceding text.
	 * Looks backwards from offset to find @type {TypeName} pattern.
	 * IMPORTANT: Verifies that @type belongs to THIS identifier, not a previous one.
	 */
	private String extractJSDocType(String fileContent, int offset)
	{
		if (offset <= 0 || fileContent == null)
		{
			return null;
		}

		// Look back ~300 characters for JSDoc comment
		int lookbackStart = Math.max(0, offset - 300);
		String precedingText = fileContent.substring(lookbackStart, offset);
		
		System.out.println("  Searching for JSDoc @type in preceding text");
		System.out.println("  Preceding text: " + precedingText.replace("\n", "\\n").substring(Math.max(0, precedingText.length() - 100)));
		
		// Find the last /** before our identifier (start of JSDoc)
		int jsDocStart = precedingText.lastIndexOf("/**");
		if (jsDocStart == -1)
		{
			System.out.println("  No JSDoc comment (/**) found before identifier");
			return null;
		}
		
		System.out.println("  Found /** at relative position: " + jsDocStart);
		
		// Get text from /** to our identifier
		String jsDocBlock = precedingText.substring(jsDocStart);
		System.out.println("  JSDoc block: " + jsDocBlock.replace("\n", "\\n"));
		
		// Check if there's another variable declaration between the JSDoc and our identifier
		// Pattern: var someOtherVar (would mean the JSDoc is for that, not ours)
		Pattern varPattern = Pattern.compile("\\*/\\s*\\n\\s*var\\s+\\w+");
		Matcher varMatcher = varPattern.matcher(jsDocBlock);
		if (varMatcher.find())
		{
			System.out.println("  Found another var declaration between JSDoc and our identifier - JSDoc belongs to different variable");
			return null;
		}
		
		// Look for @type {TypeName} pattern in the JSDoc block
		Pattern jsDocPattern = Pattern.compile("@type\\s*\\{([^}]+)\\}");
		Matcher jsDocMatcher = jsDocPattern.matcher(jsDocBlock);
		if (jsDocMatcher.find())
		{
			String type = jsDocMatcher.group(1).trim();
			System.out.println("  Found JSDoc @type: " + type);
			return type;
		}
		
		System.out.println("  No @type found in JSDoc block");
		return null;
	}

	/**
	 * Find offset of identifier in source code.
	 * Tries multiple strategies: declaration, usage, then fallback to first occurrence.
	 */
	private int findIdentifierOffset(String source, String identifier)
	{
		System.out.println("  === findIdentifierOffset() called ===");
		System.out.println("  Identifier: '" + identifier + "'");
		System.out.println("  Source length: " + source.length());
		
		// Strategy 1: Find in variable declaration: var identifier = ...
		String pattern1Str = "\\bvar\\s+(" + java.util.regex.Pattern.quote(identifier) + ")\\b";
		System.out.println("  Strategy 1 - Pattern: " + pattern1Str);
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(pattern1Str);
		java.util.regex.Matcher matcher = pattern.matcher(source);
		if (matcher.find())
		{
			int offset = matcher.start(1);
			System.out.println("  Strategy 1 SUCCESS - Found at offset: " + offset);
			System.out.println("  Context: ..." + source.substring(Math.max(0, offset - 10), Math.min(source.length(), offset + 20)) + "...");
			return offset;
		}
		System.out.println("  Strategy 1 FAILED - No match for var declaration");

		// Strategy 2: Find in usage: identifier.something or identifier(
		String pattern2Str = "\\b(" + java.util.regex.Pattern.quote(identifier) + ")\\s*[.({]";
		System.out.println("  Strategy 2 - Pattern: " + pattern2Str);
		pattern = java.util.regex.Pattern.compile(pattern2Str);
		matcher = pattern.matcher(source);
		if (matcher.find())
		{
			int offset = matcher.start(1);
			System.out.println("  Strategy 2 SUCCESS - Found at offset: " + offset);
			System.out.println("  Context: ..." + source.substring(Math.max(0, offset - 10), Math.min(source.length(), offset + 20)) + "...");
			return offset;
		}
		System.out.println("  Strategy 2 FAILED - No match for usage pattern");

		// Strategy 3: Fallback - just find first occurrence with word boundary check
		System.out.println("  Strategy 3 - Using indexOf()");
		int index = source.indexOf(identifier);
		System.out.println("  indexOf('" + identifier + "') returned: " + index);
		if (index >= 0)
		{
			boolean beforeCheck = (index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1)));
			boolean afterCheck = (index + identifier.length() >= source.length() || !Character.isJavaIdentifierPart(source.charAt(index + identifier.length())));
			System.out.println("  Before boundary check: " + beforeCheck);
			System.out.println("  After boundary check: " + afterCheck);
			
			if (beforeCheck && afterCheck)
			{
				System.out.println("  Strategy 3 SUCCESS - Accepted offset: " + index);
				System.out.println("  Context: ..." + source.substring(Math.max(0, index - 10), Math.min(source.length(), index + 20)) + "...");
				return index;
			}
			System.out.println("  Strategy 3 FAILED - Word boundary check failed");
		}
		System.out.println("  Strategy 3 FAILED - indexOf returned -1");

		System.out.println("  === All strategies failed - returning -1 ===");
		return -1;
	}

	@Tool("""
		Returns code context around a given line in a Servoy JavaScript file.

		If the surrounding function is small, the full function is returned.
		If the function is large, only lines around the error are returned.

		Use this when you need to inspect the surrounding code.
		""")
	public String codeContext(
		@P(value = "File path relative to workspace or project (e.g., 'forms/myForm.js' or 'projectName/forms/myForm.js')", required = true) String filePath,
		@P("The line number provided in the Context section. Do not guess this value.") int lineNumber,
		@P("The EXACT CharacterOffset provided in the Context section. Do not guess this value.") int characterOffset) throws Exception
	{
		String fileContent = readWorkspaceFile(filePath);
		IDocument document = new Document(fileContent);
		Statement problemStatement = ParserService.getInstance().getStatementAtOffset(document.get(), characterOffset);
		if (problemStatement == null)
		{
			throw new RuntimeException("The problem statement was not found in the provided document.");
		}
		StringBuilder context = getContext(problemStatement, document, lineNumber - 1);

		SelectionResult selectedElements = getModelElements(filePath, lineNumber - 1, characterOffset);
		processModelElements(filePath, context, selectedElements);
		processForeignElements(context, selectedElements);

		return context.toString();
	}
	
		public void processForeignElements(StringBuilder context, SelectionResult selectedElements)
		{
			for (IRElement element : selectedElements.foreignElements)
			{
				context.append("\n\n/* Typeinfo Element: " + element.getName() + " */");
				//TODO check what other info is relevant
				if (element.getSource() instanceof Element elementSource)
				{
					Object resource = elementSource.getAttribute(TypeCreator.RESOURCE);
					if (resource == null)
					{
						resource = elementSource.getAttribute(TypeCreator.LAZY_VALUECOLLECTION);
					}

					if (resource instanceof Form frm)
					{
						if (frm.getExtendsID() != null)
						{
							IPersist superForm = PersistHelper.getSuperPersist(frm);
							if (superForm != null)
							{
								context.append("\n/*   You may want to check the parent form for more context: " +
									SolutionSerializer.getScriptPath(superForm, false) + " */");
							}
						}
						resource = SolutionSerializer.getScriptPath(frm, false);
					}

					if (resource instanceof String resourcePath)
					{
						IPath path = Path.fromPortableString(resourcePath.replace('\\', '/'));
						IFile sourceFile;
						if (path.isAbsolute())
						{
							sourceFile = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
						}
						else
						{
							sourceFile = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
						}
						if (sourceFile != null && sourceFile.exists())
						{
							resource = sourceFile;
						}
					}

					if (resource instanceof IFile file)
					{
						context.append("\n/*   Use the file if you need more info: " + file.getProjectRelativePath() + " */");
					}
				}
				if (element instanceof IRMember member)
				{
					if (element instanceof IRMethod method)
					{
						context.append("\n/*   Method parameters: (");
						context.append(method.getParameters().stream()
							.map(p -> p.getName() + ":" + p.getType())
							.collect(Collectors.joining(", ")));
						context.append(") */");
					}
					context.append("\n/*   Declaring type: " + member.getDeclaringType().getName() + " */");
				}
			}
		}

	public void processModelElements(String filePath, StringBuilder context, SelectionResult selectedElements)
		throws ModelException, Exception, BadLocationException
	{
		for (IModelElement element : selectedElements.modelElements)
		{
			context.append("\n\n/* If needed, you can get more info about the Model Element: '")
				.append(element.getElementName()).append("'");
			if (element instanceof ILocalVariable localVariable)
			{
				context.append(" of type: '" + localVariable.getType() + "', ");
			}
			if (element instanceof IMethod method)
			{
				context.append(" which is a method with parameters: (");
				context.append(Arrays.stream(method.getParameters())
					.map(p -> p.getName() + ":" + p.getType())
					.collect(Collectors.joining(", ")));
				context.append("), ");
			}
			if (filePath != null && !filePath.replace("L/", "/").equals(element.getPath().toString()))
			{
				context.append(" in this file: ")
					.append(element.getPath());
				if (element instanceof SourceRefElement sourceRefElement)
				{
					int offset = sourceRefElement.getSourceRange().getOffset();
					//TODO check, do we always need to provide the line number?
					String content = readWorkspaceFile(element.getPath().toString());
					IDocument doc = new Document(content);
					int line = doc.getLineOfOffset(offset);
					if (line >= 0)
					{
						context.append(" LineNumber : ").append(line + 1);
					}
					context.append(", offset: ").append(offset);
				}
			}
			context.append(" */");
		}
	}


	class SelectionResult
	{
		public List<IModelElement> modelElements = new ArrayList<>();
		public List<IRElement> foreignElements = new ArrayList<>();
	}

	public SelectionResult getModelElements(String filePath, int lineNumber, int characterOffset) throws Exception
	{
		try
		{
			IFile file = getFile(filePath);
			if (file == null || !file.exists())
			{
				return null;
			}

			String fileContent = readWorkspaceFile(filePath);
			if (fileContent == null)
			{
				return null;
			}
			ISourceModule sourceModule = (ISourceModule)DLTKCore.create(file);
			IModuleSource module = new ModuleSource(filePath, sourceModule, fileContent);
			JavaScriptSelectionEngine2 selectionEngine = new JavaScriptSelectionEngine2();
			int offset = ParserService.getInstance().skipWhitespaceForward(fileContent, characterOffset);
			SelectionResult selectedElements = new SelectionResult();
			Thread thread = new Thread(() -> {
				try
				{
					selectionEngine.setRequestor(new ISelectionRequestor()
					{
						@Override
						public void acceptModelElement(IModelElement element)
						{
							if (element != null)
							{
								selectedElements.modelElements.add(element);
							}
						}

						@Override
						public void acceptForeignElement(Object element)
						{
							if (element instanceof IRElement ire)
							{
								selectedElements.foreignElements.add(ire);
							}
						}

						@Override
						public void acceptElement(Object element, ISourceRange range)
						{
							if (element instanceof IModelElement modelElement)
							{
								acceptModelElement(modelElement);
							}
							else
							{
								acceptForeignElement(element);
							}
						}
					});

					selectionEngine.select(module, offset, offset);
				}
				catch (Exception e)
				{
					ServoyLog.logError("Error selecting model elements: " + e.getMessage(), e);
				}
			}, "Searching model elements -" + file.getName());

			thread.start();
			thread.join();
			return selectedElements;
		}
		catch (Exception e)
		{
			throw new RuntimeException("Error computing model elements: " + e.getMessage(), e);
		}
	}

	public IFile getFile(String filePath)
	{
		if (filePath.startsWith("L/"))
		{
			filePath = filePath.substring(2);
		}

		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));

		if (!file.exists())
		{
			throw new RuntimeException("File not found: " + filePath);
		}
		return file;
	}


	public String readWorkspaceFile(String filePath) throws Exception
	{
		if (filePath.startsWith("L/"))
		{
			filePath = filePath.substring(2);
		}

		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));

		if (!file.exists())
		{
			throw new RuntimeException("File not found: " + filePath);
		}

		try (InputStream is = file.getContents())
		{
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private StringBuilder getContext(Statement statement, IDocument document, int lineNumber)
		throws BadLocationException
	{
		FunctionStatement parentFunction = ParserService.getInstance().getParentFunction(statement);

		if (parentFunction == null)
		{
			int totalLines = document.getNumberOfLines();

			int startLine = Math.max(0, lineNumber - CONTEXT_LINES_AROUND_ERROR);
			int endLine = Math.min(totalLines - 1, lineNumber + CONTEXT_LINES_AROUND_ERROR);

			int startOffset = document.getLineOffset(startLine);
			int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);

			String code = document.get(startOffset, endOffset - startOffset);
			return surroundingLines(startLine, endLine, code, lineNumber);
		}
		else
		{
			int functionStart = parentFunction.sourceStart();
			int functionEnd = parentFunction.sourceEnd();

			int startLine = document.getLineOfOffset(functionStart);
			int endLine = document.getLineOfOffset(functionEnd - 1);

			int functionLineCount = endLine - startLine + 1;

			if (functionLineCount <= MAX_FULL_FUNCTION_LINES)
			{
				int startOffset = document.getLineOffset(startLine);
				int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);
				String code = document.get(startOffset, endOffset - startOffset);
				return surroundingLines(startLine, endLine, code, lineNumber);
			}
			else
			{
				startLine = Math.max(startLine, lineNumber - CONTEXT_LINES_AROUND_ERROR);
				endLine = Math.min(endLine, lineNumber + CONTEXT_LINES_AROUND_ERROR);

				int startOffset = document.getLineOffset(startLine);
				int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);

				String code = document.get(startOffset, endOffset - startOffset);
				return surroundingLines(startLine, endLine, code, lineNumber);
			}
		}
	}

	public StringBuilder surroundingLines(int startLine, int endLine, String surroundingLines, int errorLine)
	{
		StringBuilder prompt = new StringBuilder();
		prompt.append("```javascript\n");
		String[] lines = surroundingLines.split("\n");
		for (int i = 0; i < lines.length; i++)
		{
			int line = startLine + i + 1;

			if (line == errorLine + 1)
			{
				prompt.append(String.format("%4d▶ %s\n", line, lines[i]));
			}
			else
			{
				prompt.append(String.format("%4d  %s\n", line, lines[i]));
			}
		}
		prompt.append("```");
		return prompt;
	}
}
