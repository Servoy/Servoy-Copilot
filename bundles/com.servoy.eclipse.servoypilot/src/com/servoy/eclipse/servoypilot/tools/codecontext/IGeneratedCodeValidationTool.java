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
package com.servoy.eclipse.servoypilot.tools.codecontext;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.dltk.compiler.problem.DefaultProblem;

import com.servoy.eclipse.servoypilot.dto.CodeChanges;
import com.servoy.eclipse.servoypilot.dto.SourceEdit;
import com.servoy.eclipse.servoypilot.services.ParserService;

import dev.langchain4j.agent.tool.Tool;

public interface IGeneratedCodeValidationTool
{
	@Tool("""
		Validates the given generated code changes by checking if the replacement code for each edit can be parsed as a valid statement.

		Use this tool to validate generated code before returning it to the caller. If the validation fails, provide feedback to the AI about which edit is invalid and why.
		""")
	default String validate(CodeChanges fix)
	{
		for (SourceEdit edit : fix.codeChanges())
		{
			List<DefaultProblem> errors = ParserService.getInstance().isValidStatement(edit.replacement());
			if (!errors.isEmpty())
			{
				return "Invalid code generated. The AI generated code that could not be parsed as a valid statement: " + edit.replacement() +
					". Parser errors: " + errors.stream()
						.map(e -> e.getMessage())
						.collect(Collectors.joining("; ")) +
					"\n Fix the generated code and return a valid CodeChanges object.";
			}
		}
		return "The generated code is valid. Return the CodeChanges object to the caller.";
	}
}
