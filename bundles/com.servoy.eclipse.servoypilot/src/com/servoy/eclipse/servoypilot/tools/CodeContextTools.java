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
import java.util.Arrays;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.core.ILocalVariable;
import org.eclipse.dltk.core.IMethod;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.ModelException;
import org.eclipse.dltk.internal.core.SourceRefElement;
import org.eclipse.dltk.javascript.ast.FunctionStatement;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.dltk.javascript.typeinfo.IRElement;
import org.eclipse.dltk.javascript.typeinfo.IRMember;
import org.eclipse.dltk.javascript.typeinfo.IRMethod;
import org.eclipse.dltk.javascript.typeinfo.model.Element;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.debug.script.TypeCreator;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyResourcesProject;
import com.servoy.eclipse.model.repository.DataModelManager;
import com.servoy.eclipse.model.repository.SolutionSerializer;
import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.services.CodeContextService.SelectionResult;
import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.util.Pair;
import com.servoy.j2db.util.PersistHelper;
import com.servoy.j2db.util.UUID;
import com.servoy.j2db.util.Utils;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Provides AI tools that allow the language model to inspect source code within the workspace.
 * @author emera
 */
public interface CodeContextTools
{
	// configurable limits
	public static final int CONTEXT_LINES_AROUND_ERROR = 10;
	public static final int MAX_FULL_FUNCTION_LINES = 40;

	@Tool("""
		Returns code context around a given line in a Servoy JavaScript file.

		If the surrounding function is small, the full function is returned.
		If the function is large, only lines around the error are returned.

		Use this when you need to inspect the surrounding code.
		""")
	public default String codeContext(
		@P(value = "File path relative to workspace or project (e.g., 'forms/myForm.js' or 'projectName/forms/myForm.js')", required = true) String filePath,
		@P("The line number provided in the Context section. Do not guess this value.") int lineNumber,
		@P("The EXACT CharacterOffset provided in the Context section. Do not guess this value.") int characterOffset) throws Exception
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
			StringBuilder context = getContext(problemStatement, document, lineNumber - 1);

			SelectionResult selectedElements = CodeContextService.getInstance().getModelElements(filePath, characterOffset);
			if (selectedElements.modelElements.isEmpty() && selectedElements.foreignElements.isEmpty())
			{
				//TODO or we should always call getModelElements for both?
				selectedElements = CodeContextService.getInstance().getModelElements(filePath, problemStatement.sourceStart());
			}
			processModelElements(filePath, context, selectedElements);
			processForeignElements(context, selectedElements);

			System.out.println("\n--- CODE CONTEXT RESULT (returned to AI) ---" + context.toString());
			return context.toString();
		}
		else
		{
			throw new RuntimeException("Unsupported file type for code context. Only .js files are supported.");
		}
	}

	@Tool("""
		Reads the content of a .rel, .val or .dbi file in the workspace. These files are usually json based and not too big, so the full content is returned.

		Use this when you want to get the full content of a relation, valuelist or database information file.
		""")
	public default String readPersistFile(
		@P(value = "File path relative to workspace or project (e.g., 'projectName/relations/<relation_name>.rel' or 'projectName/valuelists/<valuelist_name>.val')", required = true) String filePath)
		throws Exception
	{
		//these files are json based and usually not too big, so we can return the full content
		if (filePath.endsWith(".rel") || filePath.endsWith(".val") || filePath.endsWith(".dbi"))
		{
			String content = readWorkspaceFile(filePath);
			System.out.println("\n--- FILE CONTENT RESULT (returned to AI) ---" + content);
			return content;
		}
		return null;
	}

	private void processForeignElements(StringBuilder context, SelectionResult selectedElements)
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
					resource = extractFormInfo(context, frm);
				}
				else if (resource instanceof ValueList valuelist)
				{
					resource = extractValuelistContext(context, valuelist);
				}
				else if (resource instanceof Relation relation)
				{
					resource = extractRelationContext(context, relation);
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
					context.append("\n/*   Always use the readPersistFile tool to read the file: " + file.getFullPath() + " */");
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
					if (member.getDeclaringType() != null)
					{
						context.append("\n/*   Declaring type: " + member.getDeclaringType().getName() + " */");
					}
				}
			}
		}
	}

	private String extractRelationContext(StringBuilder context, Relation relation)
	{
		context.append("\n/*   Relation (sql joins): " + relation.getName() + " */");
		ServoyResourcesProject activeResourcesProject = ServoyModelFinder.getServoyModel().getActiveResourcesProject();
		String resourceProject = activeResourcesProject.getProject().getName();
		DataModelManager dm = ServoyModelFinder.getServoyModel().getDataModelManager();
		if (dm != null && resourceProject != null)
		{
			String dbiFile = dm.getDBIFile(relation.getPrimaryDataSource()) != null
				? resourceProject + "/" + dm.getDBIFile(relation.getPrimaryDataSource()).getProjectRelativePath().toString() : null;
			String foreignDBIFile = dm.getDBIFile(relation.getForeignDataSource()) != null
				? resourceProject + "/" + dm.getDBIFile(relation.getForeignDataSource()).getProjectRelativePath().toString() : null;
			if (dbiFile != null)
			{
				context.append("\n/*  Check the dbi (databse information) file for the relation primary datasource '")
					.append(dbiFile)
					.append("'");
			}
			if (foreignDBIFile != null)
			{
				context.append(", the dbi file for the foreign datasource : '")
					.append(foreignDBIFile)
					.append("'");
			}
			if (dbiFile != null || foreignDBIFile != null)
			{
				context.append(" */");
			}
			context.append(
				"\n/*  Always use readPersistFile tool to read the provided .rel file and .dbi (table columns) files to check the record properties. */");
		}
		Pair<String, String> filePathPair = SolutionSerializer.getFilePath(relation, false);
		return filePathPair.getLeft() + filePathPair.getRight();
	}

	private String extractFormInfo(StringBuilder context, Form frm)
	{
		IPersist superForm = PersistHelper.getSuperPersist(frm);
		if (superForm != null)
		{
			context.append("\n/*   You may want to check the parent form for more context: " +
				SolutionSerializer.getScriptPath(superForm, false) + " */");
		}
		return SolutionSerializer.getScriptPath(frm, false);
	}

	private String extractValuelistContext(StringBuilder context, ValueList valuelist)
	{
		context.append("\n/*   Valuelist" + valuelist.getName() + "*/");
		if (valuelist.getDataSource() != null)
		{
			context.append("\n/*   Data source: " + valuelist.getDataSource() + " */"); //TODO point to the .dbi file?
		}
		if (valuelist.getRelationName() != null)
		{
			context.append("\n/*   Relation: " + valuelist.getRelationName() + " */"); //TODO point to the .rel file?
		}
		if (valuelist.getCustomValues() != null && !valuelist.getCustomValues().isEmpty())
		{
			if (valuelist.getCustomValues().split("\n").length == 1)
			{
				UUID uuid = Utils.getAsUUID(valuelist.getCustomValues().split("\n")[0], false);
				if (uuid != null)
				{
					IPersist persist = ServoyModelFinder.getServoyModel().getFlattenedSolution().searchPersist(uuid);
					if (persist instanceof ScriptMethod scriptMethod)
					{
						context.append("\n/*   Global method valuelist: " + scriptMethod.getName())
							.append(" in file")
							.append(SolutionSerializer.getFileName(persist, false))
							.append(", line number offset " + scriptMethod.getLineNumberOffset())
							.append("  */");
					}
					else
					{
						context.append("\n/*   Custom values: " + valuelist.getCustomValues() + " (could not resolve UUID) */");
					}
				}
				else
				{
					context.append("\n/*   Custom values: " + valuelist.getCustomValues() + " (not a UUID) */");
				}
			}
			else
			{
				context.append("\n/*   Custom values: " + valuelist.getCustomValues() + " */");
			}
		}
		Pair<String, String> filePathPair = SolutionSerializer.getFilePath(valuelist, false);
		return filePathPair.getLeft() + filePathPair.getRight();
	}

	private void processModelElements(String filePath, StringBuilder context, SelectionResult selectedElements)
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

	private String readWorkspaceFile(String filePath) throws Exception
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

	private StringBuilder surroundingLines(int startLine, int endLine, String surroundingLines, int errorLine)
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