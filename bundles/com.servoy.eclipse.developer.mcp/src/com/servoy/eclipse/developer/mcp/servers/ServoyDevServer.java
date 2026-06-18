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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;

import com.servoy.base.persistence.IBaseColumn;
import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.j2db.persistence.TableChangeHandler;
import com.servoy.eclipse.core.util.EclipseDatabaseUtils;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.developer.mcp.dto.DocumentationItem;
import com.servoy.eclipse.developer.mcp.dto.IdentifierContext;
import com.servoy.eclipse.developer.mcp.services.CodeContextService;
import com.servoy.eclipse.developer.mcp.services.DocumentationValidatorService;
import com.servoy.eclipse.developer.mcp.services.FilePathResolver;
import com.servoy.eclipse.developer.mcp.services.JsCodeValidatorService;
import com.servoy.eclipse.developer.mcp.services.ScriptContextService;
import com.servoy.eclipse.developer.mcp.services.ServoyDocumentationService;
import com.servoy.eclipse.developer.mcp.services.ServoyScriptResolver;
import com.servoy.eclipse.developer.mcp.services.ServoySolutionService;
import com.servoy.eclipse.developer.mcp.services.ServoyArtifactCreationService;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.DataModelManager;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.preferences.PrimaryKeyType;
import com.servoy.eclipse.ui.views.solutionexplorer.actions.CreateMediaWebAppManifest;
import com.servoy.j2db.persistence.Column;
import com.servoy.j2db.persistence.ColumnInfo;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRepository;
import com.servoy.j2db.persistence.IServerInternal;
import com.servoy.j2db.persistence.ITable;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptNameValidator;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.SolutionMetaData;
import com.servoy.j2db.persistence.ValidatorSearchContext;
import com.servoy.j2db.query.ColumnType;
import com.servoy.j2db.server.ngclient.less.resources.ThemeResourceLoader;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.DatabaseUtils;
import com.servoy.j2db.util.UUID;
import com.servoy.j2db.util.Utils;
import com.servoy.j2db.util.xmlxport.ColumnInfoDef;
import com.servoy.j2db.util.xmlxport.TableDef;

/**
 * MCP server for Servoy Developer tools (stub for future development).
 */
@Creatable
@McpServer(name = "servoy-dev")
public class ServoyDevServer {
	private static final String[] NG_PACKAGES = { "12grid", "bootstrapcomponents", "fontawesome", "servoyextra" };

	private static final long ACTIVATE_SETTLE_MS = 5000;

	private final ScriptContextService scriptContextService = new ScriptContextService();
	private final ServoyScriptResolver scriptResolver = new ServoyScriptResolver();
	private final FilePathResolver filePathResolver = new FilePathResolver();
	private final ServoyDocumentationService docService = new ServoyDocumentationService();
	private final CodeContextService codeContextService = new CodeContextService();
	private final DocumentationValidatorService docValidator = new DocumentationValidatorService();
	private final JsCodeValidatorService jsCodeValidator = new JsCodeValidatorService();
	private final ServoySolutionService solutionService = new ServoySolutionService();
	private final ServoyArtifactCreationService artifactService = new ServoyArtifactCreationService();

	public ServoyDevServer() {
	}

	@Tool(name = "ping", description = "Returns a simple pong response to verify the servoy-dev endpoint is alive.", type = "object")
	public String ping() {
		return "pong";
	}

	// -------------------------------------------------------------------------
	// Step 1: Create Eclipse projects + non-Servoy files
	// -------------------------------------------------------------------------

	private void createEclipseProjects(String solutionName) throws Exception {
		// Resolve resources project - same logic as NewSolutionWizard:
		// 1. Use active resources project if available
		// 2. Find existing project named "resources"
		// 3. Create a new one with name "resources" (or "resources1", "resources2",
		// ...)
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		IProject resourcesProject = resolveOrCreateResourcesProject(model);

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {

			if (!resourcesProject.isOpen())
				resourcesProject.open(monitor);

			// Solution project - natures + builders only, no Servoy structural files
			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(solutionName);
			if (!sol.exists()) {
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(solutionName);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
						"org.eclipse.dltk.javascript.core.nature" });
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { resourcesProject });
				sol.create(d, monitor);
			}
			if (!sol.isOpen())
				sol.open(monitor);

			// .buildpath - not a Servoy structural file, safe to write directly
			writeTextFile(sol, ".buildpath",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<buildpath>\n"
							+ "\t<buildpathentry excluding=\".stp/|medias/|**/*.spec.cy.js\" kind=\"src\" path=\"\"/>\n"
							+ "</buildpath>\n",
					monitor);

			// ng_web_packages/
			IFolder ngFolder = sol.getFolder("ng_web_packages");
			if (!ngFolder.exists())
				ngFolder.create(true, true, monitor);
			copyNgPackages(ngFolder, monitor);

		}, new NullProgressMonitor());

		// Pump SWT events for 1s to let workspace jobs settle
		pumpEvents(1000);
	}

	private IProject resolveOrCreateResourcesProject(IDeveloperServoyModel model) throws Exception {
		// 1. Use active resources project
		com.servoy.eclipse.model.nature.ServoyResourcesProject activeRes = model.getActiveResourcesProject();
		if (activeRes != null)
			return activeRes.getProject();

		// 2. Find any existing resources project
		com.servoy.eclipse.model.nature.ServoyResourcesProject[] allRes = model.getResourceProjects();
		if (allRes != null && allRes.length > 0) {
			// prefer one named "resources"
			for (com.servoy.eclipse.model.nature.ServoyResourcesProject r : allRes) {
				if ("resources".equals(r.getProject().getName()))
					return r.getProject();
			}
			return allRes[0].getProject();
		}

		// 3. Create a new resources project
		String resName = "resources";
		int counter = 1;
		while (ResourcesPlugin.getWorkspace().getRoot().getProject(resName).exists()) {
			resName = "resources" + counter++;
		}
		final String finalResName = resName;
		IProject[] created = new IProject[1];
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(finalResName);
			IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(finalResName);
			res.create(d, monitor);
			res.open(monitor);
			IProjectDescription d2 = res.getDescription();
			d2.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
			res.setDescription(d2, monitor);
			created[0] = res;
		}, new NullProgressMonitor());
		return created[0];
	}

	// -------------------------------------------------------------------------
	// Step 2: Activate solution (shared implementation)
	// -------------------------------------------------------------------------

	private String doActivateSolution(String solutionName, boolean refreshAndWait) throws InterruptedException {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		if (refreshAndWait) {
			model.refreshServoyProjects();
			pumpEvents(1000);
		}

		ServoyProject project = model.getServoyProject(solutionName);
		if (project == null) {
			return "Error: Solution '" + solutionName + "' not found in the workspace.";
		}

		ServoyProject activeProject = model.getActiveProject();
		if (activeProject != null && activeProject.getProject().getName().equals(solutionName)) {
			return "Solution '" + solutionName + "' is already the active solution.";
		}

		String previousName = activeProject != null ? activeProject.getProject().getName() : "(none)";

		model.setActiveProject(project, true);

		if (refreshAndWait) {
			long activateEnd = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
			Display display = Display.getDefault();
			if (display != null && display.getThread() == Thread.currentThread()) {
				while (System.currentTimeMillis() < activateEnd && model.getActiveProject() == null)
					display.readAndDispatch();
			} else {
				while (System.currentTimeMillis() < activateEnd && model.getActiveProject() == null)
					Thread.sleep(200);
			}
		}

		return "Solution '" + solutionName + "' activated successfully. Previous active solution: " + previousName
				+ ".";
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private void pumpEvents(long ms) throws InterruptedException {
		Display display = Display.getDefault();
		long end = System.currentTimeMillis() + ms;
		if (display != null && display.getThread() == Thread.currentThread()) {
			while (System.currentTimeMillis() < end)
				display.readAndDispatch();
		} else {
			Thread.sleep(ms);
		}
	}

	private void copyNgPackages(IFolder ngFolder, org.eclipse.core.runtime.IProgressMonitor monitor)
			throws CoreException {
		// Read directly from wizardpackages/ on disk - the source of truth populated at
		// Servoy Developer startup.
		File wizardPackagesDir = new File(com.servoy.eclipse.ui.Activator.getDefault().getStateLocation().toFile(),
				"wizardpackages");

		if (!wizardPackagesDir.exists()) {
			ServoyLog.logWarning("createEclipseProjects: wizardpackages folder not found at " + wizardPackagesDir, null);
			return;
		}

		for (String name : NG_PACKAGES) {
			File packageFile = null;
			for (File f : wizardPackagesDir.listFiles()) {
				if (f.isFile() && f.getName().startsWith(name + "_")) {
					packageFile = f;
					break;
				}
			}

			if (packageFile == null) {
				ServoyLog.logWarning("createEclipseProjects: package not found in wizardpackages: " + name, null);
				continue;
			}

			IFile destFile = ngFolder.getFile(name + ".zip");
			if (!destFile.exists()) {
				try (InputStream is = new FileInputStream(packageFile)) {
					destFile.create(is, true, monitor);
				} catch (IOException e) {
					ServoyLog.logError("createEclipseProjects: failed to copy " + name + ".zip", e);
				}
			}
		}
	}

	private void writeTextFile(IProject project, String relativePath, String content,
			org.eclipse.core.runtime.IProgressMonitor monitor) throws CoreException {
		IFile file = project.getFile(relativePath);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (file.exists()) {
			file.setContents(new ByteArrayInputStream(bytes), true, false, monitor);
		} else {
			file.create(new ByteArrayInputStream(bytes), true, monitor);
		}
	}

	// -------------------------------------------------------------------------
	// syncDbiWithDatabase tool
	// -------------------------------------------------------------------------

	@Tool(name = "syncDbiWithDatabase", description = "Synchronizes the database schema with .dbi file definitions for a given server. Creates tables that exist in .dbi files but not in the DB, reports tables that exist in the DB but have no .dbi file, and for existing tables syncs columns (add/remove/update) to match the .dbi definitions. Call after git pulls or .dbi file changes to keep the database in sync.", type = "object")
	public String syncDbiWithDatabase(
			@ToolParam(name = "serverName", description = "Name of the database server to synchronize.", required = true) String serverName,
			@ToolParam(name = "tableName", description = "Optional table name to sync only a specific table. If not specified, syncs all tables.", required = false) String tableName) {
		if (serverName == null || serverName.isBlank()) {
			return "{\"errors\":[\"serverName is required\"]}";
		}
		String filterTableName = (tableName != null && !tableName.isBlank()) ? tableName : null;

		IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
				.getServer(serverName, false, false);
		if (server == null) {
			return "{\"errors\":[\"Server not found: " + escapeJson(serverName) + "\"]}";
		}

		DataModelManager dmm = ServoyModelManager.getServoyModelManager().getServoyModel().getDataModelManager();
		if (dmm == null) {
			return "{\"errors\":[\"DataModelManager not available\"]}";
		}

		List<String> tablesCreated = new ArrayList<>();
		List<String> orphanTables = new ArrayList<>();
		List<Map<String, Object>> tablesModified = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		try {
			dmm.setWritesEnabled(false);

			syncMissingTables(server, dmm, filterTableName, tablesCreated, errors);
			findOrphanTables(server, dmm, filterTableName, orphanTables);
			syncExistingTableColumns(server, dmm, filterTableName, tablesModified, errors);
		} catch (Exception e) {
			errors.add("Unexpected error: " + e.getMessage());
			ServoyLog.logError("syncDbiWithDatabase failed", e);
		} finally {
			dmm.setWritesEnabled(true);
		}

		StringBuilder sb = new StringBuilder();
		sb.append("{\"tablesCreated\":").append(toJsonArray(tablesCreated));
		sb.append(",\"orphanTables\":").append(toJsonArray(orphanTables));
		sb.append(",\"tablesModified\":").append(toJsonModifiedArray(tablesModified));
		sb.append(",\"errors\":").append(toJsonArray(errors));
		sb.append("}");
		return sb.toString();
	}

	private void syncMissingTables(IServerInternal server, DataModelManager dmm, String filterTableName,
			List<String> tablesCreated, List<String> errors) {
		try {
			IFolder serverInfoFolder = dmm.getServerInformationFolder(server.getName());
			if (serverInfoFolder == null || !serverInfoFolder.exists())
				return;

			Collection<String> existingTables = server.getTableAndViewNames(true);
			List<String> newTableNames = new ArrayList<>();

			serverInfoFolder.accept((IResourceVisitor) resource -> {
				if (resource.getType() != IResource.FILE)
					return true;
				String fileName = resource.getName();
				if (!fileName.endsWith(DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT))
					return true;

				String tName = fileName.substring(0,
						fileName.length() - DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT.length());

				if (filterTableName != null && !filterTableName.equals(tName))
					return true;
				if (tName.startsWith(DataModelManager.TEMP_UPPERCASE_PREFIX))
					return true;
				if (!tName.equals(tName.toLowerCase()))
					return true;
				if (existingTables.contains(tName))
					return true;

				try {
					IFile dbiFile = dmm.getDBIFile(server.getName(), tName);
					if (dbiFile == null || !dbiFile.exists())
						return true;

					String dbiContent;
					try (InputStream is = dbiFile.getContents()) {
						dbiContent = Utils.getTXTFileContent(is, Charset.forName("UTF8"));
					}

					if (dbiContent != null && !dbiContent.isBlank()) {
						EclipseDatabaseUtils.createNewTableFromColumnInfo(server, tName, dbiContent,
								EclipseDatabaseUtils.UPDATE_NOW, false);
						newTableNames.add(tName);
						tablesCreated.add(tName);
					}
				} catch (Exception e) {
					errors.add("Failed to create table '" + tName + "': " + e.getMessage());
					ServoyLog.logWarning("syncDbiWithDatabase: failed to create table " + tName, e);
				}
				return true;
			}, IResource.DEPTH_ONE, IResource.NONE);

			if (!newTableNames.isEmpty()) {
				TableChangeHandler.getInstance().fireTablesAdded(server, newTableNames.toArray(new String[0]));
			}
		} catch (Exception e) {
			errors.add("Phase 1 error: " + e.getMessage());
			ServoyLog.logError("syncDbiWithDatabase phase 1 failed", e);
		}
	}

	private void findOrphanTables(IServerInternal server, DataModelManager dmm, String filterTableName,
			List<String> orphanTables) {
		try {
			IFolder serverInfoFolder = dmm.getServerInformationFolder(server.getName());
			if (serverInfoFolder == null || !serverInfoFolder.exists())
				return;

			Collection<String> existingTables = server.getTableAndViewNames(true);
			for (String tName : existingTables) {
				if (filterTableName != null && !filterTableName.equals(tName))
					continue;
				IFile dbiFile = serverInfoFolder.getFile(tName + DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT);
				if (!dbiFile.exists()) {
					orphanTables.add(tName);
				}
			}
		} catch (Exception e) {
			ServoyLog.logWarning("syncDbiWithDatabase: findOrphanTables failed", e);
		}
	}

	private void syncExistingTableColumns(IServerInternal server, DataModelManager dmm, String filterTableName,
			List<Map<String, Object>> tablesModified, List<String> errors) {
		try {
			Collection<String> existingTables = server.getTableAndViewNames(true);
			IFolder serverInfoFolder = dmm.getServerInformationFolder(server.getName());
			if (serverInfoFolder == null || !serverInfoFolder.exists())
				return;

			for (String tName : existingTables) {
				if (filterTableName != null && !filterTableName.equals(tName))
					continue;

				IFile dbiFile = serverInfoFolder.getFile(tName + DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT);
				if (!dbiFile.exists())
					continue;

				try {
					String dbiContent;
					try (InputStream is = dbiFile.getContents()) {
						dbiContent = Utils.getTXTFileContent(is, Charset.forName("UTF8"));
					}
					if (dbiContent == null || dbiContent.isBlank())
						continue;

					TableDef tableDef = DatabaseUtils.deserializeTableInfo(dbiContent);
					if (tableDef == null)
						continue;

					ITable table = server.getTable(tName);
					if (table == null)
						continue;

					Map<String, Object> tableInfo = syncTableColumns(server, dmm, table, tableDef, errors);
					if (tableInfo != null) {
						tablesModified.add(tableInfo);
					}
				} catch (Exception e) {
					errors.add("Failed to sync columns for '" + tName + "': " + e.getMessage());
					ServoyLog.logWarning("syncDbiWithDatabase: failed to sync " + tName, e);
				}
			}
		} catch (Exception e) {
			errors.add("Phase 3 error: " + e.getMessage());
			ServoyLog.logError("syncDbiWithDatabase phase 3 failed", e);
		}
	}

	@SuppressWarnings("restriction")
	private Map<String, Object> syncTableColumns(IServerInternal server, DataModelManager dmm, ITable table,
			TableDef tableDef, List<String> errors) {
		List<String> columnsAdded = new ArrayList<>();
		List<String> columnsRemoved = new ArrayList<>();
		List<String> columnsUpdated = new ArrayList<>();
		IValidateName validator = createLenientValidator();

		try {
			Map<String, Column> existingColumns = new HashMap<>();
			for (Column col : table.getColumns()) {
				existingColumns.put(col.getName(), col);
			}

			Map<String, ColumnInfoDef> dbiColumns = new HashMap<>();
			if (tableDef.columnInfoDefSet != null) {
				for (ColumnInfoDef cid : tableDef.columnInfoDefSet) {
					dbiColumns.put(cid.name, cid);
				}
			}

			for (Map.Entry<String, ColumnInfoDef> entry : dbiColumns.entrySet()) {
				String colName = entry.getKey();
				ColumnInfoDef cid = entry.getValue();
				Column existingCol = existingColumns.get(colName);

				if (existingCol == null) {
					Column newCol = table.createNewColumn(validator, cid.name, cid.columnType);
					if (newCol != null) {
						if ((cid.flags & IBaseColumn.PK_COLUMN) != 0)
							newCol.setDatabasePK(true);
						newCol.setFlags(cid.flags);
						newCol.setAllowNull(cid.allowNull);
						int seqType = cid.autoEnterSubType;
						if (seqType > 0 && !server.supportsSequenceType(seqType, null)) {
							seqType = ColumnInfo.SERVOY_SEQUENCE;
						}
						newCol.setSequenceType(seqType);
						columnsAdded.add(colName);
					}
				} else if (!Column.isColumnInfoCompatible(existingCol.getColumnType(), cid.columnType, true)) {
					table.removeColumn(existingCol);
					Column newCol = table.createNewColumn(validator, cid.name, cid.columnType);
					if (newCol != null) {
						if ((cid.flags & IBaseColumn.PK_COLUMN) != 0)
							newCol.setDatabasePK(true);
						newCol.setFlags(cid.flags);
						newCol.setAllowNull(cid.allowNull);
						int seqType = cid.autoEnterSubType;
						if (seqType > 0 && !server.supportsSequenceType(seqType, null)) {
							seqType = ColumnInfo.SERVOY_SEQUENCE;
						}
						newCol.setSequenceType(seqType);
						columnsUpdated.add(colName);
					}
				}
			}

			for (Map.Entry<String, Column> entry : existingColumns.entrySet()) {
				if (!dbiColumns.containsKey(entry.getKey())) {
					table.removeColumn(entry.getValue());
					columnsRemoved.add(entry.getKey());
				}
			}

			if (!columnsAdded.isEmpty() || !columnsRemoved.isEmpty() || !columnsUpdated.isEmpty()) {
				server.syncTableObjWithDB(table, false, true);
				dmm.loadAllColumnInfo(table);

				Map<String, Object> result = new HashMap<>();
				result.put("name", table.getName());
				result.put("columnsAdded", columnsAdded);
				result.put("columnsRemoved", columnsRemoved);
				result.put("columnsUpdated", columnsUpdated);
				return result;
			}
		} catch (Exception e) {
			errors.add("Error syncing columns for '" + table.getName() + "': " + e.getMessage());
			ServoyLog.logWarning("syncDbiWithDatabase: syncTableColumns failed for " + table.getName(), e);
		}
		return null;
	}

	private IValidateName createLenientValidator() {
		return new IValidateName() {
			@Override
			public void checkName(String nameToCheck, UUID skip_element_uuid, ValidatorSearchContext searchContext,
					boolean sqlRelated) throws RepositoryException {
				try {
					new ScriptNameValidator().checkName(nameToCheck, skip_element_uuid, searchContext, sqlRelated);
				} catch (RepositoryException e) {
					ServoyLog.logWarning(
							"syncDbiWithDatabase: name validation warning for '" + nameToCheck + "': " + e.getMessage(),
							null);
				}
			}
		};
	}

	private String toJsonArray(List<String> list) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++) {
			if (i > 0)
				sb.append(",");
			sb.append("\"").append(escapeJson(list.get(i))).append("\"");
		}
		sb.append("]");
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private String toJsonModifiedArray(List<Map<String, Object>> list) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++) {
			if (i > 0)
				sb.append(",");
			Map<String, Object> entry = list.get(i);
			sb.append("{\"name\":\"").append(escapeJson((String) entry.get("name"))).append("\"");
			sb.append(",\"columnsAdded\":").append(toJsonArray((List<String>) entry.get("columnsAdded")));
			sb.append(",\"columnsRemoved\":").append(toJsonArray((List<String>) entry.get("columnsRemoved")));
			sb.append(",\"columnsUpdated\":").append(toJsonArray((List<String>) entry.get("columnsUpdated")));
			sb.append("}");
		}
		sb.append("]");
		return sb.toString();
	}

	private String escapeJson(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
				"\\t");
	}

	// -------------------------------------------------------------------------
	// getTarget / setTarget MCP tools
	// -------------------------------------------------------------------------

	@Tool(name = "getTarget", description = "Returns the currently active Servoy solution (target) in the Developer IDE. "
			+ "Also lists all available solutions in the workspace.", type = "object")
	public String getTarget() {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = model.getActiveProject();

		StringBuilder sb = new StringBuilder();
		if (activeProject != null) {
			sb.append("Active solution: ").append(activeProject.getProject().getName()).append("\n");
		} else {
			sb.append("No active solution.\n");
		}

		ServoyProject[] allProjects = model.getServoyProjects();
		if (allProjects != null && allProjects.length > 0) {
			sb.append("\nAvailable solutions:\n");
			for (ServoyProject p : allProjects)
				sb.append("- ").append(p.getProject().getName()).append("\n");
		}

		return sb.toString();
	}

	@Tool(name = "setTarget", description = "Sets the active Servoy solution (target) in the Developer IDE. "
			+ "Equivalent to activateSolution â loads the solution and its modules, and triggers a workspace build.", type = "object")
	public String setTarget(
			@ToolParam(name = "solutionName", description = "The name of the solution to activate", required = true) String solutionName) {
		return activateSolution(solutionName);
	}

	// -------------------------------------------------------------------------
	// activateSolution MCP tool
	// -------------------------------------------------------------------------

	@Tool(name = "activateSolution", description = "Activates a Servoy solution as the active solution in the Developer IDE. "
			+ "This loads the solution and its modules, and triggers a workspace build.", type = "object")
	public String activateSolution(
			@ToolParam(name = "solutionName", description = "The name of the solution to activate", required = true) String solutionName) {
		try {
			return doActivateSolution(solutionName, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "Error: activation interrupted.";
		}
	}

	// -------------------------------------------------------------------------
	// resolveIdentifierType MCP tool
	// -------------------------------------------------------------------------

	@Tool(name = "resolveIdentifierType", description = "Resolves the type of an identifier in a Servoy JavaScript file. "
			+ "Uses DLTK type inference to determine the Servoy type: foundset, JSForm, application, databaseManager, "
			+ "plugins.*, RuntimeWebComponent<name>, WebService<name>, or user-defined function. "
			+ "Returns type name, source (local variable / Servoy API / method declaration), parameters for functions, "
			+ "and JSDoc @type annotation if available. "
			+ "Accepts a form name, scope name, or project-relative path.", type = "object")
	public String resolveIdentifierType(
			@ToolParam(name = "identifier", description = "Identifier name to resolve (e.g. 'foundset', 'databaseManager', 'myVar')", required = true) String identifier,
			@ToolParam(name = "name", description = "Form name, scope name, or project-relative path (e.g. 'customers', 'utils', 'forms/customers.js')", required = true) String name,
			@ToolParam(name = "moduleName", description = "Module/project name to search in. If omitted, searches in the active solution.", required = false) String moduleName) {
		IFile file = scriptResolver.resolveScript(name, moduleName);
		if (file == null)
			return scriptResolver.buildNotFoundMessage(name, moduleName);

		return scriptContextService.resolveIdentifierType(identifier, file);
	}

	// -------------------------------------------------------------------------
	// renamePersist MCP tool
	// -------------------------------------------------------------------------

	private final com.servoy.eclipse.developer.mcp.services.PersistRenameService persistRenameService = new com.servoy.eclipse.developer.mcp.services.PersistRenameService();

	@Tool(name = "renamePersist", description = "Renames a Servoy persist (form, relation, valuelist, scope, media, menu, or solution). "
			+ "Validates the new name, checks for duplicates, and updates all references. "
			+ "For solutions, also moves the Eclipse project and updates module references in other solutions.", type = "object")
	public String renamePersist(
			@ToolParam(name = "persistType", description = "Type of persist to rename: 'form', 'relation', 'valuelist', 'scope', 'media', 'menu', 'solution'.", required = true) String persistType,
			@ToolParam(name = "oldName", description = "Current name of the persist to rename.", required = true) String oldName,
			@ToolParam(name = "newName", description = "Desired new name for the persist.", required = true) String newName,
			@ToolParam(name = "solutionName", description = "Solution to search in. If omitted, uses the active solution. Not used for 'solution' type.", required = false) String solutionName) {
		return persistRenameService.renamePersist(persistType, oldName, newName, solutionName);
	}

	// -------------------------------------------------------------------------
	// SVY-21138: duplicatePersist MCP tool
	// -------------------------------------------------------------------------

	private final com.servoy.eclipse.developer.mcp.services.PersistDuplicateService persistDuplicateService = new com.servoy.eclipse.developer.mcp.services.PersistDuplicateService();

	@Tool(name = "duplicatePersist", description = "Duplicates a Servoy persist (form, relation, valuelist, or media) "
			+ "creating a copy with a new name. Optionally places the copy in a different solution/module. "
			+ "For forms, also copies associated .less and .sec files and relinks event handlers.", type = "object")
	public String duplicatePersist(
			@ToolParam(name = "persistType", description = "Type of persist to duplicate: 'form', 'relation', 'valuelist', 'media'.", required = true) String persistType,
			@ToolParam(name = "name", description = "Name of the existing persist to duplicate.", required = true) String name,
			@ToolParam(name = "newName", description = "Name for the duplicated persist. If omitted, defaults to '<name>_copy' (or '<name>_copy2', etc. if that exists).", required = false) String newName,
			@ToolParam(name = "solutionName", description = "Solution containing the source persist. If omitted, uses the active solution.", required = false) String solutionName,
			@ToolParam(name = "destinationSolution", description = "Target solution for the duplicate. If omitted, uses the same solution as the source.", required = false) String destinationSolution) {
		return persistDuplicateService.duplicatePersist(persistType, name, newName, solutionName, destinationSolution);
	}

	// -------------------------------------------------------------------------
	// SVY-21083: createSolution MCP tool - creates a new Servoy solution like the
	// wizard
	// -------------------------------------------------------------------------

	@Tool(name = "createSolution", description = "Creates a new Servoy solution or module in the workspace, similar to the New Solution wizard. "
			+ "Creates the Eclipse project with Servoy natures, a resources project reference, "
			+ "default theme (.less), web app manifest, and optionally activates the solution. "
			+ "When creating a module, use addToSolution to attach it to a parent solution. "
			+ "Unlike createTestSolution, this creates a clean empty solution without test forms or scopes.", type = "object")
	public String createSolution(
			@ToolParam(name = "solutionName", description = "Name of the solution to create (e.g. 'my_app', 'customer_portal')", required = true) String solutionName,
			@ToolParam(name = "solutionType", description = "Solution type: 'ng_client' (default), 'ng_module', 'service', 'pre_import_hook', 'post_import_hook', 'module'. Maps to SolutionMetaData constants.", required = false) String solutionType,
			@ToolParam(name = "activate", description = "Whether to activate the solution after creation. Default: true.", required = false) String activate,
			@ToolParam(name = "addDefaultTheme", description = "Whether to add the default .less theme file. Default: true.", required = false) String addDefaultTheme,
			@ToolParam(name = "addToSolution", description = "Parent solution name to add this module to. Only applicable when solutionType is 'module' or 'ng_module'. The module will be added to the parent solution's modules list.", required = false) String addToSolution) {
		if (solutionName == null || solutionName.isBlank()) {
			return "Error: solutionName is required.";
		}

		boolean doActivate = Optional.ofNullable(activate).map(Boolean::parseBoolean).orElse(true);
		boolean doAddTheme = Optional.ofNullable(addDefaultTheme).map(Boolean::parseBoolean).orElse(true);
		int type = parseSolutionType(solutionType);

		try {
			IProject existing = ResourcesPlugin.getWorkspace().getRoot().getProject(solutionName);
			if (existing.exists()) {
				if (doActivate) {
					doActivateSolution(solutionName, true);
					return "Solution '" + solutionName + "' already exists. Activated.";
				}
				return "Solution '" + solutionName + "' already exists.";
			}

			createEclipseProjects(solutionName);
			createSolutionArtifacts(solutionName, type, doAddTheme);

			StringBuilder result = new StringBuilder();

			String parentSolution = addToSolution;
			boolean isModule = (type == SolutionMetaData.MODULE || type == SolutionMetaData.NG_MODULE);
			if (isModule && (parentSolution == null || parentSolution.isBlank())) {
				ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel()
						.getActiveProject();
				if (activeProject != null) {
					parentSolution = activeProject.getProject().getName();
				}
			}

			if (parentSolution != null && !parentSolution.isBlank()) {
				String addModuleResult = addModuleToSolution(solutionName, parentSolution);
				result.append(addModuleResult).append("\n");
			}

			if (doActivate) {
				doActivateSolution(parentSolution != null && !parentSolution.isBlank() ? parentSolution : solutionName,
						true);
				result.insert(0, "Created and activated solution '" + solutionName + "' (type: "
						+ getSolutionTypeName(type) + "). ");
			} else {
				result.insert(0, "Created solution '" + solutionName + "' (type: " + getSolutionTypeName(type)
						+ ", not activated). ");
			}
			return result.toString().trim();
		} catch (Exception e) {
			ServoyLog.logError("createSolution failed", e);
			return "Error creating solution: " + e.getMessage();
		}
	}

	private String addModuleToSolution(String moduleName, String parentSolutionName) {
		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			model.refreshServoyProjects();
			ServoyProject parentProject = model.getServoyProject(parentSolutionName);
			if (parentProject == null) {
				return "Warning: Parent solution '" + parentSolutionName + "' not found. Module created but not added.";
			}

			Solution parentSolution = parentProject.getEditingSolution();
			if (parentSolution == null) {
				return "Warning: Cannot get editing solution for '" + parentSolutionName
						+ "'. Module created but not added.";
			}

			String existingModules = parentSolution.getModulesNames();
			if (existingModules != null && !existingModules.isBlank()) {
				for (String existing : existingModules.split(",")) {
					if (existing.trim().equals(moduleName)) {
						return "Module '" + moduleName + "' already in '" + parentSolutionName + "' modules list.";
					}
				}
				parentSolution.setModulesNames(existingModules + "," + moduleName);
			} else {
				parentSolution.setModulesNames(moduleName);
			}

			com.servoy.eclipse.model.repository.EclipseRepository repository = (com.servoy.eclipse.model.repository.EclipseRepository) ApplicationServerRegistry
					.get().getDeveloperRepository();
			parentProject.saveEditingSolutionNodes(new IPersist[] { parentSolution }, true);
			repository.updateRootObject(parentSolution);

			return "Added module '" + moduleName + "' to solution '" + parentSolutionName + "'.";
		} catch (Exception e) {
			ServoyLog.logError("addModuleToSolution failed", e);
			return "Warning: Failed to add module to solution: " + e.getMessage();
		}
	}

	private void createSolutionArtifacts(String solutionName, int solutionType, boolean addDefaultTheme)
			throws RepositoryException {
		com.servoy.eclipse.model.repository.EclipseRepository repository = (com.servoy.eclipse.model.repository.EclipseRepository) com.servoy.j2db.server.shared.ApplicationServerRegistry
				.get().getDeveloperRepository();

		Solution solution = (Solution) repository.createNewRootObject(solutionName, IRepository.SOLUTIONS);
		solution.setSolutionType(solutionType);
		solution.setVersion("1.0");
		repository.updateRootObject(solution);

		if (addDefaultTheme) {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			model.refreshServoyProjects();
			ServoyProject servoyProject = model.getServoyProject(solutionName);
			if (servoyProject != null) {
				Solution editingSolution = servoyProject.getEditingSolution();
				if (editingSolution != null) {
					ScriptNameValidator scriptValidator = new ScriptNameValidator();

					Media solutionLess = editingSolution.createNewMedia(scriptValidator, solutionName + ".less");
					solutionLess.setMimeType("text/css");
					solutionLess.setPermMediaData(ThemeResourceLoader.getDefaultSolutionLess());

					Media solutionPropsLess = editingSolution.createNewMedia(scriptValidator,
							ThemeResourceLoader.SOLUTION_PROPERTIES_LESS);
					solutionPropsLess.setMimeType("text/css");
					solutionPropsLess.setPermMediaData(ThemeResourceLoader.getCustomProperties());

					Media variantsJson = editingSolution.createNewMedia(scriptValidator,
							ThemeResourceLoader.VARIANTS_JSON);
					variantsJson.setMimeType("application/json");
					variantsJson.setPermMediaData(ThemeResourceLoader.getVariantsFile());

					try {
						Media manifestJson = editingSolution.createNewMedia(scriptValidator,
								CreateMediaWebAppManifest.FILE_NAME);
						manifestJson.setMimeType("application/json");
						manifestJson.setPermMediaData(CreateMediaWebAppManifest.createManifest(solutionName));

						Media webappIcon = editingSolution.createNewMedia(scriptValidator,
								CreateMediaWebAppManifest.ICON_NAME);
						webappIcon.setMimeType("image/png");
						webappIcon.setPermMediaData(CreateMediaWebAppManifest.getIcon());
					} catch (IOException e) {
						ServoyLog.logWarning("createSolution: could not create manifest/icon", e);
					}

					editingSolution.setStyleSheetID(solutionLess.getUUID().toString());
					servoyProject.saveEditingSolutionNodes(new IPersist[] { editingSolution }, true);
					repository.updateRootObject(editingSolution);
				}
			}
		}
	}

	private int parseSolutionType(String solutionType) {
		if (solutionType == null || solutionType.isBlank())
			return SolutionMetaData.NG_CLIENT_ONLY;
		switch (solutionType.toLowerCase().trim()) {
		case "ng_client":
		case "ng_client_only":
			return SolutionMetaData.NG_CLIENT_ONLY;
		case "ng_module":
			return SolutionMetaData.NG_MODULE;
		case "service":
			return SolutionMetaData.SERVICE;
		case "pre_import_hook":
		case "pre-import hook module":
			return SolutionMetaData.PRE_IMPORT_HOOK;
		case "post_import_hook":
		case "post-import hook module":
			return SolutionMetaData.POST_IMPORT_HOOK;
		case "module":
			return SolutionMetaData.MODULE;
		default:
			return SolutionMetaData.NG_CLIENT_ONLY;
		}
	}

	private String getSolutionTypeName(int type) {
		switch (type) {
		case SolutionMetaData.MODULE:
			return "module";
		case SolutionMetaData.NG_MODULE:
			return "ng_module";
		case SolutionMetaData.NG_CLIENT_ONLY:
			return "ng_client";
		case SolutionMetaData.SERVICE:
			return "service";
		case SolutionMetaData.PRE_IMPORT_HOOK:
			return "pre_import_hook";
		case SolutionMetaData.POST_IMPORT_HOOK:
			return "post_import_hook";
		default:
			return "ng_client";
		}
	}

	// -------------------------------------------------------------------------
	// Documentation MCP tools (Faza 1a/1b/1c)
	// -------------------------------------------------------------------------

	private static final int MEMBERS_THRESHOLD = 50;

	@Tool(name = "getDocumentationForTypeMember", description = "Returns full documentation for one specific method or property of a Servoy API type - description, all parameters, return type, and overloads. "
			+ "Works without any file or editor context.", type = "object")
	public String getDocumentationForTypeMember(
			@ToolParam(name = "typeName", description = "Servoy API type name (e.g. 'application', 'databaseManager', 'JSFoundSet')", required = true) String typeName,
			@ToolParam(name = "memberName", description = "Member name to look up - case-insensitive (e.g. 'getFoundSet', 'loadAllRecords', 'showInfoDialog')", required = true) String memberName) {
		if (typeName == null || typeName.trim().isEmpty())
			return "Error: typeName parameter is required";
		if (memberName == null || memberName.trim().isEmpty())
			return "Error: memberName parameter is required";

		try {
			com.servoy.eclipse.debug.script.TypeCreator typeCreator = com.servoy.eclipse.debug.script.TypeProviderFactory
					.getTypeProvider().getTypeCreator();
			if (typeCreator == null)
				return "Error: TypeCreator not available";

			org.eclipse.dltk.javascript.typeinfo.model.Type type = typeCreator.findType(null, typeName);
			if (type == null) {
				String scriptingName = docService.mapClassNameToScriptingName(typeName);
				if (scriptingName != null && !scriptingName.equals(typeName))
					type = typeCreator.findType(null, scriptingName);
			}
			if (type == null)
				return "Error: Type '" + typeName + "' not found";

			List<org.eclipse.dltk.javascript.typeinfo.model.Member> matchingMembers = new ArrayList<>();
			for (org.eclipse.dltk.javascript.typeinfo.model.Member member : type.getMembers()) {
				if (member.getName().equalsIgnoreCase(memberName))
					matchingMembers.add(member);
			}

			if (matchingMembers.isEmpty())
				return "Error: Member '" + memberName + "' not found in type '" + type.getName() + "'";

			StringBuilder response = new StringBuilder();
			response.append("=== DOCUMENTATION FOR: ").append(type.getName()).append(".").append(memberName)
					.append(" ===\n\n");
			if (matchingMembers.size() > 1)
				response.append("[Note: ").append(matchingMembers.size()).append(" overloads found]\n\n");

			int overloadNum = 1;
			for (org.eclipse.dltk.javascript.typeinfo.model.Member member : matchingMembers) {
				if (matchingMembers.size() > 1)
					response.append("--- OVERLOAD ").append(overloadNum).append(" of ").append(matchingMembers.size())
							.append(" ---\n");
				response.append(docService.formatMemberDocumentation(member, type.getName()));
				response.append("\n");
				overloadNum++;
			}
			return response.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error getting documentation for member: " + typeName + "." + memberName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "getAvailableMembersForType", description = "Returns lightweight method and property signatures for a Servoy API type. "
			+ "Returns signatures like 'getFoundSet(query): JSFoundSet', 'loadAllRecords(): Boolean'. "
			+ "Truncates at 50 members - use memberFilter regex to narrow results: 'get.*' for getters, 'show.*|hide.*' for show/hide.", type = "object")
	public String getAvailableMembersForType(
			@ToolParam(name = "typeName", description = "Servoy API type name (e.g. 'application', 'databaseManager', 'JSFoundSet', 'controller')", required = true) String typeName,
			@ToolParam(name = "memberFilter", description = "Optional regex filter for member names. Examples: 'get.*', 'is.*', 'show.*|hide.*'. Default: all members.", required = false) String memberFilter) {
		if (typeName == null || typeName.trim().isEmpty())
			return "Error: typeName parameter is required";

		try {
			com.servoy.eclipse.debug.script.TypeCreator typeCreator = com.servoy.eclipse.debug.script.TypeProviderFactory
					.getTypeProvider().getTypeCreator();
			if (typeCreator == null)
				return "Error: TypeCreator not available";

			org.eclipse.dltk.javascript.typeinfo.model.Type type = typeCreator.findType(null, typeName);
			if (type == null) {
				String scriptingName = docService.mapClassNameToScriptingName(typeName);
				if (scriptingName != null && !scriptingName.equals(typeName))
					type = typeCreator.findType(null, scriptingName);
			}
			if (type == null)
				return "Error: Type '" + typeName
						+ "' not found. Try using scriptingName like 'application' instead of 'JSApplication'.";

			String filter = (memberFilter != null && !memberFilter.trim().isEmpty()) ? memberFilter.trim() : "*";
			java.util.regex.Pattern pattern = filter.equals("*") ? null
					: java.util.regex.Pattern.compile(filter, java.util.regex.Pattern.CASE_INSENSITIVE);

			List<org.eclipse.dltk.javascript.typeinfo.model.Member> methods = new ArrayList<>();
			List<org.eclipse.dltk.javascript.typeinfo.model.Member> properties = new ArrayList<>();

			for (org.eclipse.dltk.javascript.typeinfo.model.Member member : type.getMembers()) {
				if (pattern != null && !pattern.matcher(member.getName()).matches())
					continue;
				if (member instanceof org.eclipse.dltk.javascript.typeinfo.model.Method)
					methods.add(member);
				else if (member instanceof org.eclipse.dltk.javascript.typeinfo.model.Property)
					properties.add(member);
			}

			int totalFiltered = methods.size() + properties.size();
			boolean truncated = totalFiltered > MEMBERS_THRESHOLD;

			StringBuilder response = new StringBuilder();
			response.append("=== AVAILABLE MEMBERS FOR TYPE: ").append(type.getName()).append(" ===\n\n");
			if (!filter.equals("*"))
				response.append("Filter: ").append(filter).append("\n");
			response.append("Total found: ").append(totalFiltered).append(" members\n\n");

			if (!methods.isEmpty()) {
				response.append("METHODS (").append(methods.size()).append("):\n");
				int count = 0;
				for (org.eclipse.dltk.javascript.typeinfo.model.Member method : methods) {
					if (truncated && count >= MEMBERS_THRESHOLD)
						break;
					response.append("  - ").append(docService.formatMemberSignature(method)).append("\n");
					count++;
				}
				response.append("\n");
			}

			if (!properties.isEmpty()) {
				response.append("PROPERTIES (").append(properties.size()).append("):\n");
				int count = methods.size();
				for (org.eclipse.dltk.javascript.typeinfo.model.Member property : properties) {
					if (truncated && count >= MEMBERS_THRESHOLD)
						break;
					response.append("  - ").append(docService.formatMemberSignature(property)).append("\n");
					count++;
				}
				response.append("\n");
			}

			if (truncated) {
				response.append("[WARNING: ").append(totalFiltered).append(" members found, showing first ")
						.append(MEMBERS_THRESHOLD);
				response.append(". Use memberFilter with regex like 'get.*', 'show.*', or 'is.*' to narrow results]\n");
			}
			return response.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error getting available members for type: " + typeName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "getDocumentationForIdentifiers", description = "Returns Servoy API documentation for a list of identifiers. "
			+ "Accepts full method paths (e.g. 'databaseManager.getFoundSet', 'foundset.loadAllRecords') and Servoy types (e.g. JSEvent, JSRecord, QBSelect). "
			+ "Returns descriptions, parameter types, and return types for each identifier. "
			+ "Uses DLTK type inference on the file content to resolve identifiers in context.", type = "object")
	public String getDocumentationForIdentifiers(
			@ToolParam(name = "identifiers", description = "Comma-separated full identifier paths to look up (e.g., 'databaseManager.getFoundSet,JSRecord,plugins.dialogs.showInfoDialog')", required = true) String identifiers,
			@ToolParam(name = "filePath", description = "File path (form name, scope name, or workspace path) - provides the context for type inference", required = true) String filePath) {
		if (identifiers == null || identifiers.trim().isEmpty())
			return "Error: identifiers parameter is required";
		if (filePath == null || filePath.trim().isEmpty())
			return "Error: filePath parameter is required";

		try {
			String[] identifierArray = java.util.Arrays.stream(identifiers.split(",")).map(String::trim)
					.filter(s -> !s.isEmpty()).toArray(String[]::new);

			if (identifierArray.length == 0)
				return "Error: no valid identifiers in input";

			IFile file = filePathResolver.resolveFile(filePath);
			if (file == null || !file.exists())
				return filePathResolver.buildNotFoundMessage(filePath);

			com.servoy.eclipse.developer.mcp.dto.SelectionInfo selection = codeContextService
					.createSelectionInfoFromFile(file);
			if (selection == null)
				return "Error: Could not create selection info for: " + filePath;

			com.servoy.eclipse.developer.mcp.dto.CodeContext context = codeContextService.getCodeContext(selection,
					identifierArray);

			if (context.hasError())
				return "Error extracting context: " + context.getErrorMessage();

			StringBuilder response = new StringBuilder();
			response.append("--- DOCUMENTATION FOR: ");
			for (int i = 0; i < identifierArray.length; i++) {
				if (i > 0)
					response.append(", ");
				response.append(identifierArray[i]);
			}
			response.append(" ---\n\n");

			int foundCount = 0;
			for (String requestedId : identifierArray) {
				boolean found = false;
				String baseRequestedId = requestedId;
				int lastDotIndex = requestedId.lastIndexOf('.');
				if (lastDotIndex > 0)
					baseRequestedId = requestedId.substring(0, lastDotIndex);

				for (IdentifierContext identifierContext : context.getIdentifiers()) {
					if (identifierContext.getName().equals(requestedId)
							|| identifierContext.getName().equals(baseRequestedId)) {
						String xml = identifierContext.toFormattedXML();
						if (xml != null && !xml.trim().isEmpty()) {
							response.append(xml).append("\n");
							found = true;
							foundCount++;
							break;
						}
					}
				}

				if (!found) {
					response.append("<type>").append(requestedId).append(": NOT FOUND</type>\n");
					response.append("<description>No documentation available for this identifier</description>\n\n");
				}
			}

			response.append("--- END DOCUMENTATION ---\n\n");
			response.append("Found documentation for ").append(foundCount).append(" out of ")
					.append(identifierArray.length).append(" identifiers.");
			return response.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error getting documentation for identifiers: " + identifiers, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "applyDocumentations", description = "Writes JSDoc documentation items to a Servoy JavaScript file. "
			+ "Supports INSERT (no existing JSDoc) and REPLACE (existing JSDoc). "
			+ "Items must be provided as a JSON array string with fields: startLine, endLine, startSentence, endSentence, jsdoc. "
			+ "Items are applied bottom-to-top automatically to preserve line numbers. "
			+ "UUID values in @properties lines are automatically restored if accidentally changed.", type = "object")
	public String applyDocumentations(
			@ToolParam(name = "filePath", description = "Workspace-relative file path or form/scope name (e.g. '/svyPilotTest/utils.js' or 'utils')", required = true) String filePath,
			@ToolParam(name = "itemsJson", description = "JSON array of documentation items: [{startLine, endLine, startSentence, endSentence, jsdoc}]. INSERT: startLine==endLine and empty startSentence/endSentence. REPLACE: startLine/endLine cover the existing JSDoc block, startSentence='/**', endSentence='*/'", required = true) String itemsJson) {
		if (filePath == null || filePath.isBlank())
			return "Error: filePath is required";
		if (itemsJson == null || itemsJson.isBlank())
			return "Error: itemsJson is required";

		IFile file = filePathResolver.resolveFile(filePath);
		if (file == null || !file.exists())
			return filePathResolver.buildNotFoundMessage(filePath);

		try {
			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			List<DocumentationItem> items = mapper.readValue(itemsJson,
					mapper.getTypeFactory().constructCollectionType(List.class, DocumentationItem.class));

			if (items == null || items.isEmpty())
				return "Error: No documentation items provided";

			String resolvedPath = file.getFullPath().toString();
			String originalContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);

			List<String> lineList = new ArrayList<>();
			for (String line : originalContent.split("\r\n|\r|\n", -1))
				lineList.add(line);

			List<DocumentationItem> sortedItems = new ArrayList<>(items);
			sortedItems.sort((a, b) -> Integer.compare(b.startLine(), a.startLine()));

			List<String> errors = new ArrayList<>();
			int successCount = 0;

			for (DocumentationItem item : sortedItems) {
				try {
					if (item.startLine() < 0 || item.endLine() >= lineList.size()) {
						errors.add("Line range out of bounds: " + item.startLine() + "-" + item.endLine()
								+ " (file has " + lineList.size() + " lines)");
						continue;
					}

					if (item.isInsert()) {
						String indentation = docValidator.extractIndentation(lineList.get(item.startLine()));
						List<String> formattedLines = new ArrayList<>();
						for (String jsdocLine : item.jsdoc().split("\n"))
							formattedLines.add(indentation + jsdocLine);
						lineList.addAll(item.startLine(), formattedLines);
						successCount++;
					} else {
						String startLineContent = lineList.get(item.startLine()).trim();
						String endLineContent = lineList.get(item.endLine()).trim();

						if (!startLineContent.startsWith(item.startSentence())
								|| !endLineContent.endsWith(item.endSentence())) {
							errors.add("Validation failed at lines " + item.startLine() + "-" + item.endLine()
									+ ": start='"
									+ startLineContent.substring(0, Math.min(20, startLineContent.length())) + "' end='"
									+ endLineContent.substring(Math.max(0, endLineContent.length() - 20)) + "'");
							continue;
						}

						StringBuilder replacedContent = new StringBuilder();
						for (int i = item.startLine(); i <= item.endLine(); i++)
							replacedContent.append(lineList.get(i)).append("\n");
						List<String> originalUUIDs = docValidator.extractUUIDs(replacedContent.toString());
						String fixedJSDoc = docValidator.restoreUUIDs(item.jsdoc(), originalUUIDs);

						String indentation = docValidator.extractIndentation(lineList.get(item.startLine()));
						List<String> formattedLines = new ArrayList<>();
						for (String jsdocLine : fixedJSDoc.split("\n"))
							formattedLines.add(indentation + jsdocLine);

						for (int i = item.endLine(); i >= item.startLine(); i--)
							lineList.remove(i);
						lineList.addAll(item.startLine(), formattedLines);

						try {
							docValidator.validateJSDocSyntax(fixedJSDoc);
							successCount++;
						} catch (DocumentationValidatorService.ValidationException ve) {
							errors.add("JSDoc validation failed for lines " + item.startLine() + "-" + item.endLine()
									+ ": " + ve.getMessage());
						}
					}
				} catch (Exception e) {
					errors.add("Failed to process lines " + item.startLine() + "-" + item.endLine() + ": "
							+ e.getMessage());
				}
			}

			StringBuilder newContent = new StringBuilder();
			for (int i = 0; i < lineList.size(); i++) {
				if (i > 0)
					newContent.append("\n");
				newContent.append(lineList.get(i));
			}

			file.setContents(new ByteArrayInputStream(newContent.toString().getBytes(StandardCharsets.UTF_8)), true,
					false, null);

			if (errors.isEmpty())
				return String.format("Success: Applied %d documentation items to %s", successCount, resolvedPath);

			StringBuilder response = new StringBuilder();
			response.append("Partial success: Applied ").append(successCount).append(" out of ").append(items.size())
					.append(" documentation items.\n\nErrors encountered:\n");
			for (String error : errors)
				response.append("  - ").append(error).append("\n");
			return response.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error applying documentations to " + filePath, e);
			return "Error: " + e.getMessage();
		}
	}

	// -------------------------------------------------------------------------
	// validate MCP tool (Faza 2)
	// -------------------------------------------------------------------------

	@Tool(name = "validate", description = "Validates a JavaScript code snippet by parsing it via DLTK's JavaScript parser. "
			+ "Returns a success message if the code parses cleanly, or a list of syntax errors otherwise. "
			+ "Use this to verify AI-generated code before applying it to a file.", type = "object")
	public String validate(
			@ToolParam(name = "code", description = "JavaScript code snippet to validate (e.g. a function body, a statement, or a full file)", required = true) String code) {
		if (code == null || code.isBlank())
			return "Error: code parameter is required";

		try {
			List<org.eclipse.dltk.compiler.problem.DefaultProblem> problems = jsCodeValidator.validate(code);
			if (problems.isEmpty())
				return "Valid: code parses successfully.";

			StringBuilder sb = new StringBuilder();
			sb.append("Invalid: ").append(problems.size()).append(" problem(s) found.\n\n");
			int n = 1;
			for (org.eclipse.dltk.compiler.problem.DefaultProblem p : problems) {
				sb.append(n++).append(". ").append(p.getMessage());
				if (p.getSourceLineNumber() > 0)
					sb.append(" (line ").append(p.getSourceLineNumber()).append(")");
				sb.append("\n");
			}
			return sb.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error validating code", e);
			return "Error: " + e.getMessage();
		}
	}

	// -------------------------------------------------------------------------
	// Servoy Solution tools (Faza 3 + 5)
	// -------------------------------------------------------------------------

	// Note: deleteForms / deleteRelations / deleteValueLists were removed.
	// Use the unified `deleteFile` tool on servoy-coder with the artifact path
	// (forms/<name>.frm, relations/<name>.rel, valuelists/<name>.val); it routes
	// through ServoySolutionService for active-solution-aware deletion that
	// preserves referential consistency and removes the .js companion of forms.

	// -------------------------------------------------------------------------
	// Servoy Artifact creation tools (Faza 4)
	// -------------------------------------------------------------------------

	@Tool(name = "createForm", description = "Creates a new Servoy form in the active solution. "
			+ "Supports CSS-position and responsive layouts. "
			+ "Optionally sets dataSource, parent form (inheritance), and event handlers. "
			+ "Event handlers are auto-created as methods if they don't exist. "
			+ "Returns an error if a form with the same name already exists.", type = "object")
	public String createForm(
			@ToolParam(name = "name", description = "Form name (e.g. 'customerList', 'orderDetails')", required = true) String name,
			@ToolParam(name = "style", description = "Form style: 'css' (default) or 'responsive'", required = false) String style,
			@ToolParam(name = "width", description = "Form width in pixels (default: 640)", required = false) String width,
			@ToolParam(name = "height", description = "Form height in pixels (default: 480)", required = false) String height,
			@ToolParam(name = "dataSource", description = "DataSource (format: 'db:/server_name/table_name')", required = false) String dataSource,
			@ToolParam(name = "extendsForm", description = "Parent form name for inheritance", required = false) String extendsForm,
			@ToolParam(name = "events", description = "Comma-separated event:method pairs (e.g. 'onLoad:initForm,onShow:refreshData')", required = false) String events) {
		try {
			int w = width != null ? Integer.parseInt(width) : 640;
			int h = height != null ? Integer.parseInt(height) : 480;
			Map<String, String> eventMap = null;
			if (events != null && !events.isBlank()) {
				eventMap = new java.util.HashMap<>();
				for (String pair : events.split(",")) {
					String[] kv = pair.trim().split(":");
					if (kv.length == 2)
						eventMap.put(kv[0].trim(), kv[1].trim());
				}
			}
			return artifactService.createForm(name, style != null ? style : "css", w, h, dataSource, extendsForm,
					eventMap);
		} catch (Exception e) {
			ServoyLog.logError("Error creating form: " + name, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "createRelation", description = "Creates a new Servoy relation in the active solution. "
			+ "Requires primary and foreign dataSources. "
			+ "Optionally maps columns and sets join type. "
			+ "Returns an error if a relation with the same name already exists.", type = "object")
	public String createRelation(
			@ToolParam(name = "name", description = "Relation name (e.g. 'customers_to_orders')", required = true) String name,
			@ToolParam(name = "primaryDataSource", description = "Primary table datasource (format: 'db:/server_name/table_name')", required = true) String primaryDataSource,
			@ToolParam(name = "foreignDataSource", description = "Foreign table datasource (format: 'db:/server_name/table_name')", required = true) String foreignDataSource,
			@ToolParam(name = "primaryColumn", description = "Primary key column name for the join condition", required = false) String primaryColumn,
			@ToolParam(name = "foreignColumn", description = "Foreign key column name for the join condition", required = false) String foreignColumn,
			@ToolParam(name = "joinType", description = "Join type: 'left outer' (default) or 'inner'", required = false) String joinType) {
		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			if (activeProject != null && activeProject.getEditingSolution() != null
					&& activeProject.getEditingSolution().getRelation(name) != null) {
				return "Error: Relation '" + name + "' already exists in the active solution.";
			}
			return artifactService.createRelation(name, primaryDataSource, foreignDataSource, primaryColumn,
					foreignColumn, joinType);
		} catch (Exception e) {
			ServoyLog.logError("Error creating relation: " + name, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "createValueList", description = "Creates a new Servoy valuelist in the active solution. "
			+ "Supports types: 'custom' (fixed values), 'database' (table values), 'related' (via relation), 'global_method'. "
			+ "Returns an error if a valuelist with the same name already exists.", type = "object")
	public String createValueList(
			@ToolParam(name = "name", description = "ValueList name (e.g. 'statusList', 'countries')", required = true) String name,
			@ToolParam(name = "type", description = "ValueList type: 'custom' (default), 'database', 'related', 'global_method'", required = false) String type,
			@ToolParam(name = "customValues", description = "For custom type: newline-separated values (e.g. 'Active\\nInactive\\nPending')", required = false) String customValues,
			@ToolParam(name = "dataSource", description = "For database type: datasource (format: 'db:/server_name/table_name')", required = false) String dataSource,
			@ToolParam(name = "relationName", description = "For related type: relation name", required = false) String relationName,
			@ToolParam(name = "displayColumn", description = "Column to display", required = false) String displayColumn,
			@ToolParam(name = "returnColumn", description = "Column to return as value", required = false) String returnColumn) {
		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject activeProject = model.getActiveProject();
			if (activeProject != null && activeProject.getEditingSolution() != null
					&& activeProject.getEditingSolution().getValueList(name) != null) {
				return "Error: ValueList '" + name + "' already exists in the active solution.";
			}
			return artifactService.createValueList(name, type, customValues, dataSource, relationName, displayColumn,
					returnColumn);
		} catch (Exception e) {
			ServoyLog.logError("Error creating valuelist: " + name, e);
			return "Error: " + e.getMessage();
		}
	}

	// -------------------------------------------------------------------------
	// generateUUID MCP tool
	// -------------------------------------------------------------------------

	@Tool(name = "generateUUID", description = "Generates one or more random UUIDv4 values in uppercase format XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX. "
			+ "Use count > 1 when creating artifacts that require multiple UUIDs in a single operation (e.g. components in a .frm). "
			+ "Each UUID is returned on a separate line.", type = "object")
	public String generateUUID(
			@ToolParam(name = "count", description = "Number of UUIDs to generate. Default: 1.", required = false) String count) {
		int n = 1;
		if (count != null && !count.isBlank()) {
			try {
				n = Integer.parseInt(count.trim());
			} catch (NumberFormatException e) {
				return "Error: count must be a positive integer.";
			}
		}
		if (n < 1 || n > 100) {
			return "Error: count must be between 1 and 100.";
		}
		if (n == 1) {
			return UUID.randomUUID().toString().toUpperCase();
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append("\n");
			sb.append(UUID.randomUUID().toString().toUpperCase());
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------
	// Database schema tools
	// -------------------------------------------------------------------------

	@Tool(name = "listTables", description = "Lists all tables in a database server. Returns table names for the specified server.", type = "object")
	public String listTables(
			@ToolParam(name = "serverName", description = "Database server name", required = true) String serverName) {
		if (serverName == null || serverName.isBlank())
			return "Error: serverName parameter is required";

		try {
			IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
					.getServer(serverName, false, false);
			if (server == null)
				return "Error: Database server '" + serverName + "' not found";

			java.util.List<String> tables = server.getTableNames(false);
			StringBuilder result = new StringBuilder();
			result.append("Database Server: ").append(serverName).append("\n");
			result.append("Tables (").append(tables.size()).append("):\n\n");
			if (tables.isEmpty())
				result.append("(No tables found)\n");
			for (String tableName : tables)
				result.append("  - ").append(tableName).append("\n");
			return result.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error listing tables for server: " + serverName, e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "getTableInfo", description = "Retrieves comprehensive information about a database table including columns, primary keys, types, and metadata.", type = "object")
	public String getTableInfo(
			@ToolParam(name = "serverName", description = "Database server name", required = true) String serverName,
			@ToolParam(name = "tableName", description = "Table name", required = true) String tableName) {
		if (serverName == null || serverName.isBlank())
			return "Error: serverName parameter is required";
		if (tableName == null || tableName.isBlank())
			return "Error: tableName parameter is required";

		try {
			IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
					.getServer(serverName, false, false);
			if (server == null)
				return "Error: Database server '" + serverName + "' not found";

			ITable table = server.getTable(tableName);
			if (table == null)
				return "Error: Table '" + tableName + "' not found in server '" + serverName + "'";

			StringBuilder result = new StringBuilder();
			result.append("Table: ").append(table.getSQLName()).append("\n");
			result.append("DataSource: ").append(table.getDataSource()).append("\n\n");
			result.append("Columns:\n\n");

			java.util.Collection<Column> columns = table.getColumns();
			if (columns == null || columns.isEmpty()) {
				result.append("(No columns found)\n");
				return result.toString();
			}

			java.util.Set<String> pkNames = new java.util.HashSet<>();
			java.util.List<Column> pkColumns = table.getRowIdentColumns();
			if (pkColumns != null)
				for (Column col : pkColumns)
					pkNames.add(col.getName());

			int colNum = 1;
			for (Column col : columns) {
				result.append(colNum++).append(". ").append(col.getName()).append("\n");
				result.append("   Type: ").append(col.getColumnType()).append("\n");
				result.append("   PK: ").append(pkNames.contains(col.getName())).append("\n");
				result.append("   Nullable: ").append(col.getAllowNull()).append("\n\n");
			}
			return result.toString();
		} catch (Exception e) {
			ServoyLog.logError("Error getting table info: " + serverName + "." + tableName, e);
			return "Error: " + e.getMessage();
		}
	}
	
	@Tool(name = "executeSQL", description = "Executes raw SQL statements against a database server. "
			+ "Supports DDL (CREATE, ALTER, DROP) and DML (INSERT, UPDATE, DELETE) statements. "
			+ "For SELECT queries, returns the result set as formatted text. "
			+ "After execution, reloads all table metadata so Servoy sees the changes.", type = "object")
	public String executeSQL(
			@ToolParam(name = "serverName", description = "Database server name to execute SQL against.", required = true) String serverName,
			@ToolParam(name = "sql", description = "The SQL statement to execute.", required = true) String sql) {
		if (serverName == null || serverName.isBlank())
			return "Error: serverName is required";
		if (sql == null || sql.isBlank())
			return "Error: sql is required";

		IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
				.getServer(serverName, false, false);
		if (server == null)
			return "Error: Database server '" + serverName + "' not found";

		com.servoy.j2db.util.ITransactionConnection connection = null;
		try {
			connection = server.getUnmanagedConnection();
			String trimmedSql = sql.trim().toUpperCase();

			if (trimmedSql.startsWith("SELECT") || trimmedSql.startsWith("WITH")) {
				java.sql.PreparedStatement ps = connection.prepareStatement(sql);
				java.sql.ResultSet rs = ps.executeQuery();
				java.sql.ResultSetMetaData meta = rs.getMetaData();
				int colCount = meta.getColumnCount();

				StringBuilder result = new StringBuilder();
				for (int i = 1; i <= colCount; i++) {
					if (i > 1) result.append(" | ");
					result.append(meta.getColumnLabel(i));
				}
				result.append("\n");
				for (int i = 1; i <= colCount; i++) {
					if (i > 1) result.append("-+-");
					result.append("-".repeat(Math.max(meta.getColumnLabel(i).length(), 4)));
				}
				result.append("\n");

				int rowCount = 0;
				while (rs.next() && rowCount < 500) {
					for (int i = 1; i <= colCount; i++) {
						if (i > 1) result.append(" | ");
						Object val = rs.getObject(i);
						result.append(val == null ? "NULL" : val.toString());
					}
					result.append("\n");
					rowCount++;
				}
				rs.close();
				ps.close();

				result.append("\n(").append(rowCount).append(" row(s))");
				if (rowCount == 500) result.append(" [limited to 500 rows]");
				return result.toString();
			} else {
				java.sql.PreparedStatement ps = connection.prepareStatement(sql);
				int affected = ps.executeUpdate();
				ps.close();

				server.reloadTables();

				return "SQL executed successfully. Rows affected: " + affected + ". Tables reloaded.";
			}
		} catch (Exception e) {
			ServoyLog.logError("executeSQL failed", e);
			return "Error: " + e.getMessage();
		} finally {
			Utils.closeConnection(connection);
		}
	}

	@Tool(name = "addColumn", description = "Adds a new column to an existing database or in-memory table and saves the change immediately. "
			+ "For database tables, executes ALTER TABLE ADD COLUMN. For in-memory tables, updates the column definition in the solution.", type = "object")
	public String addColumn(
			@ToolParam(name = "serverName", description = "Database server name containing the table.", required = true) String serverName,
			@ToolParam(name = "tableName", description = "Name of the existing table to add the column to.", required = true) String tableName,
			@ToolParam(name = "columnName", description = "Name of the new column. Must be a valid SQL identifier.", required = true) String columnName,
			@ToolParam(name = "type", description = "Column type: TEXT, INTEGER, NUMBER, DATETIME, or MEDIA. Default: TEXT.", required = false) String type,
			@ToolParam(name = "length", description = "Column length. Default: 50 for TEXT, 0 for others.", required = false) String length,
			@ToolParam(name = "allowNull", description = "Whether the column allows null values. Default: true.", required = false) String allowNull,
			@ToolParam(name = "inMemory", description = "If 'true', adds the column to an in-memory table in the active solution. Default: false.", required = false) String inMemory) {
		if (serverName == null || serverName.isBlank())
			return "Error: serverName is required";
		if (tableName == null || tableName.isBlank())
			return "Error: tableName is required";
		if (columnName == null || columnName.isBlank())
			return "Error: columnName is required";

		if (!com.servoy.j2db.util.docvalidator.IdentDocumentValidator.isSQLIdentifier(columnName))
			return "Error: '" + columnName + "' is not a valid SQL identifier";

		String resolvedType = Optional.ofNullable(type).map(String::toUpperCase).orElse("TEXT");
		int typeId;
		switch (resolvedType) {
			case "INTEGER": typeId = com.servoy.j2db.persistence.IColumnTypes.INTEGER; break;
			case "NUMBER": typeId = com.servoy.j2db.persistence.IColumnTypes.NUMBER; break;
			case "DATETIME": typeId = com.servoy.j2db.persistence.IColumnTypes.DATETIME; break;
			case "MEDIA": typeId = com.servoy.j2db.persistence.IColumnTypes.MEDIA; break;
			case "TEXT": typeId = com.servoy.j2db.persistence.IColumnTypes.TEXT; break;
			default: return "Error: Invalid column type '" + type + "'. Must be TEXT, INTEGER, NUMBER, DATETIME, or MEDIA.";
		}

		int resolvedLength = resolvedType.equals("TEXT") ? 50 : 0;
		if (length != null && !length.isBlank()) {
			try {
				resolvedLength = Integer.parseInt(length);
			} catch (NumberFormatException e) {
				return "Error: Invalid length value '" + length + "'";
			}
		}

		boolean resolvedAllowNull = Optional.ofNullable(allowNull).map(Boolean::parseBoolean).orElse(true);
		boolean isInMemory = Optional.ofNullable(inMemory).map(Boolean::parseBoolean).orElse(false);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		IValidateName validator = servoyModel.getNameValidator();

		try {
			if (isInMemory) {
				ServoyProject project = servoyModel.getActiveProject();
				if (project == null)
					return "Error: No active Servoy project";

				com.servoy.eclipse.model.inmemory.MemServer memServer = project.getMemServer();
				ITable table = memServer.getTable(tableName);
				if (table == null)
					return "Error: In-memory table '" + tableName + "' not found";

				if (table.getColumn(columnName) != null)
					return "Error: Column '" + columnName + "' already exists in table '" + tableName + "'";

				((com.servoy.j2db.persistence.AbstractTable) table).createNewColumn(validator, columnName,
						ColumnType.getInstance(typeId, resolvedLength, 0), resolvedAllowNull);

				memServer.syncTableObjWithDB(table, false, true);

				Solution solution = project.getEditingSolution();
				com.servoy.j2db.persistence.TableNode tableNode = solution.getOrCreateTableNode(table.getDataSource());
				project.saveEditingSolutionNodes(new IPersist[] { tableNode }, true);

				return "Column '" + columnName + "' (" + resolvedType + ") added to in-memory table '" + tableName + "'.";
			} else {
				IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
						.getServer(serverName, false, false);
				if (server == null)
					return "Error: Database server '" + serverName + "' not found";

				ITable table = server.getTable(tableName);
				if (table == null)
					return "Error: Table '" + tableName + "' not found on server '" + serverName + "'";

				if (table.getColumn(columnName) != null)
					return "Error: Column '" + columnName + "' already exists in table '" + tableName + "'";

				((com.servoy.j2db.persistence.AbstractTable) table).createNewColumn(validator, columnName,
						ColumnType.getInstance(typeId, resolvedLength, 0), resolvedAllowNull);

				server.syncTableObjWithDB(table, false, true);

				DataModelManager dmm = servoyModel.getDataModelManager();
				if (dmm != null) {
					dmm.updateAllColumnInfo(table);
				}

				return "Column '" + columnName + "' (" + resolvedType + ", length=" + resolvedLength + ", nullable=" + resolvedAllowNull + ") added to table '" + tableName + "' on server '" + serverName + "'.";
			}
		} catch (Exception e) {
			ServoyLog.logError("addColumn failed", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "createTable", description = "Creates a new empty database table with an auto-generated primary key column. "
			+ "The PK column is named '<tableName>_id' with type INTEGER. "
			+ "For database tables, uses database identity sequence. For in-memory tables, uses Servoy sequence.", type = "object")
	public String createTable(
			@ToolParam(name = "serverName", description = "Database server name where the table will be created. Required for database tables, ignored for in-memory tables.", required = true) String serverName,
			@ToolParam(name = "tableName", description = "Name of the table to create. Must be a valid SQL identifier, cannot start with 'temp_' or 'svy_'.", required = true) String tableName,
			@ToolParam(name = "inMemory", description = "If 'true', creates an in-memory datasource table in the active solution instead of a database table. Default: false.", required = false) String inMemory) {
		if (tableName == null || tableName.isBlank())
			return "Error: tableName is required";

		if (!com.servoy.j2db.util.docvalidator.IdentDocumentValidator.isSQLIdentifier(tableName))
			return "Error: '" + tableName + "' is not a valid SQL identifier";
		if (tableName.toUpperCase().startsWith(DataModelManager.TEMP_UPPERCASE_PREFIX))
			return "Error: table name cannot start with 'temp_'";
		if (tableName.toUpperCase().startsWith(com.servoy.j2db.persistence.IServer.SERVOY_UPPERCASE_PREFIX))
			return "Error: table name cannot start with 'svy_'";

		boolean createInMemory = Optional.ofNullable(inMemory).map(Boolean::parseBoolean).orElse(false);

		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		IValidateName validator = servoyModel.getNameValidator();

		try {
			if (createInMemory) {
				ServoyProject project = servoyModel.getActiveProject();
				if (project == null)
					return "Error: No active Servoy project";

				com.servoy.eclipse.model.inmemory.MemServer memServer = project.getMemServer();
				if (memServer.getTable(tableName) != null)
					return "Error: In-memory table '" + tableName + "' already exists";

				ITable table = memServer.createNewTable(validator, tableName);

				String pkName = tableName + "_uuid";
				Column pkColumn = ((com.servoy.j2db.persistence.AbstractTable) table).createNewColumn(validator, pkName,
						ColumnType.getInstance(PrimaryKeyType.UUD_NATIVE.getColumnType(), PrimaryKeyType.UUD_NATIVE.getLength(), 0), false, true);
				pkColumn.setSequenceType(ColumnInfo.UUID_GENERATOR);
				pkColumn.setFlag(IBaseColumn.UUID_COLUMN, true);
				pkColumn.setFlag(IBaseColumn.NATIVE_COLUMN, true);
				pkColumn.setFlag(IBaseColumn.PK_COLUMN, true);

				memServer.syncTableObjWithDB(table, false, true);

				Solution solution = project.getEditingSolution();
				com.servoy.j2db.persistence.TableNode tableNode = solution.getOrCreateTableNode(table.getDataSource());
				project.saveEditingSolutionNodes(new IPersist[] { tableNode }, true);

				return "In-memory table '" + tableName + "' created in project '" + project.getProject().getName() + "' with PK column '" + pkName + "'.";
			} else {
				if (serverName == null || serverName.isBlank())
					return "Error: serverName is required for database tables";

				IServerInternal server = (IServerInternal) ApplicationServerRegistry.get().getServerManager()
						.getServer(serverName, false, false);
				if (server == null)
					return "Error: Database server '" + serverName + "' not found";

				if (server.getTable(tableName) != null)
					return "Error: Table '" + tableName + "' already exists on server '" + serverName + "'";

				ITable table = server.createNewTable(validator, tableName);

				String pkName = tableName + "_id";
				Column pkColumn = ((com.servoy.j2db.persistence.AbstractTable) table).createNewColumn(validator, pkName,
						ColumnType.getInstance(com.servoy.j2db.persistence.IColumnTypes.INTEGER, 0, 0), false, true);
				pkColumn.setSequenceType(ColumnInfo.DATABASE_IDENTITY);
				pkColumn.setFlag(IBaseColumn.PK_COLUMN, true);

				server.syncTableObjWithDB(table, false, true);
				TableChangeHandler.getInstance().fireTablesAdded(server, new String[] { tableName });

				DataModelManager dmm = servoyModel.getDataModelManager();
				if (dmm != null) {
					dmm.updateAllColumnInfo(table);
				}

				return "Table '" + tableName + "' created on server '" + serverName + "' with PK column '" + pkName + "'.";
			}
		} catch (Exception e) {
			ServoyLog.logError("createTable failed", e);
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "createServer", description = "Creates a new PostgreSQL database and registers it as a Servoy database server. "
			+ "Automatically finds an existing PostgreSQL server in the workspace to use as connection prototype (host/port/credentials). "
			+ "Creates the database with Unicode encoding, then registers a new server config pointing to it.", type = "object")
	public String createServer(
			@ToolParam(name = "serverName", description = "Server config name to register in Servoy.", required = true) String serverName,
			@ToolParam(name = "databaseName", description = "Name of the PostgreSQL database to create or connect to. If omitted, uses serverName.", required = false) String databaseName,
			@ToolParam(name = "createDatabase", description = "Whether to create the database. Default: true. If false, the database must already exist.", required = false) String createDatabase) {
		if (serverName == null || serverName.isBlank())
			return "Error: serverName is required";

		String resolvedDbName = (databaseName != null && !databaseName.isBlank()) ? databaseName : serverName;

		com.servoy.j2db.persistence.IServerManagerInternal serverManager = ApplicationServerRegistry.get().getServerManager();

		IServerInternal serverPrototype = null;
		com.servoy.j2db.persistence.ServerConfig origConfig = null;
		String[] serverNames = serverManager.getServerNames(true, false, true, true);
		for (String name : serverNames) {
			IServerInternal candidate = (IServerInternal) serverManager.getServer(name, false, false);
			if (candidate != null) {
				com.servoy.j2db.persistence.ServerConfig sc = candidate.getConfig();
				if (sc.isPostgresDriver() && sc.isEnabled()) {
					serverPrototype = candidate;
					origConfig = sc;
					break;
				}
			}
		}
		if (serverPrototype == null)
			return "Error: No enabled PostgreSQL server found in the workspace to use as prototype";

		boolean doCreateDatabase = Optional.ofNullable(createDatabase).map(Boolean::parseBoolean).orElse(true);

		if (doCreateDatabase) {
			com.servoy.j2db.util.ITransactionConnection connection = null;
			java.sql.PreparedStatement ps = null;
			try {
				connection = serverPrototype.getUnmanagedConnection();
				ps = connection.prepareStatement("CREATE DATABASE \"" + resolvedDbName + "\" WITH ENCODING 'UNICODE';");
				ps.execute();
				ps.close();
				ps = null;
			} catch (Exception e) {
				ServoyLog.logError("createServer: failed to create database", e);
				return "Error: Could not create database: " + e.getMessage();
			} finally {
				Utils.closeConnection(connection);
				Utils.closeStatement(ps);
			}
		}

		String resolvedConfigName = serverName;
		try {
			for (int i = 1; serverManager.getServerConfig(resolvedConfigName) != null && i < 100; i++) {
				resolvedConfigName = serverName + i;
			}

			String serverUrl = EclipseDatabaseUtils.getPostgresServerUrl(origConfig, resolvedDbName);
			com.servoy.j2db.persistence.ServerConfig newConfig = origConfig.newBuilder()
					.setServerName(resolvedConfigName)
					.setServerUrl(serverUrl)
					.setSchema(null)
					.setDataModelCloneFrom(null)
					.setEnabled(true)
					.setSkipSysTables(false)
					.setIdleTimeout(-1)
					.build();

			com.servoy.j2db.persistence.ServerSettings serverSettings = ((com.servoy.j2db.persistence.IServer) serverPrototype)
					.getSettings().withDefaults(origConfig);
			serverManager.saveServerConfig(null, newConfig);
			serverManager.saveServerSettings(resolvedConfigName, serverSettings);

			return "PostgreSQL database '" + databaseName + "' " + (doCreateDatabase ? "created and " : "") + "registered as server '" + resolvedConfigName + "'.";
		} catch (Exception e) {
			ServoyLog.logError("createPostgresDatabase: failed to register server config", e);
			return "Error: Database created but failed to register server config: " + e.getMessage();
		}
	}

	// -------------------------------------------------------------------------
	// Permission / Security tools
	// -------------------------------------------------------------------------

	@Tool(name = "listUsers", description = "Lists all users defined in the workspace security configuration. Returns user names and their UIDs.", type = "object")
	public String listUsers() {
		try {
			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			com.servoy.j2db.dataprocessing.IDataSet users = userManager.getUsers(getSecurityClientId());
			if (users == null || users.getRowCount() == 0) return "No users defined.";

			StringBuilder result = new StringBuilder();
			result.append("Users (").append(users.getRowCount()).append("):\n\n");
			result.append("| # | Name | UID |\n");
			result.append("|---|------|-----|\n");
			for (int i = 0; i < users.getRowCount(); i++) {
				Object[] row = users.getRow(i);
				result.append("| ").append(i + 1).append(" | ").append(row[1]).append(" | ").append(row[0]).append(" |\n");
			}
			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "createUser", description = "Creates a new user. Password is plain text and will be hashed internally. If userUID is not provided, a UUID will be auto-generated.", type = "object")
	public String createUser(
			@ToolParam(name = "userName", description = "The user name") String userName,
			@ToolParam(name = "password", description = "Plain text password (will be hashed internally)") String password,
			@ToolParam(name = "userUID", description = "Optional user UUID. If not provided, one will be auto-generated.", required = false) String userUID) {
		try {
			if (userName == null || userName.trim().isEmpty()) return "Error: userName is required";
			if (password == null || password.isEmpty()) return "Error: password is required";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			int result = userManager.createUser(getSecurityClientId(), userName, password, userUID, false);
			if (result < 0) {
				if (result == -2) return "Error: User '" + userName + "' already exists";
				return "Error: Failed to create user (code: " + result + ")";
			}

			userManager.writeAllSecurityInformation(false);
			String uid = userManager.getUserUID(getSecurityClientId(), userName);
			return "User '" + userName + "' created successfully.\n  UID: " + uid;
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "changeUserName", description = "Renames an existing user.", type = "object")
	public String changeUserName(
			@ToolParam(name = "oldName", description = "Current user name") String oldName,
			@ToolParam(name = "newName", description = "New user name") String newName) {
		try {
			if (oldName == null || oldName.trim().isEmpty()) return "Error: oldName is required";
			if (newName == null || newName.trim().isEmpty()) return "Error: newName is required";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String userUID = userManager.getUserUID(getSecurityClientId(), oldName);
			if (userUID == null) return "Error: User '" + oldName + "' not found";

			boolean success = userManager.changeUserName(getSecurityClientId(), userUID, newName);
			if (!success) return "Error: Failed to rename user. Name '" + newName + "' may already be in use.";

			userManager.writeAllSecurityInformation(false);
			return "User renamed from '" + oldName + "' to '" + newName + "' successfully.";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "setUserPassword", description = "Changes a user's password. Password is plain text and will be hashed internally.", type = "object")
	public String setUserPassword(
			@ToolParam(name = "userName", description = "The user name") String userName,
			@ToolParam(name = "newPassword", description = "New plain text password") String newPassword) {
		try {
			if (userName == null || userName.trim().isEmpty()) return "Error: userName is required";
			if (newPassword == null || newPassword.isEmpty()) return "Error: newPassword is required";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String userUID = userManager.getUserUID(getSecurityClientId(), userName);
			if (userUID == null) return "Error: User '" + userName + "' not found";

			boolean success = userManager.setPassword(getSecurityClientId(), userUID, newPassword, true);
			if (!success) return "Error: Failed to set password for user '" + userName + "'";

			userManager.writeAllSecurityInformation(false);
			return "Password updated for user '" + userName + "' successfully.";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "createPermission", description = "Creates a new permission. Permissions control access rights to form elements.", type = "object")
	public String createPermission(
			@ToolParam(name = "permissionName", description = "Name of the new permission") String permissionName) {
		try {
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			int result = userManager.createGroup(getSecurityClientId(), permissionName);
			if (result == -1) return "Error: Permission '" + permissionName + "' already exists or could not be created";

			userManager.writeAllSecurityInformation(false);
			return "Permission '" + permissionName + "' created successfully.";
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "getFormSecurity", description = "Returns current form element access rights for a given permission. Shows VIEWABLE and ACCESSIBLE flags for each element.", type = "object")
	public String getFormSecurity(
			@ToolParam(name = "permissionName", description = "Permission name") String permissionName,
			@ToolParam(name = "formName", description = "Form name") String formName,
			@ToolParam(name = "solutionName", description = "Solution name (defaults to active solution)", required = false) String solutionName) {
		try {
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";
			if (formName == null || formName.trim().isEmpty()) return "Error: formName is required";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			Form form = resolveFormForSecurity(formName, solutionName);
			if (form == null) return "Error: Form '" + formName + "' not found" + (solutionName != null ? " in solution '" + solutionName + "'" : "");

			java.util.List<com.servoy.j2db.server.shared.SecurityInfo> infos = userManager.getSecurityInfos(permissionName, form);

			StringBuilder result = new StringBuilder();
			result.append("Form security for '").append(formName).append("' with permission '").append(permissionName).append("':\n\n");

			if (infos == null || infos.isEmpty()) {
				result.append("No explicit access rights set. Default access applies (VIEWABLE + ACCESSIBLE).");
				return result.toString();
			}

			result.append("| Element | Viewable | Accessible | Access |\n");
			result.append("|---------|----------|------------|--------|\n");

			for (com.servoy.j2db.server.shared.SecurityInfo info : infos) {
				String elementName = resolveElementNameForSecurity(form, info.element_uid);
				boolean viewable = (info.access & IRepository.VIEWABLE) != 0;
				boolean accessible = (info.access & IRepository.ACCESSIBLE) != 0;
				result.append("| ").append(elementName).append(" | ").append(viewable).append(" | ").append(accessible).append(" | ").append(info.access).append(" |\n");
			}
			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "setFormElementAccess", description = "Sets VIEWABLE and ACCESSIBLE flags for a form or a specific element. When elementName is omitted, sets access on the form itself.", type = "object")
	public String setFormElementAccess(
			@ToolParam(name = "permissionName", description = "Permission name") String permissionName,
			@ToolParam(name = "formName", description = "Form name") String formName,
			@ToolParam(name = "elementName", description = "Element name (omit to set access on the form itself)", required = false) String elementName,
			@ToolParam(name = "viewable", description = "Whether the element should be viewable", type = "boolean") String viewable,
			@ToolParam(name = "accessible", description = "Whether the element should be accessible", type = "boolean") String accessible,
			@ToolParam(name = "solutionName", description = "Solution name (defaults to active solution)", required = false) String solutionName) {
		try {
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";
			if (formName == null || formName.trim().isEmpty()) return "Error: formName is required";

			boolean isViewable = Boolean.parseBoolean(viewable);
			boolean isAccessible = Boolean.parseBoolean(accessible);

			if (!isViewable && isAccessible) return "Error: Invalid combination â accessible=true has no effect when viewable=false. Valid combinations: viewable+accessible, viewable only, or neither.";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String resolvedSolution = resolveSecuritySolutionName(solutionName);
			if (resolvedSolution == null) return "Error: No active solution found";

			Form form = resolveFormForSecurity(formName, solutionName);
			if (form == null) return "Error: Form '" + formName + "' not found" + (solutionName != null ? " in solution '" + solutionName + "'" : "");

			int accessMask = (isViewable ? IRepository.VIEWABLE : 0) | (isAccessible ? IRepository.ACCESSIBLE : 0);

			String elementUID;
			if (elementName == null || elementName.trim().isEmpty()) {
				elementUID = form.getUUID().toString();
			} else {
				elementUID = resolveElementUIDForSecurity(form, elementName);
				if (elementUID == null) return "Error: Element '" + elementName + "' not found in form '" + formName + "'";
			}

			userManager.setFormSecurityAccess(getSecurityClientId(), permissionName, Integer.valueOf(accessMask), elementUID, resolvedSolution);
			userManager.writeAllSecurityInformation(false);

			String target = (elementName == null || elementName.trim().isEmpty()) ? "form '" + formName + "'" : "element '" + elementName + "' in form '" + formName + "'";
			return "Access set on " + target + " for permission '" + permissionName + "': viewable=" + isViewable + ", accessible=" + isAccessible;
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	@Tool(name = "setFormSecurityBulk", description = "Sets access for multiple elements in one call. accessEntries is a JSON array of objects with fields: elementName (optional), viewable (boolean), accessible (boolean).", type = "object")
	public String setFormSecurityBulk(
			@ToolParam(name = "permissionName", description = "Permission name") String permissionName,
			@ToolParam(name = "formName", description = "Form name") String formName,
			@ToolParam(name = "accessEntries", description = "JSON array e.g. [{\"elementName\":\"btn1\",\"viewable\":true,\"accessible\":false}]") String accessEntries,
			@ToolParam(name = "solutionName", description = "Solution name (defaults to active solution)", required = false) String solutionName) {
		try {
			if (permissionName == null || permissionName.trim().isEmpty()) return "Error: permissionName is required";
			if (formName == null || formName.trim().isEmpty()) return "Error: formName is required";
			if (accessEntries == null || accessEntries.trim().isEmpty()) return "Error: accessEntries is required";

			com.servoy.eclipse.model.repository.WorkspaceUserManager userManager = getUserManager();
			if (userManager == null) return "Error: User manager not available";

			String resolvedSolution = resolveSecuritySolutionName(solutionName);
			if (resolvedSolution == null) return "Error: No active solution found";

			Form form = resolveFormForSecurity(formName, solutionName);
			if (form == null) return "Error: Form '" + formName + "' not found" + (solutionName != null ? " in solution '" + solutionName + "'" : "");

			org.json.JSONArray entries = new org.json.JSONArray(accessEntries);
			int successCount = 0;
			StringBuilder errors = new StringBuilder();

			for (int i = 0; i < entries.length(); i++) {
				org.json.JSONObject entry = entries.getJSONObject(i);
				String elName = entry.optString("elementName", null);
				boolean elViewable = entry.getBoolean("viewable");
				boolean elAccessible = entry.getBoolean("accessible");

					if (!elViewable && elAccessible) {
						errors.append("  - Invalid combination for '").append(elName != null ? elName : "(form)").append("': accessible=true has no effect when viewable=false\n");
						continue;
					}

				int accessMask = (elViewable ? IRepository.VIEWABLE : 0) | (elAccessible ? IRepository.ACCESSIBLE : 0);

				String elementUID;
				if (elName == null || elName.trim().isEmpty()) {
					elementUID = form.getUUID().toString();
				} else {
					elementUID = resolveElementUIDForSecurity(form, elName);
					if (elementUID == null) {
						errors.append("  - Element '").append(elName).append("' not found\n");
						continue;
					}
				}

				userManager.setFormSecurityAccess(getSecurityClientId(), permissionName, Integer.valueOf(accessMask), elementUID, resolvedSolution);
				successCount++;
			}

			userManager.writeAllSecurityInformation(false);

			StringBuilder result = new StringBuilder();
			result.append("Bulk access update on form '").append(formName).append("' for permission '").append(permissionName).append("':\n");
			result.append("  ").append(successCount).append("/").append(entries.length()).append(" entries applied successfully.");
			if (errors.length() > 0) {
				result.append("\n\nErrors:\n").append(errors);
			}
			return result.toString();
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	private com.servoy.eclipse.model.repository.WorkspaceUserManager getUserManager() {
		return (com.servoy.eclipse.model.repository.WorkspaceUserManager) ServoyModelManager.getServoyModelManager().getServoyModel().getUserManager();
	}

	private String getSecurityClientId() {
		return ApplicationServerRegistry.get().getClientId();
	}

	private String resolveSecuritySolutionName(String solutionName) {
		if (solutionName != null && !solutionName.trim().isEmpty()) return solutionName;
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null && activeProject.getEditingSolution() != null) {
			return activeProject.getEditingSolution().getName();
		}
		return null;
	}

	private Form resolveFormForSecurity(String formName, String solutionName) {
		if (solutionName != null && !solutionName.trim().isEmpty()) {
			ServoyProject project = ServoyModelManager.getServoyModelManager().getServoyModel().getServoyProject(solutionName);
			if (project != null && project.getEditingSolution() != null) {
				return project.getEditingSolution().getForm(formName);
			}
			return null;
		}
		ServoyProject activeProject = ServoyModelManager.getServoyModelManager().getServoyModel().getActiveProject();
		if (activeProject != null && activeProject.getEditingSolution() != null) {
			Form form = activeProject.getEditingSolution().getForm(formName);
			if (form != null) return form;
			ServoyProject[] modules = ServoyModelManager.getServoyModelManager().getServoyModel().getModulesOfActiveProject();
			for (ServoyProject module : modules) {
				if (module != null && module.getEditingSolution() != null) {
					form = module.getEditingSolution().getForm(formName);
					if (form != null) return form;
				}
			}
		}
		return null;
	}

	private String resolveElementUIDForSecurity(Form form, String elementName) {
		java.util.Iterator<IPersist> children = form.getAllObjects();
		while (children.hasNext()) {
			IPersist child = children.next();
			if (child instanceof com.servoy.j2db.persistence.ISupportName named) {
				if (elementName.equals(named.getName())) {
					return child.getUUID().toString();
				}
			}
		}
		return null;
	}

	private String resolveElementNameForSecurity(Form form, String elementUID) {
		if (form.getUUID().toString().equals(elementUID)) {
			return "(form: " + form.getName() + ")";
		}
		java.util.Iterator<IPersist> children = form.getAllObjects();
		while (children.hasNext()) {
			IPersist child = children.next();
			if (child.getUUID().toString().equals(elementUID)) {
				if (child instanceof com.servoy.j2db.persistence.ISupportName named && named.getName() != null) {
					return named.getName();
				}
				return elementUID;
			}
		}
		return elementUID;
	}
}