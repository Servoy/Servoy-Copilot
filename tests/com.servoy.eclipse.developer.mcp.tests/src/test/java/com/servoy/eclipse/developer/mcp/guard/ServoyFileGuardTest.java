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
package com.servoy.eclipse.developer.mcp.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * JUnit 4 tests for {@link ServoyFileGuard} and {@link ServoyFileFormatProtectedException}.
 * No OSGi or Eclipse workspace required â pure Java.
 */
public class ServoyFileGuardTest
{
	// --- assertEditable: forbidden extensions ---

	@Test
	public void testAssertEditable_frm_throws()
	{
		assertThrowsProtected("forms/myForm.frm");
	}

	@Test
	public void testAssertEditable_obj_throws()
	{
		assertThrowsProtected("solution_settings.obj");
	}

	@Test
	public void testAssertEditable_tbl_throws()
	{
		assertThrowsProtected("datasources/db/server/table.tbl");
	}

	@Test
	public void testAssertEditable_val_throws()
	{
		assertThrowsProtected("valuelists/myList.val");
	}

	@Test
	public void testAssertEditable_rel_throws()
	{
		assertThrowsProtected("relations/myRelation.rel");
	}

	@Test
	public void testAssertEditable_dbi_throws()
	{
		assertThrowsProtected("datasources/db/server/table.dbi");
	}

	// --- assertEditable: safe extensions ---

	@Test
	public void testAssertEditable_js_safe()
	{
		ServoyFileGuard.assertEditable("scopes/globals/globals.js"); // must not throw
	}

	@Test
	public void testAssertEditable_ts_safe()
	{
		ServoyFileGuard.assertEditable("src/MyClass.ts");
	}

	@Test
	public void testAssertEditable_java_safe()
	{
		ServoyFileGuard.assertEditable("src/com/example/MyClass.java");
	}

	@Test
	public void testAssertEditable_xml_safe()
	{
		ServoyFileGuard.assertEditable("config/settings.xml");
	}

	@Test
	public void testAssertEditable_null_safe()
	{
		ServoyFileGuard.assertEditable(null); // must not throw
	}

	// --- assertEditable: case-insensitive ---

	@Test
	public void testAssertEditable_FRM_uppercase_throws()
	{
		assertThrowsProtected("forms/myForm.FRM");
	}

	@Test
	public void testAssertEditable_OBJ_uppercase_throws()
	{
		assertThrowsProtected("solution_settings.OBJ");
	}

	// --- isProtected ---

	@Test
	public void testIsProtected_frm_true()
	{
		assertTrue(ServoyFileGuard.isProtected("forms/myForm.frm"));
	}

	@Test
	public void testIsProtected_js_false()
	{
		assertFalse(ServoyFileGuard.isProtected("scopes/globals.js"));
	}

	@Test
	public void testIsProtected_null_false()
	{
		assertFalse(ServoyFileGuard.isProtected(null));
	}

	// --- Exception message ---

	@Test
	public void testExceptionMessage_containsRefusingText()
	{
		try
		{
			ServoyFileGuard.assertEditable("forms/test.frm");
			fail("Should have thrown");
		}
		catch (ServoyFileFormatProtectedException e)
		{
			assertNotNull(e.getMessage());
			assertTrue("Message must contain 'Refusing to edit'", e.getMessage().contains("Refusing to edit"));
			assertTrue("Message must contain the extension", e.getMessage().contains(".frm"));
			assertEquals("forms/test.frm", e.getPath());
			assertEquals(".frm", e.getExtension());
		}
	}

	// --- Helper ---

	private static void assertThrowsProtected(String path)
	{
		try
		{
			ServoyFileGuard.assertEditable(path);
			fail("Expected ServoyFileFormatProtectedException for path: " + path);
		}
		catch (ServoyFileFormatProtectedException e)
		{
			assertNotNull(e.getMessage());
			assertTrue("Message must contain 'Refusing to edit'", e.getMessage().contains("Refusing to edit"));
		}
	}
}
