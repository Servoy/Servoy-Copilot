package com.servoy.eclipse.developer.mcp.services;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.cypress.services.FormSpecGenerator;
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
			String solutionName = project.getSolution().getName();

			java.nio.file.Path oldSpecJs = specGenerator.findExistingSetupFile(oldFormName, solutionName);
			java.nio.file.Path newSpecJs = specGenerator.getSetupFilePath(newFormName, solutionName);
			if (oldSpecJs != null && newSpecJs != null && java.nio.file.Files.exists(oldSpecJs)
					&& !java.nio.file.Files.exists(newSpecJs)) {
				java.nio.file.Path newSpecParent = newSpecJs.getParent();
				if (newSpecParent != null) {
					java.nio.file.Files.createDirectories(newSpecParent);
				}
				java.nio.file.Files.move(oldSpecJs, newSpecJs);
			}

			java.nio.file.Path oldSpecCy = specGenerator.findExistingSpecFile(oldFormName, solutionName);
			java.nio.file.Path newSpecCy = specGenerator.getSpecFilePath(newFormName, solutionName);
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

	/**
	 * Renames a Servoy artifact or raw workspace file identified by
	 * {@code oldName}.
	 * <p>
	 * {@code oldName} may be a simple artifact name, a workspace-relative path
	 * (e.g. {@code "mySolution/forms/myForm.frm"}), a solution-relative path (e.g.
	 * {@code "forms/myForm"}), or an absolute filesystem path inside the workspace.
	 * {@code newName} must be a bare filename with no path separators.
	 * </p>
	 *
	 * @return a success or error message string; never throws
	 */
	public String renameByName(String oldName, String newName) {
		// Step 0 — Input validation
		if (oldName == null || oldName.isBlank())
			return "Error: oldName is required.";
		if (newName == null || newName.isBlank())
			return "Error: newName is required.";
		if (oldName.trim().equals(newName.trim()))
			return "Error: oldName and newName are the same.";
		if (newName.contains("/") || newName.contains("\\"))
			return "Error: newName must be a bare name, not a path.";

		boolean hasPathSeparator = oldName.contains("/") || oldName.contains("\\");
		boolean isAbsolute = false;
		try {
			isAbsolute = java.nio.file.Paths.get(oldName).isAbsolute();
		} catch (Exception ignored) {
		}

		if (hasPathSeparator || isAbsolute) {
			// PATH TRACK
			return renameByPath(oldName, newName);
		} else {
			// NAME TRACK
			return renameBySimpleName(oldName, newName);
		}
	}

	private String renameByPath(String oldName, String newName) {
		org.eclipse.core.resources.IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IFile file = null;

		// 1. Absolute filesystem path -> IFile via location URI
		try {
			if (java.nio.file.Paths.get(oldName).isAbsolute()) {
				IFile[] files = root.findFilesForLocationURI(java.nio.file.Paths.get(oldName).toUri());
				if (files.length > 0)
					file = files[0];
			}
		} catch (Exception ignored) {
		}

		// 2. Workspace-relative path (e.g. "mySolution/forms/myForm.frm")
		if (file == null) {
			try {
				IFile candidate = root.getFile(org.eclipse.core.runtime.IPath.fromPortableString(oldName));
				if (candidate.exists())
					file = candidate;
			} catch (Exception ignored) {
			}
		}

		// 3. Project-relative path against active project
		if (file == null) {
			IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
			ServoyProject active = model.getActiveProject();
			if (active != null) {
				// Try exact path first
				IFile candidate = active.getProject().getFile(oldName);
				if (candidate.exists()) {
					file = candidate;
				} else {
					// Probe known Servoy extensions when no extension was given
					// e.g. "forms/myForm" -> try "forms/myForm.frm" then "forms/myForm.js"
					String lowerPath = oldName.toLowerCase();
					String[] probeExtensions = null;
					if (lowerPath.startsWith("forms/") || lowerPath.startsWith("forms\\")) {
						probeExtensions = new String[] { ".frm", ".js" };
					} else if (lowerPath.startsWith("relations/") || lowerPath.startsWith("relations\\")) {
						probeExtensions = new String[] { ".rel" };
					} else if (lowerPath.startsWith("valuelists/") || lowerPath.startsWith("valuelists\\")) {
						probeExtensions = new String[] { ".val" };
					}
					if (probeExtensions != null) {
						for (String ext : probeExtensions) {
							IFile probed = active.getProject().getFile(oldName + ext);
							if (probed.exists()) {
								file = probed;
								break;
							}
						}
					}
				}
			}
		}

		if (file == null) {
			return "Error: File or artifact '" + oldName + "' not found. "
					+ "Provide a workspace-relative path (e.g. 'mySolution/forms/myForm.frm') or an absolute path inside the workspace.";
		}

		// Security check: file must belong to active solution or one of its modules.
		// If the project is not a Servoy project at all, skip the Servoy security check
		// and treat it as a raw file rename (same behaviour as the old
		// ServoyCoderServer.renameFile).
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		String fileProjectName = file.getProject().getName();
		ServoyProject fileServoyProject = model.getServoyProject(fileProjectName);

		if (fileServoyProject == null) {
			// Not a Servoy project â raw file rename, no solution-boundary restriction
			return rawFileRename(file, newName);
		}

		boolean allowed = false;
		ServoyProject activeProj = model.getActiveProject();
		if (activeProj != null) {
			if (activeProj.getProject().getName().equals(fileProjectName))
				allowed = true;
			if (!allowed && activeProj.getModules() != null) {
				for (com.servoy.j2db.persistence.Solution mod : activeProj.getModules()) {
					ServoyProject modProject = model.getServoyProject(mod.getName());
					if (modProject != null && modProject.getProject().getName().equals(fileProjectName)) {
						allowed = true;
						break;
					}
				}
			}
		}
		if (!allowed) {
			return "Error: Path is outside the active solution. Only files within the active solution or its modules may be renamed.";
		}

		// Derive artifact type from project-relative path and dispatch
		String projectRelPath = file.getProjectRelativePath().toPortableString();
		ServoyProject fileProject = fileServoyProject;

		try {
			if (projectRelPath.startsWith("forms/")) {
				String artifactName = file.getName().replaceAll("\\.(frm|js)$", "");
				return renameForm(artifactName, newName, fileProject);
			} else if (projectRelPath.startsWith("relations/")) {
				String artifactName = file.getName().replaceAll("\\.rel$", "");
				return renameRelation(artifactName, newName, fileProject);
			} else if (projectRelPath.startsWith("valuelists/")) {
				String artifactName = file.getName().replaceAll("\\.val$", "");
				return renameValueList(artifactName, newName, fileProject);
			} else if (projectRelPath.startsWith("medias/")) {
				return renameMedia(file.getName(), newName, fileProject);
			} else if (!projectRelPath.contains("/") && projectRelPath.endsWith(".js")) {
				String artifactName = file.getName().replaceAll("\\.js$", "");
				return renameScope(artifactName, newName, fileProject);
			} else {
				return rawFileRename(file, newName);
			}
		} catch (Exception e) {
			return "Error: " + e.getMessage();
		}
	}

	private String renameBySimpleName(String oldName, String newName) {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = model.getActiveProject();
		if (activeProject == null) {
			return "Error: No active solution found.";
		}

		// Build flattened project list: active solution + all modules.
		// NOTE: ServoyProject.getModules() already includes the solution itself as the
		// first element, so we must NOT add activeProject separately to avoid
		// duplicates.
		java.util.List<ServoyProject> flatProjects = new java.util.ArrayList<>();
		com.servoy.j2db.persistence.Solution[] modules = activeProject.getModules();
		if (modules != null) {
			for (com.servoy.j2db.persistence.Solution mod : modules) {
				ServoyProject modProject = model.getServoyProject(mod.getName());
				if (modProject != null && !flatProjects.contains(modProject))
					flatProjects.add(modProject);
			}
		}
		if (flatProjects.isEmpty()) {
			flatProjects.add(activeProject);
		}

		// Collect all matches across flattened solution
		// Each entry: [type, name, projectName, extra] where extra may be menu name for
		// menuitem
		java.util.List<String[]> matches = new java.util.ArrayList<>();

		// Check solution type first
		if (model.getServoyProject(oldName) != null) {
			matches.add(new String[] { "solution", oldName, oldName, null });
		}

		for (ServoyProject project : flatProjects) {
			Solution solution = null;
			try {
				solution = project.getEditingSolution();
			} catch (Exception e) {
				ServoyLog.logWarning("renameBySimpleName: could not load editing solution for project '"
						+ project.getProject().getName() + "' — excluded from ambiguity scan", e);
			}
			if (solution == null)
				continue;

			String projName = project.getProject().getName();

			// form
			if (solution.getForm(oldName) != null)
				matches.add(new String[] { "form", oldName, projName, null });

			// relation
			if (solution.getRelation(oldName) != null)
				matches.add(new String[] { "relation", oldName, projName, null });

			// valuelist
			if (solution.getValueList(oldName) != null)
				matches.add(new String[] { "valuelist", oldName, projName, null });

			// menu
			java.util.Iterator<Menu> menuIter = solution.getMenus(false);
			while (menuIter.hasNext()) {
				Menu m = menuIter.next();
				if (oldName.equals(m.getName()))
					matches.add(new String[] { "menu", oldName, projName, null });
			}

			// menuitem (recursive search across all menus)
			java.util.Iterator<Menu> menuIter2 = solution.getMenus(false);
			while (menuIter2.hasNext()) {
				Menu menu = menuIter2.next();
				MenuItem found = findMenuItemRecursive(menu, oldName);
				if (found != null)
					matches.add(new String[] { "menuitem", oldName, projName, menu.getName() });
			}

			// media
			java.util.Iterator<Media> mediaIter = solution.getMedias(false);
			while (mediaIter.hasNext()) {
				Media m = mediaIter.next();
				if (oldName.equals(m.getName()))
					matches.add(new String[] { "media", oldName, projName, null });
			}

			// scope: .js file at project root
			IFile scopeFile = project.getProject().getFile(oldName + ".js");
			if (scopeFile.exists())
				matches.add(new String[] { "scope", oldName, projName, null });
		}

		if (matches.size() == 1) {
			// Exactly one match — perform rename
			String[] match = matches.get(0);
			String type = match[0];
			String projName = match[2];

			try {
				switch (type) {
				case "solution":
					return renameSolution(oldName, newName);
				case "form": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameForm(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				case "relation": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameRelation(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				case "valuelist": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameValueList(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				case "menu": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameMenu(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				case "menuitem": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameMenuItem(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				case "media": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameMedia(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				case "scope": {
					ServoyProject proj = findProjectByName(flatProjects, projName);
					return proj != null ? renameScope(oldName, newName, proj)
							: "Error: Project '" + projName + "' not found.";
				}
				default:
					return "Error: Unknown type '" + type + "'.";
				}
			} catch (Exception e) {
				return "Error: " + e.getMessage();
			}
		} else if (matches.size() > 1) {
			// Ambiguous — build disambiguation error
			return buildAmbiguousMessage(oldName, matches, activeProject.getProject().getName());
		} else {
			// 0 matches in model — raw file fallback
			IFile rawFile = null;
			org.eclipse.core.resources.IResource member = activeProject.getProject().findMember(oldName);
			if (member instanceof IFile) {
				rawFile = (IFile) member;
			}
			if (rawFile != null) {
				return rawFileRename(rawFile, newName);
			}
			return "Error: No artifact or file named '" + oldName + "' found in the active solution or project.";
		}
	}

	private ServoyProject findProjectByName(java.util.List<ServoyProject> projects, String name) {
		for (ServoyProject p : projects) {
			if (p.getProject().getName().equals(name))
				return p;
		}
		return null;
	}

	// package-private (instead of private) so PersistRenameServiceTest can call it
	// directly rather than via reflection — reflective getDeclaredMethod() forces
	// resolution of every declared method's signature in this class, which can
	// throw NoClassDefFoundError if an unrelated OSGi bundle (e.g. com.servoy.eclipse.core,
	// referenced by other methods here) failed to activate earlier in the same test run.
	String buildAmbiguousMessage(String oldName, java.util.List<String[]> matches,
			String activeProjectName) {
		StringBuilder sb = new StringBuilder();
		sb.append("Error: Ambiguous name '").append(oldName).append("' — found in multiple locations:\n");
		for (String[] match : matches) {
			String type = match[0];
			String projName = match[2];
			String extra = match[3];
			if ("menuitem".equals(type) && extra != null) {
				sb.append("  - menuitem '").append(oldName).append("' in menu '").append(extra)
						.append("' (solution '").append(projName).append("')\n");
			} else {
				sb.append("  - ").append(type).append(" '").append(oldName).append("' in '").append(projName)
						.append("'\n");
			}
		}
		sb.append("Provide a path hint (e.g. '").append(activeProjectName).append("/forms/").append(oldName)
				.append("') to disambiguate.");
		return sb.toString();
	}

	private String rawFileRename(IFile file, String newName) {
		try {
			org.eclipse.core.resources.IContainer parent = file.getParent();
			org.eclipse.core.runtime.IPath newPath = parent.getFullPath().append(newName);
			IFile newFile = ResourcesPlugin.getWorkspace().getRoot().getFile(newPath);
			if (newFile.exists())
				return "Error: A file named '" + newName + "' already exists in the same location.";
			file.move(newPath, org.eclipse.core.resources.IResource.FORCE, new NullProgressMonitor());
			parent.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ONE, null);
			return "Renamed file '" + file.getName() + "' to '" + newName + "' successfully.";
		} catch (CoreException e) {
			return "Error renaming file: " + e.getMessage();
		}
	}

}
