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

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.internal.ui.editor.ScriptEditor;
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.dto.SourceEdit;

/**
 * @author emera
 */
public class MultiDocumentChangesPreviewManager implements IDocumentChangesPreviewManager
{
	private final Map<IPath, Set<PreviewChange>> fileChanges = new HashMap<>();

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
			IDocument document = connectAndGetDocument(path);
			if (document == null)
			{
				ServoyLog.logError("Could not resolve document for path: " + path);
				continue;
			}
			try
			{
				String originalContent = document.get();
				applyEdits(document, entry.getValue());
				FileModificationTracker.getInstance().notifyFileModified(path.toString(), originalContent);
			}
			finally
			{
				disconnectPath(path);
			}
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
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();

		for (Entry<IPath, Set<PreviewChange>> entry : fileChanges.entrySet())
		{
			IPath path = entry.getKey();
			Set<PreviewChange> changes = entry.getValue();
			if (changes.isEmpty())
			{
				continue;
			}

			IDocument document = connectAndGetDocument(path);
			if (document == null)
			{
				ServoyLog.logError("Could not resolve document for path: " + path);
				continue;
			}

			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			IEditorPart openEditor = findEditor(page, file);

			IDocumentProvider provider = (openEditor instanceof ScriptEditor se) ? se.getDocumentProvider() : null;
			Object input = (openEditor != null) ? openEditor.getEditorInput() : null;

			DocumentRewriteSession docRewriteSession = null;
			try
			{
				if (document instanceof org.eclipse.jface.text.IDocumentExtension4 docextension4)
				{
					docRewriteSession = docextension4.startRewriteSession(DocumentRewriteSessionType.UNRESTRICTED_SMALL);
				}
				if (provider != null)
				{
					provider.aboutToChange(input);
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

				if (provider != null)
				{
					provider.changed(input);
				}

				try
				{
					if (openEditor == null)
					{
						ITextFileBuffer buffer = bufferManager.getTextFileBuffer(path, LocationKind.IFILE);
						if (buffer != null && buffer.isDirty())
						{
							buffer.commit(null, true);
						}
						// only refresh local if we actually wrote to disk
						file.refreshLocal(IResource.DEPTH_ZERO, null);
					}
				}
				catch (CoreException e)
				{
					ServoyLog.logError("Failed to save file after accepting changes: " + path, e);
				}
				finally
				{
					disconnectPath(path);
				}
			}
		}
		clearPreview();
	}

	@Override
	public void reject()
	{
		for (Entry<IPath, Set<PreviewChange>> entry : fileChanges.entrySet())
		{
			IPath path = entry.getKey();
			Set<PreviewChange> changes = entry.getValue();
			IDocument document = connectAndGetDocument(path);

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
				ServoyLog.logError("Cannot revert proposed source modification for " + path, e);
			}
			finally
			{
				if (document instanceof org.eclipse.jface.text.IDocumentExtension4 docextension4 && docRewriteSession != null)
				{
					docextension4.stopRewriteSession(docRewriteSession);
				}
				disconnectPath(path);
			}
		}
		clearPreview();
	}

	private IDocument connectAndGetDocument(IPath path)
	{
		// try to see if it's open in an editor to get the LIVE document
		try
		{
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null)
			{
				IWorkbenchPage page = window.getActivePage();
				IEditorPart editor = findEditor(page, file);

				if (editor instanceof ITextEditor textEditor)
				{
					IDocumentProvider provider = textEditor.getDocumentProvider();
					IDocument doc = provider.getDocument(textEditor.getEditorInput());
					if (doc != null)
					{
						return doc;
					}
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logInfo("Editor not found or not a text editor for: " + path);
		}

		// fallback: Connect via Buffer Manager for closed files
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		try
		{
			// Note: connect() is reference-counted; ensure you call disconnect() in the calling finally block
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

	private void disconnectPath(IPath path)
	{
		try
		{
			FileBuffers.getTextFileBufferManager().disconnect(path, LocationKind.IFILE, null);
		}
		catch (CoreException e)
		{
			ServoyLog.logError("Failed to disconnect buffer for path: " + path, e);
		}
	}

	public void addAppliedChange(IPath path, PreviewChange pc)
	{
		fileChanges.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(pc);
	}

	private IEditorPart findEditor(IWorkbenchPage page, IFile fileToEdit)
	{
		if (page == null || fileToEdit == null)
		{
			return null;
		}
		IEditorReference[] editorRefs = page.getEditorReferences();
		for (IEditorReference ref : editorRefs)
		{
			try
			{
				IEditorInput input = ref.getEditorInput();
				IFile openFile = input != null ? input.getAdapter(IFile.class) : null;

				if (openFile != null && openFile.equals(fileToEdit))
				{
					return ref.getEditor(true);
				}
			}
			catch (PartInitException e)
			{
				ServoyLog.logError("Failed to inspect editor reference", e);
			}
		}
		return null;
	}
}