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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.dto.SourceEdit;

/**
 * @author emera
 */
public class MultiDocumentChangesPreviewManager implements IDocumentChangesPreviewManager
{
	private final Map<IPath, List<PreviewChange>> fileChanges = new HashMap<>();

	@Override
	public void preview(List<SourceEdit> allEdits) throws Exception
	{
		Map<IPath, List<SourceEdit>> editsByFile = allEdits.stream()
			.collect(Collectors.groupingBy(e -> {
				String p = e.filePath();
				if (p.startsWith("L/"))
				{
					p = p.substring(2);
				}
				return new Path(p);
			}));

		for (Map.Entry<IPath, List<SourceEdit>> entry : editsByFile.entrySet())
		{
			IPath path = entry.getKey();
			IDocument document = getDocumentForPath(path);
			if (document == null)
			{
				ServoyLog.logError("Could not resolve document for path: " + path);
				continue;
			}
			String originalContent = document.get();
			List<PreviewChange> changes = applyEdits(document, entry.getValue());
			fileChanges.put(path, changes);
			FileModificationTracker.getInstance().notifyFileModified(path.toString(), originalContent);
		}
	}

	@Override
	public void clearPreview()
	{
		fileChanges.clear();
	}

	@Override
	public void handleAppliedChange(SourceEdit edit, PreviewChange change, int startLine, String original, String replacement)
	{
		IPath path = new Path(edit.filePath().startsWith("L/") ? edit.filePath().substring(2) : edit.filePath());
		addAppliedChange(path, change);
	}

	@Override
	public void accept()
	{
		for (Map.Entry<IPath, List<PreviewChange>> entry : fileChanges.entrySet())
		{
			IPath path = entry.getKey();
			List<PreviewChange> changes = entry.getValue();
			IDocument document = getDocumentForPath(path);

			if (document == null)
			{
				ServoyLog.logError("Could not resolve document for path: " + path);
				continue;
			}

			DocumentRewriteSession docRewriteSession = null;
			try
			{
				if (document instanceof org.eclipse.jface.text.IDocumentExtension4 docextension4)
				{
					docRewriteSession = docextension4.startRewriteSession(DocumentRewriteSessionType.UNRESTRICTED_SMALL);
				}

				for (PreviewChange change : changes)
				{
					int line = document.getLineOfOffset(change.startOffset);
					int offset = document.getLineOffset(line);

					if (!change.isInsert)
					{
						// if it's an insert it's already in the document, so we don't need to do anything to "accept" it.
						document.replace(offset, change.originalLength, change.modifiedLine + change.lineDelimiter);
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("Cannot accept source modification for " + path, e);
			}
			finally
			{
				if (document instanceof org.eclipse.jface.text.IDocumentExtension4 docextension4 && docRewriteSession != null)
				{
					docextension4.stopRewriteSession(docRewriteSession);
				}
			}
		}

		clearPreview();
	}

	@Override
	public void reject()
	{
		for (Map.Entry<IPath, List<PreviewChange>> entry : fileChanges.entrySet())
		{
			IPath path = entry.getKey();
			List<PreviewChange> changes = entry.getValue();
			IDocument document = getDocumentForPath(path);

			if (document == null)
			{
				continue;
			}

			DocumentRewriteSession docRewriteSession = null;
			try
			{
				if (document instanceof org.eclipse.jface.text.IDocumentExtension4 docextension4)
				{
					docRewriteSession = docextension4.startRewriteSession(DocumentRewriteSessionType.UNRESTRICTED_SMALL);
				}

				for (PreviewChange change : changes)
				{
					int line = document.getLineOfOffset(change.startOffset);
					int offset = document.getLineOffset(line);

					if (change.isInsert)
					{
						// To "reject" an insertion, we replace the inserted length back to nothing.
						int insertedLength = change.modifiedLine.length() + change.lineDelimiter.length();
						document.replace(offset, insertedLength, "");
					}
					else
					{
						document.replace(offset, change.originalLength, change.originalLine);
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("Cannot revert the proposed source modification for " + path, e);
			}
			finally
			{
				if (document instanceof org.eclipse.jface.text.IDocumentExtension4 docextension4 && docRewriteSession != null)
				{
					docextension4.stopRewriteSession(docRewriteSession);
				}
			}
		}

		clearPreview();
	}

	private IDocument getDocumentForPath(IPath path)
	{
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		try
		{
			// Connect to the buffer (this loads it if it's closed, or reuses the open editor's buffer)
			bufferManager.connect(path, LocationKind.IFILE, null);
			ITextFileBuffer textFileBuffer = bufferManager.getTextFileBuffer(path, LocationKind.IFILE);

			if (textFileBuffer != null)
			{
				return textFileBuffer.getDocument();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Failed to connect to file buffer for path: " + path, e);
		}
		return null;
	}

	/**
	 * @param path
	 * @param pc
	 */
	public void addAppliedChange(IPath path, PreviewChange pc)
	{
		fileChanges.computeIfAbsent(path, k -> new ArrayList<>()).add(pc);
	}
}
