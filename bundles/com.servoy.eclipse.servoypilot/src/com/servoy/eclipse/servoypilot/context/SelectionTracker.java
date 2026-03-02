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
package com.servoy.eclipse.servoypilot.context;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.ui.DLTKUIPlugin;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.servoy.eclipse.servoypilot.context.dto.SelectionInfo;

/**
 * Singleton service that tracks the current text selection in JavaScript editors.
 * Implements ISelectionListener to monitor selection changes across the workbench.
 * 
 * Thread-safe and lazily initialized.
 */
public class SelectionTracker implements ISelectionListener
{
	private static SelectionTracker instance;
	private static final Object LOCK = new Object();

	private ISelectionService selectionService;
	private ITextSelection currentSelection;
	private IEditorPart activeEditor;
	private String currentFilePath;
	private String currentFullDocumentText;
	private volatile boolean initialized = false;

	private SelectionTracker()
	{
		// Private constructor for singleton
	}

	/**
	 * Gets the singleton instance, initializing if necessary.
	 * Thread-safe lazy initialization.
	 * 
	 * @return SelectionTracker instance
	 */
	public static SelectionTracker getInstance()
	{
		if (instance == null)
		{
			synchronized (LOCK)
			{
				if (instance == null)
				{
					instance = new SelectionTracker();
					instance.initialize();
				}
			}
		}
		return instance;
	}

	/**
	 * Initializes the selection tracker by registering with the workbench selection service.
	 */
	private void initialize()
	{
		if (!initialized)
		{
			IWorkbench workbench = PlatformUI.getWorkbench();
			if (workbench != null)
			{
				IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
				if (window != null)
				{
					selectionService = window.getSelectionService();
					if (selectionService != null)
					{
						selectionService.addSelectionListener(this);
						
						// Get initial selection
						ISelection selection = selectionService.getSelection();
						if (selection instanceof ITextSelection textSelection)
						{
							updateSelection(textSelection, window.getActivePage() != null ? window.getActivePage().getActiveEditor() : null);
						}
						
						initialized = true;
					}
				}
			}
		}
	}

	/**
	 * Gets the current selection information.
	 * If text is selected, returns selection info for that range.
	 * If no text is selected, returns selection info for the entire file.
	 * 
	 * @return Optional containing SelectionInfo if an active editor exists, empty otherwise
	 */
	public Optional<SelectionInfo> getCurrentSelection()
	{
		if (currentSelection != null && activeEditor != null && currentFilePath != null)
		{
			IEditorInput input = activeEditor.getEditorInput();
			if (input != null)
			{
				ISourceModule module = DLTKUIPlugin.getEditorInputModelElement(input);
				if (module != null)
				{
					int offset = currentSelection.getOffset();
					int length = currentSelection.getLength();
					String text = currentSelection.getText();
					
					// If no selection (length == 0), use entire file range
					if (length == 0)
					{
						try
						{
							String source = module.getSource();
							if (source != null)
							{
								offset = 0;
								length = source.length();
								text = source;
							}
						}
						catch (Exception e)
						{
							// Fall through to return empty
						}
					}
					
					return SelectionInfo.create(
						currentFilePath,
						offset,
						length,
						text,
						module);
				}
			}
		}

		if (currentSelection != null)
		{
			// Return selection info with descriptive file path for console/view selections
			String viewSource = currentFilePath != null ? currentFilePath : "<Console View Selection>";
			int offset = currentSelection.getOffset();
			int length = currentSelection.getLength();
			String text = currentSelection.getText();

			// If nothing is selected (length == 0), use the full document text from Console
			if (length == 0 && currentFullDocumentText != null)
			{
				offset = 0;
				length = currentFullDocumentText.length();
				text = currentFullDocumentText;
			}

			return SelectionInfo.create(
				viewSource,
				offset,
				length,
				text,
				null);
		}

		return Optional.empty();
	}

	/**
	 * Checks if there is a valid selection.
	 * 
	 * @return true if a selection exists with length > 0
	 */
	public boolean hasSelection()
	{
		return currentSelection != null && currentSelection.getLength() > 0;
	}

	@Override
	public void selectionChanged(IWorkbenchPart part, ISelection selection)
	{
		if (selection instanceof ITextSelection textSelection)
		{
			IEditorPart editor = null;
			if (part instanceof IEditorPart editorPart)
			{
				editor = editorPart;
			}
			updateSelection(textSelection, editor);
		}
	}

	private void updateSelection(ITextSelection selection, IEditorPart editor)
	{
		synchronized (LOCK)
		{
			currentSelection = selection;
			activeEditor = editor;

			if (editor != null)
			{
				IEditorInput input = editor.getEditorInput();
				if (input != null)
				{
					ISourceModule module = DLTKUIPlugin.getEditorInputModelElement(input);
					if (module != null)
					{
						currentFilePath = module.getPath().toString();
					}
				}
				currentFullDocumentText = null;
			}
			else if (!currentSelection.isEmpty() && editor == null)
			{
				currentFilePath = "<Console View Selection>";
				currentFullDocumentText = extractFullDocumentText(currentSelection);
			}
			else
			{
				currentFilePath = null;
				currentFullDocumentText = null;
			}
		}
	}

	/**
	 * Extracts the full document text from a TextSelection using reflection.
	 * This is needed for Console selections where getText() returns empty when nothing is selected,
	 * but the underlying document contains all the console output.
	 * 
	 * @param selection the text selection
	 * @return full document text if available, null otherwise
	 */
	private String extractFullDocumentText(ITextSelection selection)
	{
		if (selection instanceof TextSelection)
		{
			try
			{
				// Access the private fDocument field in TextSelection
				Field field = TextSelection.class.getDeclaredField("fDocument");
				field.setAccessible(true);
				IDocument doc = (IDocument)field.get(selection);
				if (doc != null)
				{
					return doc.get(); // Get full document content
				}
			}
			catch (Exception e)
			{
				// Reflection failed, return null
			}
		}
		return null;
	}

	/**
	 * Disposes this selection tracker, unregistering from the selection service.
	 * Should be called on plugin shutdown.
	 */
	public void dispose()
	{
		if (selectionService != null && initialized)
		{
			selectionService.removeSelectionListener(this);
			initialized = false;
		}
	}

	/**
	 * Registers a listener for selection changes (optional feature for future use).
	 * 
	 * @param listener callback invoked when selection changes
	 */
	public void registerSelectionChangeListener(Consumer<SelectionInfo> listener)
	{
		// TODO: Implement if needed for future features
		// Store listeners in a list and notify them in updateSelection()
	}
}
