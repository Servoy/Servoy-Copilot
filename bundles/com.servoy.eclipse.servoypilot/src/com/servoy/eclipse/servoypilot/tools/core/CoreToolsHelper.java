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
package com.servoy.eclipse.servoypilot.tools.core;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRootObject;
import com.servoy.j2db.persistence.Solution;

/**
 * Singleton helper providing shared logic for core tool interfaces
 * (FormTools, RelationTools, ValueListTools, StyleTools).
 */
public class CoreToolsHelper
{
	private static final CoreToolsHelper INSTANCE = new CoreToolsHelper();

	private CoreToolsHelper()
	{
	}

	public static CoreToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel)
	{
		String target = TargetService.getInstance().getCurrentTarget();
		ServoyProject activeProject = servoyModel.getActiveProject();

		if ("active".equals(target) || target == null)
		{
			return activeProject;
		}

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module != null && target.equals(module.getProject().getName()))
			{
				return module;
			}
		}

		return activeProject;
	}

	public String getSolutionName(IPersist persist)
	{
		try
		{
			IRootObject rootObject = persist.getRootObject();
			if (rootObject instanceof Solution solution)
			{
				return solution.getName();
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError("[CoreToolsHelper] Error getting solution name", e);
		}
		return "unknown";
	}

	public String formatOrigin(String solutionName, String activeSolutionName)
	{
		if (solutionName.equals(activeSolutionName))
		{
			return " (in: active solution)";
		}
		return " (in: " + solutionName + ")";
	}
}
