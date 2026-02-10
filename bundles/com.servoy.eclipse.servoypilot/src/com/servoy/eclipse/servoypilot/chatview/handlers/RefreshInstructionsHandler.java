package com.servoy.eclipse.servoypilot.chatview.handlers;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.servoy.eclipse.servoypilot.chatview.parts.ChatView;
import com.servoy.eclipse.servoypilot.services.InstructionsLoadService;
import com.servoy.eclipse.servoypilot.services.InstructionsSaveService;

/**
 * Handler for "Refresh Instructions" menu action.
 * Loads instructions from .servoy/ directory into the AI knowledge base.
 * If .servoy/ doesn't exist, creates it first with default instructions.
 */
public class RefreshInstructionsHandler
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

		// Execute in background job
		Job job = Job.create("Refeshing Instructions/Prompts", monitor -> {
			try
			{
				monitor.beginTask("Loading instructions", 2);

				// If .servoy doesn't exist, copy it first
				if (!fileService.servoyDirectoryExists(project))
				{
					monitor.subTask("Creating .servoy directory with default instructions...");
					fileService.copyResourcesToSolution(project, monitor);
					monitor.worked(1);
				}

				// Load from .servoy
				monitor.subTask("Loading instructions into AI...");
				loaderService.clearKnowledgeBase();
				loaderService.loadFromFileSystem(project.getFolder(".servoy"));
				monitor.worked(1);

				// Show success
				shell.getDisplay().asyncExec(() -> {
					MessageDialog.openInformation(shell, "Success",
						"Instructions loaded successfully from:\n" +
							project.getName() + "/.servoy/\n\n" +
							"Rules and embeddings are now available to the AI.\n" +
							"Chat history has been cleared - starting fresh conversation.");
				});
			}
			catch (Exception e)
			{
				shell.getDisplay().asyncExec(() -> {
					MessageDialog.openError(shell, "Error",
						"Failed to load instructions:\n" + e.getMessage());
				});
			}
			finally
			{
				monitor.done();
			}
		});

		job.schedule();
	}

	/**
	 * Clear the chat view UI to give visual feedback that conversation restarted.
	 */
	private void clearChatView()
	{
		try
		{
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null)
			{
				IWorkbenchPage page = window.getActivePage();
				if (page != null)
				{
					ChatView chatView = (ChatView)page.findView("com.servoy.eclipse.servoypilot.chatview");
					if (chatView != null)
					{
						chatView.clearChatView();
					}
				}
			}
		}
		catch (Exception e)
		{
			// Ignore
		}
	}
}
