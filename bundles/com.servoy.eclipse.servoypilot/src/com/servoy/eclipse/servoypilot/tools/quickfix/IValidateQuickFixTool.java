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
package com.servoy.eclipse.servoypilot.tools.quickfix;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.dltk.compiler.problem.DefaultProblem;

import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.eclipse.servoypilot.tools.dto.QuickFixResult;
import com.servoy.eclipse.servoypilot.tools.dto.SourceEdit;

import dev.langchain4j.agent.tool.Tool;

public interface IValidateQuickFixTool
{
	@Tool("""
Validates the given quick fix edits by checking if the replacement code for each edit can be parsed as a valid statement.

Use this tool to validate the quick fix before returning it to the caller. If the validation fails, provide feedback to the AI about which edit is invalid and why.
""")
	default String validateQuickfixResult(QuickFixResult fix)
	{
		for (SourceEdit edit : fix.edits())
		{
			List<DefaultProblem> errors = ParserService.getInstance().isValidStatement(edit.replacement());
			if (!errors.isEmpty())
			{
				return "Invalid quick fix generated. The AI generated code that could not be parsed as a valid statement: " + edit.replacement() +
					". Parser errors: " + errors.stream()
						.map(e -> e.getMessage())
						.collect(Collectors.joining("; ")) +
					"\n Fix the quick fix result and return a valid QuickFixResult object.";
			}
		}
		return "Valid quick fix. Return the QuickFixResult object to the caller.";
	}
}
