package com.servoy.eclipse.servoypilot.chatview.parts;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import jakarta.inject.Inject;

@Creatable
public class CodeEditingService
{
	@Inject
	ILog logger;

	@Inject
	UISynchronize sync;

	/**
	 * * Generates a diff between proposed code and an existing file in the project.
	 * 
	 * @param projectName  The name of the project containing the file
	 * @param filePath     The path to the file relative to the project root
	 * @param proposedCode The new/updated code being proposed
	 * @param contextLines Number of context lines to include in the diff
	 * @return A formatted string containing the diff and a summary of changes
	 */
	public String generateCodeDiff(String projectName, String filePath, String proposedCode, Integer contextLines)
	{
		Objects.requireNonNull(projectName);
		Objects.requireNonNull(filePath);

		if (projectName.isEmpty())
		{
			throw new IllegalArgumentException("Error: Project name cannot be empty.");
		}
		if (filePath.isEmpty())
		{
			throw new IllegalArgumentException("Error: File path cannot be empty.");
		}

		if (contextLines == null || contextLines < 0)
		{
			contextLines = 3; // Default context lines
		}
		try
		{
			// Get the project
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.exists())
			{
				throw new RuntimeException("Error: Project '" + projectName + "' not found.");
			}

			if (!project.isOpen())
			{
				throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
			}

			// Get the file
			IResource resource = project.findMember(filePath);
			if (resource == null || !resource.exists())
			{
				throw new RuntimeException(
					"Error: File '" + filePath + "' not found in project '" + projectName + "'.");
			}

			// Check if the resource is a file
			if (!(resource instanceof IFile))
			{
				throw new RuntimeException("Error: Resource '" + filePath + "' is not a file.");
			}

			IFile file = (IFile)resource;

			// Try to refresh the editor if the file is open
			sync.syncExec(() -> {
				safeOpenEditor(file);
				refreshEditor(file);
			});

			// Read the original file content
			String originalContent = ResourceUtilities.readFileContent(file);

			// Create temporary files for diff
			Path originalFile = Files.createTempFile("original-", ".tmp");
			Path proposedFile = Files.createTempFile("proposed-", ".tmp");

			try
			{
				// Write contents to temp files
				Files.writeString(originalFile, originalContent);
				Files.writeString(proposedFile, proposedCode);

				// Generate diff using JGit
				ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
				DiffFormatter formatter = new DiffFormatter(diffOutput);
				formatter.setContext(contextLines);
				formatter.setDiffComparator(RawTextComparator.DEFAULT);

				RawText rawOriginal = new RawText(originalFile.toFile());
				RawText rawProposed = new RawText(proposedFile.toFile());

				// Write a diff header
				diffOutput.write(("--- /" + filePath + "\n").getBytes());
				diffOutput.write(("+++ /" + filePath + "\n").getBytes());

				// Generate edit list
				EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, rawOriginal, rawProposed);

				// Format the edits with proper context
				formatter.format(edits, rawOriginal, rawProposed);

				String diffResult = diffOutput.toString();
				formatter.close();

				// If there are no changes, inform the user
				if (diffResult.trim().isEmpty() || !diffResult.contains("@@"))
				{
					// No changes detected. The proposed code is identical to the existing file.
					return "";
				}

				return diffResult;
			}
			finally
			{
				// Clean up temporary files
				Files.deleteIfExists(originalFile);
				Files.deleteIfExists(proposedFile);
			}
		}
		catch (Exception e)
		{
			logger.error(e.getMessage(), e);
			throw new RuntimeException("Error generating diff: " + ExceptionUtils.getRootCauseMessage(e));
		}
	}

	/**
	 * Safely opens a file in the editor, handling null cases, and brings the editor
	 * into focus.
	 * 
	 * @param file The file to open
	 */
	private void safeOpenEditor(IFile file)
	{
		Optional.ofNullable(PlatformUI.getWorkbench()).map(IWorkbench::getActiveWorkbenchWindow)
			.map(IWorkbenchWindow::getActivePage).ifPresent(page -> {
				try
				{
					// Open the editor and get the editor reference
					var editor = IDE.openEditor(page, file);
					// Set focus to the editor
					if (editor != null)
					{
						editor.setFocus();
					}
				}
				catch (PartInitException e)
				{
					// Log but don't propagate
					logger.error(e.getMessage(), e);
				}
			});
	}

	/**
	 * Does the actual work of refreshing an editor.
	 */
	private void refreshEditor(IFile file)
	{
		try
		{
			file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

			Optional.ofNullable(PlatformUI.getWorkbench()).map(IWorkbench::getActiveWorkbenchWindow)
				.map(IWorkbenchWindow::getActivePage).ifPresent(page -> {
					// Try to find an editor for this file
					Arrays.stream(page.getEditorReferences()).map(ref -> ref.getEditor(false))
						.filter(Objects::nonNull).filter(editor -> {
							IEditorInput input = editor.getEditorInput();
							return input instanceof IFileEditorInput && file.equals(((IFileEditorInput)input).getFile());
						}).findFirst().ifPresent(editor -> {
							try
							{
								// Found the editor, now refresh it
								IEditorInput input = editor.getEditorInput();
								if (editor instanceof ITextEditor)
								{
									((ITextEditor)editor).getDocumentProvider().resetDocument(input);
								}
							}
							catch (Exception e)
							{
								throw new RuntimeException(e);
							}
						});
				});
		}
		catch (Exception e)
		{
			logger.error("Error refreshing editor: " + e.getMessage());
		}
	}
}
