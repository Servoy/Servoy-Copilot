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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.codeassist.ISelectionRequestor;
import org.eclipse.dltk.compiler.env.IModuleSource;
import org.eclipse.dltk.compiler.env.ModuleSource;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.ILocalVariable;
import org.eclipse.dltk.core.IMember;
import org.eclipse.dltk.core.IMethod;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.IModelElementVisitor;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.dltk.core.ModelException;
import org.eclipse.dltk.core.ScriptModelUtil;
import org.eclipse.dltk.javascript.internal.core.codeassist.JavaScriptSelectionEngine2;
import org.eclipse.dltk.javascript.typeinfo.IRElement;
import org.eclipse.dltk.javascript.typeinfo.IRMethod;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.model.util.ServoyLog;
// ResolvedElementsProcessor is in the same package — no import needed

/**
 * Resolves the type of an identifier in a Servoy JavaScript file using DLTK's
 * {@code JavaScriptSelectionEngine2}.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.services.CodeContextService#resolveIdentifierType}.
 * Does not depend on servoypilot.
 * </p>
 * <p>
 * Note: {@code JavaScriptSelectionEngine2} is in an {@code x-friends} package restricted to
 * {@code org.eclipse.dltk.javascript.ui}. This works in Eclipse development mode (PDE workspace builds
 * do not enforce x-friends). For production Tycho builds, add
 * {@code com.servoy.eclipse.developer.mcp} to the x-friends list in
 * {@code org.eclipse.dltk.javascript.core/META-INF/MANIFEST.MF}.
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
public class ScriptContextService
{
	/**
	 * DTO for holding selection results from DLTK selection engine.
	 */
	public static class SelectionResult
	{
		public List<IModelElement> modelElements = new ArrayList<>();
		public List<IRElement> foreignElements = new ArrayList<>();
	}

	/**
	 * Resolves the type of an identifier in a Servoy JavaScript file.
	 *
	 * @param identifier the identifier name (e.g. {@code "foundset"}, {@code "databaseManager"})
	 * @param file       the file containing the identifier
	 * @return formatted type information string, or an error message
	 */
	public String resolveIdentifierType(String identifier, IFile file)
	{
		if (identifier == null || identifier.isBlank())
			return "Error: 'identifier' is required.";
		if (file == null || !file.exists())
			return "Error: File not found.";

		String filePath = file.getFullPath().toString();

		try
		{
			String fileContent = readFileContent(file);
			if (fileContent == null)
				return "Error: Could not read file: " + filePath;

			IDocument document = new Document(fileContent);
			int offset = findIdentifierOffset(fileContent, identifier);
			if (offset == -1)
				return "Error: Identifier '" + identifier + "' not found in file: " + filePath;

			int lineNumber = document.getLineOfOffset(offset) + 1;
			SelectionResult selectedElements = getModelElements(filePath, offset);

		if (selectedElements != null)
		{
			// Use ResolvedElementsProcessor for rich structured JSON output
			if (!selectedElements.modelElements.isEmpty() || !selectedElements.foreignElements.isEmpty())
			{
				try
				{
					return ResolvedElementsProcessor.getInstance().toJson(filePath, selectedElements);
				}
				catch (Exception e)
				{
					ServoyLog.logWarning("ScriptContextService: ResolvedElementsProcessor failed, falling back to simple format", e);
					return formatTypeInfo(selectedElements, identifier, filePath, lineNumber, fileContent, offset);
				}
			}
		}

		// JSDoc fallback when no model/foreign elements found
		String jsDocType = extractJSDocType(fileContent, offset);
		if (jsDocType != null)
			return "{ \"resolvedElements\": [{ \"name\": \"" + identifier + "\", \"source\": \"jsdoc\", \"type\": \"" + jsDocType + "\", \"location\": \"" + filePath + ":" + lineNumber + "\" }] }";

		return "Error: No type information available for identifier '" + identifier + "'";
		}
		catch (Exception e)
		{
			ServoyLog.logError("ScriptContextService: error resolving identifier type: " + identifier, e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Gets model elements at a specific location using DLTK selection engine.
	 */
	public SelectionResult getModelElements(String filePath, int characterOffset)
	{
		if (filePath == null) return null;

		try
		{
			IFile file = getFile(filePath);
			if (file == null || !file.exists()) return null;

			String fileContent = readFileContent(file);
			if (fileContent == null) return null;

			ISourceModule sourceModule = (ISourceModule)DLTKCore.create(file);
			IModuleSource module = new ModuleSource(filePath, sourceModule, fileContent);
			JavaScriptSelectionEngine2 selectionEngine = new JavaScriptSelectionEngine2();
			int offset = skipWhitespaceForward(fileContent, characterOffset);
			SelectionResult result = new SelectionResult();

			Thread thread = new Thread(() -> {
				try
				{
					selectionEngine.setRequestor(new ISelectionRequestor()
					{
						@Override
						public void acceptModelElement(IModelElement element)
						{
							if (element != null) result.modelElements.add(element);
						}

						@Override
						public void acceptForeignElement(Object element)
						{
							if (element instanceof IRElement ire) result.foreignElements.add(ire);
						}

						@Override
						public void acceptElement(Object element, ISourceRange range)
						{
							if (element instanceof IModelElement me) acceptModelElement(me);
							else acceptForeignElement(element);
						}
					});
					selectionEngine.select(module, offset, offset);
				}
				catch (Exception e)
				{
					ServoyLog.logError("ScriptContextService: error selecting model elements", e);
				}
			}, "ScriptContextService-select-" + file.getName());

			thread.start();
			thread.join();
			return result;
		}
		catch (Exception e)
		{
			ServoyLog.logError("ScriptContextService: error computing model elements: " + filePath, e);
			return null;
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private String formatTypeInfo(SelectionResult result, String identifier, String filePath,
		int lineNumber, String fileContent, int offset)
	{
		if (result == null)
			return "Error: No selection result available";

		StringBuilder sb = new StringBuilder();
		sb.append("=== TYPE RESOLUTION ===\n\n");
		sb.append("IDENTIFIER: ").append(identifier).append("\n");

		// Model elements (local variables, methods)
		for (IModelElement element : result.modelElements)
		{
			if (!element.getElementName().equals(identifier)) continue;

			if (element instanceof ILocalVariable localVar)
			{
				String type = localVar.getType();
				if (type != null && !type.isBlank())
				{
					sb.append("TYPE: ").append(type).append("\n");
					sb.append("SOURCE: Local variable\n");
					sb.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
					return sb.toString();
				}
			}

			if (element instanceof IMethod method)
			{
				sb.append("TYPE: Function\n");
				sb.append("SOURCE: Method declaration\n");
				sb.append("PARAMETERS: (");
				try
				{
					sb.append(java.util.Arrays.stream(method.getParameters())
						.map(p -> p.getName() + ":" + p.getType())
						.collect(java.util.stream.Collectors.joining(", ")));
				}
				catch (Exception e)
				{
					sb.append("unknown");
				}
				sb.append(")\n");
				sb.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return sb.toString();
			}

			// Other model element â try JSDoc fallback
			String jsDocType = extractJSDocType(fileContent, offset);
			if (jsDocType != null)
			{
				sb.append("TYPE: ").append(jsDocType).append("\n");
				sb.append("SOURCE: JSDoc @type annotation\n");
				sb.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return sb.toString();
			}

			sb.append("TYPE: ").append(element.getClass().getSimpleName()).append("\n");
			sb.append("SOURCE: Model element\n");
			sb.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
			return sb.toString();
		}

		// Foreign elements (Servoy API types)
		for (IRElement element : result.foreignElements)
		{
			String type = element.getName();
			if (type != null && !type.isBlank())
			{
				sb.append("TYPE: ").append(type).append("\n");
				sb.append("SOURCE: Servoy API type\n");

				if (element instanceof IRMethod method)
				{
					sb.append("PARAMETERS: (");
					sb.append(method.getParameters().stream()
						.map(p -> p.getName() + ":" + p.getType())
						.collect(java.util.stream.Collectors.joining(", ")));
					sb.append(")\n");
				}

				sb.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
				return sb.toString();
			}
		}

		// JSDoc fallback
		String jsDocType = extractJSDocType(fileContent, offset);
		if (jsDocType != null)
		{
			sb.append("TYPE: ").append(jsDocType).append("\n");
			sb.append("SOURCE: JSDoc @type annotation\n");
			sb.append("LOCATION: ").append(filePath).append(", line ").append(lineNumber).append("\n");
			return sb.toString();
		}

		return "Error: Could not resolve type for identifier '" + identifier + "' in file: " + filePath + " at line " + lineNumber;
	}

	private int findIdentifierOffset(String source, String identifier)
	{
		if (source == null || identifier == null) return -1;

		// Strategy 1: var declaration
		java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("\\bvar\\s+(" + java.util.regex.Pattern.quote(identifier) + ")\\b");
		java.util.regex.Matcher m1 = p1.matcher(source);
		if (m1.find()) return m1.start(1);

		// Strategy 2: usage (identifier. or identifier( or identifier{)
		java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\b(" + java.util.regex.Pattern.quote(identifier) + ")\\s*[.({]");
		java.util.regex.Matcher m2 = p2.matcher(source);
		if (m2.find()) return m2.start(1);

		// Strategy 3: first word-boundary occurrence
		int idx = source.indexOf(identifier);
		if (idx >= 0)
		{
			boolean before = (idx == 0 || !Character.isJavaIdentifierPart(source.charAt(idx - 1)));
			boolean after = (idx + identifier.length() >= source.length() ||
				!Character.isJavaIdentifierPart(source.charAt(idx + identifier.length())));
			if (before && after) return idx;
		}

		return -1;
	}

	private String extractJSDocType(String fileContent, int offset)
	{
		if (offset <= 0 || fileContent == null) return null;

		int lookbackStart = Math.max(0, offset - 300);
		String preceding = fileContent.substring(lookbackStart, offset);

		int jsDocStart = preceding.lastIndexOf("/**");
		if (jsDocStart == -1) return null;

		String jsDocBlock = preceding.substring(jsDocStart);

		// Check if another var declaration is between JSDoc and our identifier
		java.util.regex.Matcher varMatcher = java.util.regex.Pattern.compile("\\*/\\s*\\n\\s*var\\s+\\w+").matcher(jsDocBlock);
		if (varMatcher.find()) return null;

		java.util.regex.Matcher typeMatcher = java.util.regex.Pattern.compile("@type\\s*\\{([^}]+)\\}").matcher(jsDocBlock);
		if (typeMatcher.find()) return typeMatcher.group(1).trim();

		return null;
	}

	private static int skipWhitespaceForward(String source, int offset)
	{
		int len = source.length();
		while (offset < len && Character.isWhitespace(source.charAt(offset)))
			offset++;
		return offset;
	}

	private static String readFileContent(IFile file)
	{
		try (java.io.InputStream is = file.getContents())
		{
			return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			ServoyLog.logError("ScriptContextService: error reading file: " + file.getFullPath(), e);
			return null;
		}
	}

	private static IFile getFile(String filePath)
	{
		if (filePath == null) return null;
		if (filePath.startsWith("L/")) filePath = filePath.substring(2);
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
		return (file != null && file.exists()) ? file : null;
	}
}
