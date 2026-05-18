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
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.TextConsole;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Provides IDE state queries (console, editor, markers) for MCP tools.
 * <p>
 * Ported from AssistAI's {@code ConsoleService}, {@code EditorService}, and
 * {@code CodeAnalysisService} (markers only). Differences:
 * <ul>
 *   <li>No JDT dependency â {@code getCompilationErrors} uses generic {@link IMarker} API.</li>
 *   <li>No {@code UISynchronize} / {@code UISynchronizeCallable} â Servoy Developer MCP runs headless.</li>
 * </ul>
 * </p>
 */
public class IdeStateService
{
	// --- Console ---

	public String getConsoleOutput(String consoleName, int maxLines, boolean includeAllConsoles)
	{
		if (maxLines < 1) maxLines = 100;

		StringBuilder result = new StringBuilder();
		result.append("# Console Output\n\n");

		try
		{
			IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
			IConsole[] consoles = manager.getConsoles();

			if (consoles.length == 0)
			{
				result.append("No consoles available.");
				return result.toString();
			}

			List<IConsole> toRead = new ArrayList<>();
			if (consoleName != null && !consoleName.isBlank())
			{
				for (IConsole c : consoles)
					if (c.getName().contains(consoleName)) toRead.add(c);
				if (toRead.isEmpty())
					return "No console found with name containing: " + consoleName;
			}
			else if (includeAllConsoles)
			{
				for (IConsole c : consoles) toRead.add(c);
			}
			else
			{
				// Most recent (last in array)
				toRead.add(consoles[consoles.length - 1]);
			}

			for (IConsole console : toRead)
			{
				result.append("## Console: ").append(console.getName()).append("\n\n");
				if (console instanceof TextConsole textConsole)
				{
					String text = textConsole.getDocument().get();
					String[] lines = text.split("\n", -1);
					int start = Math.max(0, lines.length - maxLines);
					result.append("```\n");
					for (int i = start; i < lines.length; i++)
						result.append(lines[i]).append("\n");
					result.append("```\n\n");
				}
				else
				{
					result.append("(Console type not supported for text extraction)\n\n");
				}
			}
		}
		catch (Exception e)
		{
			result.append("Error reading console: ").append(e.getMessage());
		}

		return result.toString();
	}

	// --- Editor ---

	public String getCurrentlyOpenedFile()
	{
		try
		{
			IEditorPart editor = getActiveEditor().orElse(null);
			if (editor == null)
				return "No active editor found.";

			if (!(editor.getEditorInput() instanceof IFileEditorInput fileInput))
				return "Active editor input is not a file.";

			var file = fileInput.getFile();
			StringBuilder result = new StringBuilder();
			result.append("# Currently Opened File:\n\n");
			result.append("- **Project:** ").append(file.getProject().getName()).append("\n");
			result.append("- **Path:** ").append(file.getProjectRelativePath()).append("\n");
			result.append("- **Full path:** ").append(file.getFullPath()).append("\n");
			return result.toString();
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	public String getEditorSelection()
	{
		try
		{
			IEditorPart editor = getActiveEditor().orElse(null);
			if (editor == null)
				return "No active editor found.";

			if (!(editor instanceof ITextEditor textEditor))
				return "Active editor is not a text editor.";

			ISelection selection = textEditor.getSelectionProvider().getSelection();
			if (selection.isEmpty())
				return "No text is currently selected in the editor.";

			if (!(selection instanceof ITextSelection textSelection))
				return "The current selection is not a text selection.";

			StringBuilder result = new StringBuilder();
			result.append("# Selected Text in Editor\n\n");
			result.append("Selection from line: ").append(textSelection.getStartLine() + 1);
			result.append(" to: ").append(textSelection.getEndLine() + 1);
			result.append(" length: ").append(textSelection.getLength()).append("\n");
			result.append("=== BEGIN selected ===\n");
			result.append(textSelection.getText());
			if (!textSelection.getText().endsWith("\n")) result.append("\n");
			result.append("=== END selected text ===\n");
			return result.toString();
		}
		catch (Exception e)
		{
			return "Error: " + e.getMessage();
		}
	}

	// --- Compilation errors (generic marker API, no JDT) ---

	public String getCompilationErrors(String projectName, String severity, int maxResults)
	{
		if (maxResults <= 0) maxResults = 50;

		int severityFilter = -1; // -1 = all
		if ("ERROR".equalsIgnoreCase(severity)) severityFilter = IMarker.SEVERITY_ERROR;
		else if ("WARNING".equalsIgnoreCase(severity)) severityFilter = IMarker.SEVERITY_WARNING;

		StringBuilder result = new StringBuilder();
		result.append("# Compilation Problems\n\n");

		try
		{
			IResource scope;
			if (projectName != null && !projectName.isBlank())
			{
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
				if (!project.exists())
					return "Error: Project '" + projectName + "' not found.";
				scope = project;
				result.append("Project: ").append(projectName).append("\n\n");
			}
			else
			{
				scope = ResourcesPlugin.getWorkspace().getRoot();
			}

			IMarker[] markers = scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);

			int count = 0;
			for (IMarker marker : markers)
			{
				if (count >= maxResults) break;

				int markerSeverity = marker.getAttribute(IMarker.SEVERITY, -1);
				if (severityFilter >= 0 && markerSeverity != severityFilter) continue;

				String severityLabel = switch (markerSeverity)
				{
					case IMarker.SEVERITY_ERROR -> "ERROR";
					case IMarker.SEVERITY_WARNING -> "WARNING";
					case IMarker.SEVERITY_INFO -> "INFO";
					default -> "UNKNOWN";
				};

				String message = marker.getAttribute(IMarker.MESSAGE, "");
				int lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
				String filePath = marker.getResource() != null
					? marker.getResource().getFullPath().toString() : "(unknown)";
				String sourceId = marker.getType();

				result.append("- **").append(severityLabel).append("**");
				result.append(" at ").append(filePath);
				if (lineNumber > 0) result.append(":").append(lineNumber);
				result.append("\n  ").append(message).append("\n");
				result.append("  *(source: ").append(sourceId).append(")*\n\n");

				count++;
			}

			if (count == 0)
				result.append("No problems found with the specified criteria.\n");
			else
				result.append("Found ").append(count).append(" problem(s).\n");
		}
		catch (CoreException e)
		{
			return "Error retrieving compilation errors: " + e.getMessage();
		}

		return result.toString();
	}

	// --- Private helpers ---

	private static Optional<IEditorPart> getActiveEditor()
	{
		return Optional.ofNullable(PlatformUI.getWorkbench())
			.map(IWorkbench::getActiveWorkbenchWindow)
			.map(IWorkbenchWindow::getActivePage)
			.map(IWorkbenchPage::getActiveEditor);
	}
}
