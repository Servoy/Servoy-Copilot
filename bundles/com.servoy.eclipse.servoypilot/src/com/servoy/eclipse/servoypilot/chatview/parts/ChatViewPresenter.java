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
package com.servoy.eclipse.servoypilot.chatview.parts;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.WizardNewFileCreationPage;

import com.servoy.eclipse.core.IActiveProjectListener;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.IAssistant;
import com.servoy.eclipse.servoypilot.services.InstructionsLoadService;
import com.servoy.eclipse.servoypilot.services.InstructionsSaveService;
import com.servoy.eclipse.servoypilot.tools.ResourceUtilities;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

@Creatable
public class ChatViewPresenter
{

	@Inject
	private ILog logger;

	@Inject
	private IJobManager jobManager;

	@Inject
	private ApplyPatchWizardHelper applyPatchWizzardHelper;

	@Inject
	private CodeEditingService codeEditingService;

	@Inject
	private UISynchronize uiSync;

	private ChatView chatView;
	private String solutionName = "default"; // Current solution name
	private String currentMemoryId = "default-vibe"; // Memory ID for chat assistant conversation isolation
	private IActiveProjectListener activeProjectListener; // Solution activation listener
	private IAssistant currentAssistant; // Currently active assistant
	private IAssistant[] availableAssistants; // Array of available assistants for combo population

	public static final String JOB_PREFIX = "ServoyAI: ";

	@PostConstruct
	public void init()
	{
		// Initialize available assistants
		availableAssistants = new IAssistant[] { Activator.getDefault().getServoyAiModel().getVibeCodingAssistant(), Activator.getDefault().getServoyAiModel()
			.getDocumentationAssistant()
		};
		// Set default assistant to Chat
		currentAssistant = availableAssistants[0];

		// Register solution activation listener
		try
		{
			IServoyModel servoyModel = ServoyModelFinder.getServoyModel();
			if (servoyModel != null)
			{
				activeProjectListener = new IActiveProjectListener()
				{
					@Override
					public void activeProjectChanged(ServoyProject activeProject)
					{
						if (activeProject != null)
						{
							String projectName = activeProject.getProject().getName();
							onSolutionActivated(projectName);
						}
					}

					@Override
					public boolean activeProjectWillChange(ServoyProject activeProject, ServoyProject toProject)
					{
						return true;
					}

					@Override
					public void activeProjectUpdated(ServoyProject activeProject, int updateInfo)
					{
						// Not needed for our use case
					}
				};

				// addActiveProjectListener is on concrete ServoyModel class, not IServoyModel interface
				// Use reflection to call it without casting to concrete type
				servoyModel.getClass().getMethod("addActiveProjectListener", IActiveProjectListener.class)
					.invoke(servoyModel, activeProjectListener);
				logger.info("Solution activation listener registered successfully");
			}
		}
		catch (Exception e)
		{
			logger.error("Failed to register solution activation listener", e);
		}
	}

	@PreDestroy
	public void dispose()
	{
		// Remove solution activation listener
		if (activeProjectListener != null)
		{
			try
			{
				IServoyModel servoyModel = ServoyModelFinder.getServoyModel();
				if (servoyModel != null)
				{
					// removeActiveProjectListener is on concrete ServoyModel class, not IServoyModel interface
					servoyModel.getClass().getMethod("removeActiveProjectListener", IActiveProjectListener.class)
						.invoke(servoyModel, activeProjectListener);
					logger.info("Solution activation listener removed successfully");
				}
			}
			catch (Exception e)
			{
				logger.error("Failed to remove solution activation listener", e);
			}
			activeProjectListener = null;
		}
	}

	public void onClear()
	{
		// Stop any ongoing operations
		onStop();

		// Clear memory store for current assistant
		Activator.getDefault().getServoyAiModel().clearMemory(currentMemoryId);

		// Clear UI
		applyToView(view -> {
			view.clearChatView();
			view.clearUserInput();
		});

		logger.info("Cleared conversation for memory ID: " + currentMemoryId);
	}

	public void onAssistantChanged(int selectedIndex)
	{
		if (selectedIndex >= 0 && selectedIndex < availableAssistants.length)
		{
			currentAssistant = availableAssistants[selectedIndex];

			// Update memory ID with new assistant's suffix
			currentMemoryId = solutionName + currentAssistant.getType().getMemorySuffix();

			// Clear UI
			applyToView(view -> {
				view.clearChatView();
				view.clearUserInput();
			});

			// Reload messages from new assistant's memory
			refreshViewFromMemory();

			logger.info("Switched to assistant: " + currentAssistant.getDisplayName() + " with memory ID: " + currentMemoryId);
		}
	}

	public void populateAssistantSelector()
	{
		if (availableAssistants != null && availableAssistants.length > 0)
		{
			String[] names = new String[availableAssistants.length];
			for (int i = 0; i < availableAssistants.length; i++)
			{
				names[i] = availableAssistants[i].getDisplayName();
			}
			applyToView(view -> view.setAssistantSelectorItems(names));
		}
	}

	public void applyToView(Consumer< ? super ChatView> consumer)
	{
		consumer.accept(chatView);
	}

	/**
	 * Refresh UI view from memory store (single source of truth).
	 * Filters out system and tool messages, displays only user and AI messages.
	 */
	private void refreshViewFromMemory()
	{
		// Get messages from store for current assistant
		List<ChatMessage> allMessages = Activator.getDefault().getServoyAiModel().getSharedMemoryStore().getMessages(currentMemoryId);

		if (allMessages == null || allMessages.isEmpty())
		{
			// No messages - view already cleared
			return;
		}

		// Clear existing UI
		applyToView(view -> view.clearChatView());

		// Filter to displayable messages (User + AI only)
		int filteredIndex = 0;
		for (ChatMessage message : allMessages)
		{
			// Skip System and Tool messages
			if (message instanceof SystemMessage || message instanceof ToolExecutionResultMessage)
			{
				continue;
			}

			// Determine role and get text using pattern matching
			String role;
			String text;
			if (message instanceof UserMessage userMsg)
			{
				role = "user";
				text = userMsg.singleText();
			}
			else if (message instanceof AiMessage aiMsg)
			{
				role = "assistant";
				text = aiMsg.text();
			}
			else
			{
				continue; // Skip unknown types
			}

			// Generate UI message ID
			String messageId = "msg-" + filteredIndex;
			filteredIndex++;

			// Render in UI
			applyToView(view -> {
				view.addMessage(messageId, role);
				view.setMessageHtml(messageId, text); // Markdown→HTML conversion happens in setMessageHtml
			});
		}
	}

	public void onStop()
	{
		// Cancel ongoing jobs
		var jobs = jobManager.find(null);
		Arrays.stream(jobs).filter(job -> job.getName().startsWith(JOB_PREFIX)).forEach(Job::cancel);

		applyToView(messageView -> {
			messageView.setInputEnabled(true);
		});
	}

	public void onSendUserMessage(String text)
	{
		// Generate temporary IDs for streaming display
		String userMsgId = UUID.randomUUID().toString();
		String assistantMsgId = UUID.randomUUID().toString();

		// Show user message immediately (temporary, will be replaced by refresh)
		applyToView(part -> {
			part.clearUserInput();
			part.addMessage(userMsgId, "user");
			part.setMessageHtml(userMsgId, text);
			part.addMessage(assistantMsgId, "assistant");
			part.setMessageHtml(assistantMsgId, "...");
		});

		// Accumulate streaming tokens
		StringBuilder accumulatedResponse = new StringBuilder();

		// LangChain4j automatically adds user message to store before calling LLM
		currentAssistant.executeRequest(currentMemoryId, text)
			.onPartialResponse(partial -> {
				// Accumulate tokens and update display
				accumulatedResponse.append(partial);
				applyToView(part -> {
					part.setMessageHtml(assistantMsgId, accumulatedResponse.toString());
				});
			})
			.onCompleteResponse(fullResponse -> {
				// LangChain4j automatically added AI response to store
				// No refresh needed - streaming already shows full response
				// Refresh would cause flickering by clearing and rebuilding UI
			})
			.onError(error -> {
				applyToView(part -> {
					part.setMessageHtml(assistantMsgId, "Error: " + error.getMessage());
				});
				logger.error("Error getting assistant response", error);
			})
			.start();
	}

	public void onAttachmentAdded(ImageData imageData)
	{
		// TODO Auto-generated method stub

	}

	public void onCopyCode(String codeBlock)
	{
		var clipboard = new Clipboard(PlatformUI.getWorkbench().getDisplay());
		var textTransfer = TextTransfer.getInstance();
		clipboard.setContents(new Object[] { codeBlock }, new Transfer[] { textTransfer });
		clipboard.dispose();

	}

	public void onApplyPatch(String codeBlock)
	{
		applyPatchWizzardHelper.showApplyPatchWizardDialog(codeBlock, null);
	}

	public void onInsertCode(String codeBlock)
	{
		uiSync.asyncExec(() -> {
			try
			{
				Optional.ofNullable(PlatformUI.getWorkbench()).map(workbench -> workbench.getActiveWorkbenchWindow())
					.map(window -> window.getActivePage()).map(page -> page.getActiveEditor())
					.flatMap(editor -> Optional
						.ofNullable(editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class)))
					.ifPresent(textEditor -> {
						var selectionProvider = textEditor.getSelectionProvider();
						var document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());

						if (selectionProvider != null && document != null)
						{
							var selection = (org.eclipse.jface.text.ITextSelection)selectionProvider
								.getSelection();
							try
							{
								// Replace selection or insert at cursor position
								if (selection.getLength() > 0)
								{
									// Replace selected text
									document.replace(selection.getOffset(), selection.getLength(), codeBlock);
								}
								else
								{
									// Insert at cursor position
									document.replace(selection.getOffset(), 0, codeBlock);
								}
							}
							catch (org.eclipse.jface.text.BadLocationException e)
							{
								logger.error("Error inserting code at location", e);
							}
						}
						else
						{
							logger.error("Selection provider or document is null");
						}
					});
			}
			catch (Exception e)
			{
				logger.error("Error inserting code", e);
			}
		});
	}

	public void onDiffCode(String codeBlock)
	{
		uiSync.asyncExec(() -> {
			try
			{
				Optional.ofNullable(PlatformUI.getWorkbench()).map(workbench -> workbench.getActiveWorkbenchWindow())
					.map(window -> window.getActivePage()).map(page -> page.getActiveEditor())
					.flatMap(editor -> Optional
						.ofNullable(editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class)))
					.ifPresent(textEditor -> {
						// Get the file information
						if (textEditor.getEditorInput() instanceof org.eclipse.ui.part.FileEditorInput)
						{
							org.eclipse.ui.part.FileEditorInput fileInput = (org.eclipse.ui.part.FileEditorInput)textEditor
								.getEditorInput();

							// Get project name and file path
							String projectName = fileInput.getFile().getProject().getName();
							String filePath = fileInput.getFile().getProjectRelativePath().toString();

							// Generate diff using the CodeEditingService
							String diff = codeEditingService.generateCodeDiff(projectName, filePath, codeBlock, 3 // Default
																													// context
																													// lines
							);

							if (diff != null && !diff.isBlank())
							{
								// Show the apply patch wizard with the generated diff and preselected project
								applyPatchWizzardHelper.showApplyPatchWizardDialog(diff, projectName);
							}
							else
							{
								logger.info("No differences found between current code and provided code block");
							}
						}
						else
						{
							logger.error("Cannot get file information from editor");
						}
					});
			}
			catch (Exception e)
			{
				logger.error("Error generating diff for code", e);
			}
		});
	}

	public void onNewFile(String codeBlock, String lang)
	{
		uiSync.asyncExec(() -> {
			try
			{
				IProject project = Optional.ofNullable(PlatformUI.getWorkbench())
					.map(IWorkbench::getActiveWorkbenchWindow).map(IWorkbenchWindow::getActivePage)
					.map(IWorkbenchPage::getActiveEditor).map(editor -> editor.getEditorInput())
					.filter(input -> input instanceof org.eclipse.ui.part.FileEditorInput)
					.map(input -> ((org.eclipse.ui.part.FileEditorInput)input).getFile().getProject())
					.orElse(null);

				if (project != null)
				{
					// Create suggested file name and path based on language
					String suggestedFileName = ResourceUtilities.getSuggestedFileName(lang, codeBlock);
					IPath suggestedPath = ResourceUtilities.getSuggestedPath(project, lang, codeBlock);
					WizardNewFileCreationPage newFilePage = new WizardNewFileCreationPage("NewFilePage",
						new StructuredSelection(project));
					newFilePage.setTitle("New File");
					newFilePage.setDescription(String.format("Create a new %s file in the project",
						ResourceUtilities.getFileExtensionForLang(lang)));

					// Set suggested file name and path
					if (suggestedPath != null)
					{
						newFilePage.setContainerFullPath(suggestedPath);
					}
					if (suggestedFileName != null && !suggestedFileName.isBlank())
					{
						newFilePage.setFileName(suggestedFileName);
					}

					Wizard wizard = new Wizard()
					{
						@Override
						public void addPages()
						{
							addPage(newFilePage);
						}

						@Override
						public boolean performFinish()
						{
							IFile newFile = newFilePage.createNewFile();
							if (newFile != null)
							{
								try (InputStream stream = new ByteArrayInputStream(
									codeBlock.getBytes(StandardCharsets.UTF_8)))
								{
									newFile.setContents(stream, true, true, null);
									logger.info("New file created at: " + newFile.getFullPath().toString());
									return true;
								}
								catch (CoreException | IOException e)
								{
									logger.error("Error creating new file", e);
								}
							}
							return false;
						}
					};

					WizardDialog dialog = new WizardDialog(
						PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), wizard);
					dialog.open();
				}
				else
				{
					logger.error("No active project found");
				}
			}
			catch (Exception e)
			{
				logger.error("Error opening new file wizard", e);
			}
		});
	}

	public void setChatView(ChatView chatView)
	{
		this.chatView = chatView;
	}


	/**
	 * Called when a Servoy solution is activated
	 * @param projectName the name of the activated project
	 */
	public void onSolutionActivated(String projectName)
	{
		// Clear all memories (vibe + documentation) for the old solution
		Activator.getDefault().getServoyAiModel().clearAllMemories(solutionName);

		// Update solution name
		solutionName = projectName != null ? projectName : "default";

		// Update memory ID with current assistant suffix
		currentMemoryId = solutionName + currentAssistant.getType().getMemorySuffix();

		// Manage knowledge base: load from .servoy if exists, otherwise load default from bundle
		IProject project = getProjectByName(projectName);
		if (project != null)
		{
			InstructionsSaveService fileService = new InstructionsSaveService();
			InstructionsLoadService loaderService = new InstructionsLoadService();

			try
			{
				loaderService.clearKnowledgeBase();

				if (fileService.servoyDirectoryExists(project))
				{
					// Load from solution-specific .servoy directory
					loaderService.loadFromFileSystem(project.getFolder(".servoy"));
					logger.info("Knowledge base loaded from .servoy directory for solution: " + projectName);
				}
				else
				{
					// Load default knowledge base from bundle resources
					loaderService.loadFromBundleResources();
					logger.info("Default knowledge base loaded from bundle for solution: " + projectName);
				}
			}
			catch (Exception e)
			{
				logger.error("Error loading knowledge base for solution: " + projectName, e);
			}
		}

		applyToView(view -> {
			view.clearChatView();

			// Add a system notification message
			String notificationId = UUID.randomUUID().toString();
			view.addMessage(notificationId, "system");
			view.setMessageHtml(notificationId,
				"<div style='padding: 10px; background-color: #e8f5e9; border-left: 4px solid #4caf50; margin: 10px 0;'>" +
					"<strong>New session started</strong><br/>" +
					"Solution: <strong>" + projectName + "</strong><br/>" +
					"Conversation history has been reset." +
					"</div>");
		});
	}

	/**
	 * Get IProject by project name
	 * @param projectName the name of the Servoy project
	 * @return the IProject or null if not found
	 */
	private IProject getProjectByName(String projectName)
	{
		if (projectName == null)
		{
			return null;
		}

		ServoyProject servoyProject = ServoyModelFinder.getServoyModel().getServoyProject(projectName);

		return servoyProject != null ? servoyProject.getProject() : null;
	}
}
