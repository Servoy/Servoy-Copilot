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

package com.servoy.eclipse.servoypilot.quickfix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.dltk.internal.ui.editor.ScriptEditor;
import org.eclipse.dltk.javascript.ast.Statement;
import org.eclipse.dltk.ui.editor.IScriptAnnotation;
import org.eclipse.dltk.ui.text.IAnnotationResolution;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.QuickFixAssistant;
import com.servoy.eclipse.servoypilot.services.ParserService;
import com.servoy.eclipse.servoypilot.tools.dto.QuickFixResult;
import com.servoy.eclipse.servoypilot.tools.dto.SourceEdit;

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
		QuickFixAssistant quickFixAssistant = Activator.getDefault().getServoyAiModel().getQuickFixAssistant();
		Job job = new Job("Servoy AI QuickFix")
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				try
				{
					monitor.beginTask("Running AI QuickFix...", IProgressMonitor.UNKNOWN);

					String fixPrompt = buildFixPrompt(editor, request, null);

					QuickFixResult fix = quickFixAssistant.fix(fixPrompt);
					if (fix == null || fix.edits() == null || fix.edits().isEmpty())
					{
						return new Status(IStatus.ERROR, "No quick fix generated", "The AI did not return any quick fix.");
					}

					if (monitor.isCanceled())
					{
						return Status.CANCEL_STATUS;
					}
					monitor.worked(1);

					Display.getDefault().asyncExec(() -> {
						try
						{
							IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
							for (SourceEdit edit : fix.edits())
							{
								String editPath = edit.filePath();
								if (editPath.startsWith("L/"))
								{
									editPath = editPath.substring(2);
								}
								IPath path = new Path(editPath);
								IFile fileToEdit = ResourcesPlugin.getWorkspace().getRoot().getFile(path);

								IEditorPart targetEditor = findEditor(page, fileToEdit);
								if (targetEditor instanceof ScriptEditor scriptEditor)
								{
									InlineQuickFixPreviewManager previewManager = new InlineQuickFixPreviewManager();
									previewManager.preview(scriptEditor, fix, request, fixPrompt);
								}
								else
								{
									ServoyLog.logError("Target editor is not a ScriptEditor, cannot apply quick fix preview");
								}
							}
						}
						catch (Exception e)
						{
							ServoyLog.logError("Error applying quick fix", e);
						}
					});
					monitor.done();
				}
				catch (Exception e)
				{
					return new Status(IStatus.ERROR, "Error applying quick fix", "QuickFix failed", e);
				}
				finally
				{
					monitor.done();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();

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
		//we assume the AI always returns something that can be applied (even if it's not correct)
		return true;
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

		//TODO for this it should use the documentation tools
//		if (apiSignature != null && !apiSignature.isEmpty())
//		{
//			prompt.append("API Signature:\n");
//			prompt.append(apiSignature).append("\n\n");
//		}

		return prompt.toString();
	}

	// this is a workaround to avoid issues with curly braces in JSDoc, because it throws 
	// java.lang.IllegalArgumentException at dev.langchain4j.model.input.DefaultPromptTemplateFactory$DefaultTemplate.ensureAllVariablesProvided
	private String stripCurlyBracesFromDocumentation(String source)
	{

		if (source == null || source.isEmpty())
		{
			return source;
		}
		Pattern jsDocPattern = Pattern.compile("(?s)/\\*\\*.*?\\*/");
		Matcher matcher = jsDocPattern.matcher(source);

		StringBuffer result = new StringBuffer();

		while (matcher.find())
		{
			String jsDoc = matcher.group();
			String cleanedJsDoc = jsDoc.replaceAll("\\{[^}]*\\}", "");

			matcher.appendReplacement(result, Matcher.quoteReplacement(cleanedJsDoc));
		}

		matcher.appendTail(result);

		return result.toString();
	}

	private IEditorPart findEditor(IWorkbenchPage page, IFile fileToEdit) throws PartInitException
	{
		IEditorPart targetEditor = null;

		// iterate over all open editors in the current page
		IEditorReference[] editorRefs = page.getEditorReferences();
		for (IEditorReference ref : editorRefs)
		{
			try
			{
				IEditorInput input = ref.getEditorInput();
				IFile openFile = input != null ? input.getAdapter(IFile.class) : null;

				if (openFile != null && openFile.equals(fileToEdit))
				{
					targetEditor = ref.getEditor(true);
					page.activate(targetEditor);
					break;
				}
			}
			catch (PartInitException e)
			{
				ServoyLog.logError("Failed to inspect editor reference", e);
			}
		}

		if (targetEditor == null)
		{
			// use DLTKUIPlugin.openInEditor ?
			targetEditor = IDE.openEditor(page, fileToEdit, true);
		}
		return targetEditor;
	}
}