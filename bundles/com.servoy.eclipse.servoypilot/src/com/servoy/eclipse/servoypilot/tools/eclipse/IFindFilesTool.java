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
package com.servoy.eclipse.servoypilot.tools.eclipse;

import java.util.Optional;

import com.servoy.eclipse.servoypilot.tools.ResourceService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IFindFilesTool
{
	@Tool("Finds workspace files matching the given glob patterns.")
	default String findFiles(
		@P(value = "Glob patterns. Accepts a string like  \"*.frm,*.rel,*.val\". If omitted, defaults to '*'", required = false) String fileNamePatterns,
		@P(value = "Maximum number of results to return (default: 200)", required = false) String maxResults)
	{
		String[] patterns = EclipseToolsHelper.getInstance().normalizeFileNamePatterns(fileNamePatterns);
		int limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(0);
		return ResourceService.getInstance().findFiles(patterns, limit).toString();
	}
}
