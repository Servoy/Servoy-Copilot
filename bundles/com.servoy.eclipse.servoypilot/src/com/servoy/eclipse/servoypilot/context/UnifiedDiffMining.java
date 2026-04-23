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

		String[] lines = change.modifiedLine.split("\\r?\\n");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++)
		{
			sb.append(" ");
			if (i < lines.length - 1)
			{
				sb.append("\n");
			}
		}

		return sb.toString();
	}

	@Override
	public Point draw(GC gc, StyledText textWidget, Color color, int x, int y)
	{
		if (textWidget == null || textWidget.isDisposed() || change == null)
		{
			return new Point(0, 0);
		}

		// 1. Check if this is a deletion (no new text to show)
		boolean isDeletion = change.modifiedLine == null || change.modifiedLine.trim().isEmpty();

		int lineHeight = textWidget.getLineHeight();
		int leftPadding = 5;
		int width = textWidget.getClientArea().width;
		int totalHeight;

		String[] lines = new String[0];
		int customButtonHeight = textWidget.getLineHeight() + 5;

		if (isDeletion)
		{
			// For deletes, we only need a single line height to show the buttons
			totalHeight = customButtonHeight;
		}
		else
		{
			// 2. Process indentation for Inserts/Replacements
			int tabWidth = textWidget.getTabs();
			String tabSpaces = " ".repeat(tabWidth);
			String processedText = change.modifiedLine.replace("\t", tabSpaces);
			lines = processedText.split("\\r?\\n");

			totalHeight = lines.length * lineHeight;

			// Measure text for width
			Point textBlockSize = gc.textExtent("+ " + processedText);
			width = Math.max(width, textBlockSize.x + (leftPadding * 2));
		}

		Display display = textWidget.getDisplay();
		Color greenBg = new Color(display, 230, 255, 230);
		Color darkGreen = new Color(display, 0, 100, 0);
		Color blue = new Color(display, 43, 173, 223);
		Color neutral = new Color(display, 200, 200, 200);

		try
		{
			// 3. Only draw the background and text if NOT a deletion
			if (!isDeletion)
			{
				gc.setBackground(greenBg);
				gc.fillRectangle(0, y, width, totalHeight);

				gc.setForeground(darkGreen);
				for (int i = 0; i < lines.length; i++)
				{
					int lineY = y + (i * lineHeight);
					gc.drawText("+", leftPadding, lineY, true);
					gc.drawText(lines[i], 0, lineY, true);
				}
			}

			// 4. Always draw buttons (at the end of the line/block)
			drawButtons(gc, textWidget, y, totalHeight, width, blue, neutral);
		}
		finally
		{
			greenBg.dispose();
			darkGreen.dispose();
			blue.dispose();
			neutral.dispose();
		}

		return new Point(width, totalHeight);
	}

	private void drawButtons(GC gc, StyledText text, int y, int height, int width, Color blue, Color neutral)
	{
		gc.setAntialias(SWT.ON);

		// 1. Force the font to be NORMAL
		Font originalFont = gc.getFont();
		FontData[] fontData = originalFont.getFontData();
		for (FontData fd : fontData)
		{
			fd.setStyle(SWT.NORMAL); // Remove Bold or Italic styles
		}
		Font normalFont = new Font(text.getDisplay(), fontData);
		gc.setFont(normalFont);

		try
		{
			int btnH = text.getLineHeight() + 5;
			int spacing = 6;
			int rightMargin = 10;
			int ARC = 8;
			int PADDING_X = 12;

			String[] labels = { "✔ Keep", "✖ Undo", "⇄ Diff" };
			Color[] btnColors = { blue, neutral, neutral };

			// Measure and calculate starting X
			int totalBtnsWidth = 0;
			int[] btnWidths = new int[labels.length];
			for (int i = 0; i < labels.length; i++)
			{
				btnWidths[i] = gc.textExtent(labels[i]).x + (PADDING_X * 2);
				totalBtnsWidth += btnWidths[i];
			}
			totalBtnsWidth += (spacing * (labels.length - 1));

			int currentX = width - totalBtnsWidth - rightMargin;
			int btnY = y + (height - btnH) / 2;

			for (int i = 0; i < labels.length; i++)
			{
				Rectangle rect = new Rectangle(currentX, btnY, btnWidths[i], btnH);

				// Save rects for MouseListener
				if (i == 0)
				{
					keepBtnRect = rect;
				}
				else if (i == 1)
				{
					undoBtnRect = rect;
				}
				else if (i == 2)
				{
					diffBtnRect = rect;
				}

				// Draw Rounded Background
				gc.setBackground(btnColors[i]);
				gc.fillRoundRectangle(rect.x, rect.y, rect.width, rect.height, ARC, ARC);

				// Draw White Text (Now guaranteed to be normal/upright)
				gc.setForeground(text.getDisplay().getSystemColor(SWT.COLOR_WHITE));
				Point textSize = gc.textExtent(labels[i]);
				int textX = rect.x + (rect.width - textSize.x) / 2;
				int textY = rect.y + (rect.height - textSize.y) / 2;
				gc.drawText(labels[i], textX, textY, true);

				currentX += btnWidths[i] + spacing;
			}
		}
		finally
		{
			gc.setFont(originalFont);
			normalFont.dispose();
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
}