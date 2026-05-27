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
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.DataModelManager;
import com.servoy.eclipse.model.util.ServoyLog;
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
import com.servoy.j2db.persistence.MethodTemplate;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.PersistEncapsulation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptMethod;
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
public class ServoyDevServer
{
	private static final String DEFAULT_SOLUTION_NAME = "mcp_test_solution";

	private static final String[] NG_PACKAGES = {
		"12grid", "bootstrapcomponents", "fontawesome", "servoyextra"
	};

	private static final long ACTIVATE_SETTLE_MS = 5000;

	private static final int DEFAULT_ENCAPSULATION = PersistEncapsulation.HIDE_DATAPROVIDERS |
		PersistEncapsulation.HIDE_ELEMENTS | PersistEncapsulation.HIDE_CONTAINERS | PersistEncapsulation.HIDE_FOUNDSET;

	public ServoyDevServer()
	{
	}

	@Tool(name = "ping", description = "Returns a simple pong response to verify the servoy-dev endpoint is alive.", type = "object")
	public String ping()
	{
		return "pong";
	}

	@Tool(name = "createTestSolution",
		description = "Creates a minimal Servoy NG Client solution in the workspace for MCP tool testing. "
			+ "The solution includes a CSS-position form (testForm), a responsive form (testResponsiveForm), "
			+ "a scope (testScope), and default theme/manifest media files. "
			+ "Uses the existing resources project in the workspace. "
			+ "Optionally activates the solution in Servoy Developer.",
		type = "object")
	public String createTestSolution(
		@ToolParam(name = "solutionName",
			description = "Name of the test solution to create. Defaults to '" + DEFAULT_SOLUTION_NAME + "'.",
			required = false) String solutionName,
		@ToolParam(name = "activate",
			description = "Whether to activate the solution in Servoy Developer after creation. Default: true.",
			required = false) String activate)
	{
		String name = Optional.ofNullable(solutionName).filter(s -> !s.isBlank()).orElse(DEFAULT_SOLUTION_NAME);
		boolean doActivate = Optional.ofNullable(activate).map(Boolean::parseBoolean).orElse(true);

		try
		{
			 // Check if solution already exists
		    IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		    if (sol.exists())
		    {
		        if (doActivate)
		        {
		            doActivateSolution(name, true);
		            return "Solution '" + name + "' already exists. Activated.";
		        }
		        return "Solution '" + name + "' already exists.";
		    }
			
			createEclipseProjects(name);
			createServoyArtifacts(name);

			if (doActivate)
			{
				doActivateSolution(name, true);
				return "Created and activated test solution '" + name + "' in workspace.";
			}
			return "Created test solution '" + name + "' in workspace (not activated).";
		}
		catch (Exception e)
		{
			ServoyLog.logError("createTestSolution failed", e);
			return "Error creating test solution: " + e.getMessage();
		}
	}

	// -------------------------------------------------------------------------
	// Step 1: Create Eclipse projects + non-Servoy files
	// -------------------------------------------------------------------------

	private void createEclipseProjects(String solutionName) throws Exception
	{
		// Resolve resources project — same logic as NewSolutionWizard:
		// 1. Use active resources project if available
		// 2. Find existing project named "resources"
		// 3. Create a new one with name "resources" (or "resources1", "resources2", ...)
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		IProject resourcesProject = resolveOrCreateResourcesProject(model);

		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {

			if (!resourcesProject.isOpen()) resourcesProject.open(monitor);

			// Solution project — natures + builders only, no Servoy structural files
			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(solutionName);
			if (!sol.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(solutionName);
				d.setNatureIds(new String[] {
					"com.servoy.eclipse.core.ServoyProject",
					"org.eclipse.dltk.javascript.core.nature"
				});
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				d.setReferencedProjects(new IProject[] { resourcesProject });
				sol.create(d, monitor);
			}
			if (!sol.isOpen()) sol.open(monitor);

			// .buildpath — not a Servoy structural file, safe to write directly
			writeTextFile(sol, ".buildpath",
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
				"<buildpath>\n" +
				"\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n" +
				"</buildpath>\n",
				monitor);

			// ng_web_packages/
			IFolder ngFolder = sol.getFolder("ng_web_packages");
			if (!ngFolder.exists()) ngFolder.create(true, true, monitor);
			copyNgPackages(ngFolder, monitor);

		}, new NullProgressMonitor());

		// Pump SWT events for 1s to let workspace jobs settle
		pumpEvents(1000);
	}

	private IProject resolveOrCreateResourcesProject(IDeveloperServoyModel model) throws Exception
	{
		// 1. Use active resources project
		com.servoy.eclipse.model.nature.ServoyResourcesProject activeRes = model.getActiveResourcesProject();
		if (activeRes != null) return activeRes.getProject();

		// 2. Find any existing resources project
		com.servoy.eclipse.model.nature.ServoyResourcesProject[] allRes = model.getResourceProjects();
		if (allRes != null && allRes.length > 0)
		{
			// prefer one named "resources"
			for (com.servoy.eclipse.model.nature.ServoyResourcesProject r : allRes)
			{
				if ("resources".equals(r.getProject().getName())) return r.getProject();
			}
			return allRes[0].getProject();
		}

		// 3. Create a new resources project
		String resName = "resources";
		int counter = 1;
		while (ResourcesPlugin.getWorkspace().getRoot().getProject(resName).exists())
		{
			resName = "resources" + counter++;
		}
		final String finalResName = resName;
		IProject[] created = new IProject[1];
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
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

	private String doActivateSolution(String solutionName, boolean refreshAndWait)
		throws InterruptedException
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		if (refreshAndWait)
		{
			model.refreshServoyProjects();
			pumpEvents(1000);
		}

		ServoyProject project = model.getServoyProject(solutionName);
		if (project == null)
		{
			return "Error: Solution '" + solutionName + "' not found in the workspace.";
		}

		ServoyProject activeProject = model.getActiveProject();
		if (activeProject != null && activeProject.getProject().getName().equals(solutionName))
		{
			return "Solution '" + solutionName + "' is already the active solution.";
		}

		String previousName = activeProject != null ? activeProject.getProject().getName() : "(none)";

		model.setActiveProject(project, true);

		if (refreshAndWait)
		{
			long activateEnd = System.currentTimeMillis() + ACTIVATE_SETTLE_MS;
			Display display = Display.getDefault();
			if (display != null && display.getThread() == Thread.currentThread())
			{
				while (System.currentTimeMillis() < activateEnd && model.getActiveProject() == null)
					display.readAndDispatch();
			}
			else
			{
				while (System.currentTimeMillis() < activateEnd && model.getActiveProject() == null)
					Thread.sleep(200);
			}
		}

		return "Solution '" + solutionName + "' activated successfully. Previous active solution: " + previousName + ".";
	}

	// -------------------------------------------------------------------------
	// Step 3: Create Servoy artifacts via persistence API
	// -------------------------------------------------------------------------

	@SuppressWarnings("restriction")
	private void createServoyArtifacts(String solutionName) throws RepositoryException
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject servoyProject = model.getServoyProject(solutionName);
		if (servoyProject == null)
		{
			throw new RepositoryException("ServoyProject not found: " + solutionName);
		}

		// Create the Solution root object via EclipseRepository — this writes rootmetadata.obj + solution_settings.obj
		com.servoy.eclipse.model.repository.EclipseRepository repository =
			(com.servoy.eclipse.model.repository.EclipseRepository)com.servoy.j2db.server.shared.ApplicationServerRegistry.get().getDeveloperRepository();

		Solution solution = (Solution)repository.createNewRootObject(solutionName, IRepository.SOLUTIONS, UUID.randomUUID());
		solution.setSolutionType(SolutionMetaData.NG_CLIENT_ONLY);
		solution.setVersion("1.0");
		// Note: updateRootObject will be called once at the end after all artifacts are created

		// Reload editing solution from the newly created root object
		model.refreshServoyProjects();
		servoyProject = model.getServoyProject(solutionName);
		if (servoyProject == null)
		{
			throw new RepositoryException("ServoyProject not found after refresh: " + solutionName);
		}

		solution = servoyProject.getEditingSolution();
		if (solution == null)
		{
			throw new RepositoryException("Editing solution not available for: " + solutionName);
		}

		ScriptNameValidator scriptValidator = new ScriptNameValidator();

		// --- Media files (theme, manifest, icon) via persistence API ---
		Media solutionLess = solution.createNewMedia(scriptValidator, solutionName + ".less");
		solutionLess.setMimeType("text/css");
		solutionLess.setPermMediaData(ThemeResourceLoader.getDefaultSolutionLess());

		Media solutionPropsLess = solution.createNewMedia(scriptValidator, ThemeResourceLoader.SOLUTION_PROPERTIES_LESS);
		solutionPropsLess.setMimeType("text/css");
		solutionPropsLess.setPermMediaData(ThemeResourceLoader.getCustomProperties());

		Media variantsJson = solution.createNewMedia(scriptValidator, ThemeResourceLoader.VARIANTS_JSON);
		variantsJson.setMimeType("text/css");
		variantsJson.setPermMediaData(ThemeResourceLoader.getVariantsFile());

		Media manifestJson = solution.createNewMedia(scriptValidator, CreateMediaWebAppManifest.FILE_NAME);
		manifestJson.setMimeType("text/css");
		manifestJson.setPermMediaData(CreateMediaWebAppManifest.createManifest(solutionName));

		try
		{
			Media webappIcon = solution.createNewMedia(scriptValidator, CreateMediaWebAppManifest.ICON_NAME);
			webappIcon.setMimeType("image/png");
			webappIcon.setPermMediaData(CreateMediaWebAppManifest.getIcon());
		}
		catch (IOException e)
		{
			ServoyLog.logWarning("createTestSolution: could not load webapp icon", e);
		}

		// Set stylesheet on solution
		solution.setStyleSheetID(solutionLess.getUUID().toString());
		solution.setVersion("1.0");

		// --- CSS-position form ---
		ScriptNameValidator formValidator = new ScriptNameValidator(servoyProject.getEditingFlattenedSolution());

		Form cssForm = solution.createNewForm(formValidator, null, "testForm", null, true, null);
		cssForm.createNewPart(Part.BODY, 480);
		cssForm.setUseCssPosition(Boolean.TRUE);
		cssForm.setNavigatorID(Form.NAVIGATOR_NONE);
		cssForm.setEncapsulation(DEFAULT_ENCAPSULATION);

		ScriptMethod cssOnLoad = cssForm.createNewScriptMethod(formValidator, "onLoad");
		cssOnLoad.setDeclaration(MethodTemplate.DEFAULT_TEMPLATE.getMethodDeclaration(
			"onLoad", "// form loaded", null));

		ScriptMethod testMethod = cssForm.createNewScriptMethod(formValidator, "testMethod");
		testMethod.setDeclaration(MethodTemplate.DEFAULT_TEMPLATE.getMethodDeclaration(
			"testMethod", "return 'processed: ' + input;", null));

		// --- Responsive form ---
		Form responsiveForm = solution.createNewForm(formValidator, null, "testResponsiveForm", null, true, null);
		responsiveForm.setResponsiveLayout(true);
		responsiveForm.setNavigatorID(Form.NAVIGATOR_NONE);
		responsiveForm.setEncapsulation(DEFAULT_ENCAPSULATION);

		ScriptMethod responsiveOnLoad = responsiveForm.createNewScriptMethod(formValidator, "onLoad");
		responsiveOnLoad.setDeclaration(MethodTemplate.DEFAULT_TEMPLATE.getMethodDeclaration(
			"onLoad", "// responsive form loaded", null));

		// --- Scope (testScope) ---
		ScriptMethod helperMethod = solution.createNewGlobalScriptMethod(formValidator, "testScope", "helperMethod");
		helperMethod.setDeclaration(MethodTemplate.DEFAULT_TEMPLATE.getMethodDeclaration(
			"helperMethod", "return value ? value.trim() : '';", null));

		ScriptMethod testScopeMethod = solution.createNewGlobalScriptMethod(formValidator, "testScope", "testScopeMethod");
		testScopeMethod.setDeclaration(MethodTemplate.DEFAULT_TEMPLATE.getMethodDeclaration(
			"testScopeMethod", "return true;", null));

		// Set firstFormID
		solution.setFirstFormID(cssForm.getUUID().toString());

		// --- Save all to disk — single updateRootObject at the end ---
		java.util.List<IPersist> toSave = new java.util.ArrayList<>();
		toSave.add(solution);
		toSave.add(solutionLess);
		toSave.add(solutionPropsLess);
		toSave.add(variantsJson);
		toSave.add(manifestJson);
		toSave.add(cssForm);
		toSave.add(responsiveForm);
		toSave.add(helperMethod);
		toSave.add(testScopeMethod);
		servoyProject.saveEditingSolutionNodes(toSave.toArray(new IPersist[0]), true);
		repository.updateRootObject(solution);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private void pumpEvents(long ms) throws InterruptedException
	{
		Display display = Display.getDefault();
		long end = System.currentTimeMillis() + ms;
		if (display != null && display.getThread() == Thread.currentThread())
		{
			while (System.currentTimeMillis() < end)
				display.readAndDispatch();
		}
		else
		{
			Thread.sleep(ms);
		}
	}

	private void copyNgPackages(IFolder ngFolder, org.eclipse.core.runtime.IProgressMonitor monitor)
		throws CoreException
	{
		// Read directly from wizardpackages/ on disk — the source of truth populated at Servoy Developer startup.
		File wizardPackagesDir = new File(
			com.servoy.eclipse.ui.Activator.getDefault().getStateLocation().toFile(), "wizardpackages");

		if (!wizardPackagesDir.exists())
		{
			ServoyLog.logWarning("createTestSolution: wizardpackages folder not found at " + wizardPackagesDir, null);
			return;
		}

		for (String name : NG_PACKAGES)
		{
			File packageFile = null;
			for (File f : wizardPackagesDir.listFiles())
			{
				if (f.isFile() && f.getName().startsWith(name + "_"))
				{
					packageFile = f;
					break;
				}
			}

			if (packageFile == null)
			{
				ServoyLog.logWarning("createTestSolution: package not found in wizardpackages: " + name, null);
				continue;
			}

			IFile destFile = ngFolder.getFile(name + ".zip");
			if (!destFile.exists())
			{
				try (InputStream is = new FileInputStream(packageFile))
				{
					destFile.create(is, true, monitor);
				}
				catch (IOException e)
				{
					ServoyLog.logError("createTestSolution: failed to copy " + name + ".zip", e);
				}
			}
		}
	}

	private void writeTextFile(IProject project, String relativePath, String content,
		org.eclipse.core.runtime.IProgressMonitor monitor) throws CoreException
	{
		IFile file = project.getFile(relativePath);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (file.exists())
		{
			file.setContents(new ByteArrayInputStream(bytes), true, false, monitor);
		}
		else
		{
			file.create(new ByteArrayInputStream(bytes), true, monitor);
		}
	}

	// -------------------------------------------------------------------------
	// syncDbiWithDatabase tool
	// -------------------------------------------------------------------------

	@Tool(name = "syncDbiWithDatabase",
		description = "Synchronizes the database schema with .dbi file definitions for a given server. Creates tables that exist in .dbi files but not in the DB, reports tables that exist in the DB but have no .dbi file, and for existing tables syncs columns (add/remove/update) to match the .dbi definitions. Call after git pulls or .dbi file changes to keep the database in sync.",
		type = "object")
	public String syncDbiWithDatabase(
		@ToolParam(name = "serverName", description = "Name of the database server to synchronize.", required = true) String serverName,
		@ToolParam(name = "tableName", description = "Optional table name to sync only a specific table. If not specified, syncs all tables.", required = false) String tableName)
	{
		if (serverName == null || serverName.isBlank())
		{
			return "{\"errors\":[\"serverName is required\"]}";
		}
		String filterTableName = (tableName != null && !tableName.isBlank()) ? tableName : null;

		IServerInternal server = (IServerInternal)ApplicationServerRegistry.get().getServerManager().getServer(serverName, false, false);
		if (server == null)
		{
			return "{\"errors\":[\"Server not found: " + escapeJson(serverName) + "\"]}";
		}

		DataModelManager dmm = ServoyModelManager.getServoyModelManager().getServoyModel().getDataModelManager();
		if (dmm == null)
		{
			return "{\"errors\":[\"DataModelManager not available\"]}";
		}

		List<String> tablesCreated = new ArrayList<>();
		List<String> orphanTables = new ArrayList<>();
		List<Map<String, Object>> tablesModified = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		try
		{
			dmm.setWritesEnabled(false);

			syncMissingTables(server, dmm, filterTableName, tablesCreated, errors);
			findOrphanTables(server, dmm, filterTableName, orphanTables);
			syncExistingTableColumns(server, dmm, filterTableName, tablesModified, errors);
		}
		catch (Exception e)
		{
			errors.add("Unexpected error: " + e.getMessage());
			ServoyLog.logError("syncDbiWithDatabase failed", e);
		}
		finally
		{
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
		List<String> tablesCreated, List<String> errors)
	{
		try
		{
			IFolder serverInfoFolder = dmm.getServerInformationFolder(server.getName());
			if (serverInfoFolder == null || !serverInfoFolder.exists()) return;

			Collection<String> existingTables = server.getTableAndViewNames(true);
			List<String> newTableNames = new ArrayList<>();

			serverInfoFolder.accept((IResourceVisitor)resource -> {
				if (resource.getType() != IResource.FILE) return true;
				String fileName = resource.getName();
				if (!fileName.endsWith(DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT)) return true;

				String tName = fileName.substring(0, fileName.length() - DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT.length());

				if (filterTableName != null && !filterTableName.equals(tName)) return true;
				if (tName.startsWith(DataModelManager.TEMP_UPPERCASE_PREFIX)) return true;
				if (!tName.equals(tName.toLowerCase())) return true;
				if (existingTables.contains(tName)) return true;

				try
				{
					IFile dbiFile = dmm.getDBIFile(server.getName(), tName);
					if (dbiFile == null || !dbiFile.exists()) return true;

					String dbiContent;
					try (InputStream is = dbiFile.getContents())
					{
						dbiContent = Utils.getTXTFileContent(is, Charset.forName("UTF8"));
					}

					if (dbiContent != null && !dbiContent.isBlank())
					{
						EclipseDatabaseUtils.createNewTableFromColumnInfo(server, tName, dbiContent, EclipseDatabaseUtils.UPDATE_NOW, false);
						newTableNames.add(tName);
						tablesCreated.add(tName);
					}
				}
				catch (Exception e)
				{
					errors.add("Failed to create table '" + tName + "': " + e.getMessage());
					ServoyLog.logWarning("syncDbiWithDatabase: failed to create table " + tName, e);
				}
				return true;
			}, IResource.DEPTH_ONE, IResource.NONE);

			if (!newTableNames.isEmpty())
			{
				TableChangeHandler.getInstance().fireTablesAdded(server, newTableNames.toArray(new String[0]));
			}
		}
		catch (Exception e)
		{
			errors.add("Phase 1 error: " + e.getMessage());
			ServoyLog.logError("syncDbiWithDatabase phase 1 failed", e);
		}
	}

	private void findOrphanTables(IServerInternal server, DataModelManager dmm, String filterTableName,
		List<String> orphanTables)
	{
		try
		{
			IFolder serverInfoFolder = dmm.getServerInformationFolder(server.getName());
			if (serverInfoFolder == null || !serverInfoFolder.exists()) return;

			Collection<String> existingTables = server.getTableAndViewNames(true);
			for (String tName : existingTables)
			{
				if (filterTableName != null && !filterTableName.equals(tName)) continue;
				IFile dbiFile = serverInfoFolder.getFile(tName + DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT);
				if (!dbiFile.exists())
				{
					orphanTables.add(tName);
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logWarning("syncDbiWithDatabase: findOrphanTables failed", e);
		}
	}

	private void syncExistingTableColumns(IServerInternal server, DataModelManager dmm, String filterTableName,
		List<Map<String, Object>> tablesModified, List<String> errors)
	{
		try
		{
			Collection<String> existingTables = server.getTableAndViewNames(true);
			IFolder serverInfoFolder = dmm.getServerInformationFolder(server.getName());
			if (serverInfoFolder == null || !serverInfoFolder.exists()) return;

			for (String tName : existingTables)
			{
				if (filterTableName != null && !filterTableName.equals(tName)) continue;

				IFile dbiFile = serverInfoFolder.getFile(tName + DataModelManager.COLUMN_INFO_FILE_EXTENSION_WITH_DOT);
				if (!dbiFile.exists()) continue;

				try
				{
					String dbiContent;
					try (InputStream is = dbiFile.getContents())
					{
						dbiContent = Utils.getTXTFileContent(is, Charset.forName("UTF8"));
					}
					if (dbiContent == null || dbiContent.isBlank()) continue;

					TableDef tableDef = DatabaseUtils.deserializeTableInfo(dbiContent);
					if (tableDef == null) continue;

					ITable table = server.getTable(tName);
					if (table == null) continue;

					Map<String, Object> tableInfo = syncTableColumns(server, dmm, table, tableDef, errors);
					if (tableInfo != null)
					{
						tablesModified.add(tableInfo);
					}
				}
				catch (Exception e)
				{
					errors.add("Failed to sync columns for '" + tName + "': " + e.getMessage());
					ServoyLog.logWarning("syncDbiWithDatabase: failed to sync " + tName, e);
				}
			}
		}
		catch (Exception e)
		{
			errors.add("Phase 3 error: " + e.getMessage());
			ServoyLog.logError("syncDbiWithDatabase phase 3 failed", e);
		}
	}

	@SuppressWarnings("restriction")
	private Map<String, Object> syncTableColumns(IServerInternal server, DataModelManager dmm, ITable table,
		TableDef tableDef, List<String> errors)
	{
		List<String> columnsAdded = new ArrayList<>();
		List<String> columnsRemoved = new ArrayList<>();
		List<String> columnsUpdated = new ArrayList<>();
		IValidateName validator = createLenientValidator();

		try
		{
			Map<String, Column> existingColumns = new HashMap<>();
			for (Column col : table.getColumns())
			{
				existingColumns.put(col.getName(), col);
			}

			Map<String, ColumnInfoDef> dbiColumns = new HashMap<>();
			if (tableDef.columnInfoDefSet != null)
			{
				for (ColumnInfoDef cid : tableDef.columnInfoDefSet)
				{
					dbiColumns.put(cid.name, cid);
				}
			}

			for (Map.Entry<String, ColumnInfoDef> entry : dbiColumns.entrySet())
			{
				String colName = entry.getKey();
				ColumnInfoDef cid = entry.getValue();
				Column existingCol = existingColumns.get(colName);

				if (existingCol == null)
				{
					Column newCol = table.createNewColumn(validator, cid.name, cid.columnType);
					if (newCol != null)
					{
						if ((cid.flags & IBaseColumn.PK_COLUMN) != 0) newCol.setDatabasePK(true);
						newCol.setFlags(cid.flags);
						newCol.setAllowNull(cid.allowNull);
						int seqType = cid.autoEnterSubType;
						if (seqType > 0 && !server.supportsSequenceType(seqType, null))
						{
							seqType = ColumnInfo.SERVOY_SEQUENCE;
						}
						newCol.setSequenceType(seqType);
						columnsAdded.add(colName);
					}
				}
				else if (!Column.isColumnInfoCompatible(existingCol.getColumnType(), cid.columnType, true))
				{
					table.removeColumn(existingCol);
					Column newCol = table.createNewColumn(validator, cid.name, cid.columnType);
					if (newCol != null)
					{
						if ((cid.flags & IBaseColumn.PK_COLUMN) != 0) newCol.setDatabasePK(true);
						newCol.setFlags(cid.flags);
						newCol.setAllowNull(cid.allowNull);
						int seqType = cid.autoEnterSubType;
						if (seqType > 0 && !server.supportsSequenceType(seqType, null))
						{
							seqType = ColumnInfo.SERVOY_SEQUENCE;
						}
						newCol.setSequenceType(seqType);
						columnsUpdated.add(colName);
					}
				}
			}

			for (Map.Entry<String, Column> entry : existingColumns.entrySet())
			{
				if (!dbiColumns.containsKey(entry.getKey()))
				{
					table.removeColumn(entry.getValue());
					columnsRemoved.add(entry.getKey());
				}
			}

			if (!columnsAdded.isEmpty() || !columnsRemoved.isEmpty() || !columnsUpdated.isEmpty())
			{
				server.syncTableObjWithDB(table, false, true);
				dmm.loadAllColumnInfo(table);

				Map<String, Object> result = new HashMap<>();
				result.put("name", table.getName());
				result.put("columnsAdded", columnsAdded);
				result.put("columnsRemoved", columnsRemoved);
				result.put("columnsUpdated", columnsUpdated);
				return result;
			}
		}
		catch (Exception e)
		{
			errors.add("Error syncing columns for '" + table.getName() + "': " + e.getMessage());
			ServoyLog.logWarning("syncDbiWithDatabase: syncTableColumns failed for " + table.getName(), e);
		}
		return null;
	}

	private IValidateName createLenientValidator()
	{
		return new IValidateName()
		{
			@Override
			public void checkName(String nameToCheck, UUID skip_element_uuid, ValidatorSearchContext searchContext, boolean sqlRelated) throws RepositoryException
			{
				try
				{
					new ScriptNameValidator().checkName(nameToCheck, skip_element_uuid, searchContext, sqlRelated);
				}
				catch (RepositoryException e)
				{
					ServoyLog.logWarning("syncDbiWithDatabase: name validation warning for '" + nameToCheck + "': " + e.getMessage(), null);
				}
			}
		};
	}

	private String toJsonArray(List<String> list)
	{
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++)
		{
			if (i > 0) sb.append(",");
			sb.append("\"").append(escapeJson(list.get(i))).append("\"");
		}
		sb.append("]");
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private String toJsonModifiedArray(List<Map<String, Object>> list)
	{
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++)
		{
			if (i > 0) sb.append(",");
			Map<String, Object> entry = list.get(i);
			sb.append("{\"name\":\"").append(escapeJson((String)entry.get("name"))).append("\"");
			sb.append(",\"columnsAdded\":").append(toJsonArray((List<String>)entry.get("columnsAdded")));
			sb.append(",\"columnsRemoved\":").append(toJsonArray((List<String>)entry.get("columnsRemoved")));
			sb.append(",\"columnsUpdated\":").append(toJsonArray((List<String>)entry.get("columnsUpdated")));
			sb.append("}");
		}
		sb.append("]");
		return sb.toString();
	}

	private String escapeJson(String s)
	{
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}

	// -------------------------------------------------------------------------
	// activateSolution MCP tool
	// -------------------------------------------------------------------------

	@Tool(name = "activateSolution",
		description = "Activates a Servoy solution as the active solution in the Developer IDE. "
			+ "This loads the solution and its modules, and triggers a workspace build.",
		type = "object")
	public String activateSolution(
		@ToolParam(name = "solutionName", description = "The name of the solution to activate", required = true) String solutionName)
	{
		try
		{
			return doActivateSolution(solutionName, false);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return "Error: activation interrupted.";
		}
	}
}
