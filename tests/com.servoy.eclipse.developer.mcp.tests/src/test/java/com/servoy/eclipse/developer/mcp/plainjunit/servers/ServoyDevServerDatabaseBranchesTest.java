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
package com.servoy.eclipse.developer.mcp.plainjunit.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.servoy.base.persistence.IBaseColumn;
import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.developer.mcp.servers.ServoyDevServer;
import com.servoy.eclipse.model.inmemory.MemServer;
import com.servoy.eclipse.model.inmemory.MemTable;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.j2db.persistence.AbstractTable;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.ColumnInfo;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.IServerManagerInternal;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.TableNode;
import com.servoy.j2db.query.ColumnType;

/**
 * Plain-JUnit (Jupiter) unit tests for the <em>own</em> parse / validation /
 * formatting branches of {@link ServoyDevServer}'s <strong>database</strong>
 * tool bodies that are reachable <em>without</em> a live Eclipse workbench,
 * model, DLTK or app server - and that are <em>not</em> already exercised by
 * {@code ServoyDevServerTest} (the shared unit suite) or by
 * {@code ServoyDevServerDatabaseModelIntegrationTest} (which drives
 * {@code createTable}/{@code addColumn}/{@code generateUUID} against a real
 * in-memory server and asserts the actual model mutation).
 * <p>
 * This class is deliberately <strong>complementary</strong>: it fills the
 * remaining plain-JUnit-reachable gaps in the database tools, namely
 * <ul>
 * <li>{@code createTable}'s SQL-identifier / {@code temp_} / {@code svy_}
 * reserved-prefix guards, which - reading the current body - all run
 * <em>before</em> the static
 * {@code ServoyModelManager.getServoyModelManager().getServoyModel()} grab (the
 * prefix constants {@code DataModelManager.TEMP_UPPERCASE_PREFIX} and
 * {@code IServer.SERVOY_UPPERCASE_PREFIX} are compile-time {@code String}
 * constants, so reading them inlines the literal and does not class-init either
 * declaring type), plus the no-arg{@code  columns} 3-argument overload that
 * forwards to the 4-argument form; and</li>
 * <li>the private {@code addColumnsFromSpec(ITable, String, IValidateName)}
 * column-spec parser's success / length-parse / {@code allowNull}-parse /
 * non-numeric-length-swallow / per-column-failure branches and the trailing
 * "{@code Columns added}" result formatting - driven with a mocked
 * {@link AbstractTable} so no real column is ever created.</li>
 * </ul>
 * <p>
 * Since {@link ServoyDevServer} gained the overridable
 * {@code protected IDeveloperServoyModel getServoyModel()} seam (replacing its
 * static {@code ServoyModelManager.getServoyModelManager().getServoyModel()}
 * grabs), this class <em>also</em> drives the <strong>model-side</strong>
 * branch / dispatch logic of {@code createTable} and {@code addColumn} that runs
 * <em>after</em> the model grab, by subclassing the server and overriding
 * {@code getServoyModel()} to return a Mockito-mocked
 * {@link IDeveloperServoyModel}. Only the <strong>in-memory</strong> path is
 * unit-reachable this way - the {@code createInMemory == false} database path
 * additionally reaches the static {@code ApplicationServerRegistry} server
 * registry, which has no seam yet (see below). What is asserted here is the
 * server's own routing: which branch is taken (no-active-project guard,
 * already-exists guard, not-found guard, the identity/UUID-PK creation
 * dispatch, {@code addColumn}'s real column-add dispatch), how it maps the tool
 * arguments to the {@code MemServer} / {@code AbstractTable} calls (verified
 * with {@code verify}/{@link ArgumentCaptor} on the mocks), and the
 * result/error formatting - <em>not</em> any real model mutation, which stays
 * in {@code ServoyDevServerDatabaseModelIntegrationTest}.
 * <p>
 * Everything else in the database tools reaches the static
 * {@code ApplicationServerRegistry} server lookup, which needs the workbench and
 * has <strong>no injectable seam</strong>, so its happy paths stay in the
 * {@code integrationpde} suite:
 * <ul>
 * <li>{@code listTables} / {@code getTableInfo} / {@code executeSQL} /
 * {@code createServer} / {@code syncDbiWithDatabase} call the static server
 * registry directly - so only their blank-argument guards are unit-reachable,
 * and those are already covered by {@code ServoyDevServerTest.DatabaseToolGuards}
 * / {@code .SyncDbiGuard};</li>
 * <li>the {@code createInMemory == false} / {@code isInMemory == false} database
 * branches of {@code createTable} / {@code addColumn} reach the same static
 * {@code ApplicationServerRegistry}, so only their pre-registry portion is
 * unit-reachable (the argument parse / identifier / reserved-prefix guards here,
 * and the model-side guards once {@code createInMemory}/{@code isInMemory} is
 * {@code true}). <strong>A future {@code ApplicationServerRegistry} seam</strong>
 * (mirroring the {@code getServoyModel()} one) would unlock unit-testing the
 * database branches of {@code createTable} / {@code addColumn} and the bodies of
 * {@code listTables} / {@code getTableInfo} / {@code executeSQL} /
 * {@code createServer} / {@code syncDbiWithDatabase}.</li>
 * </ul>
 * No production change / seam is introduced <em>by this test</em>: the
 * {@code getServoyModel()} seam already exists on the server; each test either
 * instantiates the default constructor (for the pre-model guards and the private
 * spec parser reached via reflection, exactly as {@code ServoyDevServerTest}
 * does) or subclasses it to override the seam (for the model-side branches). No
 * visibility is widened and no real workbench is ever triggered.
 *
 * @since 2026.11
 */
class ServoyDevServerDatabaseBranchesTest
{
	// =======================================================================
	// reflection helper (no production seam / visibility widening)
	// =======================================================================

	/**
	 * Invokes the private {@code addColumnsFromSpec(ITable, String, IValidateName)}
	 * with a freshly-mocked {@link IValidateName}; the {@code table} drives the
	 * branch under test.
	 */
	private static String addColumnsFromSpec(ITable table, String columns)
	{
		try
		{
			Method m = ServoyDevServer.class.getDeclaredMethod("addColumnsFromSpec", ITable.class, String.class,
				IValidateName.class);
			m.setAccessible(true);
			return (String)m.invoke(new ServoyDevServer(), table, columns, mock(IValidateName.class));
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException("failed to invoke 'addColumnsFromSpec'", e);
		}
	}

	// =======================================================================
	// model-seam helpers (override getServoyModel(); no production change)
	// =======================================================================

	/**
	 * A {@link ServoyDevServer} whose protected {@code getServoyModel()} seam is
	 * overridden to return the supplied mock, so the model-side branches of
	 * {@code createTable} / {@code addColumn} run against a mocked Servoy core
	 * without a live workbench.
	 */
	private static ServoyDevServer serverWith(IDeveloperServoyModel model)
	{
		return new ServoyDevServer()
		{
			@Override
			protected IDeveloperServoyModel getServoyModel()
			{
				return model;
			}
		};
	}

	/** A model whose {@code getNameValidator()} is stubbed and {@code getActiveProject()} returns {@code null}. */
	private static IDeveloperServoyModel modelWithNoActiveProject()
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		lenient().when(model.getNameValidator()).thenReturn(mock(IValidateName.class));
		when(model.getActiveProject()).thenReturn(null);
		return model;
	}

	/**
	 * A model whose active project exposes the supplied {@link MemServer} mock and a mocked editing
	 * {@link Solution} (so {@code getOrCreateTableNode} / {@code saveEditingSolutionNodes} are driveable).
	 * The project's {@code getProject().getName()} resolves to {@code projectName}.
	 */
	private static IDeveloperServoyModel modelWithMemServer(MemServer memServer, String projectName)
		throws RepositoryException
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		lenient().when(model.getNameValidator()).thenReturn(mock(IValidateName.class));

		ServoyProject project = mock(ServoyProject.class);
		when(model.getActiveProject()).thenReturn(project);
		when(project.getMemServer()).thenReturn(memServer);

		IProject iProject = mock(IProject.class);
		lenient().when(iProject.getName()).thenReturn(projectName);
		lenient().when(project.getProject()).thenReturn(iProject);

		Solution solution = mock(Solution.class);
		lenient().when(project.getEditingSolution()).thenReturn(solution);
		lenient().when(solution.getOrCreateTableNode(any(String.class))).thenReturn(mock(TableNode.class));

		return model;
	}

	// =======================================================================
	// createTable - model-side (in-memory) branches (mocked getServoyModel())
	// =======================================================================

	@Nested
	class CreateTableInMemoryBranches
	{
		@Test
		@DisplayName("createTable in-memory with no active project reports no active Servoy project")
		void inMemoryNoActiveProject()
		{
			assertEquals("Error: No active Servoy project",
				serverWith(modelWithNoActiveProject()).createTable("srv", "orders", "true", null));
		}

		@Test
		@DisplayName("createTable in-memory rejects a table name that already exists on the MemServer")
		void inMemoryAlreadyExists() throws Exception
		{
			MemServer memServer = mock(MemServer.class);
			when(memServer.getTable("orders")).thenReturn(mock(com.servoy.eclipse.model.inmemory.MemTable.class));

			String result = serverWith(modelWithMemServer(memServer, "mysolution")).createTable("srv", "orders",
				"true", null);
			assertEquals("Error: In-memory table 'orders' already exists", result);
		}

		@Test
		@DisplayName("createTable in-memory creates the table, dispatches a UUID-native identity PK column, and reports it")
		void inMemoryHappyPath() throws Exception
		{
			MemServer memServer = mock(MemServer.class);
			when(memServer.getTable("orders")).thenReturn(null);

			MemTable table = mock(MemTable.class);
			when(memServer.createNewTable(any(IValidateName.class), eq("orders"))).thenReturn(table);

			Column pkColumn = mock(Column.class);
			when(table.createNewColumn(any(IValidateName.class), eq("orders_uuid"), any(ColumnType.class),
				eq(false), eq(true))).thenReturn(pkColumn);
			lenient().when(table.getDataSource()).thenReturn("mem:orders");

			String result = serverWith(modelWithMemServer(memServer, "mysolution")).createTable("srv", "orders",
				"true", null);

			assertEquals(
				"In-memory table 'orders' created in project 'mysolution' with PK column 'orders_uuid'.",
				result);

			// the PK column is created with allowNull=false, pkColumn=true, and flagged as a native UUID PK
			verify(table).createNewColumn(any(IValidateName.class), eq("orders_uuid"), any(ColumnType.class),
				eq(false), eq(true));
			verify(pkColumn).setSequenceType(ColumnInfo.UUID_GENERATOR);
			verify(pkColumn).setFlag(IBaseColumn.UUID_COLUMN, true);
			verify(pkColumn).setFlag(IBaseColumn.NATIVE_COLUMN, true);
			verify(pkColumn).setFlag(IBaseColumn.PK_COLUMN, true);
			verify(memServer).syncTableObjWithDB(table, false, true);
		}

		@Test
		@DisplayName("createTable in-memory with a 'columns' spec appends the addColumnsFromSpec result to the message")
		void inMemoryWithColumnsSpec() throws Exception
		{
			MemServer memServer = mock(MemServer.class);
			when(memServer.getTable("orders")).thenReturn(null);

			MemTable table = mock(MemTable.class);
			when(memServer.createNewTable(any(IValidateName.class), eq("orders"))).thenReturn(table);
			// PK column
			when(table.createNewColumn(any(IValidateName.class), eq("orders_uuid"), any(ColumnType.class),
				eq(false), eq(true))).thenReturn(mock(Column.class));
			// a spec column (4-arg overload, getColumn returns null so it is added)
			when(table.createNewColumn(any(IValidateName.class), eq("label"), any(ColumnType.class),
				anyBoolean())).thenReturn(mock(Column.class));
			lenient().when(table.getDataSource()).thenReturn("mem:orders");

			String result = serverWith(modelWithMemServer(memServer, "mysolution")).createTable("srv", "orders",
				"true", "label:TEXT:100");

			assertTrue(result.startsWith(
				"In-memory table 'orders' created in project 'mysolution' with PK column 'orders_uuid'."),
				"got: " + result);
			assertTrue(result.contains("Columns added (1): label"), "got: " + result);
		}
	}

	// =======================================================================
	// addColumn - model-side (in-memory) branches (mocked getServoyModel())
	// =======================================================================

	@Nested
	class AddColumnInMemoryBranches
	{
		@Test
		@DisplayName("addColumn in-memory with no active project reports no active Servoy project")
		void inMemoryNoActiveProject()
		{
			assertEquals("Error: No active Servoy project", serverWith(modelWithNoActiveProject()).addColumn("srv",
				"orders", "label", "TEXT", null, null, "true"));
		}

		@Test
		@DisplayName("addColumn in-memory reports when the in-memory table is not found")
		void inMemoryTableNotFound() throws Exception
		{
			MemServer memServer = mock(MemServer.class);
			when(memServer.getTable("orders")).thenReturn(null);

			String result = serverWith(modelWithMemServer(memServer, "mysolution")).addColumn("srv", "orders",
				"label", "TEXT", null, null, "true");
			assertEquals("Error: In-memory table 'orders' not found", result);
		}

		@Test
		@DisplayName("addColumn in-memory reports when the column already exists on the table")
		void inMemoryColumnAlreadyExists() throws Exception
		{
			MemServer memServer = mock(MemServer.class);
			MemTable table = mock(MemTable.class);
			when(memServer.getTable("orders")).thenReturn(table);
			when(table.getColumn("label")).thenReturn(mock(Column.class));

			String result = serverWith(modelWithMemServer(memServer, "mysolution")).addColumn("srv", "orders",
				"label", "TEXT", null, null, "true");
			assertEquals("Error: Column 'label' already exists in table 'orders'", result);
			// the add is never dispatched
			verify(table, never()).createNewColumn(any(IValidateName.class), any(String.class), any(ColumnType.class),
				anyBoolean());
		}

		@Test
		@DisplayName("addColumn in-memory dispatches the real column-add, maps type/length/allowNull, and reports it added")
		void inMemoryHappyPath() throws Exception
		{
			MemServer memServer = mock(MemServer.class);
			MemTable table = mock(MemTable.class);
			when(memServer.getTable("orders")).thenReturn(table);
			when(table.getColumn("qty")).thenReturn(null);
			when(table.createNewColumn(any(IValidateName.class), eq("qty"), any(ColumnType.class), anyBoolean()))
				.thenReturn(mock(Column.class));

			String result = serverWith(modelWithMemServer(memServer, "mysolution")).addColumn("srv", "orders", "qty",
				"INTEGER", "0", "false", "true");

			assertEquals("Column 'qty' (INTEGER) added to in-memory table 'orders'.", result);

			// args mapped onto the AbstractTable.createNewColumn(validator, name, type, allowNull) 4-arg overload
			ArgumentCaptor<ColumnType> typeCaptor = ArgumentCaptor.forClass(ColumnType.class);
			ArgumentCaptor<Boolean> allowNullCaptor = ArgumentCaptor.forClass(Boolean.class);
			verify(table).createNewColumn(any(IValidateName.class), eq("qty"), typeCaptor.capture(),
				allowNullCaptor.capture());
			assertEquals(com.servoy.j2db.persistence.IColumnTypes.INTEGER, typeCaptor.getValue().getSqlType());
			assertFalse(allowNullCaptor.getValue().booleanValue(), "allowNull=false must be passed through");
			verify(memServer).syncTableObjWithDB(table, false, true);
		}
	}

	// =======================================================================
	// createTable - identifier / reserved-prefix guards (before the model grab)
	// =======================================================================

	@Nested
	class CreateTableNameGuards
	{
		private final ServoyDevServer server = new ServoyDevServer();

		@Test
		@DisplayName("createTable rejects a tableName that is not a valid SQL identifier")
		void invalidSqlIdentifier()
		{
			assertEquals("Error: '1bad name' is not a valid SQL identifier",
				server.createTable("srv", "1bad name", "false", null));
		}

		@Test
		@DisplayName("createTable rejects a tableName starting with the reserved 'temp_' prefix (case-insensitive)")
		void rejectsTempPrefix()
		{
			// DataModelManager.TEMP_UPPERCASE_PREFIX = "TEMP_"; the check uppercases the name first
			assertEquals("Error: table name cannot start with 'temp_'",
				server.createTable("srv", "Temp_orders", "false", null));
		}

		@Test
		@DisplayName("createTable rejects a tableName starting with the reserved servoy prefix")
		void rejectsServoyPrefix()
		{
			// IServer.SERVOY_UPPERCASE_PREFIX = "SERVOY"; the message names it 'svy_' but the
			// actual check is on the uppercased 'SERVOY' prefix
			String result = server.createTable("srv", "servoy_internal", "false", null);
			assertEquals("Error: table name cannot start with 'svy_'", result);
		}

		@Test
		@DisplayName("the no-columns createTable overload forwards to the 4-argument form")
		void threeArgOverloadForwards()
		{
			// a bad identifier returns from the shared 4-arg body, proving the overload delegates
			assertEquals("Error: '1bad' is not a valid SQL identifier", server.createTable("srv", "1bad", "false"));
		}
	}

	// =======================================================================
	// addColumnsFromSpec - success / parse / failure branches (mocked table)
	// =======================================================================

	@Nested
	class AddColumnsFromSpecBranches
	{
		/** A mocked {@link AbstractTable} whose {@code createNewColumn} succeeds for every column. */
		private AbstractTable acceptingTable() throws RepositoryException
		{
			AbstractTable table = mock(AbstractTable.class);
			// getColumn(name) defaults to null (column does not yet exist)
			when(table.createNewColumn(any(IValidateName.class), any(String.class), any(ColumnType.class),
				org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(mock(Column.class));
			return table;
		}

		@Test
		@DisplayName("addColumnsFromSpec creates a column with an explicit numeric length and reports it added")
		void addsColumnWithNumericLength() throws RepositoryException
		{
			String result = addColumnsFromSpec(acceptingTable(), "label:TEXT:100");
			assertTrue(result.contains("Columns added (1): label"), "got: " + result);
			assertFalse(result.contains("Column errors:"), "got: " + result);
		}

		@Test
		@DisplayName("addColumnsFromSpec parses the allowNull part and a non-TEXT type, reporting the column added")
		void addsNonTextColumnWithAllowNull() throws RepositoryException
		{
			// 'flag:INTEGER:0:false' exercises the 4-part spec: INTEGER case + length 0 + allowNull=false
			String result = addColumnsFromSpec(acceptingTable(), "flag:INTEGER:0:false");
			assertTrue(result.contains("Columns added (1): flag"), "got: " + result);
		}

		@Test
		@DisplayName("addColumnsFromSpec swallows a non-numeric length and still adds the column")
		void nonNumericLengthIsIgnored() throws RepositoryException
		{
			// 'x:TEXT:abc' -> NumberFormatException is caught, length stays 0 (TEXT defaults to 50), column added
			String result = addColumnsFromSpec(acceptingTable(), "x:TEXT:abc");
			assertTrue(result.contains("Columns added (1): x"), "got: " + result);
			assertFalse(result.contains("Column errors:"), "got: " + result);
		}

		@Test
		@DisplayName("addColumnsFromSpec reports a per-column failure when createNewColumn throws")
		void reportsCreateFailure() throws RepositoryException
		{
			AbstractTable table = mock(AbstractTable.class);
			when(table.createNewColumn(any(IValidateName.class), eq("boom"), any(ColumnType.class),
				org.mockito.ArgumentMatchers.anyBoolean())).thenThrow(new RepositoryException("disk full"));

			String result = addColumnsFromSpec(table, "boom:TEXT");
			assertTrue(result.contains("Column errors:"), "got: " + result);
			assertTrue(result.contains("Failed to add 'boom': disk full"), "got: " + result);
			assertFalse(result.contains("Columns added"), "got: " + result);
		}
	}

	// =======================================================================
	// server-manager-seam helpers (override getServerManager(); no production
	// change). Now that ServoyDevServer exposes the overridable protected
	// IServerManagerInternal getServerManager() seam, the DATABASE (non
	// in-memory) branches that reach the static ApplicationServerRegistry server
	// registry are unit-reachable: a Mockito-mocked IServerManagerInternal whose
	// getServer(name, false, false) returns a mocked IServerInternal drives the
	// server's own routing / dispatch / formatting without a running app server.
	// =======================================================================

	/**
	 * A {@link ServoyDevServer} whose protected {@code getServerManager()} seam is
	 * overridden to return the supplied mock (and, when non-null, whose
	 * {@code getServoyModel()} seam is overridden too), so the database-path
	 * branches of the tools run against a mocked Servoy core without a live app
	 * server or workbench.
	 */
	private static ServoyDevServer serverWith(IServerManagerInternal serverManager, IDeveloperServoyModel model)
	{
		return new ServoyDevServer()
		{
			@Override
			protected IServerManagerInternal getServerManager()
			{
				return serverManager;
			}

			@Override
			protected IDeveloperServoyModel getServoyModel()
			{
				return model != null ? model : super.getServoyModel();
			}
		};
	}

	/** A server manager whose {@code getServer(name, false, false)} resolves {@code serverName} to {@code server} (any other name to {@code null}). */
	private static IServerManagerInternal serverManagerWith(String serverName, IServerInternal server)
		throws RepositoryException
	{
		IServerManagerInternal sm = mock(IServerManagerInternal.class);
		lenient().when(sm.getServer(eq(serverName), eq(false), eq(false)))
			.thenReturn((com.servoy.j2db.persistence.IServer)server);
		return sm;
	}

	/** A model whose {@code getNameValidator()} is stubbed and {@code getDataModelManager()} returns the supplied value. */
	private static IDeveloperServoyModel modelWithDmm(com.servoy.eclipse.model.repository.DataModelManager dmm)
	{
		IDeveloperServoyModel model = mock(IDeveloperServoyModel.class);
		lenient().when(model.getNameValidator()).thenReturn(mock(IValidateName.class));
		lenient().when(model.getActiveProject()).thenReturn(null);
		lenient().when(model.getDataModelManager()).thenReturn(dmm);
		return model;
	}

	// =======================================================================
	// createServer - server-registry branches (mocked getServerManager())
	// =======================================================================

	@Nested
	class CreateServerBranches
	{
		@Test
		@DisplayName("createServer requires a non-blank serverName")
		void requiresServerName() throws Exception
		{
			assertEquals("Error: serverName is required",
				serverWith(mock(IServerManagerInternal.class), null).createServer("", "db", "true"));
		}

		@Test
		@DisplayName("createServer reports when no enabled PostgreSQL server exists to use as prototype")
		void noPostgresPrototype() throws Exception
		{
			IServerManagerInternal sm = mock(IServerManagerInternal.class);
			when(sm.getServerNames(true, false, true, true)).thenReturn(new String[0]);

			assertEquals("Error: No enabled PostgreSQL server found in the workspace to use as prototype",
				serverWith(sm, null).createServer("newsrv", "newdb", "true"));
		}

		@Test
		@DisplayName("createServer skips a candidate whose config is not an enabled Postgres driver")
		void skipsNonPostgresCandidate() throws Exception
		{
			IServerManagerInternal sm = mock(IServerManagerInternal.class);
			when(sm.getServerNames(true, false, true, true)).thenReturn(new String[] { "mysql" });

			IServerInternal candidate = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(sm.getServer("mysql", false, false)).thenReturn((com.servoy.j2db.persistence.IServer)candidate);
			com.servoy.j2db.persistence.ServerConfig cfg = mock(com.servoy.j2db.persistence.ServerConfig.class);
			when(candidate.getConfig()).thenReturn(cfg);
			when(cfg.isPostgresDriver()).thenReturn(false);

			// no Postgres candidate found -> same "no prototype" error
			assertEquals("Error: No enabled PostgreSQL server found in the workspace to use as prototype",
				serverWith(sm, null).createServer("newsrv", "newdb", "true"));
		}

		@Test
		@DisplayName("createServer reports a database-creation failure when the prototype connection fails")
		void createDatabaseFailure() throws Exception
		{
			IServerManagerInternal sm = mock(IServerManagerInternal.class);
			when(sm.getServerNames(true, false, true, true)).thenReturn(new String[] { "pg" });

			IServerInternal prototype = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(sm.getServer("pg", false, false)).thenReturn((com.servoy.j2db.persistence.IServer)prototype);
			com.servoy.j2db.persistence.ServerConfig cfg = mock(com.servoy.j2db.persistence.ServerConfig.class);
			when(prototype.getConfig()).thenReturn(cfg);
			when(cfg.isPostgresDriver()).thenReturn(true);
			when(cfg.isEnabled()).thenReturn(true);
			// the CREATE DATABASE step fails before any static EclipseDatabaseUtils call
			when(prototype.getUnmanagedConnection()).thenThrow(new java.sql.SQLException("no route to host"));

			String result = serverWith(sm, null).createServer("newsrv", "newdb", "true");
			assertEquals("Error: Could not create database: no route to host", result);
		}
	}

	// =======================================================================
	// listTables - server-registry branches (mocked getServerManager())
	// =======================================================================

	@Nested
	class ListTablesBranches
	{
		@Test
		@DisplayName("listTables reports when the database server is not found")
		void serverNotFound() throws Exception
		{
			assertEquals("Error: Database server 'srv' not found",
				serverWith(serverManagerWith("srv", null), null).listTables("srv"));
		}

		@Test
		@DisplayName("listTables reports '(No tables found)' when the server has no tables")
		void emptyTableList() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getTableNames(false)).thenReturn(new java.util.ArrayList<>());

			String result = serverWith(serverManagerWith("srv", server), null).listTables("srv");
			assertTrue(result.contains("Database Server: srv"), "got: " + result);
			assertTrue(result.contains("Tables (0):"), "got: " + result);
			assertTrue(result.contains("(No tables found)"), "got: " + result);
		}

		@Test
		@DisplayName("listTables lists each table name returned by the server")
		void listsTables() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getTableNames(false))
				.thenReturn(new java.util.ArrayList<>(java.util.List.of("orders", "customers")));

			String result = serverWith(serverManagerWith("srv", server), null).listTables("srv");
			assertTrue(result.contains("Tables (2):"), "got: " + result);
			assertTrue(result.contains("  - orders"), "got: " + result);
			assertTrue(result.contains("  - customers"), "got: " + result);
			assertFalse(result.contains("(No tables found)"), "got: " + result);
		}

		@Test
		@DisplayName("listTables formats an exception thrown by the server as an Error")
		void serverException() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getTableNames(false)).thenThrow(new RepositoryException("boom"));

			assertEquals("Error: boom", serverWith(serverManagerWith("srv", server), null).listTables("srv"));
		}
	}

	// =======================================================================
	// getTableInfo - server-registry branches (mocked getServerManager())
	// =======================================================================

	@Nested
	class GetTableInfoBranches
	{
		@Test
		@DisplayName("getTableInfo reports when the database server is not found")
		void serverNotFound() throws Exception
		{
			assertEquals("Error: Database server 'srv' not found",
				serverWith(serverManagerWith("srv", null), null).getTableInfo("srv", "orders"));
		}

		@Test
		@DisplayName("getTableInfo reports when the table is not found on the server")
		void tableNotFound() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getTable("orders")).thenReturn(null);

			assertEquals("Error: Table 'orders' not found in server 'srv'",
				serverWith(serverManagerWith("srv", server), null).getTableInfo("srv", "orders"));
		}

		@Test
		@DisplayName("getTableInfo reports '(No columns found)' for a table with no columns")
		void noColumns() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			ITable table = mock(ITable.class);
			when(server.getTable("orders")).thenReturn(table);
			when(table.getSQLName()).thenReturn("orders");
			when(table.getDataSource()).thenReturn("db:/srv/orders");
			when(table.getColumns()).thenReturn(new java.util.ArrayList<>());

			String result = serverWith(serverManagerWith("srv", server), null).getTableInfo("srv", "orders");
			assertTrue(result.contains("Table: orders"), "got: " + result);
			assertTrue(result.contains("DataSource: db:/srv/orders"), "got: " + result);
			assertTrue(result.contains("(No columns found)"), "got: " + result);
		}

		@Test
		@DisplayName("getTableInfo renders each column with type, PK flag and nullability")
		void rendersColumns() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			ITable table = mock(ITable.class);
			when(server.getTable("orders")).thenReturn(table);
			when(table.getSQLName()).thenReturn("orders");
			when(table.getDataSource()).thenReturn("db:/srv/orders");

			Column id = mock(Column.class);
			lenient().when(id.getName()).thenReturn("orders_id");
			lenient().when(id.getColumnType()).thenReturn(ColumnType.getInstance(
				com.servoy.j2db.persistence.IColumnTypes.INTEGER, 0, 0));
			lenient().when(id.getAllowNull()).thenReturn(false);

			Column label = mock(Column.class);
			lenient().when(label.getName()).thenReturn("label");
			lenient().when(label.getColumnType()).thenReturn(ColumnType.getInstance(
				com.servoy.j2db.persistence.IColumnTypes.TEXT, 50, 0));
			lenient().when(label.getAllowNull()).thenReturn(true);

			when(table.getColumns()).thenReturn(new java.util.ArrayList<>(java.util.List.of(id, label)));
			when(table.getRowIdentColumns()).thenReturn(new java.util.ArrayList<>(java.util.List.of(id)));

			String result = serverWith(serverManagerWith("srv", server), null).getTableInfo("srv", "orders");
			assertTrue(result.contains("1. orders_id"), "got: " + result);
			assertTrue(result.contains("2. label"), "got: " + result);
			// orders_id is a PK, label is not
			int idIdx = result.indexOf("orders_id");
			int labelIdx = result.indexOf("label");
			assertTrue(result.indexOf("PK: true", idIdx) > 0, "orders_id should be PK; got: " + result);
			assertTrue(result.indexOf("PK: false", labelIdx) > 0, "label should not be PK; got: " + result);
		}
	}

	// =======================================================================
	// executeSQL - server-registry branches (mocked getServerManager())
	// =======================================================================

	@Nested
	class ExecuteSqlBranches
	{
		@Test
		@DisplayName("executeSQL reports when the database server is not found")
		void serverNotFound() throws Exception
		{
			assertEquals("Error: Database server 'srv' not found",
				serverWith(serverManagerWith("srv", null), null).executeSQL("srv", "UPDATE t SET x=1"));
		}

		@Test
		@DisplayName("executeSQL dispatches a non-SELECT statement as an update and reports rows affected")
		void updateStatement() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			com.servoy.j2db.util.ITransactionConnection connection = mock(
				com.servoy.j2db.util.ITransactionConnection.class);
			when(server.getUnmanagedConnection()).thenReturn(connection);
			java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
			when(connection.prepareStatement("UPDATE t SET x=1")).thenReturn(ps);
			when(ps.executeUpdate()).thenReturn(7);

			String result = serverWith(serverManagerWith("srv", server), null).executeSQL("srv", "UPDATE t SET x=1");
			assertEquals("SQL executed successfully. Rows affected: 7. Tables reloaded.", result);
			verify(server).reloadTables();
		}

		@Test
		@DisplayName("executeSQL runs a SELECT and formats the (empty) result set")
		void selectStatement() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			com.servoy.j2db.util.ITransactionConnection connection = mock(
				com.servoy.j2db.util.ITransactionConnection.class);
			when(server.getUnmanagedConnection()).thenReturn(connection);
			java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
			when(connection.prepareStatement("SELECT id FROM t")).thenReturn(ps);
			java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
			when(ps.executeQuery()).thenReturn(rs);
			java.sql.ResultSetMetaData meta = mock(java.sql.ResultSetMetaData.class);
			when(rs.getMetaData()).thenReturn(meta);
			when(meta.getColumnCount()).thenReturn(1);
			when(meta.getColumnLabel(1)).thenReturn("id");
			when(rs.next()).thenReturn(false);

			String result = serverWith(serverManagerWith("srv", server), null).executeSQL("srv", "SELECT id FROM t");
			assertTrue(result.contains("id"), "header expected; got: " + result);
			assertTrue(result.contains("(0 row(s))"), "got: " + result);
			verify(server, never()).reloadTables();
		}

		@Test
		@DisplayName("executeSQL formats an exception from the connection as an Error")
		void connectionFailure() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getUnmanagedConnection()).thenThrow(new java.sql.SQLException("down"));

			assertEquals("Error: down",
				serverWith(serverManagerWith("srv", server), null).executeSQL("srv", "UPDATE t SET x=1"));
		}
	}

	// =======================================================================
	// syncDbiWithDatabase - server-registry branches (mocked getServerManager())
	// =======================================================================

	@Nested
	class SyncDbiBranches
	{
		@Test
		@DisplayName("syncDbiWithDatabase reports when the server is not found")
		void serverNotFound() throws Exception
		{
			assertEquals("{\"errors\":[\"Server not found: srv\"]}",
				serverWith(serverManagerWith("srv", null), modelWithDmm(null)).syncDbiWithDatabase("srv", null));
		}

		@Test
		@DisplayName("syncDbiWithDatabase reports when the DataModelManager is not available")
		void noDataModelManager() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			assertEquals("{\"errors\":[\"DataModelManager not available\"]}",
				serverWith(serverManagerWith("srv", server), modelWithDmm(null)).syncDbiWithDatabase("srv", null));
		}

		@Test
		@DisplayName("syncDbiWithDatabase returns an all-empty result when no .dbi server-info folder exists")
		void emptySync() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			lenient().when(server.getName()).thenReturn("srv");
			lenient().when(server.getTableAndViewNames(true)).thenReturn(new java.util.ArrayList<>());

			com.servoy.eclipse.model.repository.DataModelManager dmm = mock(
				com.servoy.eclipse.model.repository.DataModelManager.class);
			// no server-info folder -> every phase returns early, writes stay toggled off/on
			lenient().when(dmm.getServerInformationFolder(any(String.class))).thenReturn(null);

			String result = serverWith(serverManagerWith("srv", server), modelWithDmm(dmm))
				.syncDbiWithDatabase("srv", null);
			assertEquals(
				"{\"tablesCreated\":[],\"orphanTables\":[],\"tablesModified\":[],\"errors\":[]}",
				result);
			// writes are disabled during the sync and re-enabled in the finally block
			verify(dmm).setWritesEnabled(false);
			verify(dmm).setWritesEnabled(true);
		}
	}

	// =======================================================================
	// createTable - DATABASE (non in-memory) server-registry branches
	// =======================================================================

	@Nested
	class CreateTableDatabaseBranches
	{
		@Test
		@DisplayName("createTable database path reports when the database server is not found")
		void serverNotFound() throws Exception
		{
			assertEquals("Error: Database server 'srv' not found",
				serverWith(serverManagerWith("srv", null), modelWithDmm(null)).createTable("srv", "orders", "false",
					null));
		}

		@Test
		@DisplayName("createTable database path rejects a table that already exists on the server")
		void tableAlreadyExists() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getTable("orders")).thenReturn(mock(ITable.class));

			assertEquals("Error: Table 'orders' already exists on server 'srv'",
				serverWith(serverManagerWith("srv", server), modelWithDmm(null)).createTable("srv", "orders", "false",
					null));
		}
	}

	// =======================================================================
	// addColumn - DATABASE (non in-memory) server-registry branches
	// =======================================================================

	@Nested
	class AddColumnDatabaseBranches
	{
		@Test
		@DisplayName("addColumn database path reports when the database server is not found")
		void serverNotFound() throws Exception
		{
			assertEquals("Error: Database server 'srv' not found",
				serverWith(serverManagerWith("srv", null), modelWithDmm(null)).addColumn("srv", "orders", "label",
					"TEXT", null, null, "false"));
		}

		@Test
		@DisplayName("addColumn database path reports when the table is not found on the server")
		void tableNotFound() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			when(server.getTable("orders")).thenReturn(null);

			assertEquals("Error: Table 'orders' not found on server 'srv'",
				serverWith(serverManagerWith("srv", server), modelWithDmm(null)).addColumn("srv", "orders", "label",
					"TEXT", null, null, "false"));
		}

		@Test
		@DisplayName("addColumn database path reports when the column already exists on the table")
		void columnAlreadyExists() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			AbstractTable table = mock(AbstractTable.class);
			when(server.getTable("orders")).thenReturn(table);
			when(table.getColumn("label")).thenReturn(mock(Column.class));

			assertEquals("Error: Column 'label' already exists in table 'orders'",
				serverWith(serverManagerWith("srv", server), modelWithDmm(null)).addColumn("srv", "orders", "label",
					"TEXT", null, null, "false"));
			verify(table, never()).createNewColumn(any(IValidateName.class), any(String.class), any(ColumnType.class),
				anyBoolean());
		}

		@Test
		@DisplayName("addColumn database path dispatches the column-add, syncs the server, and reports it (dmm null)")
		void databaseHappyPath() throws Exception
		{
			IServerInternal server = mock(IServerInternal.class, org.mockito.Mockito.withSettings().extraInterfaces(com.servoy.j2db.persistence.IServer.class));
			AbstractTable table = mock(AbstractTable.class);
			when(server.getTable("orders")).thenReturn(table);
			when(table.getColumn("qty")).thenReturn(null);
			when(table.createNewColumn(any(IValidateName.class), eq("qty"), any(ColumnType.class), anyBoolean()))
				.thenReturn(mock(Column.class));

			// dmm null -> the updateAllColumnInfo step is skipped, the message is still returned
			String result = serverWith(serverManagerWith("srv", server), modelWithDmm(null)).addColumn("srv", "orders",
				"qty", "INTEGER", "0", "false", "false");

			assertEquals(
				"Column 'qty' (INTEGER, length=0, nullable=false) added to table 'orders' on server 'srv'.",
				result);

			ArgumentCaptor<ColumnType> typeCaptor = ArgumentCaptor.forClass(ColumnType.class);
			ArgumentCaptor<Boolean> allowNullCaptor = ArgumentCaptor.forClass(Boolean.class);
			verify(table).createNewColumn(any(IValidateName.class), eq("qty"), typeCaptor.capture(),
				allowNullCaptor.capture());
			assertEquals(com.servoy.j2db.persistence.IColumnTypes.INTEGER, typeCaptor.getValue().getSqlType());
			assertFalse(allowNullCaptor.getValue().booleanValue(), "allowNull=false must be passed through");
			verify(server).syncTableObjWithDB(table, false, true);
		}
	}
}
