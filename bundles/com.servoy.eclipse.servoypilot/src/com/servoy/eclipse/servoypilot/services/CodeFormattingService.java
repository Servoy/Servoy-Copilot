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
package com.servoy.eclipse.servoypilot.services;

import org.eclipse.core.resources.IProject;
import org.eclipse.dltk.core.PreferencesLookupDelegate;
import org.eclipse.dltk.javascript.core.JavaScriptNature;
import org.eclipse.dltk.ui.formatter.IScriptFormatter;
import org.eclipse.dltk.ui.formatter.IScriptFormatterFactory;
import org.eclipse.dltk.ui.formatter.ScriptFormatterManager;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.text.edits.TextEdit;

import com.servoy.eclipse.model.ServoyModelFinder;

/**
 * Used to format a specific code snippet.
 * @author emera
 */
public class CodeFormattingService
{
	private static CodeFormattingService instance;
	private final IProject project;

	private CodeFormattingService()
	{
		this.project = ServoyModelFinder.getServoyModel().getActiveProject().getProject();
	}

	public static synchronized CodeFormattingService getInstance()
	{
		if (instance == null)
		{
			instance = new CodeFormattingService();
		}
		return instance;
	}

	/**
	 * Takes the raw AI replacement and formats it to match the document's 
	 * current indentation level and project style rules.
	 */
	public String format(String rawCode, IDocument document, int offset)
	{
		if (rawCode == null || rawCode.isEmpty())
		{
			return rawCode;
		}

		try
		{
			String lineDelimiter = document.getLineDelimiter(document.getLineOfOffset(offset));
			if (lineDelimiter == null)
			{
				lineDelimiter = "\n";
			}

			IScriptFormatterFactory factory = ScriptFormatterManager.getSelected(JavaScriptNature.NATURE_ID, project);
			if (factory == null)
			{
				return rawCode;
			}

			IScriptFormatter formatter = factory.createFormatter(TextUtilities.getDefaultLineDelimiter(document),
				factory.retrievePreferences(new PreferencesLookupDelegate(project)));

			int initialLevel = formatter.detectIndentationLevel(document, offset);
			TextEdit edit = formatter.format(rawCode, 0, rawCode.length(), initialLevel);

			if (edit != null)
			{
				// Apply the edit to a temporary document to get the string result
				IDocument tempDoc = new org.eclipse.jface.text.Document(rawCode);
				edit.apply(tempDoc);
				return tempDoc.get();
			}
		}
		catch (Exception e)
		{
			System.err.println("Indentation failed: " + e.getMessage());
		}
		return rawCode;
	}
}