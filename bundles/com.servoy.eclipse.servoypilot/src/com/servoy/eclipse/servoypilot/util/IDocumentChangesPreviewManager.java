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

package com.servoy.eclipse.servoypilot.util;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.CodeFormattingService;
import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.eclipse.servoypilot.tools.dto.SourceEdit;

/**
 * @author emera
 */
public interface IDocumentChangesPreviewManager
{
	static class PreviewChange
	{
		int startOffset;
		int originalLength;
		String modifiedLine;
		String lineDelimiter;
		String originalLine;
		public boolean isInsert;
	}


	void preview(List<SourceEdit> sourceEdits) throws Exception;

	void clearPreview();

	// each implementation handles where to store or how to paint these
	void handleAppliedChange(SourceEdit edit, PreviewChange change, int startLine, String original, String replacement);

	/**
	 * The shared logic for processing edits. 
	 * Implementations call this to perform the actual document work.
	 */
	default List<PreviewChange> applyEdits(IDocument document, List<SourceEdit> sourceEdits) throws Exception
	{
		List<PreviewChange> appliedChanges = new ArrayList<>();

		// Sort bottom-to-top to keep offsets valid during multiple replacements
		List<SourceEdit> sortedEdits = new ArrayList<>(sourceEdits);
		sortedEdits.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

		CodeFormattingService codeFormatter = CodeFormattingService.getInstance(document);

		for (SourceEdit edit : sortedEdits)
		{
			int startLine = edit.startLine() - 1;
			int startOffset = document.getLineOffset(startLine);

			Statement statement = ParserService.getInstance().getStatementAtOffset(document.get(), startOffset);
			int endLine = document.getLineOfOffset(statement.sourceEnd());
			int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);

			String originalStatement = document.get(startOffset, endOffset - startOffset);
			String lineDelimiter = document.getLineDelimiter(startLine);
			if (lineDelimiter == null)
			{
				lineDelimiter = "\n";
			}
			String indentedReplacement = codeFormatter.format(edit.replacement(), startOffset);
			String previewBlock = null;
			if (edit.isInsert())
			{
				previewBlock = indentedReplacement + lineDelimiter;
			}
			else if (edit.isReplacement())
			{
				previewBlock = originalStatement +
					indentedReplacement +
					lineDelimiter;
			}
			else if (edit.isDelete())
			{
				previewBlock = originalStatement;
			}
			else
			{
				ServoyLog.logWarning("Unsupported edit type for preview: " + edit, null);
				continue; // skip invalid edits
			}

			if (edit.isInsert())
			{
				document.replace(startOffset, 0, indentedReplacement + lineDelimiter);
			}
			else
			{
				document.replace(startOffset, endOffset - startOffset, previewBlock);
			}

			PreviewChange change = new PreviewChange();
			change.startOffset = startOffset;
			change.originalLength = previewBlock.length();
			change.modifiedLine = indentedReplacement;
			change.originalLine = originalStatement;
			change.lineDelimiter = lineDelimiter;
			change.isInsert = edit.isInsert();

			// Callback to the specific implementation (Inline vs Multi-file)
			handleAppliedChange(edit, change, startLine, originalStatement, indentedReplacement);

			appliedChanges.add(change);
		}
		return appliedChanges;
	}
}
