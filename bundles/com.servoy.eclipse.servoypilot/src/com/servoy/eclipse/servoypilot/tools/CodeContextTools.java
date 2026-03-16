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
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.codeassist.ISelectionRequestor;
import org.eclipse.dltk.compiler.env.IModuleSource;
import org.eclipse.dltk.compiler.env.ModuleSource;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.dltk.javascript.ast.FunctionStatement;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.dltk.javascript.internal.core.codeassist.JavaScriptSelectionEngine2;
import org.eclipse.dltk.javascript.typeinfo.IRElement;
import org.eclipse.dltk.javascript.typeinfo.IRMember;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.ParserService;

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
		String context = getContext(problemStatement, document, lineNumber - 1);
		SelectionResult selectedElements = getModelElements(filePath, lineNumber - 1, characterOffset);
		for (IModelElement element : selectedElements.modelElements)
		{
			context += "\n\n/* Model Element: " + element.toString() + " */";
		}
		for (IRElement element : selectedElements.foreignElements)
		{
			context += "\n\n/* Typeinfo Element: " + element.getName() + " */"; //TODO check what other info is relevant
			if (element instanceof IRMember member)
			{
				context += "\n/*   Declaring type: " + member.getDeclaringType().getName() + " */";
			}
		}
		return context;
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
							acceptForeignElement(element);
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

	private String getContext(Statement statement, IDocument document, int lineNumber)
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

	public String surroundingLines(int startLine, int endLine, String surroundingLines, int errorLine)
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
		return prompt.toString();
	}
}