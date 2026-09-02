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
package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.swt.widgets.Display;
import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Integration tests for the validation-related MCP tools on {@link ServoyDevServer}:
 * {@code getTarget}, {@code validate}, {@code validateFormat}, {@code validateFormElementFormat},
 * and {@code syncDbiWithDatabase}.
 * <p>
 * Requires the Eclipse platform + Servoy runtime (JUnit Plug-in Test).
 */
public class ValidationToolsIntegrationTest extends DialogGuardBase
{

	private ServoyDevServer devServer;

	@Before
	public void setUp() throws Exception
	{
		devServer = new ServoyDevServer();
		assertNotNull("No Display available - test requires a running Eclipse UI", Display.getDefault());
		TestUtilitiesClass.waitForAppServer();
	}

	// -------------------------------------------------------------------------
	// getTarget
	// -------------------------------------------------------------------------

	@Test
	public void testGetTarget_returnsSolutionInfo()
	{
		String result = devServer.getTarget();

		assertNotNull(result);
		// Either reports the active solution or "No active solution"
		assertTrue("Should describe target state: " + result,
			result.contains("Active solution:") || result.contains("No active solution"));
	}

	@Test
	public void testGetTarget_listsAvailableSolutions()
	{
		String result = devServer.getTarget();

		assertNotNull(result);
		// When solutions exist, they are listed; otherwise just the active-solution line.
		// Both are acceptable - just verify it doesn't error.
		assertFalse("Should not start with Error: " + result, result.startsWith("Error"));
	}

	// -------------------------------------------------------------------------
	// validate (JavaScript syntax)
	// -------------------------------------------------------------------------

	@Test
	public void testValidate_nullCode_returnsError()
	{
		String result = devServer.validate(null);
		assertNotNull(result);
		assertTrue("Should require code: " + result, result.contains("Error") && result.contains("code"));
	}

	@Test
	public void testValidate_blankCode_returnsError()
	{
		String result = devServer.validate("   ");
		assertNotNull(result);
		assertTrue("Should require code: " + result, result.contains("Error") && result.contains("code"));
	}

	@Test
	public void testValidate_validCode_returnsValid()
	{
		String result = devServer.validate("var x = 1; function foo() { return x + 1; }");
		assertNotNull(result);
		assertTrue("Valid code should parse cleanly: " + result, result.contains("Valid"));
	}

	@Test
	public void testValidate_invalidCode_returnsProblems()
	{
		String result = devServer.validate("function ( { return ;;; }}}");
		assertNotNull(result);
		// Broken syntax should be reported as invalid with problem(s)
		assertTrue("Invalid code should be reported: " + result,
			result.contains("Invalid") || result.contains("problem"));
	}

	// -------------------------------------------------------------------------
	// validateFormat (tool wrapper around FormatValidatorService)
	// -------------------------------------------------------------------------

	@Test
	public void testValidateFormat_validDatetime()
	{
		String result = devServer.validateFormat("dd/MM/yyyy", "DATETIME");
		assertNotNull(result);
		assertTrue("Should report valid: " + result, result.contains("Valid format for DATETIME"));
	}

	@Test
	public void testValidateFormat_validNumber()
	{
		String result = devServer.validateFormat("#,###.00", "NUMBER");
		assertNotNull(result);
		assertTrue("Should report valid: " + result, result.contains("Valid format for NUMBER"));
	}

	@Test
	public void testValidateFormat_invalidDatetime()
	{
		String result = devServer.validateFormat("dd/QQ/yyyy", "DATETIME");
		assertNotNull(result);
		assertTrue("Should report invalid: " + result, result.contains("Invalid"));
	}

	@Test
	public void testValidateFormat_nullFormat_isValid()
	{
		String result = devServer.validateFormat(null, "MEDIA");
		assertNotNull(result);
		assertTrue("Null format should be valid: " + result, result.contains("Valid format for MEDIA"));
	}

	@Test
	public void testValidateFormat_unknownType()
	{
		String result = devServer.validateFormat("dd/MM/yyyy", "FOOBAR");
		assertNotNull(result);
		// Unknown data type is reported as invalid
		assertTrue("Should report invalid for unknown type: " + result, result.contains("Invalid"));
	}

	// -------------------------------------------------------------------------
	// validateFormElementFormat
	// -------------------------------------------------------------------------

	@Test
	public void testValidateFormElementFormat_nullFormName_returnsError()
	{
		String result = devServer.validateFormElementFormat(null, null, null);
		assertNotNull(result);
		assertTrue("Should require formName: " + result, result.contains("Error") && result.contains("formName"));
	}

	@Test
	public void testValidateFormElementFormat_blankFormName_returnsError()
	{
		String result = devServer.validateFormElementFormat("   ", null, null);
		assertNotNull(result);
		assertTrue("Should require formName: " + result, result.contains("Error") && result.contains("formName"));
	}

	@Test
	public void testValidateFormElementFormat_nonExistentForm_returnsError()
	{
		String result = devServer.validateFormElementFormat("nonExistentFormXYZ_99999", null, null);
		assertNotNull(result);
		assertTrue("Should report form not found: " + result,
			result.contains("Error") && result.contains("not found"));
	}

	// -------------------------------------------------------------------------
	// syncDbiWithDatabase
	// -------------------------------------------------------------------------

	@Test
	public void testSyncDbiWithDatabase_nullServerName_returnsError()
	{
		String result = devServer.syncDbiWithDatabase(null, null);
		assertNotNull(result);
		assertTrue("Should require serverName: " + result, result.contains("serverName is required"));
	}

	@Test
	public void testSyncDbiWithDatabase_blankServerName_returnsError()
	{
		String result = devServer.syncDbiWithDatabase("   ", null);
		assertNotNull(result);
		assertTrue("Should require serverName: " + result, result.contains("serverName is required"));
	}

	@Test
	public void testSyncDbiWithDatabase_nonExistentServer_returnsError()
	{
		String result = devServer.syncDbiWithDatabase("nonexistent_server_xyz", null);
		assertNotNull(result);
		assertTrue("Should report server not found: " + result, result.contains("Server not found"));
	}

	@Test
	public void testSyncDbiWithDatabase_validServer_runsSync()
	{
		String serverName = findAvailableServer();
		if (serverName == null)
			return; // no server available in this environment

		String result = devServer.syncDbiWithDatabase(serverName, null);
		assertNotNull(result);
		// A valid server should produce a JSON result (may contain errors array but not the
		// "serverName is required" / "Server not found" guard messages)
		assertFalse("Should not be a guard error: " + result, result.contains("serverName is required"));
		assertFalse("Should not be server-not-found: " + result, result.contains("Server not found"));
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private String findAvailableServer()
	{
		try
		{
			String[] serverNames = ApplicationServerRegistry.get().getServerManager()
				.getServerNames(true, true, true, false);
			if (serverNames != null && serverNames.length > 0)
				return serverNames[0];
		}
		catch (Exception e)
		{
			// no server manager available
		}
		return null;
	}
}
