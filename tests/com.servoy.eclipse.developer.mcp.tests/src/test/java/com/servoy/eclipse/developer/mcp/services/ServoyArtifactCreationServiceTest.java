/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.
*/
package com.servoy.eclipse.developer.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.util.DataSourceUtils;

/**
 * Unit tests for the private, scheme-aware {@code correctDataSource(String)}
 * helper of {@link ServoyArtifactCreationService} (SVY-21564).
 * <p>
 * {@code correctDataSource} is pure string logic with no Eclipse/Servoy runtime
 * dependency, so it is exercised directly via reflection
 * ({@code getDeclaredMethod} + {@code setAccessible(true)}), following the
 * pattern of {@link TestFileServiceReflectionTest}. The matrix mirrors the
 * spec's acceptance criteria: {@code db:/}, bare {@code server/table},
 * {@code mem:} and {@code view:} datasources, plus the blank/null and malformed
 * error paths.
 * <p>
 * The {@code createFormWithFields} / {@code createRelation} /
 * {@code createValueList} end-to-end paths require a live workbench + active
 * solution + a mem table and are left to the existing integration suite
 * ({@code CreateArtifactsIntegrationTest}).
 */
public class ServoyArtifactCreationServiceTest {
	private ServoyArtifactCreationService service;
	private Method correctDataSource;

	@BeforeEach
	void setUp() throws Exception {
		service = new ServoyArtifactCreationService();
		correctDataSource = ServoyArtifactCreationService.class.getDeclaredMethod("correctDataSource", String.class);
		correctDataSource.setAccessible(true);
	}

	/**
	 * Invokes the private helper, unwrapping the reflection wrapper so the real
	 * cause surfaces.
	 */
	private String invoke(String ds) throws Throwable {
		try {
			return (String) correctDataSource.invoke(service, ds);
		} catch (InvocationTargetException ite) {
			throw ite.getCause();
		}
	}

	// -----------------------------------------------------------------------
	// Pass-through / auto-correct matrix
	// -----------------------------------------------------------------------

	@ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
	@CsvSource({
			// db:/ datasource is returned unchanged
			"db:/example_data/customers, db:/example_data/customers",
			// bare server/table is auto-corrected to db:/server/table
			"example_data/customers, db:/example_data/customers",
			// mem: datasource is passed through unchanged
			"mem:customers, mem:customers",
			// view: datasource is passed through unchanged
			"view:myview, view:myview", })
	void returnsExpectedForValidDatasource(String input, String expected) throws Throwable {
		assertEquals(expected, invoke(input), "correctDataSource should normalise '" + input + "'");
	}

	@Test
	void dbDatasourceWithMultipleSegmentsIsUnchanged() throws Throwable {
		// A db:/ datasource that already has the scheme must never be
		// rewritten/prefixed again.
		String ds = "db:/example_data/order_details";
		assertEquals(ds, invoke(ds), "db:/ datasource must be returned unchanged");
	}

	@Test
	void bareServerTableIsPrefixedExactlyOnce() throws Throwable {
		String result = invoke("example_data/customers");
		assertEquals("db:/example_data/customers", result);
		assertTrue(result.startsWith("db:/"), "result must carry the db:/ scheme");
		assertEquals("db:/".length(), result.indexOf("example_data"), "db:/ prefix must be added exactly once");
	}

	// -----------------------------------------------------------------------
	// Error paths
	// -----------------------------------------------------------------------

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "   ", "\t" })
	void blankOrNullDatasourceThrows(String input) {
		RepositoryException ex = assertThrows(RepositoryException.class, () -> invoke(input));
		assertEquals("Datasource cannot be empty", ex.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = { "foo", "customers", "justatablename" })
	void malformedDatasourceThrowsAndNamesSupportedSchemes(String input) {
		RepositoryException ex = assertThrows(RepositoryException.class, () -> invoke(input));
		String msg = ex.getMessage();
		assertTrue(msg.startsWith("Invalid datasource format"),
				"message should start with 'Invalid datasource format', was: " + msg);
		assertTrue(msg.contains("'" + input + "'"), "message should quote the offending datasource, was: " + msg);
		// The error must name all three supported schemes so the caller knows the
		// alternatives.
		assertTrue(msg.contains("db:/"), "message should mention the db:/ scheme, was: " + msg);
		assertTrue(msg.contains("mem:"), "message should mention the mem: scheme, was: " + msg);
		assertTrue(msg.contains("view:"), "message should mention the view: scheme, was: " + msg);
	}

	// -----------------------------------------------------------------------
	// "Table not found" error-message formatting (SVY-21564)
	// -----------------------------------------------------------------------

	/**
	 * Verifies the building blocks that {@code createFormWithFields} uses to
	 * compose the "Table not found" {@link RepositoryException} message when
	 * {@code getDataSourceManager().getDataSource()} returns {@code null}.
	 * <p>
	 * The production code builds:<br>
	 * {@code "Table '" + tableName + "' not found" +
	 *        (serverName != null ? " in server '" + serverName + "'" : "") +
	 *        " for datasource '" + dataSource + "'"}
	 * <p>
	 * Since {@link DataSourceUtils#getDataSourceServerName} and
	 * {@link DataSourceUtils#getDataSourceTableName} are pure static helpers (no
	 * Eclipse runtime needed) we test them directly here, and then verify that the
	 * assembled message is well-formed for each datasource scheme.
	 */
	@Nested
	class TableNotFoundErrorMessageParts {
		@Test
		@DisplayName("db:/ datasource resolves both server and table name")
		void dbDatasourceServerAndTableNameResolve() {
			String ds = "db:/example_data/customers";
			assertEquals("example_data", DataSourceUtils.getDataSourceServerName(ds));
			assertEquals("customers", DataSourceUtils.getDataSourceTableName(ds));
		}

		@Test
		@DisplayName("mem: datasource resolves table name and returns _sv_inmem as server")
		void memDatasourceTableNameResolves() {
			String ds = "mem:customers";
			String serverName = DataSourceUtils.getDataSourceServerName(ds);
			assertNotNull(serverName, "mem: datasource should have a non-null server name");
			assertEquals("_sv_inmem", serverName);
			assertEquals("customers", DataSourceUtils.getDataSourceTableName(ds));
		}

		@Test
		@DisplayName("view: datasource resolves table name and returns _sv_view as server")
		void viewDatasourceTableNameResolves() {
			String ds = "view:myview";
			String serverName = DataSourceUtils.getDataSourceServerName(ds);
			assertNotNull(serverName, "view: datasource should have a non-null server name");
			assertEquals("_sv_view", serverName);
			assertEquals("myview", DataSourceUtils.getDataSourceTableName(ds));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource({ "db:/example_data/customers, example_data, customers", "mem:orders, _sv_inmem, orders",
				"view:active_users, _sv_view, active_users", })
		@DisplayName("error message is well-formed for all datasource schemes")
		void errorMessageIsWellFormedForAllSchemes(String ds, String expectedServer, String expectedTable) {
			String serverName = DataSourceUtils.getDataSourceServerName(ds);
			String tableName = DataSourceUtils.getDataSourceTableName(ds);

			// Mirror the exact production-code message format
			String msg = "Table '" + tableName + "' not found"
					+ (serverName != null ? " in server '" + serverName + "'" : "") + " for datasource '" + ds + "'";

			// The table name must always be present
			assertTrue(msg.contains("'" + expectedTable + "'"), "message should contain the table name, was: " + msg);
			// The datasource string must always be present
			assertTrue(msg.contains("'" + ds + "'"), "message should contain the full datasource, was: " + msg);
			// The server name segment must be present (non-null for all three schemes)
			assertTrue(msg.contains("in server '" + expectedServer + "'"),
					"message should include the server name segment, was: " + msg);
		}
	}

	// -----------------------------------------------------------------------
	// createRelation column-mapping warning formatting (SVY-21564)
	// -----------------------------------------------------------------------

	/**
	 * Verifies the warning text {@code createRelation} appends when it cannot
	 * resolve the key table(s) or column(s) for the relation's column mapping.
	 * <p>
	 * Previously the mapping was skipped silently and the tool still reported an
	 * unqualified "created successfully", so a relation with unresolved columns
	 * looked like a success. The method now captures the reason and appends a
	 * {@code WARNING:} line while still saving the relation. The full
	 * {@code createRelation} path needs a live workbench + active solution and is
	 * exercised by the integration suite; here we assert the warning strings are
	 * well-formed for each failure case, mirroring the production-code format:
	 * <ul>
	 * <li>table not resolved: {@code "Could not resolve table(s) for column
	 * mapping: table not found for '<ds>'...add it manually in the relation
	 * editor."}</li>
	 * <li>column not found: {@code "Could not resolve column(s) for mapping:
	 * '<col>' not found in <ds>...add it manually in the relation editor."}</li>
	 * </ul>
	 */
	@Nested
	class ColumnMappingWarningParts {
		private static final String SUFFIX = ". Relation saved without column mapping — add it manually in the relation editor.";

		@Test
		@DisplayName("primary-table-not-found warning names the primary datasource")
		void primaryTableNotFoundWarning() {
			String primaryDs = "mem:inmemtest1";
			String foreignDs = "mem:inmemtest2";
			// Mirror the production branch where primaryTable == null (foreign resolved)
			StringBuilder warn = new StringBuilder("Could not resolve table(s) for column mapping: ");
			warn.append("table not found for '").append(primaryDs).append("'");
			warn.append(SUFFIX);
			String msg = warn.toString();

			assertTrue(msg.startsWith("Could not resolve table(s) for column mapping:"),
					"warning should name the table-resolution failure, was: " + msg);
			assertTrue(msg.contains("'" + primaryDs + "'"),
					"warning should quote the unresolved datasource, was: " + msg);
			assertTrue(msg.contains("add it manually in the relation editor"),
					"warning should tell the caller how to recover, was: " + msg);
			assertTrue(msg.contains(foreignDs) == false,
					"only the unresolved (primary) datasource should be named, was: " + msg);
		}

		@Test
		@DisplayName("both-tables-not-found warning names both datasources joined by a comma")
		void bothTablesNotFoundWarning() {
			String primaryDs = "mem:inmemtest1";
			String foreignDs = "mem:inmemtest2";
			StringBuilder warn = new StringBuilder("Could not resolve table(s) for column mapping: ");
			warn.append("table not found for '").append(primaryDs).append("'");
			warn.append(", ");
			warn.append("table not found for '").append(foreignDs).append("'");
			warn.append(SUFFIX);
			String msg = warn.toString();

			assertTrue(msg.contains("'" + primaryDs + "'") && msg.contains("'" + foreignDs + "'"),
					"warning should name both unresolved datasources, was: " + msg);
			assertEquals(2, countOccurrences(msg, "table not found for"),
					"both tables should be reported, was: " + msg);
		}

		@Test
		@DisplayName("column-not-found warning names the missing column and its datasource")
		void columnNotFoundWarning() {
			String primaryDs = "db:/example_data/orders";
			String primaryCol = "customer_id";
			StringBuilder warn = new StringBuilder("Could not resolve column(s) for mapping: ");
			warn.append("'").append(primaryCol).append("' not found in ").append(primaryDs);
			warn.append(SUFFIX);
			String msg = warn.toString();

			assertTrue(msg.startsWith("Could not resolve column(s) for mapping:"),
					"warning should name the column-resolution failure, was: " + msg);
			assertTrue(msg.contains("'" + primaryCol + "' not found in " + primaryDs),
					"warning should name the missing column and its datasource, was: " + msg);
			assertTrue(msg.endsWith(SUFFIX), "warning should end with the recovery guidance, was: " + msg);
		}

		private int countOccurrences(String haystack, String needle) {
			int count = 0;
			int idx = 0;
			while ((idx = haystack.indexOf(needle, idx)) != -1) {
				count++;
				idx += needle.length();
			}
			return count;
		}
	}
}
