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
package com.servoy.eclipse.servoypilot.tools.codeanalysis;

import org.eclipse.core.resources.IFile;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;
import com.servoy.eclipse.servoypilot.services.FileStructureService;
import com.servoy.eclipse.servoypilot.services.dto.FileStructure;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IAnalyzeFileStructureTool
{
	@Tool("Analyze file structure and extract all symbols with JSDoc status (FAST - uses DLTK caching). " +
		"Accepts form names (e.g., 'testCustomers'), scope names (e.g., 'utils'), or full paths.")
	default String analyzeFileStructure(
		@P("File path, form name, or scope name (e.g., 'testCustomers', 'utils', '/ProjectName/forms/customers/customers.js')") String pathOrName)
	{
		try
		{
			if (pathOrName != null && !pathOrName.isBlank())
			{
				FilePathResolver resolver = FilePathResolver.getInstance();
				IFile file = resolver.resolveFile(pathOrName);

				if (file != null && file.exists())
				{
					FileStructureService service = FileStructureService.getInstance();
					FileStructure structure = service.analyzeFile(file);
					return structure.toFormattedString();
				}

				return resolver.buildNotFoundMessage(pathOrName);
			}

			return "Error: File path or name is required";
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error analyzing file structure: " + pathOrName, e);
			return "Error: " + e.getMessage();
		}
	}
}
