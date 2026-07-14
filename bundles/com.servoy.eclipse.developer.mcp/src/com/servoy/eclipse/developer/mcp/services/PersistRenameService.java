package com.servoy.eclipse.developer.mcp.services;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.ISupportUpdateableName;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Menu;
import com.servoy.j2db.persistence.MenuItem;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.server.shared.ApplicationServerRegistry;

/**
 * Service for renaming Servoy persists (forms, relations, valuelists, scopes,
 * media, menus, solutions). Handles the different rename patterns for each
 * persist type.
 */
public class PersistRenameService {
	public String renamePersist(String persistType, String oldName, String newName, String solutionName) {
		if (persistType == null || persistType.isBlank())
			return "Error: persistType is required.";
		if (oldName == null || oldName.isBlank())
			return "Error: oldName is required.";
		if (newName == null || newName.isBlank())
			return "Error: newName is required.";
		if (oldName.equals(newName))
			return "Error: oldName and newName are the same.";

		try {
			ServoyProject project = resolveProject(solutionName);
			if (project == null)
				return "Error: Solution '" + (solutionName != null ? solutionName : "active") + "' not found.";

			switch (persistType.toLowerCase().trim()) {
			case "form":
				return renameForm(oldName, newName, project);
			case "relation":
				return renameRelation(oldName, newName, project);
			case "valuelist":
				return renameValueList(oldName, newName, project);
			case "menu":
				return renameMenu(oldName, newName, project);
			case "menuitem":
				return renameMenuItem(oldName, newName, project);
			case "media":
				return renameMedia(oldName, newName, project);
			case "scope":
				return renameScope(oldName, newName, project);
			case "solution":
				return renameSolution(oldName, newName);
			default:
				return "Error: Unsupported persistType '" + persistType
						+ "'. Supported: form, relation, valuelist, menu, menuitem, media, scope, solution.";
			}
		} catch (Exception e) {
			ServoyLog.logError("renamePersist failed", e);
			return "Error: " + e.getMessage();
		}
	}

	public String renameForm(String oldName, String newName, ServoyProject project) throws RepositoryException {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Form form = solution.getForm(oldName);
		if (form == null)
			return "Error: Form '" + oldName + "' not found in solution '" + project.getProject().getName() + "'.";

		String result = doUpdateName(form, newName, project, "Form");

		if (!result.startsWith("Error")) {
			renameFormSpecFiles(oldName, newName, project);
		}

		return result;
	}

	private void renameFormSpecFiles(String oldFormName, String newFormName, ServoyProject project) {
		try {
			FormSpecGenerator specGenerator = new FormSpecGenerator();

			// Rename .spec.js (setUp/tearDown) — lives in cy-form-spec/ outside the project
			java.nio.file.Path oldSpecJs = specGenerator.getSetupFilePath(oldFormName);
			java.nio.file.Path newSpecJs = specGenerator.getSetupFilePath(newFormName);
			if (oldSpecJs != null && newSpecJs != null && java.nio.file.Files.exists(oldSpecJs)
					&& !java.nio.file.Files.exists(newSpecJs)) {
				java.nio.file.Path newSpecParent = newSpecJs.getParent();
				if (newSpecParent != null) {
					java.nio.file.Files.createDirectories(newSpecParent);
				}
				java.nio.file.Files.move(oldSpecJs, newSpecJs);
			}

			// Rename .spec.cy.js — lives in cy-form/ outside the project
			java.nio.file.Path oldSpecCy = specGenerator.getSpecFilePath(oldFormName);
			java.nio.file.Path newSpecCy = specGenerator.getSpecFilePath(newFormName);
			if (oldSpecCy != null && newSpecCy != null && java.nio.file.Files.exists(oldSpecCy)
					&& !java.nio.file.Files.exists(newSpecCy)) {
				java.nio.file.Path newSpecParent = newSpecCy.getParent();
				if (newSpecParent != null) {
					java.nio.file.Files.createDirectories(newSpecParent);
				}
				java.nio.file.Files.move(oldSpecCy, newSpecCy);
			}
		} catch (Exception e) {
			ServoyLog.logWarning("renameFormSpecFiles: " + e.getMessage(), e);
		}
	}

	public String renameRelation(String oldName, String newName, ServoyProject project) throws RepositoryException {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Relation relation = solution.getRelation(oldName);
		if (relation == null)
			return "Error: Relation '" + oldName + "' not found in solution '" + project.getProject().getName() + "'.";

		return doUpdateName(relation, newName, project, "Relation");
	}

	public String renameValueList(String oldName, String newName, ServoyProject project) throws RepositoryException {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		ValueList vl = solution.getValueList(oldName);
		if (vl == null)
			return "Error: ValueList '" + oldName + "' not found in solution '" + project.getProject().getName() + "'.";

		return doUpdateName(vl, newName, project, "ValueList");
	}

	public String renameMenu(String oldName, String newName, ServoyProject project) throws RepositoryException {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Menu menu = null;
		java.util.Iterator<Menu> menuIter = solution.getMenus(false);
		while (menuIter.hasNext()) {
			Menu m = menuIter.next();
			if (oldName.equals(m.getName())) {
				menu = m;
				break;
			}
		}
		if (menu == null)
			return "Error: Menu '" + oldName + "' not found in solution '" + project.getProject().getName() + "'.";

		return doUpdateName(menu, newName, project, "Menu");
	}

	public String renameMenuItem(String oldName, String newName, ServoyProject project) throws RepositoryException {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		MenuItem menuItem = null;
		java.util.Iterator<Menu> menuIter = solution.getMenus(false);
		while (menuIter.hasNext() && menuItem == null) {
			menuItem = findMenuItemRecursive(menuIter.next(), oldName);
		}
		if (menuItem == null)
			return "Error: MenuItem '" + oldName + "' not found in solution '" + project.getProject().getName() + "'.";

		return doUpdateName(menuItem, newName, project, "MenuItem");
	}

	private MenuItem findMenuItemRecursive(com.servoy.j2db.persistence.ISupportChilds parent, String name) {
		java.util.Iterator<IPersist> iter = parent.getAllObjects();
		while (iter.hasNext()) {
			IPersist child = iter.next();
			if (child instanceof MenuItem) {
				MenuItem item = (MenuItem) child;
				if (name.equals(item.getName()))
					return item;
				MenuItem found = findMenuItemRecursive(item, name);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	public String renameMedia(String oldName, String newName, ServoyProject project) {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Media media = null;
		java.util.Iterator<Media> mediaIter = solution.getMedias(false);
		while (mediaIter.hasNext()) {
			Media m = mediaIter.next();
			if (oldName.equals(m.getName())) {
				media = m;
				break;
			}
		}
		if (media == null)
			return "Error: Media '" + oldName + "' not found in solution '" + project.getProject().getName() + "'.";

		try {
			Media existingCheck = solution.getMedia(newName);
			if (existingCheck != null)
				return "Error: Media '" + newName + "' already exists.";

			media.setName(newName);
			project.saveEditingSolutionNodes(new IPersist[] { media }, true);
			return "Renamed media '" + oldName + "' to '" + newName + "' successfully.";
		} catch (Exception e) {
			return "Error renaming media: " + e.getMessage();
		}
	}

	public String renameScope(String oldName, String newName, ServoyProject project) {
		try {
			IProject eclipseProject = project.getProject();
			String oldFileName = oldName.endsWith(".js") ? oldName : oldName + ".js";
			String newFileName = newName.endsWith(".js") ? newName : newName + ".js";

			IFile oldFile = eclipseProject.getFile(oldFileName);
			if (!oldFile.exists())
				return "Error: Scope file '" + oldFileName + "' not found in project '" + eclipseProject.getName()
						+ "'.";

			IFile newFile = eclipseProject.getFile(newFileName);
			if (newFile.exists())
				return "Error: Scope file '" + newFileName + "' already exists in project '" + eclipseProject.getName()
						+ "'.";

			oldFile.move(eclipseProject.getFullPath().append(new Path(newFileName)), true, new NullProgressMonitor());

			return "Renamed scope '" + oldName + "' to '" + newName + "' successfully.";
		} catch (CoreException e) {
			return "Error renaming scope: " + e.getMessage();
		}
	}

	public String renameSolution(String oldName, String newName) {
		try {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject servoyProject = model.getServoyProject(oldName);
			if (servoyProject == null)
				return "Error: Solution '" + oldName + "' not found.";

			IProject eclipseProject = servoyProject.getProject();
			if (!eclipseProject.exists())
				return "Error: Eclipse project for '" + oldName + "' does not exist.";

			IProject existingNew = ResourcesPlugin.getWorkspace().getRoot().getProject(newName);
			if (existingNew.exists())
				return "Error: A project named '" + newName + "' already exists in the workspace.";

			Solution editingSolution = servoyProject.getEditingSolution();
			if (editingSolution == null)
				return "Error: Cannot get editing solution for '" + oldName + "'.";

			boolean wasActive = model.isSolutionActive(oldName);

			// Get validator BEFORE deactivating (it depends on the active flattened
			// solution)
			IValidateName validator = model.getNameValidator();

			// Step 1: Close all editors and deactivate (releases file handles + prevents
			// stale editor NPEs)
			if (wasActive) {
				org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
					try {
						org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
								.closeAllEditors(false);
					} catch (Exception e) {
						// ignore - editors may already be closed
					}
				});
				model.setActiveProject(null, false);
			}

			// Step 2: Run the rename inside a workspace runnable to hold the scheduling
			// rule
			// This defers resource change notifications until the operation completes
			final String[] result = new String[1];
			ResourcesPlugin.getWorkspace().run(monitor -> {
				try {
					// Refresh project to ensure clean state
					eclipseProject.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);

					// Update solution name in memory
					editingSolution.updateName(validator, newName);

					// Move project (rename directory on disk + workspace metadata)
					org.eclipse.core.resources.IProjectDescription description = eclipseProject.getDescription();
					description.setName(newName);
					description.setLocation(eclipseProject.getLocation().removeLastSegments(1).append(newName));
					eclipseProject.move(description, false, monitor);

					// Persist the renamed solution via repository
					com.servoy.eclipse.model.repository.EclipseRepository repository = (com.servoy.eclipse.model.repository.EclipseRepository) ApplicationServerRegistry
							.get().getDeveloperRepository();
					repository.updateNodes(new IPersist[] { editingSolution }, true);

					// Update module references in other solutions
					java.util.List<IPersist> toUpdate = new java.util.ArrayList<>();
					ServoyProject[] allProjects = model.getServoyProjects();
					if (allProjects != null) {
						for (ServoyProject sp : allProjects) {
							Solution sol = sp.getEditingSolution();
							if (sol == null)
								continue;
							String modules = sol.getModulesNames();
							if (modules == null || modules.isBlank())
								continue;

							String[] moduleList = modules.split(",");
							boolean changed = false;
							for (int i = 0; i < moduleList.length; i++) {
								if (oldName.equals(moduleList[i].trim())) {
									moduleList[i] = newName;
									changed = true;
								}
							}
							if (changed) {
								sol.setModulesNames(String.join(",", moduleList));
								toUpdate.add(sol);
							}
						}
					}
					if (!toUpdate.isEmpty()) {
						repository.updateNodes(toUpdate.toArray(new IPersist[0]), false);
					}

					result[0] = null;
				} catch (Exception e) {
					result[0] = e.getMessage();
				}
			}, ResourcesPlugin.getWorkspace().getRoot(), org.eclipse.core.resources.IWorkspace.AVOID_UPDATE,
					new NullProgressMonitor());

			if (result[0] != null)
				return "Error renaming solution: " + result[0];

			// Step 3: Reactivate if it was active (outside workspace runnable so listeners
			// can fire)
			if (wasActive) {
				com.servoy.eclipse.model.repository.EclipseRepository repository = (com.servoy.eclipse.model.repository.EclipseRepository) ApplicationServerRegistry
						.get().getDeveloperRepository();
				repository.flush();
				model.refreshServoyProjects();
				ServoyProject renamedProject = model.getServoyProject(newName);
				if (renamedProject != null) {
					model.setActiveProject(renamedProject, true);
				}
			}

			return "Renamed solution '" + oldName + "' to '" + newName + "' successfully.";
		} catch (Exception e) {
			ServoyLog.logError("renameSolution failed", e);
			return "Error renaming solution: " + e.getMessage();
		}
	}

	private String doUpdateName(IPersist persist, String newName, ServoyProject project, String typeName)
			throws RepositoryException {
		if (!(persist instanceof ISupportUpdateableName))
			return "Error: " + typeName + " does not support renaming.";

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		String oldName = ((com.servoy.j2db.persistence.ISupportName) persist).getName();
		((ISupportUpdateableName) persist).updateName(validator, newName);
		project.saveEditingSolutionNodes(new IPersist[] { persist }, true);

		return "Renamed " + typeName.toLowerCase() + " '" + oldName + "' to '" + newName + "' successfully.";
	}

	private ServoyProject resolveProject(String solutionName) {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		if (solutionName != null && !solutionName.isBlank()) {
			return model.getServoyProject(solutionName);
		}
		return model.getActiveProject();
	}
}
