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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.servoypilot.util.IDocumentChangesPreviewManager;

public class UnifiedDiffMining extends LineHeaderCodeMining
{
	private final IDocumentChangesPreviewManager.PreviewChange change;
	private final Runnable onKeep;
	private final Runnable onUndo;
	private final Runnable onDiff;

	private Rectangle keepBtnRect;
	private Rectangle undoBtnRect;
	private Rectangle diffBtnRect;

	// Cached resources
	private Color greenBg;
	private Color darkGreen;
	private Color blue;
	private Color neutral;
	private Font normalFont;

	private int[] cachedBtnWidths;
	private int cachedTotalBtnsWidth;
	private String cachedTabSpaces;
	private String[] lines;
	private int totalHeight;
	private int customButtonHeight = -1;
	private String processedText;
	private String cachedLabel;

	private static final String[] BTN_LABELS = { "\u2714 Keep", "\u2716 Undo", "\u21C4 Diff" };
	private static final int BTN_SPACING = 6;
	private static final int BTN_RIGHT_MARGIN = 10;
	private static final int BTN_ARC = 8;
	private static final int BTN_PADDING_X = 12;
	private static final int LEFT_MARGIN = 20;

	public UnifiedDiffMining(int line,
		IDocument doc,
		ICodeMiningProvider provider,
		IDocumentChangesPreviewManager.PreviewChange change,
		Runnable onKeep,
		Runnable onUndo,
		Runnable onDiff) throws BadLocationException
	{
		super(line, doc, provider);
		this.change = change;
		this.onKeep = onKeep;
		this.onUndo = onUndo;
		this.onDiff = onDiff;
	}

	private void initResources(StyledText textWidget, GC gc)
	{
		Display display = textWidget.getDisplay();

		if (greenBg == null)
		{
			greenBg = new Color(display, 230, 255, 230);
			darkGreen = new Color(display, 0, 100, 0);
			blue = new Color(display, 43, 173, 223);
			neutral = new Color(display, 200, 200, 200);
		}

		if (normalFont == null)
		{
			FontData[] fd = gc.getFont().getFontData();
			for (FontData f : fd)
			{
				f.setStyle(SWT.NORMAL);
			}
			normalFont = new Font(display, fd);
			cachedBtnWidths = null; // font changed, remeasure
		}

		if (cachedBtnWidths == null)
		{
			Font prev = gc.getFont();
			gc.setFont(normalFont);
			cachedBtnWidths = new int[BTN_LABELS.length];
			cachedTotalBtnsWidth = 0;
			for (int i = 0; i < BTN_LABELS.length; i++)
			{
				cachedBtnWidths[i] = gc.textExtent(BTN_LABELS[i]).x + (BTN_PADDING_X * 2);
				cachedTotalBtnsWidth += cachedBtnWidths[i];
			}
			cachedTotalBtnsWidth += BTN_SPACING * (BTN_LABELS.length - 1);
			gc.setFont(prev);
		}
	}

	@Override
	public String getLabel()
	{
		return getText();
	}

	public String getText()
	{
		if (change == null || change.modifiedLine == null || change.modifiedLine.trim().isEmpty())
		{
			return " ";
		}

		if (cachedLabel != null)
		{
			return cachedLabel;
		}

		String[] logicalLines = change.modifiedLine.split("\\r?\\n");
		int estimatedVisualLines = 0;
		for (String line : logicalLines)
		{
			int len = line.replace("\t", "    ").length();
			estimatedVisualLines += Math.max(1, (int)Math.ceil(len / 80.0));
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < estimatedVisualLines; i++)
		{
			sb.append(" ");
			if (i < estimatedVisualLines - 1)
			{
				sb.append("\n");
			}
		}
		cachedLabel = sb.toString();
		return cachedLabel;
	}

	private List<String> wrapLine(GC gc, String line, int maxWidth)
	{
		List<String> wrapped = new ArrayList<>();
		if (maxWidth <= 0)
		{
			wrapped.add(line);
			return wrapped;
		}

		int textWidth = gc.textExtent(line).x;
		if (textWidth <= maxWidth)
		{
			wrapped.add(line);
			return wrapped;
		}

		int start = 0;
		while (start < line.length())
		{
			int end = line.length();
			while (end > start + 1 && gc.textExtent(line.substring(start, end)).x > maxWidth)
			{
				end = start + (end - start) / 2;
			}
			while (end < line.length() && gc.textExtent(line.substring(start, end + 1)).x <= maxWidth)
			{
				end++;
			}
			if (end == start)
			{
				end = start + 1;
			}
			wrapped.add(line.substring(start, end));
			start = end;
		}
		return wrapped;
	}

	@Override
	public Point draw(GC gc, StyledText textWidget, Color color, int x, int y)
	{
		if (textWidget == null || textWidget.isDisposed() || change == null)
		{
			return new Point(0, 0);
		}

		initResources(textWidget, gc);

		boolean isDeletion = change.modifiedLine == null || change.modifiedLine.trim().isEmpty();
		int lineHeight = textWidget.getLineHeight();
		int clientWidth = textWidget.getClientArea().width;

		// Recompute every time — lineHeight can change (zoom, font)
		customButtonHeight = lineHeight + 5;

		if (isDeletion)
		{
			totalHeight = customButtonHeight;
		}
		else
		{
			// Reprocess lines only if tab width changed
			int tabWidth = textWidget.getTabs();
			String newTabSpaces = " ".repeat(tabWidth);
			if (!newTabSpaces.equals(cachedTabSpaces) || lines == null)
			{
				cachedTabSpaces = newTabSpaces;
				processedText = change.modifiedLine.replace("\t", cachedTabSpaces);
				lines = processedText.split("\\r?\\n");
			}

			int availableWidth = clientWidth - LEFT_MARGIN;
			List<String> visualLines = new ArrayList<>();

			for (String line : lines)
			{
				List<String> wrappedSegments = wrapLine(gc, line, availableWidth);
				visualLines.addAll(wrappedSegments);
			}

			totalHeight = visualLines.size() * lineHeight;

			gc.setBackground(greenBg);
			gc.fillRectangle(0, y, clientWidth, totalHeight);

			gc.setForeground(darkGreen);
			for (int i = 0; i < visualLines.size(); i++)
			{
				int lineY = y + (i * lineHeight);
				gc.drawText("+", 5, lineY, true);
				gc.drawText(visualLines.get(i), LEFT_MARGIN, lineY, true);
			}
		}

		drawButtons(gc, textWidget, y, totalHeight, clientWidth);

		return new Point(clientWidth, totalHeight);
	}

	private void drawButtons(GC gc, StyledText text, int y, int height, int viewportWidth)
	{
		gc.setAntialias(SWT.ON);

		Font prev = gc.getFont();
		gc.setFont(normalFont); // use cached font, no new Font() here

		try
		{
			int btnH = customButtonHeight;
			int btnY = y + (height - btnH) / 2;
			int currentX = viewportWidth - cachedTotalBtnsWidth - BTN_RIGHT_MARGIN;

			Color[] btnColors = { blue, neutral, neutral };

			for (int i = 0; i < BTN_LABELS.length; i++)
			{
				Rectangle rect = new Rectangle(currentX, btnY, cachedBtnWidths[i], btnH);

				if (i == 0)
				{
					keepBtnRect = rect;
				}
				else if (i == 1)
				{
					undoBtnRect = rect;
				}
				else
				{
					diffBtnRect = rect;
				}

				gc.setBackground(btnColors[i]);
				gc.fillRoundRectangle(rect.x, rect.y, rect.width, rect.height, BTN_ARC, BTN_ARC);

				gc.setForeground(text.getDisplay().getSystemColor(SWT.COLOR_WHITE));
				Point ts = gc.textExtent(BTN_LABELS[i]);
				gc.drawText(BTN_LABELS[i], rect.x + (rect.width - ts.x) / 2, rect.y + (rect.height - ts.y) / 2, true);

				currentX += cachedBtnWidths[i] + BTN_SPACING;
			}
		}
		finally
		{
			gc.setFont(prev);
		}
	}

	@Override
	public Consumer<MouseEvent> getAction()
	{
		return e -> {
			if (keepBtnRect != null && keepBtnRect.contains(e.x, e.y) && onKeep != null)
			{
				onKeep.run();
			}
			else if (undoBtnRect != null && undoBtnRect.contains(e.x, e.y) && onUndo != null)
			{
				onUndo.run();
			}
			else if (diffBtnRect != null && diffBtnRect.contains(e.x, e.y) && onDiff != null)
			{
				onDiff.run();
			}
		};
	}

	@Override
	protected CompletableFuture<Void> doResolve(ITextViewer viewer, IProgressMonitor monitor)
	{
		return CompletableFuture.runAsync(() -> {
			super.setLabel(getText());
		});
	}

	@Override
	public void dispose()
	{
		keepBtnRect = null;
		undoBtnRect = null;
		diffBtnRect = null;

		if (greenBg != null)
		{
			greenBg.dispose();
			greenBg = null;
		}
		if (darkGreen != null)
		{
			darkGreen.dispose();
			darkGreen = null;
		}
		if (blue != null)
		{
			blue.dispose();
			blue = null;
		}
		if (neutral != null)
		{
			neutral.dispose();
			neutral = null;
		}
		if (normalFont != null)
		{
			normalFont.dispose();
			normalFont = null;
		}

		super.dispose();
	}
}