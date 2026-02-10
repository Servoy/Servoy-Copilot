package com.servoy.eclipse.servoypilot.chatview.handlers;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import com.servoy.eclipse.servoypilot.services.InstructionsLoadService;
import com.servoy.eclipse.servoypilot.services.InstructionsSaveService;

/**
 * Handler for "Save Instructions" menu action.
 * Copies instructions from knowledgebase bundle to .servoy/ directory in active solution.
 */
public class ResetInstructionsHandler
{
	@Execute
	public void execute(Shell shell)
	{
		InstructionsSaveService fileService = new InstructionsSaveService();
		InstructionsLoadService loaderService = new InstructionsLoadService();

		IProject project = fileService.getActiveProject();
		if (project == null)
		{
			MessageDialog.openError(shell, "No Active Solution",
				"No active Servoy solution found. Please activate a solution first.");
			return;
		}

		// Check if .servoy already exists
		if (fileService.servoyDirectoryExists(project))
		{
			boolean override = MessageDialog.openQuestion(shell,
				"Override Instructions?",
				"Instructions already exist in solution '" + project.getName() + "'.\n\n" +
					"Do you want to override them with fresh instructions from the knowledge base?");

			if (!override)
			{
				return; // User cancelled
			}
		}

		// Execute in background job with progress
		Job job = Job.create("Saving Instructions", monitor -> {
			try
			{
				monitor.beginTask("Saving instructions to .servoy directory", 3);

				// Delete existing if override
				if (fileService.servoyDirectoryExists(project))
				{
					monitor.subTask("Deleting existing instructions...");
					fileService.deleteServoyDirectory(project, monitor);
					monitor.worked(1);
				}

				// Copy fresh resources
				monitor.subTask("Copying instructions from knowledge base...");
				fileService.copyResourcesToSolution(project, monitor);
				monitor.worked(1);

				// Load into knowledge base
				monitor.subTask("Loading instructions into AI...");
				loaderService.clearKnowledgeBase();
				loaderService.loadFromFileSystem(project.getFolder(".servoy"));
				monitor.worked(1);

				// Show success
				shell.getDisplay().asyncExec(() -> {
					MessageDialog.openInformation(shell, "Success",
						"Instructions saved successfully to:\n" +
							project.getName() + "/.servoy/\n\n" +
							"Rules and embeddings have been loaded into the AI.\n" +
							"Chat history has been cleared - starting fresh conversation.");
				});
			}
			catch (Exception e)
			{
				shell.getDisplay().asyncExec(() -> {
					MessageDialog.openError(shell, "Error",
						"Failed to save instructions:\n" + e.getMessage());
				});
			}
			finally
			{
				monitor.done();
			}
		});
		job.schedule();
	}
}
