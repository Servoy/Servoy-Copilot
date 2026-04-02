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

package com.servoy.eclipse.servoypilot.quickfix;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.internal.ui.editor.ScriptEditor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.tools.dto.QuickFixResult;
import com.servoy.eclipse.servoypilot.tools.dto.SourceEdit;

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

	private InlineDocumentChangesPreviewManager activePreviewManager;

	public void previewFix(String fixPrompt, QuickFixResult fix)
	{
		Display.getDefault().asyncExec(() -> {
			try
			{
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				Set<IPath> uniquePaths = new HashSet<>();
				for (SourceEdit edit : fix.edits())
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
					IFile fileToEdit = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
					IEditorPart targetEditor = findEditor(page, fileToEdit);

					if (targetEditor instanceof ScriptEditor scriptEditor)
					{
						List<SourceEdit> filteredEdits = fix.edits().stream()
							.filter(e -> {
								String p = e.filePath();
								if (p.startsWith("L/"))
								{
									p = p.substring(2);
								}
								return new Path(p).equals(path);
							})
							.toList();

						if (activePreviewManager != null)
						{
							activePreviewManager.clearPreview();
						}
						//TODO improve preview for multiple editors and multiple/large fixes
						this.activePreviewManager = new InlineDocumentChangesPreviewManager(scriptEditor);
						activePreviewManager.preview(filteredEdits);
					}
					else
					{
						ServoyLog.logError("Target editor is not a ScriptEditor, cannot apply quick fix preview");
					}
				}
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
}
