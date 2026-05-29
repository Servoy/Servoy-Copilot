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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.e4.core.di.annotations.Creatable;

/**
 * Validator for documentation application.
 * <p>
 * Ported from {@code com.servoy.eclipse.servoypilot.services.DocumentationValidatorService}.
 * </p>
 * <p>
 * Validates:
 * <ul>
 *   <li>UUID preservation (CRITICAL — UUIDs must never change)</li>
 *   <li>JSDoc syntax correctness</li>
 * </ul>
 * </p>
 */
@Creatable
public class DocumentationValidatorService
{
	private static final Pattern UUID_PATTERN = Pattern.compile("@UUID\\s+([a-f0-9-]{36})");
	private static final Pattern JSDOC_BLOCK_PATTERN = Pattern.compile("/\\*\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern PARAM_TAG_PATTERN = Pattern.compile("@param\\s+\\{[^}]+\\}");

	/**
	 * Exception thrown when validation fails (UUID corruption, JSDoc syntax errors, etc.).
	 */
	public static class ValidationException extends Exception
	{
		private static final long serialVersionUID = 1L;
		public ValidationException(String message) { super(message); }
		public ValidationException(String message, Throwable cause) { super(message, cause); }
	}

	public List<String> extractUUIDs(String content)
	{
		List<String> uuids = new ArrayList<>();
		if (content != null)
		{
			Matcher matcher = UUID_PATTERN.matcher(content);
			while (matcher.find()) uuids.add(matcher.group(1));
		}
		return uuids;
	}

	public void validateUUIDs(String originalContent, String newContent) throws ValidationException
	{
		List<String> originalUUIDs = extractUUIDs(originalContent);
		List<String> newUUIDs = extractUUIDs(newContent);

		if (originalUUIDs.size() != newUUIDs.size())
		{
			throw new ValidationException(
				"UUID count mismatch! Original: " + originalUUIDs.size() +
					", Modified: " + newUUIDs.size() +
					"\nOriginal UUIDs: " + originalUUIDs +
					"\nModified UUIDs: " + newUUIDs);
		}
		for (int i = 0; i < originalUUIDs.size(); i++)
		{
			if (!originalUUIDs.get(i).equals(newUUIDs.get(i)))
				throw new ValidationException(
					"UUID modification detected at position " + i +
						"!\nOriginal: " + originalUUIDs.get(i) +
						"\nModified: " + newUUIDs.get(i));
		}
	}

	public String restoreUUIDs(String newJSDoc, List<String> originalUUIDs)
	{
		if (originalUUIDs == null || originalUUIDs.isEmpty()) return newJSDoc;

		List<String> newUUIDs = extractUUIDs(newJSDoc);
		if (newUUIDs.isEmpty()) return newJSDoc;

		String result = newJSDoc;
		for (int i = 0; i < Math.min(newUUIDs.size(), originalUUIDs.size()); i++)
		{
			String newUUID = newUUIDs.get(i);
			String originalUUID = originalUUIDs.get(i);
			if (!newUUID.equals(originalUUID))
				result = result.replaceFirst("@UUID\\s+" + Pattern.quote(newUUID), "@UUID " + originalUUID);
		}
		return result;
	}

	public void validateJSDocSyntax(String content) throws ValidationException
	{
		if (content == null) return;
		Matcher matcher = JSDOC_BLOCK_PATTERN.matcher(content);
		while (matcher.find())
		{
			String jsdoc = matcher.group();
			if (!jsdoc.startsWith("/**") || !jsdoc.endsWith("*/"))
				throw new ValidationException("Invalid JSDoc syntax: " + jsdoc.substring(0, Math.min(50, jsdoc.length())));
			if (jsdoc.contains("@param") && !PARAM_TAG_PATTERN.matcher(jsdoc).find())
				throw new ValidationException("@param tag missing type annotation: " + jsdoc.substring(0, Math.min(100, jsdoc.length())));
		}
	}

	/**
	 * Extracts indentation (leading whitespace) from a line.
	 */
	public String extractIndentation(String line)
	{
		if (line == null || line.isEmpty()) return "";
		int i = 0;
		while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
		return line.substring(0, i);
	}
}
