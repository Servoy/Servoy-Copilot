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
package com.servoy.eclipse.servoypilot.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.dltk.internal.ui.editor.ScriptEditor;
import org.eclipse.jface.text.DocumentRewriteSession;
import org.eclipse.jface.text.DocumentRewriteSessionType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileCompareEditorInput;
import com.servoy.eclipse.servoypilot.dto.SourceEdit;
import com.servoy.eclipse.servoypilot.services.CompareEditorService;

/**
 * Manages the interactive inline "ghost" preview of AI-generated code changes 
 * within a {@code ScriptEditor}.
 * <p>The manager is responsible for:
 * <ul>
 * <li><b>Visual Diffing:</b> Highlighting added lines in green and 
 * removed lines in red using editor annotations.</li>
 * <li><b>Interaction:</b> Displaying floating action buttons (e.g., <b>Keep</b>, 
 * <b>Undo</b>, <b>Diff</b>) to allow the user to accept or reject changes.</li>
 * <li><b>Lifecycle:</b> Cleaning up previous previews and disposing of 
 * listeners/widgets when a new fix is proposed or the preview is closed.</li>
 * </ul>
 * </p>
 * <p>This class operates directly on the {@link org.eclipse.jface.text.IDocument} 
 * to insert temporary projections or overlays without permanently modifying 
 * the underlying file until the "Keep" action is triggered.</p>
 * @see com.servoy.eclipse.servoypilot.dto.SourceEdit
 * @author emera
 */
public class InlineDocumentChangesPreviewManager implements IDocumentChangesPreviewManager
{
	private String originalContent;
	private LineBackgroundListener backgroundListener;
	private PaintListener paintListener;

	private final Set<Integer> removedLines = new HashSet<>();

	//private final List<PreviewChange> previewChanges = new ArrayList<>(); //TODO rem preview changes

	private final List<Color> colors = new ArrayList<>();
	private FileCompareEditorInput compareEditorInput;
	private StyledText textWidget;

	private ScriptEditor scriptEditor;

	private static final Map<IDocument, List<PreviewChange>> activeChangesMap = new ConcurrentHashMap<>();

	public static List<PreviewChange> getActiveChanges(IDocument doc)
	{
		return activeChangesMap.get(doc);
	}

	public InlineDocumentChangesPreviewManager(ScriptEditor scriptEditor)
	{
		super();
		this.scriptEditor = scriptEditor;
	}

	@Override
	public void preview(List<SourceEdit> sourceEdits) throws Exception
	{
		ISourceViewer viewer = scriptEditor.getViewer();
		if (viewer == null || viewer.getTextWidget() == null)
		{
			return;
		}

		textWidget = viewer.getTextWidget();
		IDocument document = viewer.getDocument();

		originalContent = document.get();
		Display display = textWidget.getDisplay();
		Color removedColor = new Color(display, 255, 230, 230);
		colors.add(removedColor);
		removedLines.clear();
		activeChangesMap.remove(document); // Clear old map entry

		if (sourceEdits == null || sourceEdits.isEmpty())
		{
			return;
		}

		try
		{
			textWidget.setRedraw(false);
			calculateEdits(document, sourceEdits);

			if (viewer instanceof ISourceViewerExtension5 ext5)
			{
				ext5.updateCodeMinings();
			}

			// setup Red Background Listeners (for the deleted lines)
			setupVisualListeners(removedColor);

		}
		finally
		{
			textWidget.setRedraw(true);
			textWidget.redraw();
		}
		//TODO add focus listener to refresh the minings
	}

	private void setupVisualListeners(Color removedColor)
	{
		backgroundListener = event -> {
			try
			{
				int docOffset = event.lineOffset;
				if (scriptEditor.getViewer() instanceof org.eclipse.jface.text.ITextViewerExtension5 ext5)
				{
					docOffset = ext5.widgetOffset2ModelOffset(event.lineOffset);
				}
				int line = scriptEditor.getViewer().getDocument().getLineOfOffset(docOffset);
				if (removedLines.contains(line))
				{
					event.lineBackground = removedColor;
				}
			}
			catch (Exception ex)
			{
			}
		};
		textWidget.addLineBackgroundListener(backgroundListener);

		paintListener = e -> {
			drawDiffDecorations(e.gc, removedLines, "-", removedColor, textWidget.getClientArea().width);
		};
		textWidget.addPaintListener(paintListener);
	}

	@Override
	public void handleAppliedChange(SourceEdit edit, PreviewChange change, int startLine, String original, String replacement)
	{
		IDocument doc = scriptEditor.getViewer().getDocument();
		activeChangesMap.computeIfAbsent(doc, k -> new ArrayList<>()).add(change);

		int originalLineCount = edit.isInsert() ? 0 : countLines(original);

		if (!edit.isInsert())
		{
			for (int i = 0; i < originalLineCount; i++)
			{
				removedLines.add(startLine + i);
			}
		}
	}

	private void drawDiffDecorations(GC gc, Set<Integer> lines, String symbol, Color bgColor, int width)
	{
		if (lines.isEmpty() || textWidget.isDisposed())
		{
			return;
		}

		ISourceViewer viewer = scriptEditor.getViewer();
		if (viewer == null)
		{
			return;
		}
		IDocument document = viewer.getDocument();
		org.eclipse.jface.text.ITextViewerExtension5 ext5 = (viewer instanceof org.eclipse.jface.text.ITextViewerExtension5)
			? (org.eclipse.jface.text.ITextViewerExtension5)viewer : null;

		List<Integer> sortedLines = new ArrayList<>(lines);
		Collections.sort(sortedLines);
		Rectangle clipping = gc.getClipping();

		int i = 0;
		while (i < sortedLines.size())
		{
			int startDocLine = sortedLines.get(i);
			int endDocLine = startDocLine;
			while (i + 1 < sortedLines.size() && sortedLines.get(i + 1) == endDocLine + 1)
			{
				endDocLine = sortedLines.get(++i);
			}
			i++;

			try
			{
				int startDocOffset = document.getLineOffset(startDocLine);
				int endDocOffset = document.getLineOffset(endDocLine);

				// Convert to widget offsets (handles folding safely)
				int startWidgetOffset = ext5 != null ? ext5.modelOffset2WidgetOffset(startDocOffset) : startDocOffset;
				int endWidgetOffset = ext5 != null ? ext5.modelOffset2WidgetOffset(endDocOffset) : endDocOffset;

				if (startWidgetOffset == -1 && endWidgetOffset == -1)
				{
					continue;
				}
				if (startWidgetOffset == -1)
				{
					startWidgetOffset = endWidgetOffset;
				}
				if (endWidgetOffset == -1)
				{
					endWidgetOffset = startWidgetOffset;
				}

				int startY = textWidget.getLocationAtOffset(startWidgetOffset).y;
				int endY = textWidget.getLocationAtOffset(endWidgetOffset).y;
				int endLineHeight = textWidget.getLineHeight(endWidgetOffset);
				int height = (endY + endLineHeight) - startY;

				// Skip if completely out of view
				if (startY + height < clipping.y || startY > clipping.y + clipping.height)
				{
					continue;
				}

				gc.setForeground(gc.getDevice().getSystemColor(SWT.COLOR_GRAY));
				gc.setLineWidth(1);
				gc.setLineStyle(SWT.LINE_SOLID);
				gc.drawRectangle(0, startY, width - 1, height - 1);

				gc.setForeground(gc.getDevice().getSystemColor(SWT.COLOR_BLACK));
				Font oldFont = gc.getFont();

				// Draw symbols on visible lines
				for (int line = startDocLine; line <= endDocLine; line++)
				{
					int docOffset = document.getLineOffset(line);
					int wOffset = ext5 != null ? ext5.modelOffset2WidgetOffset(docOffset) : docOffset;
					if (wOffset != -1)
					{
						int lineY = textWidget.getLocationAtOffset(wOffset).y;
						gc.drawString(symbol, 5, lineY + 2, true);
					}
				}
				gc.setFont(oldFont);

			}
			catch (Exception ex)
			{
				// Line might not be visible or disposed
			}
		}
	}

	private int countLines(String text)
	{
		if (text == null || text.isEmpty())
		{
			return 0;
		}
		int len = text.split("\\r?\\n", -1).length;
		return text.endsWith("\n") ? len - 1 : len;
	}

	@Override
	public void clearPreview()
	{
		// Trigger a UI refresh to remove the drawings from the editor
		// We use asyncExec to ensure we are on the UI thread
		Display.getDefault().asyncExec(() -> {
			if (scriptEditor != null && scriptEditor.getViewer() != null && !scriptEditor.getViewer().getTextWidget().isDisposed())
			{
				ISourceViewer viewer = scriptEditor.getViewer();
				List<PreviewChange> changes = activeChangesMap.get(viewer.getDocument());
				if (changes != null)
				{
					changes.clear();
				}
				if (viewer instanceof ISourceViewerExtension5 extension)
				{
					extension.updateCodeMinings();
				}

				cleanup();
			}
		});
	}

	private void cleanup()
	{
		try
		{
			ISourceViewer viewer = scriptEditor.getViewer();
			if (viewer == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
			{
				return;
			}

			StyledText textWidget = viewer.getTextWidget();
			IDocument document = viewer.getDocument();

			// 1. Clear the old, stale line numbers completely
			removedLines.clear();

			// 2. Check remaining changes
			List<PreviewChange> remainingChanges = activeChangesMap.get(document);
			boolean hasActiveChanges = (remainingChanges != null && !remainingChanges.isEmpty());

			if (hasActiveChanges)
			{
				// 3. REBUILD the removedLines list using the automatically-updated Positions
				for (PreviewChange activeChange : remainingChanges)
				{
					Position pos = activeChange.getPosition();
					if (pos != null && !pos.isDeleted())
					{
						int startOffset = pos.getOffset();
						// DO NOT use activeChange.endOffset! Use the dynamic Position length.
						int length = pos.getLength();
						int endOffset = startOffset + Math.max(0, length - 1);

						int startLine = document.getLineOfOffset(startOffset);
						int endLine = document.getLineOfOffset(endOffset);

						for (int i = startLine; i <= endLine; i++)
						{
							removedLines.add(i); // This is guaranteed to be the CORRECT new line number
						}
					}
				}
			}
			else
			{
				// 4. Full Teardown if no changes are left
				if (backgroundListener != null)
				{
					textWidget.removeLineBackgroundListener(backgroundListener);
					textWidget.removePaintListener(paintListener);
					backgroundListener = null;
					paintListener = null;
				}

				for (Color color : colors)
				{
					if (color != null && !color.isDisposed())
					{
						color.dispose();
					}
				}
				colors.clear();

				if (compareEditorInput != null)
				{
					CompareEditorService.getInstance().closeCompareEditor(compareEditorInput);
					compareEditorInput = null;
				}
			}

			// 5. Redraw the text widget to apply the fresh, accurate lines
			textWidget.redraw();
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error on cleanup for specific change.", e);
		}
	}

	public void toggleDiffEditor()
	{
		if (compareEditorInput == null)
		{
			IEditorInput input = scriptEditor.getEditorInput();
			IFile file = input.getAdapter(IFile.class);
			if (file == null && input instanceof IFileEditorInput)
			{
				file = ((IFileEditorInput)input).getFile();
			}
			IDocument document = scriptEditor.getDocumentProvider()
				.getDocument(scriptEditor.getEditorInput());

			CompareEditorService compareService = CompareEditorService.getInstance();
			try
			{
				compareEditorInput = compareService.openCompareEditor(file.getName(), originalContent, buildModifiedContent(document));
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error opening diff editor", e);
			}
		}
		else
		{
			CompareEditorService.getInstance().closeCompareEditor(compareEditorInput);
			compareEditorInput = null;
		}
	}

	private String buildModifiedContent(IDocument document) throws Exception
	{
		StringBuilder builder = new StringBuilder(document.get());
		List<PreviewChange> previewChanges = activeChangesMap.get(document);
		for (int i = 0; i < previewChanges.size(); i++)
		{
			PreviewChange change = previewChanges.get(i);
			builder.replace(
				change.startOffset,
				change.startOffset + change.originalLength,
				change.modifiedLine + change.lineDelimiter);
		}
		return builder.toString();
	}

	public List<PreviewChange> getPreviewChanges()
	{
		return new ArrayList<>(activeChangesMap.getOrDefault(scriptEditor.getViewer().getDocument(), Collections.emptyList()));
	}

	public ScriptEditor getEditor()
	{
		return scriptEditor;
	}


	public void accept(PreviewChange change)
	{
		DocumentRewriteSession docRewriteSession = null;
		IDocument document = null;
		try
		{
			ISourceViewer viewer = scriptEditor.getViewer();
			document = viewer.getDocument();

			if (document instanceof IDocumentExtension4 docextension4)
			{
				docRewriteSession = docextension4.startRewriteSession(DocumentRewriteSessionType.UNRESTRICTED_SMALL);
			}

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
			activeChangesMap.get(document).remove(change);
			cleanup();
			if (viewer instanceof ISourceViewerExtension5 extension)
			{
				extension.updateCodeMinings();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Cannot accept source modification", e);
		}
		finally
		{
			if (document instanceof IDocumentExtension4 docextension4 && docRewriteSession != null)
			{
				docextension4.stopRewriteSession(docRewriteSession);
			}
		}
	}

	public void reject(PreviewChange change)
	{
		try
		{
			IDocument document = scriptEditor.getViewer().getDocument();
			document.removePosition(change.getPosition());
			activeChangesMap.get(document).remove(change);
			cleanup();
			ISourceViewer viewer = scriptEditor.getViewer();
			if (viewer instanceof ISourceViewerExtension5 extension)
			{
				extension.updateCodeMinings();
				viewer.invalidateTextPresentation();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Cannot reject source modification", e);
		}
	}
}