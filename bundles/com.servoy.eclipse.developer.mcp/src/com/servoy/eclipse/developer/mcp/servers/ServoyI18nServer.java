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
package com.servoy.eclipse.developer.mcp.servers;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import com.servoy.j2db.Messages;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.EclipseMessages;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.preferences.DesignerPreferences;
import com.servoy.j2db.i18n.I18NMessagesTable;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.DataSourceUtils;

@McpServer(name = "servoy-i18n")
public class ServoyI18nServer {
	@Tool(name = "i18n_listTables", description = "Lists existing i18n-compatible tables across all configured database servers. "
			+ "A table is compatible if it has columns 'message_key', 'message_value', and 'message_language'. "
			+ "Use this when the active solution has no i18nDataSource configured and you need to find or choose an existing i18n table.", type = "object")
	public String i18nListTables() {
		try {
			String[] serverNames = ApplicationServerRegistry.get().getServerManager().getServerNames(true, true, true,
					false);
			List<String> results = new ArrayList<>();

			for (String serverName : serverNames) {
				IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
						.getServer(serverName, false, false);
				if (server == null)
					continue;

				List<String> tableNames = server.getTableNames(false);
				for (String tableName : tableNames) {
					ITable table = server.getTable(tableName);
					if (table == null)
						continue;

					boolean hasKey = false;
					boolean hasValue = false;
					boolean hasLanguage = false;

					for (Column col : table.getColumns()) {
						String colName = col.getName().toLowerCase();
						if ("message_key".equals(colName))
							hasKey = true;
						else if ("message_value".equals(colName))
							hasValue = true;
						else if ("message_language".equals(colName))
							hasLanguage = true;
					}

					if (hasKey && hasValue && hasLanguage) {
						results.add(serverName + "." + tableName);
					}
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append("I18N-compatible tables (").append(results.size()).append("):\n\n");
			if (results.isEmpty()) {
				sb.append(
						"(No i18n-compatible tables found. Use i18n_setTable with createIfMissing=true to create one.)\n");
			} else {
				for (String entry : results) {
					String[] parts = entry.split("\\.", 2);
					sb.append("  - server: ").append(parts[0]).append(", table: ").append(parts[1]).append("\n");
				}
			}
			return sb.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error listing i18n tables", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "i18n_searchMessages", description = "Search all available i18n messages (Servoy platform defaults + solution-specific keys) by value. Returns matching key=value pairs labeled by source. Use this before creating new i18n keys to check if a suitable key already exists.")
	public String i18nSearchMessages(
			@ToolParam(name = "searchValue", description = "The text/value to search for (case-insensitive substring match)", required = true) String searchValue) {
		if (searchValue == null || searchValue.isBlank())
			return "Error: searchValue parameter is required";

		try {
			String searchLower = searchValue.toLowerCase();
			List<String> platformMatches = new ArrayList<>();
			List<String> solutionMatches = new ArrayList<>();

			ResourceBundle bundle = ResourceBundle.getBundle(Messages.BUNDLE_NAME);
			for (String key : bundle.keySet()) {
				String value = bundle.getString(key);
				if (value.toLowerCase().contains(searchLower)) {
					platformMatches.add(key + "=" + value);
				}
			}

			String solutionNote = null;
			try {
				IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
				ServoyProject activeProject = model.getActiveProject();
				if (activeProject != null && activeProject.getSolution() != null) {
					String i18nDataSource = activeProject.getSolution().getI18nDataSource();
					if (i18nDataSource != null && !i18nDataSource.isBlank()) {
						EclipseMessages messagesManager = (EclipseMessages) model.getMessagesManager();
						java.util.TreeMap<String, com.servoy.j2db.persistence.I18NUtil.MessageEntry> messages = messagesManager
								.getDatasourceMessages(i18nDataSource);
						if (messages != null) {
							for (java.util.Map.Entry<String, com.servoy.j2db.persistence.I18NUtil.MessageEntry> entry : messages
									.entrySet()) {
								String value = entry.getValue().getValue();
								if (value != null && value.toLowerCase().contains(searchLower)) {
									solutionMatches.add(entry.getValue().getKey() + "=" + value);
								}
							}
						}
					} else {
						solutionNote = "No solution i18n configured";
					}
				} else {
					solutionNote = "No active solution";
				}
			} catch (Exception e) {
				solutionNote = "Could not search solution keys: " + e.getMessage();
			}

			if (platformMatches.isEmpty() && solutionMatches.isEmpty()) {
				return "No matching i18n keys found";
			}

			StringBuilder sb = new StringBuilder();
			sb.append("Platform defaults (").append(platformMatches.size()).append(" matches):\n");
			for (String match : platformMatches) {
				sb.append("  ").append(match).append("\n");
			}
			sb.append("\n");
			sb.append("Solution keys (").append(solutionMatches.size()).append(" matches):\n");
			for (String match : solutionMatches) {
				sb.append("  ").append(match).append("\n");
			}
			if (solutionNote != null) {
				sb.append("  (").append(solutionNote).append(")\n");
			}
			return sb.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error searching i18n messages", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "i18n_setTable", description = "Sets the i18n table on the active solution. "
			+ "If createIfMissing is true, creates the i18n table with the standard schema (message_id, message_key, message_language, message_value) before setting it. "
			+ "Also creates the initial empty .properties file(s) in the resources project. "
			+ "Use i18n_listTables first to discover existing compatible tables.", type = "object")
	public String i18nSetTable(
			@ToolParam(name = "serverName", description = "The database server name where the i18n table exists or should be created.", required = true) String serverName,
			@ToolParam(name = "tableName", description = "The i18n table name (e.g. 'messages', 'i18n').", required = true) String tableName,
			@ToolParam(name = "createIfMissing", description = "If 'true', creates the i18n table if it does not already exist. Default: false.", required = false) String createIfMissing) {
		if (serverName == null || serverName.isBlank())
			return "Error: serverName parameter is required";
		if (tableName == null || tableName.isBlank())
			return "Error: tableName parameter is required";

		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			if (activeProject == null)
				return "Error: No active solution. Activate a solution first.";

			IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
					.getServer(serverName, false, false);
			if (server == null)
				return "Error: Database server '" + serverName + "' not found";

			boolean shouldCreate = "true".equalsIgnoreCase(createIfMissing);

			ITable table = server.getTable(tableName);
			if (table == null) {
				if (!shouldCreate) {
					return "Error: Table '" + tableName + "' not found in server '" + serverName
							+ "'. Set createIfMissing=true to create it.";
				}
				int pkSequenceType = new DesignerPreferences().getPrimaryKeySequenceType();
				I18NMessagesTable.createMessagesTable(server, tableName, pkSequenceType);
			}

			Solution solution = activeProject.getEditingSolution();
			if (solution == null)
				return "Error: Cannot get editing solution for active project.";

			solution.setI18nDataSource(DataSourceUtils.createDBTableDataSource(serverName, tableName));
			activeProject.saveEditingSolutionNodes(new IPersist[] { solution }, false);
			EclipseMessages.writeProjectI18NFiles(activeProject, false, false);

			String datasourceUri = "db:/" + serverName + "/" + tableName;
			return "Success: i18n table set to '" + datasourceUri + "' on solution '"
					+ activeProject.getProject().getName() + "'.\n"
					+ "Initial .properties files created in resources/messages/.";
		} catch (Exception e) {
			ServoyLog.logError("Error setting i18n table", e);
			return "Error: " + e.getMessage();
		}
	}
}
