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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;

import com.servoy.eclipse.servoypilot.util.IDocumentChangesPreviewManager;
import com.servoy.eclipse.servoypilot.util.InlineDocumentChangesPreviewManager;

/**
 * @author emera
 */
public class AiPreviewCodeMiningProvider implements ICodeMiningProvider
{

	@Override
	public CompletableFuture<List< ? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
		IProgressMonitor monitor)
	{
		return CompletableFuture.supplyAsync(() -> {

			List<ICodeMining> minings = new ArrayList<>();
			IDocument document = viewer.getDocument();

			List<IDocumentChangesPreviewManager.PreviewChange> changes = InlineDocumentChangesPreviewManager.getActiveChanges(document);

			if (changes == null || changes.isEmpty())
			{
				return minings;
			}

			InlineDocumentChangesPreviewManager manager = QuickFixPresenter.getInstance().getManagerFor(document);

			for (IDocumentChangesPreviewManager.PreviewChange change : changes)
			{
				try
				{
					Position pos = change.getPosition();
					if (pos == null || pos.isDeleted())
					{
						continue;
					}

					int line = document.getLineOfOffset(pos.getOffset());
					if (!change.isInsert)
					{
						// Anchor replacements/deletes to the end of the affected range
						line = document.getLineOfOffset(pos.getOffset() + change.originalLength);
					}

					minings.add(new UnifiedDiffMining(
						line,
						document,
						this,
						change,
						() -> manager.accept(change),
						() -> manager.reject(change),
						() -> manager.toggleDiffEditor()));


				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
			return minings;
		});
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.text.codemining.ICodeMiningProvider#dispose()
	 */
	@Override
	public void dispose()
	{
		// TODO Auto-generated method stub

	}
}