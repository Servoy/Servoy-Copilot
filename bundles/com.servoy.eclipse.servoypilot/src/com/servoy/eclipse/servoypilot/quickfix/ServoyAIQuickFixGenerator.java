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
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IMarkerResolutionGenerator;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.Activator;

public class ServoyAIQuickFixGenerator implements IMarkerResolutionGenerator
{

	@Override
	public IMarkerResolution[] getResolutions(IMarker marker)
	{
		if (!Activator.getDefault().getAiConfiguration().isValid())
		{
			return new IMarkerResolution[0];
		}
		try
		{
			return new IMarkerResolution[] { new ServoyAIQuickFixResolution(marker.getResource().getProject(),
				(marker.getResource() instanceof IFile) ? (IFile)marker.getResource() : null, marker)
			};
		}
		catch (Exception e)
		{
			ServoyLog.logError(e);
		}
		return null;
	}
}