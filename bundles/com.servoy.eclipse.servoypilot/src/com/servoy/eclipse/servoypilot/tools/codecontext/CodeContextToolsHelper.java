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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.javascript.ast.FunctionStatement;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.services.CodeContextService.SelectionResult;
import com.servoy.eclipse.servoypilot.services.ParserService;

/**
 * Singleton helper providing all implementation logic for QuickFix tool interfaces
 * ({@link ICodeContextTool}, {@link IReadPersistFileTool}).
 */
public class CodeContextToolsHelper
{
	public static final int CONTEXT_LINES_AROUND_ERROR = 10;
	public static final int MAX_FULL_FUNCTION_LINES = 40;

	private static final CodeContextToolsHelper INSTANCE = new CodeContextToolsHelper();

	private CodeContextToolsHelper()
	{
	}

	public static CodeContextToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public String codeContextImpl(String filePath, int lineNumber, int characterOffset) throws Exception
	{
		String fileContent = readWorkspaceFile(filePath);
		if (filePath.endsWith(".js"))
		{
			IDocument document = new Document(fileContent);
			Statement problemStatement = ParserService.getInstance().getStatementAtOffset(document.get(), characterOffset);
			if (problemStatement == null)
			{
				throw new RuntimeException("The problem statement was not found in the provided document.");
			}
			StringBuilder codeContext = getContext(problemStatement, document, lineNumber - 1);

			SelectionResult selectedElements = CodeContextService.getInstance().getModelElements(filePath, characterOffset);
			if (selectedElements.modelElements.isEmpty() && selectedElements.foreignElements.isEmpty())
			{
				selectedElements = CodeContextService.getInstance().getModelElements(filePath, problemStatement.sourceStart());
			}

			String resolvedJson = ResolvedElementsProcessor.getInstance().toJson(filePath, selectedElements);

			StringBuilder output = new StringBuilder();
			output.append(codeContext);
			output.append("\n\n");
			output.append(resolvedJson);

			System.out.println("\n--- CODE CONTEXT RESULT (returned to AI) ---" + output.toString());
			return output.toString();
		}
		throw new RuntimeException("Unsupported file type for code context. Only .js files are supported.");
	}

	public String resolveElementsImpl(String filePath, int characterOffset) throws Exception
	{
		SelectionResult selectedElements = CodeContextService.getInstance().getModelElements(filePath, characterOffset);
		return ResolvedElementsProcessor.getInstance().toJson(filePath, selectedElements);
	}

	public String readPersistFileImpl(String filePath) throws Exception
	{
		if (filePath.endsWith(".rel") || filePath.endsWith(".val") || filePath.endsWith(".dbi"))
		{
			String content = readWorkspaceFile(filePath);
			System.out.println("\n--- FILE CONTENT RESULT (returned to AI) ---" + content);
			return content;
		}
		return null;
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

	private StringBuilder getContext(Statement statement, IDocument document, int lineNumber) throws BadLocationException
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

		startLine = Math.max(startLine, lineNumber - CONTEXT_LINES_AROUND_ERROR);
		endLine = Math.min(endLine, lineNumber + CONTEXT_LINES_AROUND_ERROR);
		int startOffset = document.getLineOffset(startLine);
		int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);
		String code = document.get(startOffset, endOffset - startOffset);
		return surroundingLines(startLine, endLine, code, lineNumber);
	}

	private StringBuilder surroundingLines(int startLine, int endLine, String surroundingLinesText, int errorLine)
	{
		StringBuilder prompt = new StringBuilder();
		prompt.append("```javascript\n");
		String[] lines = surroundingLinesText.split("\n");
		for (int i = 0; i < lines.length; i++)
		{
			int line = startLine + i + 1;
			if (line == errorLine + 1)
			{
				prompt.append(String.format("%4d\u25b6 %s\n", line, lines[i]));
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
