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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.services.DocumentationValidatorService.ValidationException;

/**
 * Unit tests for {@link DocumentationValidatorService}.
 * <p>
 * Pure JUnit 4 - no Eclipse/OSGi runtime required (regex-based logic only).
 * </p>
 */
public class DocumentationValidatorServiceTest
{
	private static final String UUID_A = "12345678-90ab-cdef-1234-567890abcdef";
	private static final String UUID_B = "abcdef12-3456-7890-abcd-ef1234567890";
	private static final String UUID_C = "11111111-2222-3333-4444-555555555555";
	private static final String UUID_D = "66666666-7777-8888-9999-aaaaaaaaaaaa";

	private DocumentationValidatorService service;

	@Before
	public void setUp()
	{
		service = new DocumentationValidatorService();
	}

	// -------------------------------------------------------------------------
	// extractUUIDs
	// -------------------------------------------------------------------------

	@Test
	public void testExtractUUIDs_null_returnsEmpty()
	{
		assertTrue(service.extractUUIDs(null).isEmpty());
	}

	@Test
	public void testExtractUUIDs_noUuids_returnsEmpty()
	{
		assertTrue(service.extractUUIDs("just some text without uuids").isEmpty());
	}

	@Test
	public void testExtractUUIDs_single()
	{
		List<String> uuids = service.extractUUIDs("/** @UUID " + UUID_A + " */");
		assertEquals(1, uuids.size());
		assertEquals(UUID_A, uuids.get(0));
	}

	@Test
	public void testExtractUUIDs_multiple_preservesOrder()
	{
		String content = "@UUID " + UUID_A + "\nsome code\n@UUID " + UUID_B;
		List<String> uuids = service.extractUUIDs(content);
		assertEquals(2, uuids.size());
		assertEquals(UUID_A, uuids.get(0));
		assertEquals(UUID_B, uuids.get(1));
	}

	// -------------------------------------------------------------------------
	// validateUUIDs
	// -------------------------------------------------------------------------

	@Test
	public void testValidateUUIDs_identical_passes() throws Exception
	{
		String content = "@UUID " + UUID_A;
		service.validateUUIDs(content, content); // should not throw
	}

	@Test
	public void testValidateUUIDs_bothEmpty_passes() throws Exception
	{
		service.validateUUIDs("no uuids here", "still no uuids"); // should not throw
	}

	@Test
	public void testValidateUUIDs_countMismatch_throws()
	{
		String original = "@UUID " + UUID_A + "\n@UUID " + UUID_B;
		String modified = "@UUID " + UUID_A;
		try
		{
			service.validateUUIDs(original, modified);
			fail("Expected ValidationException for count mismatch");
		}
		catch (ValidationException e)
		{
			assertTrue(e.getMessage().contains("UUID count mismatch"));
		}
	}

	@Test
	public void testValidateUUIDs_modified_throws()
	{
		String original = "@UUID " + UUID_A;
		String modified = "@UUID " + UUID_B;
		try
		{
			service.validateUUIDs(original, modified);
			fail("Expected ValidationException for modified UUID");
		}
		catch (ValidationException e)
		{
			assertTrue(e.getMessage().contains("UUID modification detected"));
		}
	}

	@Test
	public void testValidateUUIDs_nullContent_treatedAsEmpty() throws Exception
	{
		service.validateUUIDs(null, null); // both empty -> passes
	}

	// -------------------------------------------------------------------------
	// restoreUUIDs
	// -------------------------------------------------------------------------

	@Test
	public void testRestoreUUIDs_nullOriginals_returnsUnchanged()
	{
		String jsdoc = "@UUID " + UUID_B;
		assertEquals(jsdoc, service.restoreUUIDs(jsdoc, null));
	}

	@Test
	public void testRestoreUUIDs_emptyOriginals_returnsUnchanged()
	{
		String jsdoc = "@UUID " + UUID_B;
		assertEquals(jsdoc, service.restoreUUIDs(jsdoc, Collections.emptyList()));
	}

	@Test
	public void testRestoreUUIDs_noUuidsInJsdoc_returnsUnchanged()
	{
		String jsdoc = "no uuids in this doc";
		assertEquals(jsdoc, service.restoreUUIDs(jsdoc, List.of(UUID_A)));
	}

	@Test
	public void testRestoreUUIDs_replacesChangedUuid()
	{
		String jsdoc = "/** @UUID " + UUID_B + " */";
		String restored = service.restoreUUIDs(jsdoc, List.of(UUID_A));
		assertTrue("Should restore original UUID: " + restored, restored.contains(UUID_A));
		assertFalse("Should not contain the changed UUID: " + restored, restored.contains(UUID_B));
	}

	@Test
	public void testRestoreUUIDs_alreadyCorrect_returnsUnchanged()
	{
		String jsdoc = "@UUID " + UUID_A;
		assertEquals(jsdoc, service.restoreUUIDs(jsdoc, List.of(UUID_A)));
	}

	@Test
	public void testRestoreUUIDs_multiple()
	{
		// jsdoc has C and D; originals are A and B (all distinct, no collision on replaceFirst)
		String jsdoc = "@UUID " + UUID_C + "\n@UUID " + UUID_D;
		String restored = service.restoreUUIDs(jsdoc, Arrays.asList(UUID_A, UUID_B));
		List<String> result = service.extractUUIDs(restored);
		assertEquals(UUID_A, result.get(0));
		assertEquals(UUID_B, result.get(1));
	}

	// -------------------------------------------------------------------------
	// validateJSDocSyntax
	// -------------------------------------------------------------------------

	@Test
	public void testValidateJSDocSyntax_null_passes() throws Exception
	{
		service.validateJSDocSyntax(null); // should not throw
	}

	@Test
	public void testValidateJSDocSyntax_validBlock_passes() throws Exception
	{
		service.validateJSDocSyntax("/** A simple valid comment */");
	}

	@Test
	public void testValidateJSDocSyntax_validParamWithType_passes() throws Exception
	{
		service.validateJSDocSyntax("/**\n * @param {String} name the name\n */");
	}

	@Test
	public void testValidateJSDocSyntax_paramWithoutType_throws()
	{
		try
		{
			service.validateJSDocSyntax("/**\n * @param name missing type\n */");
			fail("Expected ValidationException for @param without type");
		}
		catch (ValidationException e)
		{
			assertTrue(e.getMessage().contains("@param tag missing type"));
		}
	}

	@Test
	public void testValidateJSDocSyntax_noJsdocBlocks_passes() throws Exception
	{
		service.validateJSDocSyntax("var x = 1; // line comment only");
	}

	// -------------------------------------------------------------------------
	// extractIndentation
	// -------------------------------------------------------------------------

	@Test
	public void testExtractIndentation_null_returnsEmpty()
	{
		assertEquals("", service.extractIndentation(null));
	}

	@Test
	public void testExtractIndentation_empty_returnsEmpty()
	{
		assertEquals("", service.extractIndentation(""));
	}

	@Test
	public void testExtractIndentation_noLeadingWhitespace()
	{
		assertEquals("", service.extractIndentation("code"));
	}

	@Test
	public void testExtractIndentation_spaces()
	{
		assertEquals("    ", service.extractIndentation("    code"));
	}

	@Test
	public void testExtractIndentation_tabs()
	{
		assertEquals("\t\t", service.extractIndentation("\t\tcode"));
	}

	@Test
	public void testExtractIndentation_mixedTabsAndSpaces()
	{
		assertEquals("\t  ", service.extractIndentation("\t  code"));
	}
}
