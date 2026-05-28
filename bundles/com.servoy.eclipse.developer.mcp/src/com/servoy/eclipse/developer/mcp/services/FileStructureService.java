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
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.IMember;
import org.eclipse.dltk.core.IMethod;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Analyzes JavaScript file structure using DLTK APIs.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.services.FileStructureService}.
 * Extracts symbols (functions, variables) with line numbers and parameter names.
 * Uses DLTK's {@code ISourceModule.getChildren()} for symbol extraction â fast,
 * cached by DLTK's model infrastructure.
 * </p>
 */
@org.eclipse.e4.core.di.annotations.Creatable
public class FileStructureService
{
	/**
	 * Represents a single symbol (function or variable) in a JS file.
	 */
	public record SymbolInfo(
		String name,
		int elementType,
		int startOffset,
		int endOffset,
		int lineNumber,
		String[] parameterNames)
	{
		/** Returns true if this symbol is a function/method. */
		public boolean isFunction()
		{
			return elementType == IModelElement.METHOD;
		}

		public String toFormattedString()
		{
			StringBuilder sb = new StringBuilder();
			sb.append("Line ").append(lineNumber).append(": ").append(name);
			if (isFunction())
			{
				sb.append("(");
				if (parameterNames != null && parameterNames.length > 0)
					sb.append(String.join(", ", parameterNames));
				sb.append(")");
			}
			return sb.toString();
		}
	}

	/**
	 * Represents the full symbol structure of a JS file.
	 */
	public record FileStructure(String filePath, List<SymbolInfo> symbols)
	{
		public String toFormattedString()
		{
			if (symbols == null || symbols.isEmpty())
				return "No symbols found in: " + filePath;

			StringBuilder sb = new StringBuilder();
			sb.append("# File Structure: ").append(filePath).append("\n\n");
			sb.append("Found ").append(symbols.size()).append(" symbol(s):\n\n");
			for (SymbolInfo s : symbols)
				sb.append("- ").append(s.toFormattedString()).append("\n");
			return sb.toString();
		}
	}

	/**
	 * Analyzes a JavaScript file and extracts all top-level symbols.
	 *
	 * @param file the JavaScript file to analyze
	 * @return {@link FileStructure} containing all symbols with line numbers
	 */
	public FileStructure analyzeFile(IFile file)
	{
		if (file == null || !file.exists())
			return new FileStructure(file != null ? file.getFullPath().toString() : "unknown", new ArrayList<>());

		try
		{
			ISourceModule module = DLTKCore.createSourceModuleFrom(file);
			if (module == null)
			{
				ServoyLog.logInfo("FileStructureService: could not create ISourceModule for: " + file.getFullPath());
				return new FileStructure(file.getFullPath().toString(), new ArrayList<>());
			}

			String source = module.getSource();
			IDocument document = new Document(source);
			IModelElement[] children = module.getChildren();
			List<SymbolInfo> symbols = new ArrayList<>();

			for (IModelElement child : children)
			{
				if (child instanceof IMember member)
				{
					ISourceRange range = member.getNameRange();
					if (range == null) continue;

					int lineNumber = 1;
					try
					{
						lineNumber = document.getLineOfOffset(range.getOffset()) + 1;
					}
					catch (BadLocationException e)
					{
						lineNumber = calculateLineNumber(source, range.getOffset());
					}

					String[] parameterNames = new String[0];
					if (member instanceof IMethod method)
					{
						try { parameterNames = method.getParameterNames(); }
						catch (Exception e) { /* leave empty */ }
					}

					symbols.add(new SymbolInfo(
						member.getElementName(),
						member.getElementType(),
						range.getOffset(),
						range.getOffset() + range.getLength(),
						lineNumber,
						parameterNames));
				}
			}

			return new FileStructure(file.getFullPath().toString(), symbols);
		}
		catch (Exception e)
		{
			ServoyLog.logError("FileStructureService: error analyzing " + file.getFullPath(), e);
			return new FileStructure(file.getFullPath().toString(), new ArrayList<>());
		}
	}

	/**
	 * Finds a symbol by name in a file structure.
	 */
	public SymbolInfo findSymbol(FileStructure structure, String symbolName)
	{
		if (structure == null || symbolName == null) return null;
		for (SymbolInfo s : structure.symbols())
			if (symbolName.equals(s.name())) return s;
		return null;
	}

	private static int calculateLineNumber(String source, int offset)
	{
		if (source == null || offset < 0 || offset > source.length()) return 1;
		int line = 1;
		for (int i = 0; i < offset; i++)
			if (source.charAt(i) == '\n') line++;
		return line;
	}
}
