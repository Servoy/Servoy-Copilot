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

package com.servoy.eclipse.servoypilot.context;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.dltk.ui.editor.IScriptAnnotation;
import org.eclipse.dltk.ui.text.IAnnotationResolution;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.AssistantType;
import com.servoy.eclipse.servoypilot.dto.QuickFixRequest;
import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.eclipse.servoypilot.util.ChatViewActivator;

public class ServoyAIQuickFixResolution implements IMarkerResolution, IAnnotationResolution
{
	private IFile resource;
	private int offset;
	private IScriptAnnotation annotation;
	private IMarker marker;

	public ServoyAIQuickFixResolution(IProject project, IFile resource, int offset, IScriptAnnotation annotation)
	{
		this.resource = resource;
		this.offset = offset;
		this.annotation = annotation;
	}

	public ServoyAIQuickFixResolution(IProject project2, IFile resource, IMarker marker)
	{
		this.resource = resource;
		this.marker = marker;
	}

	@Override
	public String getLabel()
	{
		return "Fix with Servoy AI";
	}

	@Override
	public void run(IMarker marker)
	{
		ITextEditor editor = (ITextEditor)PlatformUI.getWorkbench()
			.getActiveWorkbenchWindow()
			.getActivePage()
			.getActiveEditor();

		QuickFixRequest request = buildRequest(editor, marker);
		run(editor, request);
	}

	@Override
	public void run(IScriptAnnotation annotation, IDocument document)
	{
		ITextEditor editor = (ITextEditor)PlatformUI.getWorkbench()
			.getActiveWorkbenchWindow()
			.getActivePage()
			.getActiveEditor();

		QuickFixRequest request = buildRequest(editor, annotation);
		run(editor, request);
	}

	public void run(ITextEditor editor, QuickFixRequest request)
	{
		try
		{
			String fixPrompt = buildFixPrompt(editor, request, null);
			Display.getDefault().asyncExec(() -> {

				ChatViewActivator.openAndSwitchToAssistant(AssistantType.QUICKFIX, fixPrompt);
			});
		}
		catch (Exception e)
		{
			ServoyLog.logError("Error applying quick fix", e);
		}

	}

	private QuickFixRequest buildRequest(
		ITextEditor editor,
		IMarker marker)
	{
		try
		{
			IDocument document = editor.getDocumentProvider()
				.getDocument(editor.getEditorInput());

			String fullSource = document.get();

			int start = marker.getAttribute(IMarker.CHAR_START, -1);
			int end = marker.getAttribute(IMarker.CHAR_END, -1);
			String message = marker.getAttribute(IMarker.MESSAGE, "");

			if (start < 0 || end < 0 || end <= start)
			{
				int lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
				if (lineNumber > 0)
				{
					try
					{
						int lineIndex = lineNumber - 1; // IDocument lines are 0-based
						IRegion lineRegion = document.getLineInformation(lineIndex);
						start = lineRegion.getOffset();
						end = start + lineRegion.getLength();
					}
					catch (BadLocationException e)
					{
						ServoyLog.logError("Error calculating line offsets for quick fix", e);
						return null;
					}
				}
				else
				{
					// No start/end and no line number → nothing to do
					return null;
				}
			}

			Statement problemCode = ParserService.getInstance().getStatementAtOffset(fullSource, start);

			IFile file = (IFile)marker.getResource();
			String fileName = file.getName();

			return new QuickFixRequest(
				fullSource,
				problemCode,
				message,
				start,
				end,
				fileName);

		}
		catch (Exception e)
		{
			ServoyLog.logError("Error building quick fix request", e);
			return null;
		}
	}

	private QuickFixRequest buildRequest(ITextEditor editor, IScriptAnnotation annotation2)
	{
		try
		{
			IDocument document = editor.getDocumentProvider()
				.getDocument(editor.getEditorInput());
			String fullSource = document.get();

			int start = offset > 0 ? offset : annotation2.getSourceStart();
			int end = annotation2.getSourceEnd();
			String message = annotation2.getText();
			if (start < 0 || end < 0 || end <= start)
			{
				return null;
			}

			Statement problemCode = ParserService.getInstance().getStatementAtOffset(fullSource, start);

			IFile file = resource;
			String fileName = file.getName();

			return new QuickFixRequest(
				fullSource,
				problemCode,
				message,
				start,
				end,
				fileName);

		}
		catch (Exception e)
		{
			ServoyLog.logError("Error building quick fix request", e);
			return null;
		}
	}

	public boolean canFix()
	{
		return Activator.getDefault().getAiConfiguration().isValid();
	}

	//TODO move to separate service/tool?
	private String buildFixPrompt(
		ITextEditor editor,
		QuickFixRequest request, String apiSignature) throws Exception
	{
		IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());

		//TODO refactor the code that computes the start,end and line number
		int lineNumber = -1;
		String message = "";
		if (marker != null)
		{
			lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1) - 1;
			message = marker.getAttribute(IMarker.MESSAGE, "");
		}
		else if (annotation != null)
		{
			lineNumber = document.getLineOfOffset(annotation.getSourceStart());
			message = annotation.getText();
		}

		if (lineNumber < 0)
		{
			return null;
		}
		StringBuilder prompt = new StringBuilder();

		prompt.append("You are generating a minimal Servoy Javascript quickfix, a statement.\n\n");
		prompt.append("File: ").append(resource).append("\n");
		prompt.append("Language: Servoy JavaScript\n");
		prompt.append("Environment: Eclipse DLTK ScriptEditor\n\n");

		prompt.append("Context:\n");
		prompt.append("- Message: ").append(message).append("\n");
		prompt.append("- Line: ").append(lineNumber + 1).append("\n\n");
		prompt.append("- CharacterOffset: ").append(request.startOffset).append("\n\n");
		prompt.append("- Problem statement: ").append(request.statement != null ? request.statement.toString() : "N/A").append("\n\n");

		prompt.append("If needed, inspect the context around the statement before generating a fix, using the tool:\n");
		prompt.append("codeContext(filePath, lineNumber, characterOffset)\n");
		prompt.append("This returns the surrounding code around the requested line.\n\n");

		return prompt.toString();
	}
}