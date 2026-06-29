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
package com.servoy.eclipse.developer.mcp.services;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.ui.ide.IDE;

import com.servoy.eclipse.core.IDeveloperServoyModel;
import com.servoy.eclipse.core.ServoyModelManager;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.ui.search.FileMatch;
import com.servoy.eclipse.ui.search.FileSearchResult;
import com.servoy.eclipse.ui.search.ScriptMethodSearch;
import com.servoy.eclipse.ui.search.ScriptVariableSearch;
import com.servoy.j2db.persistence.Form;
import com.servoy.j2db.persistence.ScriptMethod;
import com.servoy.j2db.persistence.ScriptVariable;
import com.servoy.j2db.persistence.Solution;

import jakarta.inject.Inject;

/**
 * find references, form type hierarchy, method call hierarchy, and quick-fix
 * execution.
 * <p>
 * These tools replace the JDT-based equivalents (which require
 * {@code org.eclipse.jdt.core}) with Servoy-native alternatives:
 * {@link ScriptMethodSearch} / {@link ScriptVariableSearch} for DLTK-backed
 * reference search, the Servoy persistence model for form inheritance, and
 * {@link IDE#getMarkerHelpRegistry()} for marker resolutions.
 * </p>
 *
 * @since 2026.06
 */
@Creatable
public class CodeAnalysisService {
	@Inject
	private ServoyScriptResolver servoyScriptResolver;

	/** Default constructor — required by E4 DI. */
	public CodeAnalysisService() {
	}

	// ---------------------------------------------------------------------------
	// findReferences
	// ---------------------------------------------------------------------------

	/**
	 * Finds all references to a Servoy form method or scope variable.
	 *
	 * @param fullyQualifiedClassName the form or scope name (e.g.
	 *                                {@code forms.myForm}, {@code scopes.globals})
	 * @param elementName             optional method or variable name; when
	 *                                {@code null}/blank, references to the form
	 *                                itself are returned
	 * @return markdown-formatted list of references
	 */
	public String findReferences(String fullyQualifiedClassName, String elementName) {
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = servoyModel.getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null)
			return "No active solution. Open a Servoy solution first.";

		// Resolve element
		String resolvedName = resolveSimpleName(fullyQualifiedClassName);
		boolean hasElement = elementName != null && !elementName.isBlank();

		if (hasElement) {
			// Try method first, then variable
			ScriptMethod method = findMethod(servoyModel, resolvedName, elementName);
			if (method != null)
				return runMethodSearch(method);

			ScriptVariable variable = findVariable(servoyModel, resolvedName, elementName);
			if (variable != null)
				return runVariableSearch(variable);

			return "Could not resolve '" + elementName + "' in '" + fullyQualifiedClassName + "'. "
					+ "Searched for form/scope name '" + resolvedName + "' across "
					+ (servoyModel.getModulesOfActiveProject().length + 1) + " project(s). "
					+ "Make sure the solution is active and the names are correct (use 'forms.myForm' or 'scopes.globals').";
		} else {
			// Search for the form/scope by name using text search
			return "No elementName provided. Use elementName to specify a method or variable to find references for.";
		}
	}

	private String runMethodSearch(ScriptMethod method) {
		ScriptMethodSearch search = new ScriptMethodSearch(method);
		search.run(new NullProgressMonitor());
		FileSearchResult result = (FileSearchResult) search.getSearchResult();
		return formatSearchResult("References to method '" + method.getName() + "'", result);
	}

	private String runVariableSearch(ScriptVariable variable) {
		ScriptVariableSearch search = new ScriptVariableSearch(variable);
		search.run(new NullProgressMonitor());
		FileSearchResult result = (FileSearchResult) search.getSearchResult();
		return formatSearchResult("References to variable '" + variable.getName() + "'", result);
	}

	private String formatSearchResult(String title, FileSearchResult result) {
		StringBuilder sb = new StringBuilder();
		sb.append("# ").append(title).append("\n\n");
		int count = result.getMatchCount();
		if (count == 0) {
			sb.append("No references found.\n");
			return sb.toString();
		}
		Object[] elements = result.getElements();
		for (Object element : elements) {
			org.eclipse.search.ui.text.Match[] matches = result.getMatches(element);
			for (org.eclipse.search.ui.text.Match match : matches) {
				if (match instanceof FileMatch fileMatch) {
					String path = fileMatch.getFile() != null ? fileMatch.getFile().getFullPath().toString()
							: "(unknown)";
					int line = fileMatch.getLineElement() != null ? fileMatch.getLineElement().getLine() : -1;
					String contents = fileMatch.getLineElement() != null
							? fileMatch.getLineElement().getContents().trim()
							: "";
					sb.append("- **").append(path).append("**");
					if (line > 0)
						sb.append(":").append(line);
					if (!contents.isEmpty())
						sb.append("\n  `").append(contents).append("`");
					sb.append("\n");
				}
			}
		}
		sb.append("\nFound ").append(count).append(" reference(s).\n");
		return sb.toString();
	}

	// ---------------------------------------------------------------------------
	// getTypeHierarchy (form inheritance)
	// ---------------------------------------------------------------------------

	/**
	 * Returns the form inheritance hierarchy for the given form name.
	 *
	 * @param formName the form name (simple or with {@code forms.} prefix)
	 * @return markdown-formatted hierarchy showing supertypes and subtypes
	 */
	public String getTypeHierarchy(String formName) {
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = servoyModel.getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null)
			return "No active solution. Open a Servoy solution first.";

		String simpleName = resolveSimpleName(formName);

		// Collect all forms across active solution + modules
		List<Form> allForms = collectAllForms(servoyModel);

		// Find target form
		Form target = allForms.stream().filter(f -> simpleName.equalsIgnoreCase(f.getName())).findFirst().orElse(null);

		if (target == null)
			return "Form '" + simpleName + "' not found in the active solution or its modules.";

		StringBuilder sb = new StringBuilder();
		sb.append("# Form Hierarchy: ").append(target.getName()).append("\n\n");

		// Supertypes (walk up)
		List<String> supertypes = new ArrayList<>();
		Form current = target.getExtendsForm();
		while (current != null) {
			supertypes.add(current.getName());
			current = current.getExtendsForm();
		}

		sb.append("## Supertypes\n");
		if (supertypes.isEmpty()) {
			sb.append("- *(none — this form does not extend another form)*\n");
		} else {
			for (String name : supertypes)
				sb.append("- ").append(name).append("\n");
		}

		// Subtypes (forms that extend the target)
		sb.append("\n## Direct Subtypes\n");
		List<String> subtypes = new ArrayList<>();
		for (Form f : allForms) {
			Form extendsForm = f.getExtendsForm();
			if (extendsForm != null && extendsForm.getName().equals(target.getName()))
				subtypes.add(f.getName());
		}

		if (subtypes.isEmpty()) {
			sb.append("- *(none — no forms extend this form)*\n");
		} else {
			for (String name : subtypes)
				sb.append("- ").append(name).append("\n");
		}

		return sb.toString();
	}

	// ---------------------------------------------------------------------------
	// getMethodCallHierarchy
	// ---------------------------------------------------------------------------

	/**
	 * Returns the callers of the specified method up to {@code maxDepth} levels.
	 *
	 * @param fullyQualifiedClassName form or scope name containing the method
	 * @param methodName              the method name to analyse
	 * @param methodSignature         ignored (reserved for future overload
	 *                                disambiguation)
	 * @param maxDepth                maximum recursion depth (default 3)
	 * @return markdown-formatted call hierarchy
	 */
	public String getMethodCallHierarchy(String fullyQualifiedClassName, String methodName, String methodSignature,
			String maxDepth) {
		IDeveloperServoyModel servoyModel = ServoyModelManager.getServoyModelManager().getServoyModel();
		ServoyProject activeProject = servoyModel.getActiveProject();
		if (activeProject == null || activeProject.getEditingSolution() == null)
			return "No active solution. Open a Servoy solution first.";

		int depth = 3;
		if (maxDepth != null && !maxDepth.isBlank()) {
			try {
				depth = Integer.parseInt(maxDepth.trim());
			} catch (NumberFormatException ignored) {
			}
		}

		String simpleName = resolveSimpleName(fullyQualifiedClassName);
		ScriptMethod method = findMethod(servoyModel, simpleName, methodName);
		if (method == null)
			return "Could not resolve method '" + methodName + "' in '" + fullyQualifiedClassName + "'. "
					+ "Searched for form/scope name '" + simpleName + "' across "
					+ (servoyModel.getModulesOfActiveProject().length + 1) + " project(s). "
					+ "Make sure the solution is active and the names are correct (use 'forms.myForm' or 'scopes.globals').";

		StringBuilder sb = new StringBuilder();
		sb.append("# Call Hierarchy (callers) for: ").append(fullyQualifiedClassName).append(".").append(methodName)
				.append("\n\n");

		buildCallerHierarchy(servoyModel, method, sb, 0, depth, new ArrayList<>());

		return sb.toString();
	}

	private void buildCallerHierarchy(IDeveloperServoyModel servoyModel, ScriptMethod method, StringBuilder sb,
			int currentDepth, int maxDepth, List<String> visited) {
		String key = method.getRootObject().getName() + "." + method.getName();
		if (visited.contains(key))
			return; // cycle guard
		visited.add(key);

		ScriptMethodSearch search = new ScriptMethodSearch(method);
		search.run(new NullProgressMonitor());
		FileSearchResult result = (FileSearchResult) search.getSearchResult();

		String indent = "  ".repeat(currentDepth);
		Object[] elements = result.getElements();

		if (elements.length == 0 && currentDepth == 0) {
			sb.append("*(no callers found)*\n");
			return;
		}

		for (Object element : elements) {
			org.eclipse.search.ui.text.Match[] matches = result.getMatches(element);
			for (org.eclipse.search.ui.text.Match match : matches) {
				if (match instanceof FileMatch fileMatch) {
					IFile file = fileMatch.getFile();
					String path = file != null ? file.getFullPath().toString() : "(unknown)";
					int line = fileMatch.getLineElement() != null ? fileMatch.getLineElement().getLine() : -1;
					String contents = fileMatch.getLineElement() != null
							? fileMatch.getLineElement().getContents().trim()
							: "";
					sb.append(indent).append("- **").append(path).append("**");
					if (line > 0)
						sb.append(":").append(line);
					if (!contents.isEmpty())
						sb.append(" — `").append(contents).append("`");
					sb.append("\n");

					// Recurse: find which ScriptMethod contains this match and walk up
					if (currentDepth + 1 < maxDepth && file != null && file.getName().endsWith(".js")) {
						ScriptMethod callerMethod = resolveCallerMethod(servoyModel, file, fileMatch.getOffset());
						if (callerMethod != null)
							buildCallerHierarchy(servoyModel, callerMethod, sb, currentDepth + 1, maxDepth, visited);
					}
				}
			}
		}
	}

	/**
	 * Given a match in a .js file at a character offset, resolves which Servoy
	 * {@link ScriptMethod} contains that offset.
	 */
	private ScriptMethod resolveCallerMethod(IDeveloperServoyModel servoyModel, IFile file, int matchOffset) {
		try {
			org.eclipse.dltk.core.ISourceModule sourceModule = org.eclipse.dltk.core.DLTKCore
					.createSourceModuleFrom(file);
			if (sourceModule == null)
				return null;
			org.eclipse.dltk.core.IModelElement[] children = sourceModule.getChildren();
			if (children == null)
				return null;

			// Find the DLTK IMethod whose source range contains the match offset
			String callerMethodName = null;
			for (org.eclipse.dltk.core.IModelElement child : children) {
				if (child instanceof org.eclipse.dltk.core.IMethod dltKMethod) {
					org.eclipse.dltk.core.SourceRange range = (org.eclipse.dltk.core.SourceRange) dltKMethod
							.getSourceRange();
					if (range != null && matchOffset >= range.getOffset()
							&& matchOffset <= range.getOffset() + range.getLength()) {
						callerMethodName = dltKMethod.getElementName();
						break;
					}
				}
			}
			if (callerMethodName == null)
				return null;

			// Derive the form/scope name from the file path: project/forms/X.js → X,
			// project/X.js → X
			String filePath = file.getProjectRelativePath().toString();
			String formOrScopeName;
			if (filePath.startsWith("forms/"))
				formOrScopeName = filePath.substring("forms/".length(), filePath.length() - ".js".length());
			else
				formOrScopeName = filePath.substring(0, filePath.length() - ".js".length());

			return findMethod(servoyModel, formOrScopeName, callerMethodName);
		} catch (Exception e) {
			return null;
		}
	}

	// ---------------------------------------------------------------------------
	// executeQuickFix
	// ---------------------------------------------------------------------------

	/**
	 * Lists or applies quick-fix resolutions for a problem marker.
	 * <p>
	 * Use {@code getCompilationErrors} to obtain marker IDs. Pass
	 * {@code proposalIndex = -1} to list all available resolutions without applying
	 * any.
	 * </p>
	 *
	 * @param markerId      the numeric marker ID from {@code getCompilationErrors}
	 * @param proposalIndex 0-based index of the resolution to apply, or {@code -1}
	 *                      to list
	 * @return result description
	 */
	public String executeQuickFix(long markerId, int proposalIndex) {
		IMarker marker = findMarkerById(markerId);
		if (marker == null)
			return "Marker with ID " + markerId + " not found. Use getCompilationErrors to get current marker IDs.";

		org.eclipse.ui.IMarkerResolution[] resolutions;
		try {
			resolutions = IDE.getMarkerHelpRegistry().getResolutions(marker);
		} catch (Exception e) {
			return "Error retrieving resolutions for marker " + markerId + ": " + e.getMessage();
		}

		if (resolutions == null || resolutions.length == 0)
			return "No quick-fix resolutions available for marker " + markerId + " (type: " + safeGetMarkerType(marker)
					+ ", message: " + safeGetMarkerMessage(marker) + ").";

		if (proposalIndex == -1) {
			// List mode
			StringBuilder sb = new StringBuilder();
			sb.append("# Quick Fix Proposals for Marker ").append(markerId).append("\n\n");
			sb.append("**Message:** ").append(safeGetMarkerMessage(marker)).append("\n");
			sb.append("**Type:** ").append(safeGetMarkerType(marker)).append("\n\n");
			sb.append("## Available Resolutions\n\n");
			for (int i = 0; i < resolutions.length; i++) {
				sb.append("- **[").append(i).append("]** ").append(resolutions[i].getLabel()).append("\n");
			}
			sb.append("\nCall `executeQuickFix` with the desired index to apply a resolution.\n");
			return sb.toString();
		}

		if (proposalIndex < 0 || proposalIndex >= resolutions.length)
			return "Invalid proposalIndex " + proposalIndex + ". Available: 0–" + (resolutions.length - 1) + ". "
					+ "Call with proposalIndex=-1 to list them.";

		try {
			Exception[] error = new Exception[1];
			org.eclipse.swt.widgets.Display.getDefault().syncExec(() -> {
				try {
					resolutions[proposalIndex].run(marker);
				} catch (Exception e) {
					error[0] = e;
				}
			});
			if (error[0] != null)
				return "Error applying resolution [" + proposalIndex + "]: " + error[0].getMessage();
			return "Applied resolution [" + proposalIndex + "]: '" + resolutions[proposalIndex].getLabel()
					+ "' for marker " + markerId + ".";
		} catch (Exception e) {
			return "Error applying resolution [" + proposalIndex + "]: " + e.getMessage();
		}
	}

	// ---------------------------------------------------------------------------
	// Private helpers
	// ---------------------------------------------------------------------------

	private IMarker findMarkerById(long markerId) {
		try {
			IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true,
					IResource.DEPTH_INFINITE);
			for (IMarker m : markers) {
				if (m.getId() == markerId)
					return m;
			}
		} catch (CoreException e) {
			// ignore — return null
		}
		return null;
	}

	private String safeGetMarkerType(IMarker marker) {
		try {
			return marker.getType();
		} catch (CoreException e) {
			return "(unknown)";
		}
	}

	private String safeGetMarkerMessage(IMarker marker) {
		return marker.getAttribute(IMarker.MESSAGE, "(no message)");
	}

	/**
	 * Strips a {@code forms.} or {@code scopes.} prefix from a Servoy element name,
	 * returning just the simple form or scope name.
	 * <p>
	 * Accepts:
	 * <ul>
	 * <li>{@code "forms.myForm"} → {@code "myForm"}</li>
	 * <li>{@code "scopes.globals"} → {@code "globals"}</li>
	 * <li>{@code "myForm"} → {@code "myForm"}</li>
	 * </ul>
	 * Only the known {@code forms.} and {@code scopes.} prefixes are stripped so
	 * that a caller who accidentally includes the element name (e.g.
	 * {@code "forms.myForm.myMethod"}) is NOT silently misrouted to a wrong form
	 * name.
	 */
	private static String resolveSimpleName(String name) {
		if (name == null)
			return "";
		String trimmed = name.trim();
		if (trimmed.startsWith("forms."))
			return trimmed.substring("forms.".length());
		if (trimmed.startsWith("scopes."))
			return trimmed.substring("scopes.".length());
		return trimmed;
	}

	private ScriptMethod findMethod(IDeveloperServoyModel servoyModel, String formOrScopeName, String methodName) {
		ServoyProject activeProject = servoyModel.getActiveProject();
		List<ServoyProject> projects = new ArrayList<>();
		projects.add(activeProject);
		for (ServoyProject mod : servoyModel.getModulesOfActiveProject())
			if (mod != null && mod.getEditingSolution() != null)
				projects.add(mod);

		for (ServoyProject project : projects) {
			Solution solution = project.getEditingSolution();
			if (solution == null)
				continue;

			// Check scope methods
			ScriptMethod method = solution.getScriptMethod(formOrScopeName, methodName);
			if (method != null)
				return method;

			// Check form methods
			Form form = solution.getForm(formOrScopeName);
			if (form != null) {
				Iterator<ScriptMethod> methods = form.getScriptMethods(false);
				while (methods.hasNext()) {
					ScriptMethod m = methods.next();
					if (methodName.equals(m.getName()))
						return m;
				}
			}
		}
		return null;
	}

	private ScriptVariable findVariable(IDeveloperServoyModel servoyModel, String formOrScopeName,
			String variableName) {
		ServoyProject activeProject = servoyModel.getActiveProject();
		List<ServoyProject> projects = new ArrayList<>();
		projects.add(activeProject);
		for (ServoyProject mod : servoyModel.getModulesOfActiveProject())
			if (mod != null && mod.getEditingSolution() != null)
				projects.add(mod);

		for (ServoyProject project : projects) {
			Solution solution = project.getEditingSolution();
			if (solution == null)
				continue;

			// Check scope variables
			ScriptVariable variable = solution.getScriptVariable(formOrScopeName, variableName);
			if (variable != null)
				return variable;

			// Check form variables
			Form form = solution.getForm(formOrScopeName);
			if (form != null) {
				Iterator<ScriptVariable> variables = form.getScriptVariables(false);
				while (variables.hasNext()) {
					ScriptVariable v = variables.next();
					if (variableName.equals(v.getName()))
						return v;
				}
			}
		}
		return null;
	}

	private List<Form> collectAllForms(IDeveloperServoyModel servoyModel) {
		List<Form> forms = new ArrayList<>();
		ServoyProject activeProject = servoyModel.getActiveProject();
		List<ServoyProject> projects = new ArrayList<>();
		projects.add(activeProject);
		for (ServoyProject mod : servoyModel.getModulesOfActiveProject())
			if (mod != null && mod.getEditingSolution() != null)
				projects.add(mod);

		for (ServoyProject project : projects) {
			Solution solution = project.getEditingSolution();
			if (solution == null)
				continue;
			Iterator<Form> it = solution.getForms(null, false);
			while (it.hasNext())
				forms.add(it.next());
		}
		return forms;
	}
}
