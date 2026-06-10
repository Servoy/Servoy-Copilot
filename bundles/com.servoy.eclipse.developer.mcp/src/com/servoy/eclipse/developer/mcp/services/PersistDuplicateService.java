package com.servoy.eclipse.developer.mcp.services;

import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.repository.DataModelManager;
import com.servoy.eclipse.model.repository.SolutionSerializer;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.views.solutionexplorer.actions.PersistCloner;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.IPersist;
import com.servoy.j2db.persistence.IValidateName;
import com.servoy.j2db.persistence.Media;
import com.servoy.j2db.persistence.Relation;
import com.servoy.j2db.persistence.RepositoryException;
import com.servoy.j2db.persistence.Solution;
import com.servoy.j2db.persistence.ValueList;
import com.servoy.j2db.util.Pair;
import com.servoy.j2db.util.docvalidator.IdentDocumentValidator;

/**
 * Service for duplicating Servoy persists (forms, relations, valuelists,
 * media). Handles cloning with automatic name generation, cross-solution
 * duplication, and associated file copying.
 */
public class PersistDuplicateService {
	public String duplicatePersist(String persistType, String name, String newName, String solutionName,
			String destinationSolution) {
		if (persistType == null || persistType.isBlank())
			return "Error: persistType is required.";
		if (name == null || name.isBlank())
			return "Error: name is required.";

		try {
			ServoyProject sourceProject = resolveProject(solutionName);
			if (sourceProject == null)
				return "Error: Solution '" + (solutionName != null ? solutionName : "active") + "' not found.";

			ServoyProject destProject;
			if (destinationSolution != null && !destinationSolution.isBlank()) {
				destProject = resolveProject(destinationSolution);
				if (destProject == null)
					return "Error: Solution '" + destinationSolution + "' not found.";
			} else {
				destProject = sourceProject;
			}

			String type = persistType.toLowerCase().trim();
			return switch (type) {
			case "form" -> duplicateForm(name, newName, sourceProject, destProject);
			case "relation" -> duplicateRelation(name, newName, sourceProject, destProject);
			case "valuelist" -> duplicateValueList(name, newName, sourceProject, destProject);
			case "media" -> duplicateMedia(name, newName, sourceProject, destProject);
			default ->
				"Error: Unsupported persistType '" + persistType + "'. Supported: form, relation, valuelist, media.";
			};
		} catch (Exception e) {
			ServoyLog.logError("duplicatePersist failed", e);
			return "Error: " + e.getMessage();
		}
	}

	private String duplicateForm(String name, String newName, ServoyProject sourceProject, ServoyProject destProject)
			throws RepositoryException {
		Solution solution = sourceProject.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Form form = solution.getForm(name);
		if (form == null)
			return "Error: Form '" + name + "' not found in solution '" + sourceProject.getProject().getName() + "'.";

		String resolvedName = resolveNewName(newName, name, "form", destProject);
		if (resolvedName.startsWith("Error:"))
			return resolvedName;

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		IPersist duplicate = PersistCloner.intelligentClonePersist(form, resolvedName, destProject, validator, true);
		if (duplicate == null)
			return "Error: Failed to duplicate form '" + name + "'.";

		copyFormFiles(form, (Form) duplicate, sourceProject, destProject);

		return successJson(resolvedName, "form", destProject.getProject().getName());
	}

	private String duplicateRelation(String name, String newName, ServoyProject sourceProject,
			ServoyProject destProject) throws RepositoryException {
		Solution solution = sourceProject.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Relation relation = solution.getRelation(name);
		if (relation == null)
			return "Error: Relation '" + name + "' not found in solution '" + sourceProject.getProject().getName()
					+ "'.";

		String resolvedName = resolveNewName(newName, name, "relation", destProject);
		if (resolvedName.startsWith("Error:"))
			return resolvedName;

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		IPersist duplicate = PersistCloner.intelligentClonePersist(relation, resolvedName, destProject, validator,
				true);
		if (duplicate == null)
			return "Error: Failed to duplicate relation '" + name + "'.";

		return successJson(resolvedName, "relation", destProject.getProject().getName());
	}

	private String duplicateValueList(String name, String newName, ServoyProject sourceProject,
			ServoyProject destProject) throws RepositoryException {
		Solution solution = sourceProject.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		ValueList vl = solution.getValueList(name);
		if (vl == null)
			return "Error: ValueList '" + name + "' not found in solution '" + sourceProject.getProject().getName()
					+ "'.";

		String resolvedName = resolveNewName(newName, name, "valuelist", destProject);
		if (resolvedName.startsWith("Error:"))
			return resolvedName;

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		IPersist duplicate = PersistCloner.intelligentClonePersist(vl, resolvedName, destProject, validator, true);
		if (duplicate == null)
			return "Error: Failed to duplicate valuelist '" + name + "'.";

		return successJson(resolvedName, "valuelist", destProject.getProject().getName());
	}

	private String duplicateMedia(String name, String newName, ServoyProject sourceProject, ServoyProject destProject)
			throws RepositoryException {
		Solution solution = sourceProject.getEditingSolution();
		if (solution == null)
			return "Error: Cannot get editing solution.";

		Media media = findMedia(solution, name);
		if (media == null)
			return "Error: Media '" + name + "' not found in solution '" + sourceProject.getProject().getName() + "'.";

		String resolvedName = resolveNewName(newName, name, "media", destProject);
		if (resolvedName.startsWith("Error:"))
			return resolvedName;

		IValidateName validator = ServoyModelManager.getServoyModelManager().getServoyModel().getNameValidator();
		IPersist duplicate = PersistCloner.intelligentClonePersist(media, resolvedName, destProject, validator, true);
		if (duplicate == null)
			return "Error: Failed to duplicate media '" + name + "'.";

		return successJson(resolvedName, "media", destProject.getProject().getName());
	}

	private void copyFormFiles(Form sourceForm, Form duplicateForm, ServoyProject sourceProject,
			ServoyProject destProject) {
		try {
			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
			Pair<String, String> sourcePath = SolutionSerializer.getFilePath(sourceForm, false);
			Pair<String, String> duplicatePath = SolutionSerializer.getFilePath(duplicateForm, false);

			IFile lessFile = root.getFile(new Path(
					sourcePath.getLeft() + sourceForm.getName() + SolutionSerializer.FORM_LESS_FILE_EXTENSION));
			if (lessFile.exists()) {
				IFile duplicateLessFile = root.getFile(new Path(duplicatePath.getLeft() + duplicateForm.getName()
						+ SolutionSerializer.FORM_LESS_FILE_EXTENSION));
				if (!duplicateLessFile.exists()) {
					duplicateLessFile.create(lessFile.getContents(), true, null);
				}
			}

			IFile secFile = root.getFile(new Path(
					sourcePath.getLeft() + sourceForm.getName() + DataModelManager.SECURITY_FILE_EXTENSION_WITH_DOT));
			if (secFile.exists()) {
				IFile duplicateSecFile = root.getFile(new Path(duplicatePath.getLeft() + duplicateForm.getName()
						+ DataModelManager.SECURITY_FILE_EXTENSION_WITH_DOT));
				if (!duplicateSecFile.exists()) {
					duplicateSecFile.create(secFile.getContents(), true, null);
				}
			}
		} catch (Exception e) {
			ServoyLog.logWarning("copyFormFiles: " + e.getMessage(), e);
		}
	}

	private String resolveNewName(String newName, String originalName, String persistType, ServoyProject destProject) {
		if (newName != null && !newName.isBlank()) {
			String trimmed = newName.trim();
			if (!"media".equalsIgnoreCase(persistType) && !IdentDocumentValidator.isJavaIdentifier(trimmed))
				return "Error: Invalid name '" + trimmed
						+ "': name must be a valid identifier (no spaces, special characters, or reserved words).";
			String existing = checkNameExists(trimmed, persistType, destProject);
			if (existing != null)
				return existing;
			return trimmed;
		}

		String candidate = originalName + "_copy";
		if (checkNameExists(candidate, persistType, destProject) == null) {
			return candidate;
		}

		for (int i = 2; i <= 100; i++) {
			candidate = originalName + "_copy" + i;
			if (checkNameExists(candidate, persistType, destProject) == null) {
				return candidate;
			}
		}
		return "Error: Could not generate a unique name for the duplicate.";
	}

	private String checkNameExists(String candidateName, String persistType, ServoyProject project) {
		Solution solution = project.getEditingSolution();
		if (solution == null)
			return null;

		boolean exists = switch (persistType) {
		case "form" -> solution.getForm(candidateName) != null;
		case "relation" -> solution.getRelation(candidateName) != null;
		case "valuelist" -> solution.getValueList(candidateName) != null;
		case "media" -> findMedia(solution, candidateName) != null;
		default -> false;
		};

		if (exists) {
			return "Error: A " + persistType + " named '" + candidateName + "' already exists.";
		}
		return null;
	}

	private Media findMedia(Solution solution, String name) {
		Iterator<Media> mediaIter = solution.getMedias(false);
		while (mediaIter.hasNext()) {
			Media m = mediaIter.next();
			if (name.equals(m.getName())) {
				return m;
			}
		}
		return null;
	}

	private ServoyProject resolveProject(String solutionName) {
		IDeveloperServoyModel model = ServoyModelManager.getServoyModelManager().getServoyModel();
		if (solutionName != null && !solutionName.isBlank()) {
			return model.getServoyProject(solutionName);
		}
		return model.getActiveProject();
	}

	private String successJson(String duplicatedName, String persistType, String solutionName) {
		return "{\"status\":\"ok\",\"duplicated\":\"" + escapeJson(duplicatedName) + "\",\"persistType\":\""
				+ escapeJson(persistType) + "\",\"solution\":\"" + escapeJson(solutionName) + "\"}";
	}

	private String escapeJson(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
