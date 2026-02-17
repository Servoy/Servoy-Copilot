package com.servoy.eclipse.servoypilot.context;

import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.ui.DLTKUIPlugin;
import org.eclipse.jface.text.ITextSelection;
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
						module
					);
				}
			}
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
			}
		}
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
