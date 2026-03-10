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
package com.servoy.eclipse.servoypilot.services.documentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for JSDoc manipulation operations.
 * 
 * Handles:
 * - Finding existing JSDoc blocks
 * - Replacing JSDoc
 * - Inserting new JSDoc
 * - Indentation preservation
 */
public class JSDocManipulator
{
	/**
	 * Finds the start line of JSDoc block immediately above the given line.
	 * Searches backward from functionLine-1.
	 * 
	 * @param lines Array of file lines
	 * @param functionLine Line number where function/variable starts (0-based)
	 * @return Line number where JSDoc starts (/** line), or -1 if no JSDoc found
	 */
	public int findExistingJSDocStart(String[] lines, int functionLine)
	{
		if (functionLine <= 0 || functionLine >= lines.length)
		{
			return -1;
		}

		// Start from line immediately above function
		int searchLine = functionLine - 1;

		// Skip blank lines and single-line comments
		while (searchLine >= 0)
		{
			String line = lines[searchLine].trim();

			if (line.isEmpty())
			{
				searchLine--;
				continue;
			}

			// Found end of JSDoc (looking for */ going upward)
			if (line.endsWith("*/"))
			{
				// Search backward for start
				for (int i = searchLine; i >= 0; i--)
				{
					if (lines[i].trim().startsWith("/**"))
					{
						return i; // Found JSDoc block start
					}
				}
			}

			// Found something else (not JSDoc) - no JSDoc above function
			break;
		}

		return -1; // No JSDoc found
	}

	/**
	 * Replaces existing JSDoc block with new JSDoc.
	 * Removes lines from jsdocStart to jsdocEnd and inserts newJSDoc at jsdocStart.
	 * 
	 * @param lines Array of file lines (modified in place)
	 * @param jsdocStart Start line of existing JSDoc (inclusive)
	 * @param jsdocEnd End line of existing JSDoc (inclusive)
	 * @param newJSDoc New JSDoc content to insert
	 * @param indentation Indentation string to apply to each line
	 */
	public void replaceJSDoc(List<String> lines, int jsdocStart, int jsdocEnd, String newJSDoc, String indentation)
	{
		if (jsdocStart < 0 || jsdocEnd < jsdocStart || jsdocStart >= lines.size())
		{
			return;
		}

		// Format new JSDoc with indentation
		String formattedJSDoc = formatJSDocWithIndentation(newJSDoc, indentation);

		// Remove old JSDoc lines (from start to end inclusive)
		for (int i = jsdocEnd; i >= jsdocStart; i--)
		{
			lines.remove(i);
		}

		// Insert new JSDoc at the same position
		String[] jsdocLines = formattedJSDoc.split("\n");
		for (int i = jsdocLines.length - 1; i >= 0; i--)
		{
			lines.add(jsdocStart, jsdocLines[i]);
		}
	}

	/**
	 * Inserts new JSDoc above the specified line.
	 * 
	 * @param lines Array of file lines (modified in place)
	 * @param functionLine Line where function/variable declaration starts
	 * @param newJSDoc JSDoc content to insert
	 * @param indentation Indentation string to apply
	 */
	public void insertJSDoc(List<String> lines, int functionLine, String newJSDoc, String indentation)
	{
		if (functionLine < 0 || functionLine >= lines.size())
		{
			return;
		}

		// Format JSDoc with indentation
		String formattedJSDoc = formatJSDocWithIndentation(newJSDoc, indentation);

		// Insert JSDoc lines before function line
		String[] jsdocLines = formattedJSDoc.split("\n");
		for (int i = jsdocLines.length - 1; i >= 0; i--)
		{
			lines.add(functionLine, jsdocLines[i]);
		}
	}

	/**
	 * Formats JSDoc with proper indentation.
	 * 
	 * @param jsdoc Raw JSDoc content
	 * @param indentation Indentation string to prepend to each line
	 * @return Formatted JSDoc with indentation
	 */
	private String formatJSDocWithIndentation(String jsdoc, String indentation)
	{
		String[] lines = jsdoc.split("\n");
		StringBuilder formatted = new StringBuilder();

		for (int i = 0; i < lines.length; i++)
		{
			if (i > 0)
			{
				formatted.append("\n");
			}
			formatted.append(indentation).append(lines[i]);
		}

		return formatted.toString();
	}

	/**
	 * Extracts indentation from a line of code.
	 * 
	 * @param line Line of code
	 * @return Indentation string (spaces or tabs)
	 */
	public String extractIndentation(String line)
	{
		if (line == null || line.isEmpty())
		{
			return "";
		}

		int i = 0;
		while (i < line.length() && Character.isWhitespace(line.charAt(i)))
		{
			i++;
		}

		return line.substring(0, i);
	}

	/**
	 * Converts line list back to string with newlines.
	 * 
	 * @param lines List of lines
	 * @return Joined string with \n separators
	 */
	public String linesToString(List<String> lines)
	{
		return String.join("\n", lines);
	}

	/**
	 * Converts string to line list.
	 * 
	 * @param content File content
	 * @return List of lines
	 */
	public List<String> stringToLines(String content)
	{
		List<String> lines = new ArrayList<>();
		String[] arr = content.split("\n", -1); // -1 to preserve empty lines
		for (String line : arr)
		{
			lines.add(line);
		}
		return lines;
	}

	/**
	 * Finds signature within bounded range (selection).
	 * Searches backward (bottom-to-top) to support multiple matches.
	 * 
	 * @param content Full file content
	 * @param signature Function signature to find (e.g., "function onLoad(event)")
	 * @param searchStart Character offset where to start searching (selection start)
	 * @param searchEnd Character offset where to stop searching (selection end)
	 * @return Absolute position in file where signature found, or -1 if not found
	 */
	public int findSignaturePosition(String content, String signature, int searchStart, int searchEnd)
	{
		if (content == null || signature == null || searchStart < 0 || searchEnd > content.length() || searchStart >= searchEnd)
		{
			return -1;
		}

		// Extract bounded substring
		String searchArea = content.substring(searchStart, searchEnd);

		// Search backward (lastIndexOf)
		int relativePosition = searchArea.lastIndexOf(signature);
		if (relativePosition < 0)
		{
			return -1;
		}

		// Return absolute position
		return searchStart + relativePosition;
	}

	/**
	 * Finds JSDoc block immediately above the given position.
	 * Searches backward for "/**" and "*\/" pattern.
	 * Returns -1 if syntax error detected (broken JSDoc).
	 * 
	 * @param content Full file content
	 * @param signaturePosition Position where function/variable starts
	 * @return Start position of JSDoc ("/**"), or -1 if no valid JSDoc found
	 */
	public int findJSDocStart(String content, int signaturePosition)
	{
		if (content == null || signaturePosition <= 0 || signaturePosition > content.length())
		{
			return -1;
		}

		// Search backward from signature position
		int searchPos = signaturePosition - 1;

		// Skip whitespace and newlines
		while (searchPos >= 0 && Character.isWhitespace(content.charAt(searchPos)))
		{
			searchPos--;
		}

		// Check if we're at end of JSDoc (*/)
		if (searchPos >= 1 && content.charAt(searchPos) == '/' && content.charAt(searchPos - 1) == '*')
		{
			// Found end of JSDoc, search backward for start
			int endPos = searchPos;

			// Search for /**
			for (int i = endPos - 2; i >= 1; i--)
			{
				if (content.charAt(i) == '*' && content.charAt(i - 1) == '/' && (i >= 2 && content.charAt(i + 1) == '*'))
				{
					// Found /** at position i-1
					return i - 1;
				}
			}

			// Found */ but no /** - broken JSDoc, treat as no JSDoc
			return -1;
		}

		// No JSDoc above signature
		return -1;
	}

	/**
	 * Finds end position of JSDoc block ("*\/") given start position.
	 * 
	 * @param content Full file content
	 * @param jsdocStartPosition Position of "/**"
	 * @return Position after "*\/" (exclusive), or -1 if not found
	 */
	public int findJSDocEnd(String content, int jsdocStartPosition)
	{
		if (content == null || jsdocStartPosition < 0 || jsdocStartPosition >= content.length() - 2)
		{
			return -1;
		}

		// Search forward for */
		for (int i = jsdocStartPosition + 3; i < content.length() - 1; i++)
		{
			if (content.charAt(i) == '*' && content.charAt(i + 1) == '/')
			{
				return i + 2; // Position after */
			}
		}

		return -1; // Not found
	}
}
