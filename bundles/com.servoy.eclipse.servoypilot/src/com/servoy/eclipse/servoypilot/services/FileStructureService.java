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
package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.IMember;
import org.eclipse.dltk.core.IModelElement;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.core.ISourceRange;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;
import com.servoy.eclipse.servoypilot.services.dto.SymbolInfo;

/**
 * Service for analyzing JavaScript file structure using DLTK APIs.
 * Extracts symbols (functions, variables) and their JSDoc status.
 * 
 * This is a thin wrapper around existing DLTK infrastructure:
 * - IModelElement.getChildren() for symbol extraction
 * - MultiLineComment.isDocumentation() for JSDoc detection
 * - ScriptModelUtil.reconcile() for caching (implicit via DLTK)
 */
public class FileStructureService
{
	private static FileStructureService instance;

	private FileStructureService()
	{
		// Singleton
	}

	public static FileStructureService getInstance()
	{
		if (instance == null)
		{
			instance = new FileStructureService();
		}
		return instance;
	}

	/**
	 * Analyze file structure and extract all top-level symbols.
	 * 
	 * @param file The JavaScript file to analyze
	 * @return FileStructure containing all symbols with JSDoc status
	 */
	public FileStructure analyzeFile(IFile file)
	{
		if (file != null && file.exists())
		{
			try
			{
				// Get DLTK source module
				ISourceModule module = DLTKCore.createSourceModuleFrom(file);
				if (module != null)
				{
					// Get source and create IDocument for line number calculation
					String source = module.getSource();
					IDocument document = new Document(source);
					
					// Get all children (DLTK extracts symbols automatically)
					IModelElement[] children = module.getChildren();
					List<SymbolInfo> symbols = new ArrayList<>();

					// Extract metadata from each child
					for (IModelElement child : children)
					{
						if (child instanceof IMember)
						{
							IMember member = (IMember)child;
							ISourceRange range = member.getNameRange();

							if (range != null)
							{
								// Check if JSDoc exists
								boolean hasJSDoc = hasJSDocComment(member, module);
								
								// Get line number using IDocument (0-based, so add 1)
								int lineNumber = 1;
								try
								{
									lineNumber = document.getLineOfOffset(range.getOffset()) + 1;
								}
								catch (BadLocationException e)
								{
									// Fallback to manual calculation
									lineNumber = calculateLineNumber(source, range.getOffset());
								}

								SymbolInfo symbolInfo = new SymbolInfo(
									member.getElementName(),
									member.getElementType(),
									range.getOffset(),
									range.getOffset() + range.getLength(),
									lineNumber,
									hasJSDoc);

								symbols.add(symbolInfo);
							}
						}
					}

					return new FileStructure(file.getFullPath().toString(), symbols);
				}

				ServoyLog.logInfo("Could not create ISourceModule for file: " + file.getFullPath());
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error analyzing file structure: " + file.getFullPath(), e);
			}
		}

		return new FileStructure(file != null ? file.getFullPath().toString() : "unknown", new ArrayList<>());
	}

	/**
	 * Check if a member has JSDoc comment.
	 * Simple approach: Look for /** comment in text before member position.
	 */
	private boolean hasJSDocComment(IMember member, ISourceModule module)
	{
		try
		{
			ISourceRange range = member.getNameRange();
			if (range != null && range.getOffset() >= 50)
			{
				// Get source text before member
				String source = module.getSource();
				if (source != null)
				{
					// Check for /** comment in the ~100 characters before member
					int startPos = Math.max(0, range.getOffset() - 100);
					int endPos = range.getOffset();
					String precedingText = source.substring(startPos, endPos);

					// Look for JSDoc pattern: /**
					return precedingText.contains("/**");
				}
			}
		}
		catch (Exception e)
		{
			// Fail silently
		}

		return false;
	}

	/**
	 * Calculate line number (1-based) from character offset.
	 * 
	 * @param source The complete source text
	 * @param offset Character offset in source
	 * @return Line number (1-based) where offset occurs
	 */
	private int calculateLineNumber(String source, int offset)
	{
		if (source != null && offset >= 0 && offset <= source.length())
		{
			int lineNumber = 1;
			for (int i = 0; i < offset; i++)
			{
				if (source.charAt(i) == '\n')
				{
					lineNumber++;
				}
			}
			return lineNumber;
		}
		return 1; // Default to line 1 if calculation fails
	}

	/**
	 * Find a symbol by name in the file structure.
	 * Used by CodeChunkReader for targeted symbol reading.
	 */
	public SymbolInfo findSymbol(FileStructure structure, String symbolName)
	{
		if (structure != null && symbolName != null)
		{
			for (SymbolInfo symbol : structure.getSymbols())
			{
				if (symbolName.equals(symbol.getName()))
				{
					return symbol;
				}
			}
		}

		return null;
	}
}
