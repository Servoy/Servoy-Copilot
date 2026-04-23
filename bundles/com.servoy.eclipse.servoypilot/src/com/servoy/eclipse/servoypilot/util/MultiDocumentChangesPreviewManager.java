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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
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
 * Manager for multi-file previews that uses Code Mining to show changes without 
 * dirtying the documents until accepted.
 * * @author emera
 */
public class MultiDocumentChangesPreviewManager implements IDocumentChangesPreviewManager
{
	// Static map shared with the AiPreviewCodeMiningProvider
	private static final Map<IDocument, List<PreviewChange>> activeChangesMap = new ConcurrentHashMap<>();

	private final Map<IPath, Set<PreviewChange>> fileChanges = new HashMap<>();

	@Override
	public void preview(List<SourceEdit> allEdits) throws Exception
	{
		//TODO check clearPreview(); // Clean start

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

			if (document != null)
			{
				try
				{
					// This populates fileChanges and activeChangesMap via handleAppliedChange call-back
					calculateEdits(document, entry.getValue());

					// Force UI refresh for the editor if it's open
					triggerUIUpdate(path);
					FileModificationTracker.getInstance().notifyFileModified(path.toString(), document.get());
				}
				finally
				{
					disconnectPath(path);
				}
			}
		}
	}

	@Override
	public void handleAppliedChange(SourceEdit edit, PreviewChange change, int startLine, String original, String replacement)
	{
		IPath path = new Path(edit.filePath().startsWith("L/") ? edit.filePath().substring(2) : edit.filePath());

		// 1. Store for the actual 'accept' modification later
		fileChanges.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(change);

		// 2. Populate the static map for CodeMining UI
		IDocument doc = connectAndGetDocument(path);
		if (doc != null)
		{
			activeChangesMap.computeIfAbsent(doc, k -> new ArrayList<>()).add(change);
		}
	}

	public void accept()
	{
		for (Entry<IPath, Set<PreviewChange>> entry : fileChanges.entrySet())
		{
			IPath path = entry.getKey();
			IDocument document = connectAndGetDocument(path);
			if (document == null)
			{
				continue;
			}

			try
			{
				applyChangesToDocument(path, document, entry.getValue());
			}
			catch (Exception e)
			{
				ServoyLog.logError("Cannot accept source modification for " + path, e);
			}
			finally
			{
				disconnectPath(path);
			}
		}
		clearPreview();
	}

	private void applyChangesToDocument(IPath path, IDocument document, Set<PreviewChange> changes) throws Exception
	{
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
		createLocalHistoryEntry(file);

		IWorkbenchPage page = getActivePage();
		IEditorPart openEditor = findEditor(page, file);
		IDocumentProvider provider = (openEditor instanceof ITextEditor te) ? te.getDocumentProvider() : null;
		Object input = (openEditor != null) ? openEditor.getEditorInput() : null;

		DocumentRewriteSession session = null;
		try
		{
			if (document instanceof IDocumentExtension4 ext4)
			{
				session = ext4.startRewriteSession(DocumentRewriteSessionType.UNRESTRICTED_SMALL);
			}

			if (provider != null)
			{
				provider.aboutToChange(input);
			}

			// CRITICAL: Sort DESCENDING by offset so applying a change doesn't invalidate 
			// the offsets of subsequent changes in the same document.
			List<PreviewChange> sorted = new ArrayList<>(changes);
			sorted.sort(Comparator.comparingInt((PreviewChange c) -> c.startOffset).reversed());

			for (PreviewChange change : sorted)
			{
				String textToInsert = "";
				if (change.modifiedLine != null && !change.modifiedLine.isEmpty())
				{
					textToInsert = change.modifiedLine + change.lineDelimiter;
				}
				Position pos = change.getPosition();
				// pos.getOffset() will now be the CORRECT current offset, 
				// even if lines above it were added or removed!
				document.replace(pos.getOffset(), pos.getLength(), textToInsert);
				document.removePosition(pos);
			}
		}
		finally
		{
			if (document instanceof IDocumentExtension4 ext4 && session != null)
			{
				ext4.stopRewriteSession(session);
			}

			if (provider != null)
			{
				provider.changed(input);
			}

			// If editor wasn't open, we need to commit the buffer manually
			if (openEditor == null)
			{
				ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(path, LocationKind.IFILE);
				if (buffer != null && buffer.isDirty())
				{
					buffer.commit(null, true);
				}
				file.refreshLocal(IResource.DEPTH_ZERO, null);
			}
		}
	}

	public void reject()
	{
		clearPreview();
	}

	@Override
	public void clearPreview()
	{
		// Clear the CodeMining UI state for all documents involved
		for (IPath path : fileChanges.keySet())
		{
			IDocument doc = connectAndGetDocument(path);
			if (doc != null)
			{
				activeChangesMap.remove(doc);
				triggerUIUpdate(path);
			}
		}
		fileChanges.clear();
	}

	/**
	 * Tells the Eclipse editor to re-query Code Mining providers.
	 */
	private void triggerUIUpdate(IPath path)
	{
		IWorkbenchPage page = getActivePage();
		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
		IEditorPart editor = findEditor(page, file);
		if (editor != null)
		{
			ISourceViewerExtension5 ext5 = editor.getAdapter(ISourceViewerExtension5.class);
			if (ext5 != null)
			{
				ext5.updateCodeMinings();
			}
		}
	}

	private IDocument connectAndGetDocument(IPath path)
	{
		IWorkbenchPage page = getActivePage();
		if (page != null)
		{
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
			IEditorPart editor = findEditor(page, file);
			if (editor instanceof ITextEditor te)
			{
				return te.getDocumentProvider().getDocument(te.getEditorInput());
			}
		}

		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		try
		{
			bufferManager.connect(path, LocationKind.IFILE, null);
			ITextFileBuffer buffer = bufferManager.getTextFileBuffer(path, LocationKind.IFILE);
			return buffer != null ? buffer.getDocument() : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private void disconnectPath(IPath path)
	{
		try
		{
			FileBuffers.getTextFileBufferManager().disconnect(path, LocationKind.IFILE, null);
		}
		catch (CoreException e)
		{
			ServoyLog.logError("Disconnect failed", e);
		}
	}

	private IWorkbenchPage getActivePage()
	{
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window != null ? window.getActivePage() : null;
	}

	private IEditorPart findEditor(IWorkbenchPage page, IFile fileToEdit)
	{
		if (page == null || fileToEdit == null)
		{
			return null;
		}
		for (IEditorReference ref : page.getEditorReferences())
		{
			try
			{
				IEditorInput input = ref.getEditorInput();
				IFile openFile = input != null ? input.getAdapter(IFile.class) : null;
				if (fileToEdit.equals(openFile))
				{
					return ref.getEditor(true);
				}
			}
			catch (PartInitException e)
			{
				ServoyLog.logError("Editor check failed", e);
			}
		}
		return null;
	}

	public static List<PreviewChange> getActiveChanges(IDocument doc)
	{
		return activeChangesMap.getOrDefault(doc, java.util.Collections.emptyList());
	}

	public void addAppliedChange(IPath path, PreviewChange pc)
	{
		fileChanges.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(pc);
	}
}