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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FormatValidatorService}.
 * <p>
 * No Eclipse/OSGi runtime is required - all tests run as plain JUnit 5.
 * </p>
 */
public class FormatValidatorServiceTest
{
	private FormatValidatorService service;

	@BeforeEach
	void setUp()
	{
		service = new FormatValidatorService();
	}

	// -------------------------------------------------------------------------
	// Null / empty format - always valid regardless of type
	// -------------------------------------------------------------------------

	@Test
	void testNullFormat_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat(null, "DATETIME");
		assertTrue(r.valid(), "null format must be valid");
		assertNull(r.error(), "no error expected");
		assertNull(r.displayFormat(), "no display format");
	}

	@Test
	void testBlankFormat_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("   ", "NUMBER");
		assertTrue(r.valid(), "blank format must be valid");
		assertNull(r.error(), "no error expected");
	}

	@Test
	void testEmptyString_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("", "TEXT");
		assertTrue(r.valid(), "empty string must be valid");
	}

	// -------------------------------------------------------------------------
	// Unknown / null data type
	// -------------------------------------------------------------------------

	@Test
	void testUnknownDataType_isInvalid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy", "FOOBAR");
		assertFalse(r.valid(), "unknown data type must be invalid");
		assertNotNull(r.error(), "error message expected");
		assertTrue(r.error().contains("FOOBAR"), "error mentions the unknown type");
		assertEquals("UNKNOWN", r.dataType());
	}

	@Test
	void testNullDataType_isInvalid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy", null);
		assertFalse(r.valid(), "null data type must be invalid");
		assertNotNull(r.error(), "error message expected");
	}

	// -------------------------------------------------------------------------
	// Data-type names are case-insensitive
	// -------------------------------------------------------------------------

	@Test
	void testDataTypeCaseInsensitive_lowercase()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy", "datetime");
		assertTrue(r.valid(), "lower-case 'datetime' must be accepted");
		assertEquals("DATETIME", r.dataType());
	}

	@Test
	void testDataTypeCaseInsensitive_mixedCase()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("0.##", "Number");
		assertTrue(r.valid(), "mixed-case 'Number' must be accepted");
	}

	// -------------------------------------------------------------------------
	// DATETIME - valid patterns
	// -------------------------------------------------------------------------

	@Test
	void testDatetime_simplePattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy", "DATETIME");
		assertTrue(r.valid(), r.error());
		assertEquals("dd/MM/yyyy", r.displayFormat());
		assertNull(r.editFormat(), "no edit format");
		assertFalse(r.flags().contains("MASK"), "no flags");
	}

	@Test
	void testDatetime_displayAndEditPattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy|dd-MM-yyyy", "DATETIME");
		assertTrue(r.valid(), r.error());
		assertEquals("dd/MM/yyyy", r.displayFormat());
		assertEquals("dd-MM-yyyy", r.editFormat());
	}

	@Test
	void testDatetime_maskPattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy|dd/MM/yyyy|mask", "DATETIME");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().contains("MASK"), "MASK flag expected");
	}

	@Test
	void testDatetime_withTime_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy HH:mm:ss", "DATETIME");
		assertTrue(r.valid(), r.error());
	}

	// -------------------------------------------------------------------------
	// DATETIME - invalid patterns
	// -------------------------------------------------------------------------

	@Test
	void testDatetime_invalidPattern_isInvalid()
	{
		// 'Q' is not a valid SimpleDateFormat pattern letter
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/QQ/yyyy", "DATETIME");
		assertFalse(r.valid(), "invalid datetime pattern must be rejected");
		assertNotNull(r.error());
		assertTrue(r.error().contains("display"), "error mentions display");
	}

	@Test
	void testDatetime_invalidEditPattern_isInvalid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy|dd/QQ/yyyy", "DATETIME");
		assertFalse(r.valid(), "invalid edit pattern must be rejected");
		assertNotNull(r.error());
		assertTrue(r.error().contains("edit"), "error mentions edit");
	}

	// -------------------------------------------------------------------------
	// NUMBER - valid patterns
	// -------------------------------------------------------------------------

	@Test
	void testNumber_simplePattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("0.##", "NUMBER");
		assertTrue(r.valid(), r.error());
		assertEquals("0.##", r.displayFormat());
	}

	@Test
	void testNumber_groupingPattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("#,###.00", "NUMBER");
		assertTrue(r.valid(), r.error());
	}

	@Test
	void testNumber_displayAndEditPattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("#,###.00|0.##", "NUMBER");
		assertTrue(r.valid(), r.error());
		assertEquals("#,###.00", r.displayFormat());
		assertEquals("0.##", r.editFormat());
	}

	@Test
	void testNumber_currencyPattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("\u00a4#,##0.00", "NUMBER");
		assertTrue(r.valid(), r.error());
	}

	// -------------------------------------------------------------------------
	// NUMBER - invalid patterns
	// -------------------------------------------------------------------------

	@Test
	void testNumber_invalidPattern_isInvalid()
	{
		// Multiple decimal separators is illegal in DecimalFormat
		FormatValidatorService.ValidationResult r = service.validateFormat("0.##.##", "NUMBER");
		assertFalse(r.valid(), "invalid decimal pattern must be rejected");
		assertNotNull(r.error());
	}

	@Test
	void testNumber_invalidEditPattern_isInvalid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("0.##|0.##.##", "NUMBER");
		assertFalse(r.valid(), "invalid edit decimal pattern must be rejected");
		assertNotNull(r.error());
		assertTrue(r.error().contains("edit"), "error mentions edit");
	}

	// -------------------------------------------------------------------------
	// INTEGER - treated same as NUMBER for pattern validation
	// -------------------------------------------------------------------------

	@Test
	void testInteger_validPattern_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("#,##0", "INTEGER");
		assertTrue(r.valid(), r.error());
		assertEquals("INTEGER", r.dataType());
	}

	@Test
	void testInteger_invalidPattern_isInvalid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("0.00.00", "INTEGER");
		assertFalse(r.valid(), "invalid integer format must be rejected");
	}

	// -------------------------------------------------------------------------
	// TEXT - structural flags, no pattern validation
	// -------------------------------------------------------------------------

	@Test
	void testText_uppercase_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("|U", "TEXT");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().contains("UPPERCASE"), "UPPERCASE flag expected");
		assertNull(r.displayFormat(), "no display format for |U");
	}

	@Test
	void testText_lowercase_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("|L", "TEXT");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().contains("LOWERCASE"), "LOWERCASE flag expected");
	}

	@Test
	void testText_numberValidator_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("|#", "TEXT");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().contains("NUMBER_VALIDATOR"), "NUMBER_VALIDATOR flag expected");
	}

	@Test
	void testText_uppercaseWithMaxLength_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("|U[50]", "TEXT");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().contains("UPPERCASE"), "UPPERCASE flag expected");
		assertTrue(r.flags().stream().anyMatch(f -> f.startsWith("MAX_LENGTH")), "MAX_LENGTH flag expected");
	}

	@Test
	void testText_displayFormat_isValid()
	{
		// Plain text mask-like display format - no JVM validation, always valid
		FormatValidatorService.ValidationResult r = service.validateFormat("(###) ###-####", "TEXT");
		assertTrue(r.valid(), r.error());
		assertEquals("(###) ###-####", r.displayFormat());
	}

	// -------------------------------------------------------------------------
	// MEDIA - no pattern validation
	// -------------------------------------------------------------------------

	@Test
	void testMedia_emptyFormat_isValid()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat(null, "MEDIA");
		assertTrue(r.valid());
		assertEquals("MEDIA", r.dataType());
	}

	// -------------------------------------------------------------------------
	// i18n-prefixed patterns - skipped, always accepted
	// -------------------------------------------------------------------------

	@Test
	void testDatetime_i18nDisplayFormat_skipsValidation()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("i18n:myapp.dateformat", "DATETIME");
		assertTrue(r.valid(), "i18n-prefixed display format must be accepted without JVM validation");
		assertEquals("i18n:myapp.dateformat", r.displayFormat());
	}

	@Test
	void testNumber_i18nDisplayFormat_skipsValidation()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("i18n:myapp.numberformat", "NUMBER");
		assertTrue(r.valid(), "i18n-prefixed display format must be accepted without JVM validation");
	}

	@Test
	void testDatetime_i18nEditFormat_skipsValidation()
	{
		// valid display + i18n edit - should pass
		FormatValidatorService.ValidationResult r = service.validateFormat("dd/MM/yyyy|i18n:myapp.editformat", "DATETIME");
		assertTrue(r.valid(), "i18n-prefixed edit format must be accepted");
		assertEquals("dd/MM/yyyy", r.displayFormat());
		assertEquals("i18n:myapp.editformat", r.editFormat());
	}

	// -------------------------------------------------------------------------
	// JSON format strings
	// -------------------------------------------------------------------------

	@Test
	void testJson_datetimeMask_isValid()
	{
		String json = "{\"displayFormat\":\"dd/MM/yyyy\",\"mask\":true}";
		FormatValidatorService.ValidationResult r = service.validateFormat(json, "DATETIME");
		assertTrue(r.valid(), r.error());
		assertEquals("dd/MM/yyyy", r.displayFormat());
		assertTrue(r.flags().contains("MASK"), "MASK flag expected");
	}

	@Test
	void testJson_numberFormat_isValid()
	{
		String json = "{\"displayFormat\":\"#,###.00\",\"editOrPlaceholder\":\"0.##\"}";
		FormatValidatorService.ValidationResult r = service.validateFormat(json, "NUMBER");
		assertTrue(r.valid(), r.error());
		assertEquals("#,###.00", r.displayFormat());
	}

	@Test
	void testJson_withConverter_reportsConverterFlag()
	{
		String json = "{\"converter\":{\"name\":\"myConverter\"},\"displayFormat\":\"dd/MM/yyyy\"}";
		FormatValidatorService.ValidationResult r = service.validateFormat(json, "DATETIME");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().stream().anyMatch(f -> f.startsWith("CONVERTER")), "CONVERTER flag expected");
	}

	// -------------------------------------------------------------------------
	// Flags reporting
	// -------------------------------------------------------------------------

	@Test
	void testFlags_rawFormat_reportsRawFlag()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("someformat|raw", "TEXT");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().contains("RAW"), "RAW flag expected");
	}

	@Test
	void testFlags_maxLengthSuffix_reportsMaxLengthFlag()
	{
		FormatValidatorService.ValidationResult r = service.validateFormat("someformat|#(100)", "TEXT");
		assertTrue(r.valid(), r.error());
		assertTrue(r.flags().stream().anyMatch(f -> f.startsWith("MAX_LENGTH")), "MAX_LENGTH flag expected");
	}

	// -------------------------------------------------------------------------
	// Returned data type name
	// -------------------------------------------------------------------------

	@Test
	void testResolvedTypeName_isUpperCase()
	{
		assertEquals("TEXT", service.validateFormat("|U", "text").dataType());
		assertEquals("NUMBER", service.validateFormat("0.##", "number").dataType());
		assertEquals("INTEGER", service.validateFormat("#,##0", "integer").dataType());
		assertEquals("DATETIME", service.validateFormat("dd/MM/yyyy", "Datetime").dataType());
		assertEquals("MEDIA", service.validateFormat(null, "media").dataType());
	}

	// -------------------------------------------------------------------------
	// columnTypeToName - inverse mapping
	// -------------------------------------------------------------------------

	@Test
	void testColumnTypeToName_text()
	{
		assertEquals("TEXT", FormatValidatorService.columnTypeToName(com.servoy.j2db.persistence.IColumnTypes.TEXT));
	}

	@Test
	void testColumnTypeToName_number()
	{
		assertEquals("NUMBER", FormatValidatorService.columnTypeToName(com.servoy.j2db.persistence.IColumnTypes.NUMBER));
	}

	@Test
	void testColumnTypeToName_integer()
	{
		assertEquals("INTEGER", FormatValidatorService.columnTypeToName(com.servoy.j2db.persistence.IColumnTypes.INTEGER));
	}

	@Test
	void testColumnTypeToName_datetime()
	{
		assertEquals("DATETIME", FormatValidatorService.columnTypeToName(com.servoy.j2db.persistence.IColumnTypes.DATETIME));
	}

	@Test
	void testColumnTypeToName_media()
	{
		assertEquals("MEDIA", FormatValidatorService.columnTypeToName(com.servoy.j2db.persistence.IColumnTypes.MEDIA));
	}

	@Test
	void testColumnTypeToName_unknown()
	{
		assertNull(FormatValidatorService.columnTypeToName(9999));
	}
}
