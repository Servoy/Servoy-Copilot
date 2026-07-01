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
 Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.servoy.eclipse.developer.mcp.services;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.servoy.j2db.persistence.IColumnTypes;
import com.servoy.j2db.util.FormatParser;
import com.servoy.j2db.util.FormatParser.ParsedFormat;
import com.servoy.j2db.util.RoundHalfUpDecimalFormat;

/**
 * Validates Servoy format strings for a given column data type.
 * <p>
 * Replicates the same validation logic used by {@code ServoyFormBuilder} at build
 * time: the format string is first parsed by {@link FormatParser}, then the
 * resulting display/edit pattern is compiled by the appropriate JDK formatter
 * ({@link SimpleDateFormat} for DATETIME, {@link DecimalFormat} for
 * NUMBER/INTEGER) so that ill-formed patterns are caught early.
 * </p>
 * <p>
 * TEXT and MEDIA types have no pattern syntax to validate â only structural
 * flags (uppercase, lowercase, number-validator, mask, etc.) are reported.
 * </p>
 * <p>
 * i18n-prefixed patterns (e.g. {@code "i18n:my.key"}) are accepted as-is; their
 * resolved value is only known at runtime so JVM-level validation is skipped.
 * </p>
 *
 * <p>This service has no Eclipse/OSGi dependencies and can be unit-tested without
 * a running workbench.</p>
 */
public class FormatValidatorService
{
	/** Accepted data-type strings (case-insensitive) and their IColumnTypes mappings. */
	private static final String[] TYPE_NAMES = { "TEXT", "NUMBER", "INTEGER", "DATETIME", "MEDIA" };

	/**
	 * Result of a single {@link #validateFormat} call.
	 *
	 * @param valid         {@code true} when no validation error was found
	 * @param dataType      the resolved data type name (upper-cased), or {@code "UNKNOWN"} when
	 *                      the supplied type string could not be recognised
	 * @param displayFormat the display-format string extracted from the parsed format
	 *                      (may be {@code null} for flags-only formats such as {@code "|U"})
	 * @param editFormat    the edit-format / placeholder string extracted from the parsed format
	 *                      (may be {@code null})
	 * @param flags         human-readable list of active flags (e.g. {@code ["MASK", "UPPERCASE"]})
	 * @param error         error message when {@code valid} is {@code false}; {@code null} otherwise
	 */
	public record ValidationResult(
		boolean valid,
		String dataType,
		String displayFormat,
		String editFormat,
		List<String> flags,
		String error)
	{
	}

	/**
	 * Validates {@code format} for the given {@code dataType}.
	 *
	 * @param format   the Servoy format property string â plain pipe-style (e.g.
	 *                 {@code "dd/MM/yyyy|dd-MM-yyyy|mask"}) or JSON (e.g.
	 *                 {@code "{\"displayFormat\":\"dd/MM/yyyy\",\"mask\":true}"}).
	 *                 {@code null} or blank is treated as "no format" (always valid).
	 * @param dataType one of {@code TEXT}, {@code NUMBER}, {@code INTEGER},
	 *                 {@code DATETIME}, {@code MEDIA} (case-insensitive)
	 * @return a {@link ValidationResult} describing the outcome
	 */
	public ValidationResult validateFormat(String format, String dataType)
	{
		// --- resolve data type ---
		int columnType = resolveColumnType(dataType);
		String resolvedTypeName = columnType == -1 ? "UNKNOWN" : dataType.toUpperCase(Locale.ENGLISH);

		if (columnType == -1)
		{
			return new ValidationResult(false, resolvedTypeName, null, null, List.of(),
				"Unknown dataType '" + dataType + "'. Use one of: TEXT, NUMBER, INTEGER, DATETIME, MEDIA.");
		}

		// --- empty / null format is always valid ---
		if (format == null || format.isBlank())
		{
			return new ValidationResult(true, resolvedTypeName, null, null, List.of(), null);
		}

		// --- parse ---
		ParsedFormat parsed = FormatParser.parseFormatProperty(format);

		// --- collect flags ---
		List<String> flags = collectFlags(parsed);

		String display = parsed.getDisplayFormat();
		String edit = parsed.getEditFormat();

		// --- validate pattern for DATETIME / NUMBER / INTEGER ---
		if (display != null && !display.startsWith("i18n:"))
		{
			String patternError = validatePattern(display, columnType, "display");
			if (patternError != null)
			{
				return new ValidationResult(false, resolvedTypeName, display, edit, flags, patternError);
			}
		}

		if (edit != null && !edit.startsWith("i18n:"))
		{
			String patternError = validatePattern(edit, columnType, "edit");
			if (patternError != null)
			{
				return new ValidationResult(false, resolvedTypeName, display, edit, flags, patternError);
			}
		}

		return new ValidationResult(true, resolvedTypeName, display, edit, flags, null);
	}

	// -------------------------------------------------------------------------
	// Public helpers
	// -------------------------------------------------------------------------

	/**
	 * Maps an {@link IColumnTypes} constant to the corresponding human-readable
	 * type name (e.g. {@code IColumnTypes.TEXT} &rarr; {@code "TEXT"}).
	 *
	 * @param columnType one of the {@link IColumnTypes} constants
	 * @return the type name, or {@code null} when the constant is not recognised
	 */
	public static String columnTypeToName(int columnType)
	{
		switch (columnType)
		{
			case IColumnTypes.TEXT :
				return "TEXT";
			case IColumnTypes.NUMBER :
				return "NUMBER";
			case IColumnTypes.INTEGER :
				return "INTEGER";
			case IColumnTypes.DATETIME :
				return "DATETIME";
			case IColumnTypes.MEDIA :
				return "MEDIA";
			default :
				return null;
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	/**
	 * Maps a human-readable type name to an {@link IColumnTypes} constant,
	 * or returns {@code -1} when the name is not recognised.
	 */
	private static int resolveColumnType(String dataType)
	{
		if (dataType == null) return -1;
		switch (dataType.toUpperCase(Locale.ENGLISH))
		{
			case "TEXT" :
				return IColumnTypes.TEXT;
			case "NUMBER" :
				return IColumnTypes.NUMBER;
			case "INTEGER" :
				return IColumnTypes.INTEGER;
			case "DATETIME" :
				return IColumnTypes.DATETIME;
			case "MEDIA" :
				return IColumnTypes.MEDIA;
			default :
				return -1;
		}
	}

	/**
	 * Validates a single pattern string against the column type by constructing the
	 * appropriate JDK formatter.  Returns an error message on failure, or {@code null}
	 * when the pattern is acceptable.
	 */
	private static String validatePattern(String pattern, int columnType, String role)
	{
		if (pattern == null || pattern.isBlank()) return null;

		try
		{
			if (columnType == IColumnTypes.DATETIME)
			{
				new SimpleDateFormat(pattern);
			}
			else if (columnType == IColumnTypes.NUMBER || columnType == IColumnTypes.INTEGER)
			{
				new DecimalFormat(pattern, RoundHalfUpDecimalFormat.getDecimalFormatSymbols(Locale.getDefault()));
			}
			// TEXT / MEDIA: no JVM-level pattern validation needed
		}
		catch (IllegalArgumentException ex)
		{
			return "Invalid " + role + " format pattern '" + pattern + "': " + ex.getMessage();
		}
		return null;
	}

	/**
	 * Collects all active boolean flags from a {@link ParsedFormat} into a
	 * human-readable list.
	 */
	private static List<String> collectFlags(ParsedFormat parsed)
	{
		List<String> flags = new ArrayList<>();
		if (parsed.isAllUpperCase()) flags.add("UPPERCASE");
		if (parsed.isAllLowerCase()) flags.add("LOWERCASE");
		if (parsed.isNumberValidator()) flags.add("NUMBER_VALIDATOR");
		if (parsed.isMask()) flags.add("MASK");
		if (parsed.isRaw()) flags.add("RAW");
		if (parsed.useLocalDateTime()) flags.add("LOCAL_DATETIME");
		if (parsed.getMaxLength() != null) flags.add("MAX_LENGTH(" + parsed.getMaxLength() + ")");
		if (parsed.getUIConverterName() != null) flags.add("CONVERTER(" + parsed.getUIConverterName() + ")");
		if (parsed.getAllowedCharacters() != null) flags.add("ALLOWED_CHARS");
		return flags;
	}
}
