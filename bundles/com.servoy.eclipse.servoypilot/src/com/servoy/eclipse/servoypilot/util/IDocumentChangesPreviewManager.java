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

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.dto.SourceEdit;
import com.servoy.eclipse.servoypilot.services.CodeFormattingService;
import com.servoy.eclipse.servoypilot.services.ParserService;

/**
 * @author emera
 */
public interface IDocumentChangesPreviewManager
{
	static class PreviewChange
	{
		public int startOffset;
		public int originalLength;
		public String modifiedLine;
		String lineDelimiter;
		String originalLine;
		public boolean isInsert;
		private Position position;
		public int endOffset;

		public void setPosition(Position pos)
		{
			this.position = pos;
		}

		public Position getPosition()
		{
			return position;
		}
	}

	void preview(List<SourceEdit> sourceEdits) throws Exception;

	void clearPreview();


	default void calculateEdits(IDocument document, List<SourceEdit> sourceEdits) throws Exception
	{
		// Sort bottom-to-top 
		List<SourceEdit> sortedEdits = new ArrayList<>(sourceEdits);
		sortedEdits.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

		CodeFormattingService codeFormatter = CodeFormattingService.getInstance(document);

		for (SourceEdit edit : sortedEdits)
		{
			int startLine = edit.startLine() - 1;
			int startOffset = document.getLineOffset(startLine);

			Statement statement = ParserService.getInstance().getStatementAtOffset(document.get(), startOffset);
			int endLine = statement != null ? document.getLineOfOffset(statement.sourceEnd()) : edit.endLine() - 1;
			if (edit.forceEndLineUse())
			{
				Statement endStatement = null;
				int end = edit.endLine() - 1;
				while ((endStatement = ParserService.getInstance().getStatementAtOffset(document.get(), document.getLineOffset(end))) == null &&
					end >= startLine)
				{
					end -= 1;
				}
				if (endStatement != null)
				{
					endLine = document.getLineOfOffset(endStatement.sourceEnd());
				}
			}
			int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);

			String originalStatement = document.get(startOffset, endOffset - startOffset);
			String lineDelimiter = document.getLineDelimiter(startLine) != null ? document.getLineDelimiter(startLine) : "\n";

			String indentedReplacement = codeFormatter.format(edit.replacement(), startOffset);

			PreviewChange change = new PreviewChange();
			change.startOffset = startOffset;
			change.originalLength = originalStatement.length(); // Just the length of the old code
			change.modifiedLine = indentedReplacement;
			change.originalLine = originalStatement;
			change.lineDelimiter = lineDelimiter;
			change.isInsert = edit.isInsert();
			change.endOffset = endOffset;

			Position pos = new Position(change.startOffset, change.isInsert ? 0 : change.originalLength);
			document.addPosition(pos); // Document now "tracks" this range
			change.setPosition(pos);

			// Store the change (but DO NOT call document.replace here!)
			handleAppliedChange(edit, change, startLine, originalStatement, indentedReplacement);
		}
	}

	// each implementation handles where to store or how to paint these
	void handleAppliedChange(SourceEdit edit, PreviewChange change, int startLine, String original, String replacement);

	default void createLocalHistoryEntry(IFile file)
	{
		if (!file.exists())
		{
			return;
		}

		try
		{
			file.appendContents(new ByteArrayInputStream(new byte[0]), IResource.KEEP_HISTORY, null);
		}
		catch (

		CoreException e)
		{
			ServoyLog.logError("Could not create local history entry for " + file.getName(), e);
		}
	}

	default boolean shouldSkipChange(IDocument document, SourceEdit edit, int startOffset, int endOffset, String originalStatement, String indentedReplacement,
		String previewBlock)
	{
		boolean shouldSkip = false;
		if (edit.isDelete())
		{
			// If the original statement is ALREADY GONE (or doesn't match), 
			// it means the delete fix was already applied.
			if (!isContentAtRange(document, startOffset, endOffset, originalStatement))
			{
				shouldSkip = true;
			}
		}
		else
		{
			// For Inserts and Replacements, use the "Fix or Preview" check
			boolean fixAlreadyApplied = isContentAtRange(document, startOffset, endOffset, indentedReplacement);
			boolean previewAlreadyRendered = isContentAtRange(document, startOffset, endOffset, previewBlock);

			if (fixAlreadyApplied || previewAlreadyRendered)
			{
				shouldSkip = true;
			}
		}
		return shouldSkip;
	}

	private boolean isContentAtRange(IDocument doc, int start, int end, String expected)
	{
		try
		{
			int lengthToRead = expected.length();

			if (lengthToRead == 0)
			{
				return true;
			}

			if (start < 0 || start + lengthToRead > doc.getLength())
			{
				return false;
			}
			String actual = doc.get(start, lengthToRead);
			return actual.trim().equals(expected.trim());
		}
		catch (BadLocationException e)
		{
			return false;
		}
	}
}
