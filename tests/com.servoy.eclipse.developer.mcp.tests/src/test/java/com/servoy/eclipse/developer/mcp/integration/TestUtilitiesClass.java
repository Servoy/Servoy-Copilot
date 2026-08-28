package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ngclient.ui.CopySourceFolderAction;
import com.servoy.eclipse.ngclient.ui.NodeFolderCreatorJob;
import com.servoy.eclipse.ui.views.solutionexplorer.actions.RenameSolutionAction;
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.UUID;

public class TestUtilitiesClass {

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;
	private static final long TITANIUM_BUILD_SETTLE_MS = 300_000;

	private static Boolean appServerAvailableCache;
	protected String testSolutionName;
	private String servoyResourcesProjectName;
	protected String solutionUUID;
	
	protected TestUtilitiesClass(String testSolutionName, String servoyResourcesProjectName) {
		this.testSolutionName = testSolutionName;
		this.servoyResourcesProjectName = servoyResourcesProjectName;
		this.solutionUUID = UUID.randomUUID().toString();
	}

	protected static void waitForAppServer() throws InterruptedException
	{
		if (appServerAvailableCache == null)
		{
			long deadline = System.currentTimeMillis() + APP_SERVER_POLL_MS;
			while (!ApplicationServerRegistry.exists() && System.currentTimeMillis() < deadline)
				Thread.sleep(500);
			appServerAvailableCache = ApplicationServerRegistry.exists();
		}
		assertTrue("Servoy application server not started - skipping", appServerAvailableCache);
	}

	protected void ensureTestSolutionInWorkspace(String nameOfModules[], BiConsumer<IProject, IProgressMonitor> setupSolutionInternals) throws Exception
	{
		ensureSolutionInWorkspace(testSolutionName, solutionUUID, servoyResourcesProjectName, nameOfModules, setupSolutionInternals);
	}
	
	/**
	 * @param nameOfModules can be null, or a list of modules (make sure those projects already exist in the workspace) to include as IProject refs & solution modules in the solution_settings.obj file
	 */
	protected static void ensureSolutionInWorkspace(String solutionName, String solutionUUID,
			String resPrjName, String nameOfModules[], BiConsumer<IProject, IProgressMonitor> setupSolutionInternals) throws Exception
	{
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable)monitor -> {
			IProject res = ResourcesPlugin.getWorkspace().getRoot().getProject(resPrjName);
			if (!res.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(resPrjName);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyResources" });
				res.create(d, monitor);
			}
			if (!res.isOpen()) res.open(monitor);

			IProject sol = ResourcesPlugin.getWorkspace().getRoot().getProject(solutionName);
			boolean initializeIt = false;
			if (!sol.exists())
			{
				IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(solutionName);
				d.setNatureIds(new String[] { "com.servoy.eclipse.core.ServoyProject",
				"org.eclipse.dltk.javascript.core.nature" });
				ICommand sc = d.newCommand();
				sc.setBuilderName("org.eclipse.dltk.core.scriptbuilder");
				ICommand sb = d.newCommand();
				sb.setBuilderName("com.servoy.eclipse.core.servoyBuilder");
				d.setBuildSpec(new ICommand[] { sc, sb });
				IProject[] refPrjs = new IProject[(nameOfModules != null ? nameOfModules.length : 0) + 1];
				refPrjs[0] = res;
				if (nameOfModules != null) for (int i = 0; i < nameOfModules.length; i++) {
					IProject modulePrj = ResourcesPlugin.getWorkspace().getRoot().getProject(nameOfModules[i]);
					if (modulePrj == null) fail("Please make sure that the module with name '" + nameOfModules[i] + "' is created before calling this method.");
					refPrjs[i + 1] = modulePrj; 
				}
				d.setReferencedProjects(refPrjs);
				sol.create(d, monitor);
				
				initializeIt = true;
			}
			if (!sol.isOpen()) {
				sol.open(monitor);
				initializeIt = true;
			}

			if (initializeIt) {
				writeProjectFile(sol, "rootmetadata.obj",
						"fileVersion:" + AbstractRepository.repository_version + ",\nmustAuthenticate:false,\nname:\"" +
								solutionName + "\",\nsolutionType:1024,\ntypeid:43,\nuuid:\"" + solutionUUID + "\"\n",
								monitor);
				writeProjectFile(sol, "solution_settings.obj",
						(nameOfModules != null ?  "modulesNames:\"" 
								+ List.of(nameOfModules).stream().collect(Collectors.joining(","))
								+ "\",\n" : "")
						+ "typeid:43,\nuuid:\"" + solutionUUID + "\",\nversion:\"1.0\"\n", monitor);
				writeProjectFile(sol, ".buildpath",
						"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
						monitor);
				
				if (setupSolutionInternals != null) setupSolutionInternals.accept(sol, monitor);
			}
		}, new NullProgressMonitor());
	}

	protected void ensureActiveProject() throws Exception
	{
		ensureSolutionReadyAndOptionallyActive(testSolutionName, true);
	}

	protected static void ensureSolutionReadyAndOptionallyActive(String solName, boolean shouldBeTheActiveSolutionAsWell) throws Exception
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		model.refreshServoyProjects();
		ServoyProject servoyProjectForSolution[] = { null };
		pumpEventsUntil(2000, () -> {
			ServoyProject[] projects = model.getServoyProjects();
			assertTrue("No ServoyProject found in workspace", projects != null && projects.length > 0);

			for (ServoyProject p : projects)
			{
				if (solName.equals(p.getProject().getName()))
				{
					servoyProjectForSolution[0] = p;
					break;
				}
			}
			assertNotNull("Cannot find test solution's project in order to activate it", servoyProjectForSolution[0]);
		});

		if (shouldBeTheActiveSolutionAsWell) {
			ServoyProject active = model.getActiveProject();
			if (active == null || !solName.equals(active.getProject().getName()))
				model.setActiveProject(servoyProjectForSolution[0], true);
		}

		pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
			if (shouldBeTheActiveSolutionAsWell) assertNotNull("Active project is null", model.getActiveProject());
			
			// The background "Writing I18N files..." job (EclipseMessages) dereferences
			// servoyProject.getSolution() without a null-check. While our synthetic
			// solution is still loading, getSolution() can be null and that job NPEs,
			// popping a modal error dialog that blocks unattended runs. Wait for the
			// in-memory Solution to be resolved and let pending workspace jobs settle
			// BEFORE writing the script file (which triggers the builder that schedules
			// that job), so it finds a valid solution.

			assertNotNull("Solution should be loaded after activation", model.getActiveProject().getSolution());
			assertNotNull("Editing solution should be resolved after activation", model.getActiveProject().getEditingSolution());
			if (shouldBeTheActiveSolutionAsWell) assertEquals("Project '" + solName + "'was not activated sucessfully", solName, model.getActiveProject().getSolution().getName());
		});

		waitForWorkspaceBuildJobs();
	}

	protected static void writeProjectFile(IProject project, String fileName, String content,
			org.eclipse.core.runtime.IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException
	{
		IFile file = project.getFile(fileName);
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		if (file.exists())
			file.setContents(new java.io.ByteArrayInputStream(bytes), true, true, monitor);
		else {
			IContainer parent = file.getParent();
			if (parent instanceof IFolder && !parent.exists()) {
				createFolderHierarchy((IFolder) parent, monitor);
			}
			file.create(new java.io.ByteArrayInputStream(bytes), true, monitor);
		}
	}
	
	protected void writeProjectFileInWorkspaceRun(IProject project, String path, String content) throws CoreException {
		ResourcesPlugin.getWorkspace().run((IWorkspaceRunnable) monitor -> {
			writeProjectFile(project, path, content, monitor);
		}, new NullProgressMonitor());
	}

	private static void createFolderHierarchy(IFolder folder, IProgressMonitor monitor) throws CoreException {
		if (!folder.getParent().exists() && folder.getParent() instanceof IFolder) {
			createFolderHierarchy((IFolder) folder.getParent(), monitor);
		}
		if (!folder.exists()) {
			folder.create(true, true, monitor);
		}
	}

	protected static void pumpEventsUntil(long forMaxMs, Runnable untilAssertionsPass) {
		boolean success = false;
		try
		{
			Display display = Display.getDefault();
			long end = System.currentTimeMillis() + forMaxMs;
			if (display.getThread() == Thread.currentThread())
			{
				while (!(success = justCheckCall(untilAssertionsPass)) && System.currentTimeMillis() < end)
					display.readAndDispatch();
			}
			else
			{
				while (!(success = justCheckCall(untilAssertionsPass)) && System.currentTimeMillis() < end)
					Thread.sleep(100);
			}
			if (!success) untilAssertionsPass.run();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private static boolean justCheckCall(Runnable untilAssertionsPass) {
		try {
			untilAssertionsPass.run();
			return true;
		} catch (AssertionError e) {
			return false;
		}
	}

	/**
	 * Deletes the named projects from the workspace (with their contents), ignoring
	 * projects that do not exist. Call this from a {@code @BeforeClass} method so
	 * that each test class always starts with a fresh project state.
	 */
	public static void deleteProjects(String... projectNames) throws CoreException
	{
		NullProgressMonitor monitor = new NullProgressMonitor();
		for (String name : projectNames)
		{
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
			if (project.exists())
			{
				if (!project.isOpen()) project.open(monitor);
				project.delete(true, true, monitor);
			}
		}
	}

	protected static void waitForWorkspaceBuildJobs() {
		org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
		// Pump the SWT loop while the auto-build and any scheduled workspace jobs run.
		pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
			try {
				jm.join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
				jm.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, null);
				jm.join(CopySourceFolderAction.JOB_FAMILY, null);
			} catch (Exception e) {
				// ignore - best-effort drain
			}
			if (jm.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length != 0
					|| jm.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length != 0
					|| jm.find(CopySourceFolderAction.JOB_FAMILY).length != 0) {
				fail("Build jobs still running after " + (ACTIVATE_SETTLE_MS / 1000) + " sec.");
			}
		});
	}

	protected static void waitForTitaniumuildJobs() {
		org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
		// Pump the SWT loop while the auto-build and any scheduled workspace jobs run.
		pumpEventsUntil(TITANIUM_BUILD_SETTLE_MS, () -> {
			try {
				jm.join(CopySourceFolderAction.JOB_FAMILY, null);
			} catch (Exception e) {
				// ignore - best-effort drain
			}
			if (jm.find(CopySourceFolderAction.JOB_FAMILY).length != 0) {
				fail("Build jobs still running after " + (TITANIUM_BUILD_SETTLE_MS / 1000) + " sec.");
			}
		});
	}
	
}
