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
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools.documentation;

import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.dltk.core.DLTKCore;
import org.eclipse.dltk.core.ISourceModule;
import org.eclipse.dltk.javascript.typeinfo.model.Member;
import org.eclipse.dltk.javascript.typeinfo.model.Method;
import org.eclipse.dltk.javascript.typeinfo.model.Parameter;
import org.eclipse.dltk.javascript.typeinfo.model.Property;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.dto.SelectionInfo;
import com.servoy.eclipse.servoypilot.services.FilePathResolver;

/**
 * Singleton helper providing shared logic for documentation tool interfaces.
 */
public class DocumentationToolsHelper
{
	private static final DocumentationToolsHelper INSTANCE = new DocumentationToolsHelper();

	private DocumentationToolsHelper()
	{
	}

	public static DocumentationToolsHelper getInstance()
	{
		return INSTANCE;
	}

	/**
	 * Convert absolute file path to workspace-relative path.
	 */
	public String convertToWorkspacePath(String absolutePath)
	{
		if (absolutePath != null)
		{
			if (absolutePath.startsWith("/") && !absolutePath.startsWith("//"))
			{
				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(absolutePath));
				if (file != null && file.exists())
				{
					return absolutePath;
				}
			}

			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(new Path(absolutePath));
			if (file != null)
			{
				return file.getFullPath().toString();
			}
		}
		return null;
	}

	/**
	 * Creates SelectionInfo from a file path without requiring SelectionTracker.
	 */
	public SelectionInfo createSelectionInfoFromFile(String pathOrName)
	{
		if (pathOrName == null || pathOrName.trim().isEmpty())
		{
			return null;
		}

		try
		{
			FilePathResolver resolver = FilePathResolver.getInstance();
			IFile file = resolver.resolveFile(pathOrName);

			if (file != null && file.exists())
			{
				ISourceModule module = (ISourceModule)DLTKCore.create(file);
				if (module != null)
				{
					String source = module.getSource();
					if (source != null)
					{
						int totalLines = source.split("\r\n|\r|\n", -1).length;

						Optional<SelectionInfo> selectionOpt = SelectionInfo.create(
							file.getFullPath().toString(),
							0,
							source.length(),
							source,
							module,
							0,
							totalLines - 1,
							true);

						if (selectionOpt.isPresent())
						{
							return selectionOpt.get();
						}
					}
				}
			}

			return null;
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error creating SelectionInfo from file: " + pathOrName, e);
			return null;
		}
	}

	/**
	 * Extract indentation (leading whitespace) from a line.
	 */
	public String extractIndentation(String line)
	{
		if (line == null || line.isEmpty())
		{
			return "";
		}

		int i = 0;
		while (i < line.length() && Character.isWhitespace(line.charAt(i)))
		{
			i++;
		}

		return line.substring(0, i);
	}

	/**
	 * Clear selection in the active editor and set cursor to original selection start.
	 */
	public void clearEditorSelection(IFile file, int originalOffset)
	{
		Display.getDefault().asyncExec(() -> {
			try
			{
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window == null)
				{
					return;
				}

				IWorkbenchPage page = window.getActivePage();
				if (page == null)
				{
					return;
				}

				IEditorPart editor = page.getActiveEditor();
				if (editor == null)
				{
					return;
				}

				if (editor.getEditorInput() instanceof FileEditorInput fileInput)
				{
					if (fileInput.getFile().equals(file))
					{
						ITextEditor textEditor = editor.getAdapter(ITextEditor.class);
						if (textEditor != null)
						{
							var selectionProvider = textEditor.getSelectionProvider();
							if (selectionProvider != null)
							{
								selectionProvider.setSelection(new TextSelection(originalOffset, 0));
							}
						}
					}
				}
			}
			catch (Exception e)
			{
				ServoyLog.logError("Error clearing editor selection", e);
			}
		});
	}

	/**
	 * Maps Java class names to @ServoyDocumented scriptingName values.
	 */
	public String mapClassNameToScriptingName(String className)
	{
		if (className == null)
		{
			return null;
		}

		return switch (className)
		{
			case "JSApplication" -> "application";
			case "JSDatabaseManager" -> "databaseManager";
			case "JSSecurity" -> "security";
			case "JSI18N" -> "i18n";
			case "JSUtils" -> "utils";
			case "JSForm" -> "controller";
			case "JSEventsManager" -> "eventsManager";
			case "JSSolutionModel" -> "solutionModel";
			default -> null;
		};
	}

	/**
	 * Formats a member signature without full documentation (lightweight).
	 */
	public String formatMemberSignature(Member member)
	{
		if (member == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		sb.append(member.getName());

		if (member instanceof Method method)
		{
			sb.append("(");
			List<Parameter> params = method.getParameters();
			if (params != null && !params.isEmpty())
			{
				for (int i = 0; i < params.size(); i++)
				{
					Parameter param = params.get(i);
					sb.append(param.getName());
					if (param.getType() != null)
					{
						sb.append(":").append(param.getType().getName());
					}
					if (i < params.size() - 1)
					{
						sb.append(", ");
					}
				}
			}
			sb.append(")");

			if (method.getType() != null)
			{
				sb.append(": ").append(method.getType().getName());
			}
		}
		else if (member instanceof Property property)
		{
			if (property.getType() != null)
			{
				sb.append(": ").append(property.getType().getName());
			}
		}

		return sb.toString();
	}

	/**
	 * Formats full documentation for a member.
	 */
	public String formatMemberDocumentation(Member member, String typeName)
	{
		if (member == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();

		sb.append("SIGNATURE: ").append(typeName).append(".").append(formatMemberSignature(member)).append("\n\n");

		String description = member.getDescription();
		if (description != null && !description.trim().isEmpty())
		{
			sb.append("DESCRIPTION:\n").append(description).append("\n\n");
		}

		if (member instanceof Method method)
		{
			List<Parameter> params = method.getParameters();
			if (params != null && !params.isEmpty())
			{
				sb.append("PARAMETERS:\n");
				for (Parameter param : params)
				{
					sb.append("  - ").append(param.getName());
					if (param.getType() != null)
					{
						sb.append(" (").append(param.getType().getName()).append(")");
					}
					sb.append("\n");
				}
				sb.append("\n");
			}

			if (method.getType() != null)
			{
				sb.append("RETURNS: ").append(method.getType().getName()).append("\n");
			}
		}

		if (member.isDeprecated())
		{
			sb.append("\n[DEPRECATED]");
			if (description == null || !description.toLowerCase().contains("deprecated"))
			{
				sb.append(" This member is deprecated.");
			}
			sb.append("\n");
		}

		return sb.toString();
	}
}
