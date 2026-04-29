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

package com.servoy.eclipse.servoypilot.context;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.internal.ui.editor.ScriptEditor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileModificationTracker;
import com.servoy.eclipse.servoypilot.dto.CodeChanges;
import com.servoy.eclipse.servoypilot.dto.SourceEdit;
import com.servoy.eclipse.servoypilot.util.IDocumentChangesPreviewManager.PreviewChange;
import com.servoy.eclipse.servoypilot.util.InlineDocumentChangesPreviewManager;
import com.servoy.eclipse.servoypilot.util.MultiDocumentChangesPreviewManager;

/**
 * @author emera
 */
public class QuickFixPresenter
{
	private static QuickFixPresenter INSTANCE = new QuickFixPresenter();

	public static QuickFixPresenter getInstance()
	{
		return INSTANCE;
	}

	private final Map<IPath, InlineDocumentChangesPreviewManager> activeInlineManagers = new HashMap<>();
	private final Map<IPath, List<SourceEdit>> pendingEdits = new HashMap<>();
	private MultiDocumentChangesPreviewManager activeMultiManager;

	public void previewFix(String fixPrompt, CodeChanges fix)
	{
		Display.getDefault().asyncExec(() -> {
			try
			{
				Set<IPath> uniquePaths = new HashSet<>();
				for (SourceEdit edit : fix.codeChanges())
				{
					String editPath = edit.filePath();
					if (editPath.startsWith("L/"))
					{
						editPath = editPath.substring(2);
					}
					uniquePaths.add(new Path(editPath));
				}

				for (IPath path : uniquePaths)
				{
					List<SourceEdit> filteredEdits = fix.codeChanges().stream()
						.filter(e -> {
							String p = e.filePath();
							if (p.startsWith("L/"))
							{
								p = p.substring(2);
							}
							return new Path(p).equals(path);
						})
						.toList();

					InlineDocumentChangesPreviewManager existingManager = activeInlineManagers.get(path);
					if (existingManager != null)
					{
						//we have new updates, clear the old ones
						existingManager.clearPreview();
					}
					pendingEdits.put(path, filteredEdits);

					// TODO should look for the initial file that triggered the fix and show the inline preview there
					openInlinePreview(path);
				}

				activeMultiManager = new MultiDocumentChangesPreviewManager();
				//TODO check, do we need to clear, do we always get all the changes back?
				activeMultiManager.preview(fix.codeChanges());
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error applying quick fix", e);
			}
		});
	}

	private IEditorPart findEditor(IWorkbenchPage page, IFile fileToEdit) throws PartInitException
	{
		IEditorPart targetEditor = null;

		// iterate over all open editors in the current page
		IEditorReference[] editorRefs = page.getEditorReferences();
		for (IEditorReference ref : editorRefs)
		{
			try
			{
				IEditorInput input = ref.getEditorInput();
				IFile openFile = input != null ? input.getAdapter(IFile.class) : null;

				if (openFile != null && openFile.equals(fileToEdit))
				{
					targetEditor = ref.getEditor(true);
					page.activate(targetEditor);
					break;
				}
			}
			catch (PartInitException e)
			{
				ServoyLog.logError("Failed to inspect editor reference", e);
			}
		}

		if (targetEditor == null)
		{
			// use DLTKUIPlugin.openInEditor ?
			targetEditor = IDE.openEditor(page, fileToEdit, true);
		}
		return targetEditor;
	}

	public void openInlinePreview(String filePath)
	{
		IPath path = new Path(filePath.startsWith("L/") ? filePath.substring(2) : filePath);
		try
		{
			openInlinePreview(path);
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error opening inline preview for " + filePath, e);
		}
	}

	public void openInlinePreview(IPath path) throws Exception
	{
		IFile fileToEdit = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
		IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		IEditorPart targetEditor = findEditor(page, fileToEdit);

		if (targetEditor instanceof ScriptEditor scriptEditor)
		{
			page.activate(targetEditor);

			Display.getDefault().asyncExec(() -> {
				try
				{
					InlineDocumentChangesPreviewManager inlinePreviewManager = new InlineDocumentChangesPreviewManager(scriptEditor);
					activeInlineManagers.put(path, inlinePreviewManager);

					if (scriptEditor.getViewer() != null)
					{
						inlinePreviewManager.preview(pendingEdits.get(path));
						for (PreviewChange pc : inlinePreviewManager.getPreviewChanges())
						{
							activeMultiManager.addAppliedChange(path, pc);
						}
					}
					else
					{
						ServoyLog.logError("Viewer is still null after async yield. Cannot apply inline preview to " + path);
					}
				}
				catch (Exception e)
				{
					ServoyLog.logError("Error applying delayed inline preview", e);
				}
			});
		}
		else
		{
			ServoyLog.logError("Target editor is not a ScriptEditor, cannot apply quick fix preview");
		}
	}

	public void onUserClickedKeepAll()
	{
		if (activeMultiManager != null)
		{
			activeMultiManager.accept();
		}

		clearManagers();
	}

	public void clearManagers()
	{
		// clear the UI colors from any open editors
		for (InlineDocumentChangesPreviewManager inline : activeInlineManagers.values())
		{
			inline.clearPreview();
		}
		activeInlineManagers.clear();
		FileModificationTracker.getInstance().clear();
		activeMultiManager = null;
	}

	public void onUserClickedUndoAll()
	{
		if (activeMultiManager != null)
		{
			activeMultiManager.reject();
		}

		clearManagers();
	}


	public InlineDocumentChangesPreviewManager getManagerFor(IDocument document)
	{
		for (InlineDocumentChangesPreviewManager manager : activeInlineManagers.values())
		{
			// Checking if the manager's editor viewer uses this document
			if (manager.getEditor().getViewer().getDocument() == document)
			{
				return manager;
			}
		}
		return null;
	}

	public void keepFile(String filePath)
	{
		IPath path = new Path(filePath.startsWith("L/") ? filePath.substring(2) : filePath);
		InlineDocumentChangesPreviewManager existingManager = activeInlineManagers.get(path);
		if (existingManager != null)
		{
			existingManager.accept();
			activeMultiManager.remove(path);
			activeInlineManagers.remove(path);
			pendingEdits.remove(path);
		}
		else
		{
			activeMultiManager.accept(path);
		}
	}

	public void undoFile(String filePath)
	{
		IPath path = new Path(filePath.startsWith("L/") ? filePath.substring(2) : filePath);
		InlineDocumentChangesPreviewManager existingManager = activeInlineManagers.get(path);
		if (existingManager != null)
		{
			existingManager.reject();
			activeInlineManagers.remove(path);
			pendingEdits.remove(path);
		}
		activeMultiManager.remove(path);
	}
}