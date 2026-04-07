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
package com.servoy.eclipse.servoypilot.tools.component;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.services.TargetService;
import com.servoy.j2db.persistence.RepositoryException;

/**
 * Singleton helper providing shared project-resolution logic for all component tool interfaces,
 * regardless of component type (bootstrap or otherwise).
 */
public class ComponentToolsHelper
{
	private static final ComponentToolsHelper INSTANCE = new ComponentToolsHelper();

	private ComponentToolsHelper()
	{
	}

	public static ComponentToolsHelper getInstance()
	{
		return INSTANCE;
	}

	public String getProjectPath() throws Exception
	{
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject targetProject = resolveTargetProject(servoyModel);
		if (targetProject == null) throw new Exception("No target solution/module found");
		return targetProject.getProject().getLocation().toOSString();
	}

	public ServoyProject resolveTargetProject(IDeveloperServoyModel servoyModel) throws RepositoryException
	{
		String target = TargetService.getInstance().getCurrentTarget();
		if ("active".equals(target)) return servoyModel.getActiveProject();

		ServoyProject[] modules = servoyModel.getModulesOfActiveProject();
		for (ServoyProject module : modules)
		{
			if (module.getProject().getName().equals(target)) return module;
		}

		throw new RepositoryException("Target '" + target + "' not found");
	}
}
