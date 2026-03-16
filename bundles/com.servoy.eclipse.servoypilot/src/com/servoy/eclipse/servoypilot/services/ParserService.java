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

package com.servoy.eclipse.servoypilot.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.dltk.ast.ASTNode;
import org.eclipse.dltk.ast.ASTVisitor;
import org.eclipse.dltk.compiler.problem.DefaultProblem;
import org.eclipse.dltk.compiler.problem.IProblemReporter;
import org.eclipse.dltk.javascript.ast.FunctionStatement;
import org.eclipse.dltk.javascript.ast.JSNode;
import org.eclipse.dltk.javascript.ast.Script;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.dltk.javascript.parser.JavaScriptParserUtil;

//TODO tool?
public class ParserService
{
	private static ParserService instance;

	public static ParserService getInstance()
	{
		if (instance == null)
		{
			instance = new ParserService();
		}
		return instance;
	}

	/**
	 * Checks if the given code represents a full statement without syntax errors.
	 * @param code AI-generated replacement
	 * @return true if the code parses as a valid statement without any errors
	 */
	public boolean isValidStatement(String code)
	{
		// Collect problems reported by the parser
		List<DefaultProblem> problems = new ArrayList<>();
		IProblemReporter reporter = (problem) -> problems.add((DefaultProblem)problem);

		try
		{
			Script script = JavaScriptParserUtil.parse(code, reporter);
			// Full statement if parse succeeds and no problems reported
			return script != null && !script.getStatements().isEmpty() && problems.isEmpty();
		}
		catch (Exception e)
		{
			// Parsing failed, not a full statement
			return false;
		}
	}

	public Statement getStatementAtOffset(String source, int startOffset) throws Exception
	{
		int offset = skipWhitespaceForward(source, startOffset);

		Script script = JavaScriptParserUtil.parse(source, null);
		if (script == null)
		{
			return null;
		}

		final Statement[] result = new Statement[1];
		for (Statement topLevel : script.getStatements())
		{
			int start = topLevel.sourceStart();
			int end = topLevel.sourceEnd();

			// Skip statements before offset
			if (offset < start)
			{
				// since statements are ordered, we can stop completely
				break;
			}

			// Skip statements that don't contain offset
			if (offset >= end)
			{
				continue;
			}

			// We are inside this top-level statement , traverse only this subtree
			topLevel.traverse(new ASTVisitor()
			{
				@Override
				public boolean visitGeneral(ASTNode node) throws Exception
				{
					int nodeStart = node.sourceStart();
					int nodeEnd = node.sourceEnd();

					// Prune branches that don't contain the offset
					if (offset < nodeStart || offset >= nodeEnd)
					{
						return false;
					}

					if (node instanceof Statement)
					{
						if (result[0] == null ||
							(nodeEnd - nodeStart) < (result[0].sourceEnd() - result[0].sourceStart()))
						{
							result[0] = (Statement)node;
						}
					}

					return true; // continue deeper only if offset inside
				}
			});
			break;
		}

		return result[0];
	}

	public FunctionStatement getParentFunction(JSNode node)
	{
		JSNode parent = node.getParent();
		while (parent != null)
		{
			if (parent instanceof FunctionStatement)
			{
				return (FunctionStatement)parent;
			}

			parent = parent.getParent();
		}
		return null;
	}

	public int skipWhitespaceForward(String source, int offset)
	{
		int len = source.length();
		while (offset < len && Character.isWhitespace(source.charAt(offset)))
		{
			offset++;
		}
		return offset;
	}
}