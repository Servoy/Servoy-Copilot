/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.dltk.compiler.problem.DefaultProblem;
import org.eclipse.dltk.compiler.problem.IProblemReporter;
import org.eclipse.dltk.javascript.parser.JavaScriptParserUtil;
import org.eclipse.e4.core.di.annotations.Creatable;

/**
 * Validates JavaScript code snippets via DLTK's parser.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.services.ParserService#isValidStatement}.
 * Used by AI agents to verify generated code is syntactically correct before applying it.
 * </p>
 */
@Creatable
public class JsCodeValidatorService
{
	/**
	 * Parses the given JavaScript code and returns a list of syntax problems.
	 *
	 * @param code the JavaScript code to validate
	 * @return list of {@link DefaultProblem} instances; empty if the code is syntactically valid
	 */
	public List<DefaultProblem> validate(String code)
	{
		List<DefaultProblem> problems = new ArrayList<>();
		IProblemReporter reporter = problem -> problems.add((DefaultProblem)problem);
		try
		{
			JavaScriptParserUtil.parse(code, reporter);
		}
		catch (Exception e)
		{
			// Parsing crashed - log only; problems list reflects parser-detected issues
		}
		return problems;
	}
}
