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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.dltk.ui.editor.IScriptAnnotation;
import org.eclipse.dltk.ui.text.IScriptCorrectionContext;
import org.eclipse.dltk.ui.text.IScriptCorrectionProcessor;

public class ServoyAICorrectionProcessor implements IScriptCorrectionProcessor
{
	@Override
	public boolean canFix(IScriptAnnotation annotation)
	{
		return true; // TODO check here if we have an AI quick fix for this annotation?
	}

	@Override
	public boolean canFix(IMarker marker)
	{
		return true; // TODO check here if we have an AI quick fix for this marker?
	}

	@Override
	public void computeQuickAssistProposals(IScriptAnnotation annotation, IScriptCorrectionContext context)
	{
		IResource resource = annotation.getSourceModule().getResource();
		if (!(resource instanceof IFile))
		{
			return;
		}

		int offset = context.getInvocationContext().getOffset();
		ServoyAIQuickFixResolution resolution = new ServoyAIQuickFixResolution(
			resource.getProject(),
			(IFile)resource,
			offset,
			annotation);

		if (resolution.canFix())
		{
			context.addResolution(resolution, annotation);
		}
	}

	@Override
	public void computeQuickAssistProposals(IMarker marker, IScriptCorrectionContext context)
	{
		if (marker == null)
		{
			return;
		}

		IResource resource = marker.getResource();
		if (!(resource instanceof IFile))
		{
			return;
		}

		int start = marker.getAttribute(IMarker.CHAR_START, -1);
		int end = marker.getAttribute(IMarker.CHAR_END, -1);

		if ((start < 0 || end < 0) && marker.getAttribute(IMarker.LINE_NUMBER, -1) >= 0)
		{
			//do not compute the exact location here, we just need to know we have a location
			start = marker.getAttribute(IMarker.LINE_NUMBER, -1);
			end = start;
		}

		if (start < 0 || end < 0)
		{
			return;
		}

		ServoyAIQuickFixResolution resolution = new ServoyAIQuickFixResolution(
			resource.getProject(),
			(IFile)resource,
			marker);

		if (resolution.canFix()) //TODO do we need canFix ?
		{
			context.addResolution(resolution, marker);
		}
	}
}

