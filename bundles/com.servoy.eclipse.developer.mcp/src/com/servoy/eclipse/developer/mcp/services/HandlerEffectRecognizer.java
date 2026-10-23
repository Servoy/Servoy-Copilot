/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.servoy.eclipse.cypress.services.FormSpecModel.Effect;
import com.servoy.eclipse.cypress.services.FormSpecModel.EffectKind;

/**
 * Recognises, in the source text of a handler method, the closed set of effects that can be asserted on the form (visible, enabled, text,
 * addStyleClass, removeStyleClass). Everything else is reported as an off-form statement. Pure: a string goes in, no Servoy types are used.
 * <p>
 * This is a deliberately small, regex grade scanner. It does not parse JavaScript: comments and string literals are handled, statements are split on
 * top level semicolons, and each statement is matched as a whole. A statement without a trailing semicolon merges with the next line and ends up in
 * the off-form bucket (documented limitation).
 * </p>
 */
public final class HandlerEffectRecognizer
{
	/** Value of the single off-form effect that is returned when the method source cannot be analysed. */
	public static final String NOT_ANALYZABLE = "method body could not be analyzed";

	private static final int MAX_SOURCE_LENGTH = 160;

	private static final String NAME = "[A-Za-z_$][\\w$]*";
	private static final String ELEMENT = "elements\\s*\\.\\s*(" + NAME + ")\\s*\\.\\s*";
	private static final String BOOLEAN = "\\s*=\\s*(true|false)";
	private static final String CLASS_NAME = "(?:'([A-Za-z0-9_-]+)'|\"([A-Za-z0-9_-]+)\")";

	private static final Pattern VISIBLE = Pattern.compile(ELEMENT + "visible" + BOOLEAN);
	private static final Pattern ENABLED = Pattern.compile(ELEMENT + "enabled" + BOOLEAN);
	private static final Pattern TEXT = Pattern.compile(ELEMENT + "text\\s*=\\s*(?:'([^'\"\\\\]*)'|\"([^'\"\\\\]*)\")");
	private static final Pattern ADD_CLASS = Pattern.compile(ELEMENT + "addStyleClass\\s*\\(\\s*" + CLASS_NAME + "\\s*\\)");
	private static final Pattern REMOVE_CLASS = Pattern.compile(ELEMENT + "removeStyleClass\\s*\\(\\s*" + CLASS_NAME + "\\s*\\)");
	/**
	 * Start of an assignment (also compound {@code +=}) to a bgcolor/fgcolor/font/border property, through dot or bracket access, optionally qualified
	 * with {@code forms.<name>}. It only consumes up to the end of the line (never past a {@code ;} outside a string or a line break), so a
	 * following statement that lacks a separating semicolon is not swallowed.
	 */
	private static final Pattern DROPPED = Pattern.compile("(?:forms\\s*\\.\\s*" + NAME + "\\s*\\.\\s*)?elements\\s*(?:\\.\\s*" + NAME +
		"|\\[\\s*(?:'[^'\\r\\n]*'|\"[^\"\\r\\n]*\")\\s*\\])\\s*\\.\\s*(?:bgcolor|fgcolor|font|border)\\s*\\+?=(?!=)(?:'[^'\\r\\n]*'|\"[^\"\\r\\n]*\"|[^;\\r\\n'\"])*");
	/** A final {@code return;} or {@code return <literal>;}, which has no effect on the form. */
	private static final Pattern TRAILING_LITERAL_RETURN = Pattern
		.compile("return(?:\\s+(?:true|false|null|undefined|-?\\d+(?:\\.\\d+)?|'[^'\\\\\\r\\n]*'|\"[^\"\\\\\\r\\n]*\"))?");
	private static final Pattern MARKUP_CHARACTER = Pattern.compile("[<>&]");

	private static final Pattern FUNCTION = Pattern.compile("(?<![\\w$.])function(?![\\w$])");
	private static final Pattern BRANCH_KEYWORD = Pattern.compile("(?<![\\w$.])(?:if|else|switch|for|while|do|try|catch)(?![\\w$])");
	private static final Pattern RETURN = Pattern.compile("(?<![\\w$.])return(?![\\w$])");
	private static final Pattern FUNCTION_NAME = Pattern.compile("[\\w$]*");

	private HandlerEffectRecognizer()
	{
	}

	/**
	 * The outcome of {@link HandlerEffectRecognizer#analyze(String)}.
	 *
	 * @param analyzable false when the source could not be scanned; {@code effects} then holds a single off-form note
	 * @param branching true when the body contains conditional or looping code, so its effects are not unconditional
	 * @param effects the effects in source order, after collapsing superseded statements
	 * @param sources the collapsed single-line source statement of each effect, same size and order as {@code effects}
	 */
	public record Result(boolean analyzable, boolean branching, List<Effect> effects, List<String> sources)
	{
		public Result
		{
			effects = effects == null ? List.of() : List.copyOf(effects);
			sources = sources == null ? List.of() : List.copyOf(sources);
		}
	}

	/**
	 * @param declaration the method declaration text as returned by {@code ScriptMethod.getDeclaration()}, may be {@code null}
	 */
	public static Result analyze(String declaration)
	{
		if (declaration == null)
		{
			return notAnalyzable();
		}
		String clean = stripComments(declaration);
		if (clean == null)
		{
			return notAnalyzable();
		}
		String mask = mask(clean);

		Matcher function = FUNCTION.matcher(mask);
		if (!function.find())
		{
			return notAnalyzable();
		}
		int open = bodyStart(mask, function.end());
		if (open < 0)
		{
			return notAnalyzable();
		}
		int close = matching(mask, open, '{', '}');
		if (close < 0)
		{
			return notAnalyzable();
		}

		String body = clean.substring(open + 1, close);
		String maskedBody = mask.substring(open + 1, close);
		List<Statement> statements = split(body, maskedBody);

		boolean branching = BRANCH_KEYWORD.matcher(maskedBody).find() || hasTernary(maskedBody) || hasNonFinalReturn(maskedBody, statements);

		List<Effect> effects = new ArrayList<>();
		List<String> sources = new ArrayList<>();
		for (int s = 0; s < statements.size(); s++)
		{
			String text = statements.get(s).text();
			Matcher dropped = DROPPED.matcher(text);
			while (dropped.lookingAt())
			{
				text = text.substring(dropped.end()).strip();
				dropped = DROPPED.matcher(text);
			}
			if (text.isEmpty() || (s == statements.size() - 1 && TRAILING_LITERAL_RETURN.matcher(text).matches()))
			{
				continue;
			}
			Effect effect = classify(text);
			effects.add(effect);
			sources.add(collapse(text));
		}

		// only the final state is observable: keep the last statement of each (kind-family, target)
		List<Effect> keptEffects = new ArrayList<>();
		List<String> keptSources = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = effects.size() - 1; i >= 0; i--)
		{
			String key = familyKey(effects.get(i));
			if (key != null && !seen.add(key))
			{
				continue;
			}
			keptEffects.add(effects.get(i));
			keptSources.add(sources.get(i));
		}
		Collections.reverse(keptEffects);
		Collections.reverse(keptSources);
		return new Result(true, branching, keptEffects, keptSources);
	}

	private static Result notAnalyzable()
	{
		return new Result(false, false, List.of(new Effect(EffectKind.OFF_FORM, null, NOT_ANALYZABLE)), List.of(NOT_ANALYZABLE));
	}

	private static Effect classify(String statement)
	{
		Matcher m = VISIBLE.matcher(statement);
		if (m.matches())
		{
			return new Effect(EffectKind.VISIBLE, m.group(1), m.group(2));
		}
		m = ENABLED.matcher(statement);
		if (m.matches())
		{
			return new Effect(EffectKind.ENABLED, m.group(1), m.group(2));
		}
		m = TEXT.matcher(statement);
		if (m.matches())
		{
			String literal = m.group(2) != null ? m.group(2) : m.group(3);
			// an empty literal cannot be asserted, and markup or entities render differently from the source literal
			if (!literal.isBlank() && !MARKUP_CHARACTER.matcher(literal).find())
			{
				return new Effect(EffectKind.TEXT, m.group(1), literal);
			}
		}
		m = ADD_CLASS.matcher(statement);
		if (m.matches())
		{
			return new Effect(EffectKind.ADD_STYLE_CLASS, m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
		}
		m = REMOVE_CLASS.matcher(statement);
		if (m.matches())
		{
			return new Effect(EffectKind.REMOVE_STYLE_CLASS, m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
		}
		return new Effect(EffectKind.OFF_FORM, null, collapse(statement));
	}

	private static String familyKey(Effect effect)
	{
		switch (effect.kind())
		{
			case VISIBLE :
				return "visible:" + effect.target();
			case ENABLED :
				return "enabled:" + effect.target();
			case TEXT :
				return "text:" + effect.target();
			case ADD_STYLE_CLASS :
			case REMOVE_STYLE_CLASS :
				return "class:" + effect.target() + ":" + effect.value();
			default :
				return null;
		}
	}

	/** Whitespace runs become one space, trailing semicolons are removed, truncated to 160 characters ending with "...". */
	private static String collapse(String statement)
	{
		String text = statement.replaceAll("\\s+", " ").trim();
		while (text.endsWith(";"))
		{
			text = text.substring(0, text.length() - 1).trim();
		}
		if (text.length() > MAX_SOURCE_LENGTH)
		{
			text = text.substring(0, MAX_SOURCE_LENGTH - 3) + "...";
		}
		return text;
	}

	private record Statement(int start, String text)
	{
	}

	/** Splits on semicolons at paren/bracket/brace depth 0. Works on the masked body, slices the real body. */
	private static List<Statement> split(String body, String maskedBody)
	{
		List<Statement> result = new ArrayList<>();
		int depth = 0;
		int from = 0;
		for (int i = 0; i < maskedBody.length(); i++)
		{
			char c = maskedBody.charAt(i);
			if (c == '(' || c == '[' || c == '{')
			{
				depth++;
			}
			else if (c == ')' || c == ']' || c == '}')
			{
				if (depth > 0)
				{
					depth--;
				}
			}
			else if (c == ';' && depth == 0)
			{
				addStatement(result, body, from, i);
				from = i + 1;
			}
		}
		addStatement(result, body, from, body.length());
		return result;
	}

	private static void addStatement(List<Statement> result, String body, int from, int to)
	{
		String raw = body.substring(from, to);
		String text = raw.strip();
		if (!text.isEmpty())
		{
			int leading = raw.length() - raw.stripLeading().length();
			result.add(new Statement(from + leading, text));
		}
	}

	private static boolean hasTernary(String maskedBody)
	{
		for (int i = 0; i < maskedBody.length(); i++)
		{
			if (maskedBody.charAt(i) != '?')
			{
				continue;
			}
			char previous = i > 0 ? maskedBody.charAt(i - 1) : ' ';
			char next = i + 1 < maskedBody.length() ? maskedBody.charAt(i + 1) : ' ';
			if (previous == '?' || next == '?')
			{
				continue;
			}
			if (next == '.' && !(i + 2 < maskedBody.length() && Character.isDigit(maskedBody.charAt(i + 2))))
			{
				continue;
			}
			return true;
		}
		return false;
	}

	/** A return is fine only when it starts the last top level statement. */
	private static boolean hasNonFinalReturn(String maskedBody, List<Statement> statements)
	{
		int lastStart = statements.isEmpty() ? -1 : statements.get(statements.size() - 1).start();
		Matcher m = RETURN.matcher(maskedBody);
		while (m.find())
		{
			if (m.start() != lastStart)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes block and line comments, respecting string literals.
	 *
	 * @return the text without comments, or {@code null} when a string literal or a block comment is not terminated
	 */
	private static String stripComments(String text)
	{
		StringBuilder sb = new StringBuilder(text.length());
		int n = text.length();
		int i = 0;
		while (i < n)
		{
			char c = text.charAt(i);
			if (c == '\'' || c == '"' || c == '\u0060')
			{
				int end = skipString(text, i);
				if (end < 0)
				{
					return null;
				}
				sb.append(text, i, end);
				i = end;
			}
			else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/')
			{
				while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r')
				{
					i++;
				}
			}
			else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*')
			{
				int end = text.indexOf("*/", i + 2);
				if (end < 0)
				{
					return null;
				}
				sb.append(' ');
				i = end + 2;
			}
			else
			{
				sb.append(c);
				i++;
			}
		}
		return sb.toString();
	}

	/**
	 * @return the index just after the closing quote of the string literal that starts at {@code start}, or -1 when it is not terminated
	 */
	private static int skipString(String text, int start)
	{
		char quote = text.charAt(start);
		int n = text.length();
		int i = start + 1;
		while (i < n)
		{
			char c = text.charAt(i);
			if (c == '\\')
			{
				i += 2;
				continue;
			}
			if (c == quote)
			{
				return i + 1;
			}
			if (quote != '\u0060' && (c == '\n' || c == '\r'))
			{
				return -1;
			}
			i++;
		}
		return -1;
	}

	/** Same length as the input, the content of every string literal is replaced by spaces so structure scanning cannot be fooled by strings. */
	private static String mask(String text)
	{
		char[] out = text.toCharArray();
		int i = 0;
		while (i < out.length)
		{
			char c = out[i];
			if (c == '\'' || c == '"' || c == '\u0060')
			{
				int end = skipString(text, i);
				if (end < 0)
				{
					break;
				}
				for (int k = i + 1; k < end - 1; k++)
				{
					out[k] = ' ';
				}
				i = end;
			}
			else
			{
				i++;
			}
		}
		return new String(out);
	}

	/** @return the index of the opening brace of the function body, or -1 when the header is not recognisable */
	private static int bodyStart(String mask, int afterFunctionKeyword)
	{
		int paren = mask.indexOf('(', afterFunctionKeyword);
		if (paren < 0 || !FUNCTION_NAME.matcher(mask.substring(afterFunctionKeyword, paren).trim()).matches())
		{
			return -1;
		}
		int closeParen = matching(mask, paren, '(', ')');
		if (closeParen < 0)
		{
			return -1;
		}
		int i = closeParen + 1;
		while (i < mask.length() && Character.isWhitespace(mask.charAt(i)))
		{
			i++;
		}
		return i < mask.length() && mask.charAt(i) == '{' ? i : -1;
	}

	/** @return the index of the bracket that closes the one at {@code open}, or -1 when they do not balance */
	private static int matching(String mask, int open, char openChar, char closeChar)
	{
		int depth = 0;
		for (int i = open; i < mask.length(); i++)
		{
			char c = mask.charAt(i);
			if (c == openChar)
			{
				depth++;
			}
			else if (c == closeChar)
			{
				depth--;
				if (depth == 0)
				{
					return i;
				}
			}
		}
		return -1;
	}
}
