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

package com.servoy.eclipse.servoypilot.quickfix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.dltk.internal.ui.editor.ScriptEditor;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.chatview.parts.FileCompareEditorInput;
import com.servoy.eclipse.servoypilot.services.CodeFormattingService;
import com.servoy.eclipse.servoypilot.services.CompareEditorService;
import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.eclipse.servoypilot.tools.dto.SourceEdit;

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
 * @see com.servoy.eclipse.servoypilot.ai.SourceEdit
 * @author emera
 */
public class InlineDocumentChangesPreviewManager implements IDocumentChangesPreviewManager
{
	private String originalContent;
	private LineBackgroundListener backgroundListener;
	private PaintListener paintListener;
	private Composite floatingBar;

	private final Set<Integer> addedLines = new HashSet<>();
	private final Set<Integer> removedLines = new HashSet<>();

	private final List<PreviewChange> previewChanges = new ArrayList<>();

	private final List<Color> colors = new ArrayList<>();
	private FileCompareEditorInput compareEditorInput;
	private StyledText textWidget;

	private ScriptEditor scriptEditor;

	private static class PreviewChange
	{
		int startOffset;
		int originalLength;
		String modifiedLine;
		String lineDelimiter;
		String originalLine;
		public boolean isInsert;
	}

	public InlineDocumentChangesPreviewManager(ScriptEditor scriptEditor)
	{
		super();
		this.scriptEditor = scriptEditor;
	}

	@Override
	public void preview(
		List<SourceEdit> sourceEdits) throws Exception
	{
		ISourceViewer viewer = scriptEditor.getViewer();
		if (viewer == null)
		{
			return;
		}

		textWidget = viewer.getTextWidget();
		IDocument document = viewer.getDocument();

		originalContent = document.get();

		addedLines.clear();
		removedLines.clear();
		previewChanges.clear();

		Display display = textWidget.getDisplay();
		Color addedColor = new Color(display, 230, 255, 230);
		Color removedColor = new Color(display, 255, 230, 230);
		colors.add(addedColor);
		colors.add(removedColor);
		removedLines.clear();
		addedLines.clear();

		List<SourceEdit> sortedEdits = new ArrayList<>(sourceEdits);
		sortedEdits.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

		for (SourceEdit edit : sortedEdits)
		{
			int startLine = edit.startLine() - 1;
			Statement statement = ParserService.getInstance().getStatementAtOffset(document.get(), document.getLineOffset(startLine));
			int endLine = document.getLineOfOffset(statement.sourceEnd());

			int startOffset = document.getLineOffset(startLine);
			int endOffset = document.getLineOffset(endLine) + document.getLineLength(endLine);

			String originalStatement = document.get(startOffset, endOffset - startOffset);
			String lineDelimiter = document.getLineDelimiter(startLine);
			if (lineDelimiter == null)
			{
				lineDelimiter = "\n";
			}

			String indentedReplacement = CodeFormattingService.getInstance()
				.format(edit.replacement(), document, startOffset);

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

			if (edit.isInsert())
			{
				document.replace(startOffset, 0, indentedReplacement + lineDelimiter);
			}
			else
			{
				document.replace(startOffset, endOffset - startOffset, previewBlock);
			}

			int originalLineCount = edit.isInsert() ? 0 : countLines(originalStatement);
			int addedLinesCount = countLines(edit.replacement());
			if (!edit.isInsert())
			{
				for (int i = 0; i < originalLineCount; i++)
				{
					removedLines.add(startLine + i);
				}
			}
			if (!edit.isDelete())
			{
				for (int i = 0; i < addedLinesCount; i++)
				{
					addedLines.add(startLine + originalLineCount + i);
				}
			}

			PreviewChange change = new PreviewChange();
			change.startOffset = startOffset;
			change.originalLength = previewBlock.length();
			change.modifiedLine = edit.replacement();
			change.originalLine = originalStatement;
			change.lineDelimiter = lineDelimiter;
			change.isInsert = edit.isInsert();
			previewChanges.add(change);
		}

		backgroundListener = event -> {
			int lineIndex = textWidget.getLineAtOffset(event.lineOffset);
			if (removedLines.contains(lineIndex))
			{
				event.lineBackground = removedColor;
			}
			else if (addedLines.contains(lineIndex))
			{
				event.lineBackground = addedColor;
			}
		};

		textWidget.addLineBackgroundListener(backgroundListener);

		paintListener = e -> {
			GC gc = e.gc;
			int clientWidth = textWidget.getClientArea().width;
			drawDiffDecorations(gc, removedLines, "-", removedColor, clientWidth);
			drawDiffDecorations(gc, addedLines, "+", addedColor, clientWidth);
		};
		textWidget.addPaintListener(paintListener);
		textWidget.redraw();

		showAcceptRejectUI(scriptEditor);
	}

	private void drawDiffDecorations(GC gc, Set<Integer> lines, String symbol, Color bgColor, int width)
	{
		if (lines.isEmpty())
		{
			return;
		}
		List<Integer> sortedLines = new ArrayList<>(lines);
		Collections.sort(sortedLines);

		int i = 0;
		while (i < sortedLines.size())
		{
			int startLine = sortedLines.get(i);
			int endLine = startLine;
			while (i + 1 < sortedLines.size() && sortedLines.get(i + 1) == endLine + 1)
			{
				endLine = sortedLines.get(++i);
			}
			i++;
			try
			{
				int startY = textWidget.getLinePixel(startLine);
				int endY = textWidget.getLinePixel(endLine) + textWidget.getLineHeight(textWidget.getOffsetAtLine(endLine));
				int height = endY - startY;
				gc.setForeground(gc.getDevice().getSystemColor(SWT.COLOR_GRAY));
				gc.setLineWidth(1);
				gc.setLineStyle(SWT.LINE_SOLID);
				gc.drawRectangle(0, startY, width - 1, height);

				gc.setForeground(gc.getDevice().getSystemColor(SWT.COLOR_BLACK));
				Font oldFont = gc.getFont();
				for (int line = startLine; line <= endLine; line++)
				{
					int lineY = textWidget.getLinePixel(line);
					gc.drawString(symbol, 5, lineY + 2, true);
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

	private void accept()
	{
		try
		{
			ISourceViewer viewer = scriptEditor.getViewer();
			IDocument document = viewer.getDocument();

			for (PreviewChange change : previewChanges)
			{
				int line = document.getLineOfOffset(change.startOffset);
				int offset = document.getLineOffset(line);
				if (!change.isInsert)
				{
					//if it's an insert it's already in the document, so we don't need to do anything to "accept" it.
					document.replace(offset, change.originalLength, change.modifiedLine + change.lineDelimiter);
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Cannot accept source modification", e);
		}

		cleanup();
	}

	private void reject()
	{
		try
		{
			ISourceViewer viewer = scriptEditor.getViewer();
			IDocument document = viewer.getDocument();

			for (PreviewChange change : previewChanges)
			{
				int line = document.getLineOfOffset(change.startOffset);
				int offset = document.getLineOffset(line);
				if (change.isInsert)
				{
					// To "reject" an insertion, we replace the inserted length 
					// (which was change.modifiedLine.length()) back to nothing.
					// Note: This assumes the document currently contains the modifiedLine.
					int insertedLength = change.modifiedLine.length() + change.lineDelimiter.length();
					document.replace(offset, insertedLength, "");
				}
				else
				{
					document.replace(
						offset,
						change.originalLength,
						change.originalLine);
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Cannot revert the proposed source modification", e);
		}

		cleanup();
	}

	@Override
	public void clearPreview()
	{
		reject();
		cleanup();
	}

	private void cleanup()
	{
		try
		{
			ISourceViewer viewer = scriptEditor.getViewer();
			StyledText textWidget = viewer.getTextWidget();

			if (backgroundListener != null)
			{
				textWidget.removeLineBackgroundListener(backgroundListener);
				textWidget.removePaintListener(paintListener);
				backgroundListener = null;
			}
			for (Color color : colors)
			{
				color.dispose();
			}

			addedLines.clear();
			removedLines.clear();
			previewChanges.clear();

			disposeFloatingBar();

			textWidget.redraw();

			if (compareEditorInput != null)
			{
				CompareEditorService.getInstance().closeCompareEditor(compareEditorInput);
				compareEditorInput = null;
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error on cleanup.", e);
		}
	}

	private void showAcceptRejectUI(ScriptEditor scriptEditor)
	{
		StyledText text = scriptEditor.getViewer().getTextWidget();

		if (floatingBar != null && !floatingBar.isDisposed())
		{
			floatingBar.dispose();
		}

		floatingBar = new Composite(text.getShell(), SWT.DOUBLE_BUFFERED | SWT.NO_TRIM | SWT.ON_TOP);
		floatingBar.moveAbove(null);

		RowLayout layout = new RowLayout(SWT.HORIZONTAL);
		layout.marginTop = 3;
		layout.marginBottom = 0;
		layout.marginLeft = 0;
		layout.marginRight = 0;
		layout.spacing = 6;
		layout.wrap = false;
		layout.center = true;
		floatingBar.setLayout(layout);
		int lineHeight = text.getLineHeight() + 5;
		Point size = floatingBar.computeSize(SWT.DEFAULT, lineHeight);
		floatingBar.setSize(size.x, lineHeight);

		Color blue = new Color(text.getDisplay(), 43, 173, 223);
		colors.add(blue);
		Color blueHover = new Color(text.getDisplay(), 30, 155, 205);
		colors.add(blueHover);
		Color neutral = new Color(text.getDisplay(), 200, 200, 200);
		colors.add(neutral);
		Color neutralHover = new Color(text.getDisplay(), 170, 170, 170);
		colors.add(neutralHover);

		createStyledButton(
			floatingBar,
			text,
			"✔ Keep",
			blue,
			blueHover,
			() -> {
				accept();
				disposeFloatingBar();
			});

		createStyledButton(
			floatingBar,
			text,
			"✖ Undo",
			neutral,
			neutralHover,
			() -> {
				reject();
				disposeFloatingBar();
			});

		createStyledButton(
			floatingBar,
			text,
			"⇄ Diff",
			neutral,
			neutralHover,
			() -> {
				toggleDiffEditor();
			});

		floatingBar.pack();
		positionFloatingBar(text);

		// Reposition on scroll
		text.addListener(SWT.MouseWheel, e -> positionFloatingBar(text));
		text.addListener(SWT.Resize, e -> positionFloatingBar(text));
		text.addListener(SWT.KeyDown, e -> positionFloatingBar(text));
		text.getHorizontalBar().addListener(SWT.Selection, e -> positionFloatingBar(text));
		text.getVerticalBar().addListener(SWT.Selection, e -> positionFloatingBar(text));

		floatingBar.setVisible(true);

		text.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				if (floatingBar != null && !floatingBar.isDisposed())
				{
					floatingBar.setVisible(false);
				}
			}

			@Override
			public void focusGained(FocusEvent e)
			{
				if (floatingBar != null && !floatingBar.isDisposed())
				{
					floatingBar.setVisible(true);
					positionFloatingBar(text);
				}
			}
		});
	}

	private Control createStyledButton(
		Composite parent,
		StyledText styledText,
		String text,
		Color normalBg,
		Color hoverBg,
		Runnable action)
	{
		Canvas button = new Canvas(parent, SWT.DOUBLE_BUFFERED);
		Display display = parent.getDisplay();

		final int ARC = 8;
		final int PADDING_X = 12;
		final int lineHeight = styledText.getLineHeight() + 5;
		button.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));


		final boolean[] hovered = { false };
		button.addPaintListener(e -> {
			GC gc = e.gc;
			gc.setAntialias(SWT.ON);
			Rectangle bounds = button.getClientArea();
			gc.setBackground(hovered[0] ? hoverBg : normalBg);
			gc.fillRoundRectangle(
				bounds.x,
				bounds.y,
				bounds.width - 1,
				bounds.height - 1,
				ARC,
				ARC);
			gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
			Point textSize = gc.textExtent(text);
			int textX = bounds.x + (bounds.width - textSize.x) / 2;
			int textY = bounds.y + (bounds.height - textSize.y) / 2;
			gc.drawText(text, textX, textY, true);
		});

		button.addListener(SWT.MouseEnter, e -> {
			hovered[0] = true;
			button.redraw();
		});

		button.addListener(SWT.MouseExit, e -> {
			hovered[0] = false;
			button.redraw();
		});

		button.addListener(SWT.MouseUp, e -> {
			if (action != null)
			{
				action.run();
			}
		});

		GC gc = new GC(button);
		Point textSize = gc.textExtent(text);
		gc.dispose();

		int width = textSize.x + PADDING_X * 2;
		button.setSize(width, lineHeight);
		RowData rd = new RowData();
		rd.height = lineHeight;
		button.setLayoutData(rd);

		return button;
	}

	private void positionFloatingBar(StyledText text)
	{
		if (floatingBar == null || floatingBar.isDisposed())
		{
			return;
		}

		int targetLine;

		if (!addedLines.isEmpty())
		{
			targetLine = addedLines.iterator().next();
		}
		else if (!removedLines.isEmpty())
		{
			targetLine = removedLines.stream().max(Integer::compareTo).orElse(-1);
		}
		else
		{
			return;
		}

		try
		{
			int lineHeight = text.getLineHeight();
			int yInText = text.getLinePixel(targetLine);
			Point displayPoint = text.toDisplay(0, yInText);
			if (yInText < 0 || yInText > text.getClientArea().height - lineHeight)
			{
				floatingBar.setVisible(false); // hide if line not visible
				return;
			}
			floatingBar.setVisible(true);
			Point size = floatingBar.computeSize(SWT.DEFAULT, lineHeight);
			int x = displayPoint.x + text.getClientArea().width - size.x - 10;
			int y = displayPoint.y - size.y - 10;

			floatingBar.setBounds(x, y, size.x, lineHeight + 10);
		}
		catch (Exception ignore)
		{
		}
	}

	private void disposeFloatingBar()
	{
		if (floatingBar != null && !floatingBar.isDisposed())
		{
			floatingBar.dispose();
		}
	}

	private void toggleDiffEditor()
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
}