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

import org.junit.Before;
import org.junit.Test;

import com.servoy.eclipse.developer.mcp.servers.ServoyI18nServer;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

public class ServoyI18nServerIntegrationTest extends DialogGuardBase {

	private ServoyI18nServer i18nServer;

	@Before
	public void setUp() throws Exception {
		i18nServer = new ServoyI18nServer();
		TestUtilitiesClass.waitForAppServer();
	}

	@Test
	public void testI18nListTables_returnsResult() {
		String result = i18nServer.i18nListTables();

		assertNotNull(result);
		assertFalse("Should not be an error: " + result, result.startsWith("Error"));
		assertTrue(result.contains("I18N-compatible tables"));
	}

	@Test
	public void testI18nListTables_containsCountInfo() {
		String result = i18nServer.i18nListTables();

		assertTrue(result.contains("I18N-compatible tables ("));
		assertTrue(result.contains("):"));
	}

	@Test
	public void testI18nSearchMessages_nullValue() {
		String result = i18nServer.i18nSearchMessages(null);

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("searchValue"));
	}

	@Test
	public void testI18nSearchMessages_blankValue() {
		String result = i18nServer.i18nSearchMessages("   ");

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("searchValue"));
	}

	@Test
	public void testI18nSearchMessages_noMatchReturnsMessage() {
		String result = i18nServer.i18nSearchMessages("xyzzy_nonexistent_value_98765");

		assertNotNull(result);
		assertTrue(result.contains("No matching") || result.contains("0 matches"));
	}

	@Test
	public void testI18nSearchMessages_findsCommonPlatformKey() {
		String result = i18nServer.i18nSearchMessages("OK");

		assertNotNull(result);
		assertFalse("Should find at least one match", result.contains("No matching"));
		assertTrue(result.contains("Platform defaults"));
	}

	@Test
	public void testI18nSetTable_nullServerName() {
		String result = i18nServer.i18nSetTable(null, "messages", null);

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName"));
	}

	@Test
	public void testI18nSetTable_blankServerName() {
		String result = i18nServer.i18nSetTable("   ", "messages", null);

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("serverName"));
	}

	@Test
	public void testI18nSetTable_nullTableName() {
		String result = i18nServer.i18nSetTable("repository_server", null, null);

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("tableName"));
	}

	@Test
	public void testI18nSetTable_blankTableName() {
		String result = i18nServer.i18nSetTable("repository_server", "   ", null);

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("tableName"));
	}

	@Test
	public void testI18nSetTable_invalidServer() {
		String result = i18nServer.i18nSetTable("nonexistent_server_xyz", "messages", null);

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("not found") || result.contains("No active solution"));
	}

	@Test
	public void testI18nSetTable_tableNotFound_noCreate() {
		String serverName = findAvailableServer();
		if (serverName == null)
			return;

		String result = i18nServer.i18nSetTable(serverName, "nonexistent_i18n_table_xyz", "false");

		assertTrue(result.contains("Error"));
		assertTrue(result.contains("not found") || result.contains("No active solution"));
	}

	private String findAvailableServer() {
		String[] serverNames = ApplicationServerRegistry.get().getServerManager().getServerNames(true, true, true,
				false);
		if (serverNames.length > 0) {
			return serverNames[0];
		}
		return null;
	}
}
