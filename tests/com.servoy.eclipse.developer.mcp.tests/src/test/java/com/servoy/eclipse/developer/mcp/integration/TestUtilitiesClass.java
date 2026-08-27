package com.servoy.eclipse.developer.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

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
import com.servoy.j2db.persistence.AbstractRepository;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;
import com.servoy.j2db.util.UUID;

public class TestUtilitiesClass {

	private static final long APP_SERVER_POLL_MS = 15_000;
	private static final long ACTIVATE_SETTLE_MS = 10_000;

	private static Boolean appServerAvailableCache;
	private String testSolutionName;
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

	protected void ensureTestSolutionInWorkspace(BiConsumer<IProject, IProgressMonitor> setupSolutionInternals) throws Exception
	{
		ensureSolutionInWorkspace(testSolutionName, solutionUUID, servoyResourcesProjectName, setupSolutionInternals);
	}
	
	protected static void ensureSolutionInWorkspace(String solutionName, String solutionUUID,
			String resPrjName, BiConsumer<IProject, IProgressMonitor> setupSolutionInternals) throws Exception
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
				d.setReferencedProjects(new IProject[] { res });
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
						"typeid:43,\nuuid:\"" + solutionUUID + "\",\nversion:\"1.0\"\n", monitor);
				writeProjectFile(sol, ".buildpath",
						"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<buildpath>\n\t<buildpathentry excluding=\".stp/|medias/\" kind=\"src\" path=\"\"/>\n</buildpath>\n",
						monitor);
				
				if (setupSolutionInternals != null) setupSolutionInternals.accept(sol, monitor);
			}
		}, new NullProgressMonitor());
	}

	protected void ensureActiveProject() throws Exception
	{
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();

		ServoyProject active = model.getActiveProject();
		if (active != null && testSolutionName.equals(active.getProject().getName()))
			return;

		model.refreshServoyProjects();
		ServoyProject toActivate[] = { null };
		pumpEventsUntil(2000, () -> {
			ServoyProject[] projects = model.getServoyProjects();
			assertTrue("No ServoyProject found in workspace", projects != null && projects.length > 0);

			for (ServoyProject p : projects)
			{
				if (testSolutionName.equals(p.getProject().getName()))
				{
					toActivate[0] = p;
					break;
				}
			}
			assertNotNull("Cannot find test solution's project in order to activate it", toActivate[0]);
		});

		try
		{
			model.setActiveProject(toActivate[0], true);
		}
		catch (Exception e)
		{
			// caught by assertNotNull below
		}

		pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
			assertNotNull("Active project is null", model.getActiveProject());
			
			// The background "Writing I18N files..." job (EclipseMessages) dereferences
			// servoyProject.getSolution() without a null-check. While our synthetic
			// solution is still loading, getSolution() can be null and that job NPEs,
			// popping a modal error dialog that blocks unattended runs. Wait for the
			// in-memory Solution to be resolved and let pending workspace jobs settle
			// BEFORE writing the script file (which triggers the builder that schedules
			// that job), so it finds a valid solution.

			assertNotNull("Solution should be loaded after activation", model.getActiveProject().getSolution());
			assertNotNull("Editing solution should be resolved after activation", model.getActiveProject().getEditingSolution());
			assertEquals("Project '" + testSolutionName + "'was not activated sucessfully", testSolutionName, model.getActiveProject().getSolution().getName());
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

	protected void pumpEventsUntil(long forMaxMs, Runnable untilAssertionsPass) {
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
	
	private boolean justCheckCall(Runnable untilAssertionsPass) {
		try {
			untilAssertionsPass.run();
			return true;
		} catch (AssertionError e) {
			return false;
		}
	}

	protected void waitForWorkspaceBuildJobs() {
		org.eclipse.core.runtime.jobs.IJobManager jm = org.eclipse.core.runtime.jobs.Job.getJobManager();
		// Pump the SWT loop while the auto-build and any scheduled workspace jobs run.
		pumpEventsUntil(ACTIVATE_SETTLE_MS, () -> {
			try {
				jm.join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
				jm.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, null);
			} catch (Exception e) {
				// ignore - best-effort drain
			}
			if (jm.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length != 0
					|| jm.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length != 0) {
				fail("Build jobs still running after " + (ACTIVATE_SETTLE_MS / 1000) + " sec.");
			}
		});
	}

}
