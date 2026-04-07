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

import com.servoy.eclipse.servoypilot.services.CodeContextService;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public interface IResolveIdentifierTypeTool
{
	@Tool("Resolve the type of an identifier in a JavaScript file. " +
		"Returns concise type information (not full code context). " +
		"Accepts form names, scope names, or full file paths.")
	default String resolveIdentifierType(
		@P("Identifier name to resolve (e.g., 'foundset', 'fs', 'record', 'customerName')") String identifier,
		@P("File path, form name, or scope name (e.g., 'myForm', 'utils', 'forms/myForm.js')") String pathOrName)
	{
		if (identifier != null && !identifier.isBlank() && pathOrName != null && !pathOrName.isBlank())
		{
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(pathOrName);

			if (file != null && file.exists())
			{
				return CodeContextService.getInstance().resolveIdentifierType(identifier, file);
			}

			return resolver.buildNotFoundMessage(pathOrName);
		}

		return "Error: Identifier and file path are required";
	}
}
