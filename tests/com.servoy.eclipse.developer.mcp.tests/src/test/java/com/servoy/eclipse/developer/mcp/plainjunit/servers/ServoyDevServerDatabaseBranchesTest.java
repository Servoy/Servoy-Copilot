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
}
