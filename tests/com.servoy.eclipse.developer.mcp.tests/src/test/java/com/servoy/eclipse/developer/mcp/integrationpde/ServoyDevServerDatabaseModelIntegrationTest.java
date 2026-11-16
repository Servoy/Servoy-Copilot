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
package com.servoy.eclipse.developer.mcp.integrationpde;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.base.persistence.IBaseColumn;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.integrationpde.utils.TestUtilitiesClassCopy;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.inmemory.MemServer;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.ITable;

/**
 * Full-workbench integration tests for the {@link ServoyDevServer}
 * database-model tool <em>bodies</em> that mutate the real Servoy model and
 * therefore can only be driven inside a running Servoy Developer: the in-memory
 * variants of {@code createTable} (including the {@code columns} spec parsing in
 * {@code addColumnsFromSpec}) and {@code addColumn}, plus the deterministic
 * no-PostgreSQL-prototype branch of {@code createServer} and the body of
 * {@code generateUUID}.
 * <p>
 * The guard/validation branches (null/blank/invalid-identifier/temp_/svy_
 * prefixes, invalid server/type/length) are already covered by
 * {@link DatabaseToolsIntegrationTest}; this class deliberately covers only the
 * happy-path and model-mutation branches those tests leave untouched. In-memory
 * tables live under the active project's {@link MemServer}, so no external
 * database is needed - the outcome is asserted against the real
 * {@link com.servoy.eclipse.model.inmemory.MemServer} of the active project per
 * the "resolve against the active project" convention in AGENTS.md.
 * <p>
 * The setUp mirrors the proven pattern in {@link CreateArtifactsIntegrationTest}
 * and {@link MenuToolsIntegrationTest}.
 */
public class ServoyDevServerDatabaseModelIntegrationTest extends TestUtilitiesClassCopy {

	private static final String TEST_SOLUTION = "test_dbmodel_suite";
	private static final String SERVOY_RESOURCES = "servoy_resources";

	private ServoyDevServer devServer;
	private ServoyProject activeProject;

	public ServoyDevServerDatabaseModelIntegrationTest() {
		super(TEST_SOLUTION, SERVOY_RESOURCES);
	}

	@BeforeAll
	public static void deleteProjectsBeforeClass() throws Exception {
		deleteProjects(TEST_SOLUTION, SERVOY_RESOURCES);
		waitForWorkspaceBuildJobs();
	}

	@BeforeEach
	public void setUp() throws Exception {
		devServer = new ServoyDevServer();

		assertNotNull(Display.getDefault(), "No Display available - test requires a running Eclipse UI");

		waitForAppServer();
		ensureTestSolutionInWorkspace(null, null);
		ensureActiveProject();

		activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		assertNotNull(activeProject, "Active project required");
	}

	// -----------------------------------------------------------------------
	// createTable (in-memory) - exercises the createInMemory body branch
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("createTable in-memory creates the table with an auto-generated UUID PK column")
	public void testCreateTable_inMemory_createsTableWithPk() throws Exception {
		String tableName = "mem_basic_" + System.currentTimeMillis();

		String result = devServer.createTable("ignored_server", tableName, "true", null);

		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "createTable in-memory should not error: " + result);
		assertTrue(result.contains("In-memory table") && result.contains(tableName),
				"Result should confirm in-memory creation: " + result);

		MemServer memServer = activeProject.getMemServer();
		ITable table = memServer.getTable(tableName);
		assertNotNull(table, "In-memory table should exist in the active project's MemServer");

		String pkName = tableName + "_uuid";
		Column pk = table.getColumn(pkName);
		assertNotNull(pk, "Auto-generated PK column '" + pkName + "' should exist");
		assertTrue((pk.getFlags() & IBaseColumn.PK_COLUMN) != 0, "PK column should carry the PK flag");
	}

	@Test
	@DisplayName("createTable in-memory with a columns spec adds the typed columns (addColumnsFromSpec)")
	public void testCreateTable_inMemory_withColumnsSpec() throws Exception {
		String tableName = "mem_cols_" + System.currentTimeMillis();

		// TEXT with explicit length, INTEGER, NUMBER, DATETIME, MEDIA and a
		// non-nullable INTEGER (name:TYPE::false form) exercise every type branch
		// and the length/nullable parsing in addColumnsFromSpec.
		String columns = "cust_name:TEXT:100,qty:INTEGER,price:NUMBER,created:DATETIME,photo:MEDIA,flag:INTEGER::false";

		String result = devServer.createTable("ignored_server", tableName, "true", columns);

		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "createTable with columns should not error: " + result);
		assertTrue(result.contains("Columns added"), "Result should report added columns: " + result);

		MemServer memServer = activeProject.getMemServer();
		ITable table = memServer.getTable(tableName);
		assertNotNull(table, "In-memory table should exist");

		assertNotNull(table.getColumn("cust_name"), "TEXT column should be added");
		assertNotNull(table.getColumn("qty"), "INTEGER column should be added");
		assertNotNull(table.getColumn("price"), "NUMBER column should be added");
		assertNotNull(table.getColumn("created"), "DATETIME column should be added");
		assertNotNull(table.getColumn("photo"), "MEDIA column should be added");

		Column flag = table.getColumn("flag");
		assertNotNull(flag, "Non-nullable INTEGER column should be added");
		assertFalse(flag.getAllowNull(), "flag column should be non-nullable (nullable=false in spec)");
	}

	@Test
	@DisplayName("createTable in-memory with an invalid column type reports a column error but still creates the table")
	public void testCreateTable_inMemory_invalidColumnSpec_reportsColumnError() throws Exception {
		String tableName = "mem_badcol_" + System.currentTimeMillis();

		String result = devServer.createTable("ignored_server", tableName, "true", "good:TEXT,bad:BOGUS");

		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "Top-level createTable should succeed: " + result);
		assertTrue(result.contains("Column errors"), "Result should report the per-column error: " + result);
		assertTrue(result.contains("BOGUS"), "Result should name the invalid type: " + result);

		ITable table = activeProject.getMemServer().getTable(tableName);
		assertNotNull(table, "Table should still be created despite the bad column");
		assertNotNull(table.getColumn("good"), "The valid column should have been added");
	}

	@Test
	@DisplayName("createTable in-memory rejects a duplicate table name")
	public void testCreateTable_inMemory_duplicate_returnsError() {
		String tableName = "mem_dup_" + System.currentTimeMillis();

		String first = devServer.createTable("ignored_server", tableName, "true", null);
		assertFalse(first.startsWith("Error"), "First creation should succeed: " + first);

		String second = devServer.createTable("ignored_server", tableName, "true", null);
		assertTrue(second.startsWith("Error") && second.contains("already exists"),
				"Duplicate in-memory table should error: " + second);
	}

	// -----------------------------------------------------------------------
	// addColumn (in-memory) - exercises the isInMemory body branch
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("addColumn in-memory adds a column to an existing in-memory table")
	public void testAddColumn_inMemory_success() throws Exception {
		String tableName = "mem_addcol_" + System.currentTimeMillis();
		String createResult = devServer.createTable("ignored_server", tableName, "true", null);
		assertFalse(createResult.startsWith("Error"), "Table prerequisite failed: " + createResult);

		String result = devServer.addColumn("ignored_server", tableName, "description", "TEXT", "200", "true", "true");

		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "addColumn in-memory should not error: " + result);
		assertTrue(result.contains("added to in-memory table") && result.contains(tableName),
				"Result should confirm the in-memory add: " + result);

		ITable table = activeProject.getMemServer().getTable(tableName);
		assertNotNull(table, "In-memory table should exist");
		assertNotNull(table.getColumn("description"), "Added column should exist on the table");
	}

	@Test
	@DisplayName("addColumn in-memory rejects a duplicate column name")
	public void testAddColumn_inMemory_duplicate_returnsError() {
		String tableName = "mem_addcoldup_" + System.currentTimeMillis();
		String createResult = devServer.createTable("ignored_server", tableName, "true", null);
		assertFalse(createResult.startsWith("Error"), "Table prerequisite failed: " + createResult);

		String first = devServer.addColumn("ignored_server", tableName, "notes", "TEXT", null, null, "true");
		assertFalse(first.startsWith("Error"), "First addColumn should succeed: " + first);

		String second = devServer.addColumn("ignored_server", tableName, "notes", "TEXT", null, null, "true");
		assertTrue(second.startsWith("Error") && second.contains("already exists"),
				"Duplicate column should error: " + second);
	}

	@Test
	@DisplayName("addColumn in-memory errors when the target in-memory table does not exist")
	public void testAddColumn_inMemory_tableNotFound_returnsError() {
		String result = devServer.addColumn("ignored_server", "no_such_mem_table_" + System.currentTimeMillis(),
				"col", "TEXT", null, null, "true");

		assertNotNull(result);
		assertTrue(result.startsWith("Error") && result.contains("not found"),
				"Should error when the in-memory table is missing: " + result);
	}

	// -----------------------------------------------------------------------
	// createServer - the prototype-search body with no PostgreSQL server present
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("createServer errors when no enabled PostgreSQL server exists to clone from")
	public void testCreateServer_noPostgresPrototype_returnsError() {
		String result = devServer.createServer("devmcp_server_" + System.currentTimeMillis(), null, "false");

		assertNotNull(result);
		// The test workbench has no enabled PostgreSQL server to use as a connection
		// prototype, so the tool walks the server list and returns the prototype error.
		assertTrue(result.startsWith("Error"), "createServer should error without a PostgreSQL prototype: " + result);
		assertTrue(result.contains("PostgreSQL"), "Error should mention the missing PostgreSQL prototype: " + result);
	}

	// -----------------------------------------------------------------------
	// generateUUID - tool body
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("generateUUID returns a single uppercase UUID by default")
	public void testGenerateUUID_single() {
		String result = devServer.generateUUID(null);
		assertNotNull(result);
		assertFalse(result.startsWith("Error"), "generateUUID should not error: " + result);
		assertTrue(result.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"),
				"Should be a single uppercase UUID: " + result);
	}

	@Test
	@DisplayName("generateUUID returns one UUID per line for count > 1")
	public void testGenerateUUID_multiple() {
		String result = devServer.generateUUID("3");
		assertNotNull(result);
		String[] lines = result.split("\n");
		assertTrue(lines.length == 3, "Should return 3 UUIDs, got: " + result);
		for (String line : lines) {
			assertTrue(line.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"),
					"Each line should be an uppercase UUID: " + line);
		}
	}

	@Test
	@DisplayName("generateUUID rejects a non-numeric count")
	public void testGenerateUUID_invalidCount_returnsError() {
		String result = devServer.generateUUID("abc");
		assertTrue(result.startsWith("Error"), "Should error on a non-numeric count: " + result);
	}

	@Test
	@DisplayName("generateUUID rejects an out-of-range count")
	public void testGenerateUUID_outOfRange_returnsError() {
		String result = devServer.generateUUID("0");
		assertTrue(result.startsWith("Error"), "Should error on count below 1: " + result);
	}
}
