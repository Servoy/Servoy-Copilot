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
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.developer.mcp.annotations.McpServer;
import com.servoy.eclipse.developer.mcp.annotations.Tool;
import com.servoy.eclipse.developer.mcp.annotations.ToolParam;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.views.solutionexplorer.actions.CreateMediaWebAppManifest;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IRepository;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.MethodTemplate;
import com.servoy.j2db.persistence.Part;
import com.servoy.j2db.persistence.PersistEncapsulation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.ScriptNameValidator;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.SolutionMetaData;
import com.servoy.j2db.server.ngclient.less.resources.ThemeResourceLoader;
import com.servoy.j2db.util.UUID;

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
			createEclipseProjects(name);
			createServoyArtifacts(name);

			if (doActivate)
			{
				activateSolution(name);
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
	// Step 2: Activate solution so getEditingSolution() works
	// -------------------------------------------------------------------------

	private void activateSolution(String solutionName) throws InterruptedException
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		model.refreshServoyProjects();
		pumpEvents(1000);

		ServoyProject toActivate = null;
		for (ServoyProject p : model.getServoyProjects())
		{
			if (solutionName.equals(p.getProject().getName()))
			{
				toActivate = p;
				break;
			}
		}

		if (toActivate == null)
		{
			ServoyLog.logWarning("createTestSolution: project '" + solutionName + "' not found in Servoy model after refresh", null);
			return;
		}

		model.setActiveProject(toActivate, true);

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
}
