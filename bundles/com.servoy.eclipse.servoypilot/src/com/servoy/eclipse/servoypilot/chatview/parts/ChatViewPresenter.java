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
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
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
import com.servoy.eclipse.servoypilot.ai.AssistantType;
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
			.getDocumentationAssistant(), Activator.getDefault().getServoyAiModel().getExplainAssistant()
		};
		// Set default assistant to Chat
		currentAssistant = availableAssistants[0];

		// Register file modification listener
		FileModificationTracker.getInstance().setListener(new FileModificationTracker.FileModificationListener()
		{
			@Override
			public void onFileModified(String filePath)
			{
				if (chatView != null)
				{
					uiSync.asyncExec(() -> chatView.updateModifiedFilesSection());
				}
			}

			@Override
			public void onFilesCleared()
			{
				if (chatView != null)
				{
					uiSync.asyncExec(() -> chatView.clearModifiedFilesSection());
				}
			}
		});

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

			// Clear modified files tracking when switching assistants
			FileModificationTracker.getInstance().clear();

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

	/**
	 * Programmatically switch to a specific assistant type.
	 * @param assistantType The type of assistant to switch to
	 * @return true if the assistant was found and switched, false otherwise
	 */
	public boolean switchToAssistant(AssistantType assistantType)
	{
		if (availableAssistants == null || assistantType == null)
		{
			return false;
		}

		// Find the index of the requested assistant type
		for (int i = 0; i < availableAssistants.length; i++)
		{
			if (availableAssistants[i].getType() == assistantType)
			{
				final int index = i;

				// Check if already on the requested assistant
				if (currentAssistant != null && currentAssistant.getType() == assistantType)
				{
					// Already on this assistant, but ensure UI is synchronized
					applyToView(view -> view.setAssistantSelectorIndex(index));
					return true; // No need to trigger full assistant change
				}

				// Update the UI combo box
				applyToView(view -> view.setAssistantSelectorIndex(index));

				// Trigger the assistant change logic
				onAssistantChanged(index);

				return true;
			}
		}

		return false;
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
		onSendUserMessageWithContext(text, text);
	}

	/**
	 * Send a message where the displayed text differs from what's sent to the AI.
	 * Useful for hiding verbose context from the UI while providing it to the assistant.
	 * 
	 * @param displayText Text shown in the chat UI
	 * @param fullTextForAI Complete text (including hidden context) sent to the AI
	 */
	public void onSendUserMessageWithContext(String displayText, String fullTextForAI)
	{
		// Generate temporary IDs for streaming display
		String userMsgId = UUID.randomUUID().toString();
		String assistantMsgId = UUID.randomUUID().toString();

		// Detect if AI will need to read files (for large file analysis)
		boolean willReadFiles = fullTextForAI != null && fullTextForAI.contains("<large_file_notice>");

		// Show user message immediately with displayText only
		applyToView(part -> {
			part.clearUserInput();
			part.addMessage(userMsgId, "user");
			part.setMessageHtml(userMsgId, displayText);
			part.addMessage(assistantMsgId, "assistant");
			// Show different initial message if file reading is expected
			part.setMessageHtml(assistantMsgId, willReadFiles ? "Reading file content..." : "...");
		});

		// Accumulate streaming tokens
		StringBuilder accumulatedResponse = new StringBuilder();

		// LangChain4j automatically adds user message to store before calling LLM
		// Send fullTextForAI (with context) to the assistant
		currentAssistant.executeRequest(currentMemoryId, fullTextForAI)
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

		// Clear modified files tracking when switching solutions
		FileModificationTracker.getInstance().clear();

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

	// ========== Modified Files Tracking Handlers ==========

	/**
	 * Handler for clicking a file in the modified files list.
	 * Opens the modified file in an editor.
	 * Note: For full compare functionality, consider using Eclipse's Team > Show Local History
	 * 
	 * @param filePath workspace-relative path (e.g., "/ProjectName/path/file.js")
	 */
	public void onFileClick(String filePath)
	{
		System.out.println("[DEBUG] onFileClick called with filePath: " + filePath);
		logger.info("Opening compare editor for file: " + filePath);

		uiSync.asyncExec(() -> {
			try
			{
				System.out.println("[DEBUG] Starting compare editor opening in UI thread");
				
				String originalContent = FileModificationTracker.getInstance().getOriginalContent(filePath);
				if (originalContent == null)
				{
					System.out.println("[DEBUG] No original content found for: " + filePath);
					logger.error("No original content found for file: " + filePath);
					return;
				}
				System.out.println("[DEBUG] Original content found, length: " + originalContent.length());

				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
				if (!file.exists())
				{
					System.out.println("[DEBUG] File does not exist: " + filePath);
					logger.error("File does not exist: " + filePath);
					return;
				}
				System.out.println("[DEBUG] File exists: " + file.getFullPath());

				// Get current (modified) content
				String currentContent = new String(file.getContents().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				System.out.println("[DEBUG] Current content length: " + currentContent.length());

				// Use CompareEditorService for reusable compare functionality
				com.servoy.eclipse.servoypilot.services.CompareEditorService compareService = 
					com.servoy.eclipse.servoypilot.services.CompareEditorService.getInstance();
				
				System.out.println("[DEBUG] Opening compare editor via CompareEditorService");
				boolean success = compareService.openCompareEditor(file.getName(), originalContent, currentContent);
				
				if (success)
				{
					System.out.println("[DEBUG] Compare editor opened successfully");
				}
				else
				{
					System.out.println("[DEBUG] Compare editor failed to open");
				}
			}
			catch (Exception e)
			{
				System.out.println("[DEBUG] Exception in onFileClick: " + e.getClass().getName() + " - " + e.getMessage());
				e.printStackTrace();
				logger.error("Error opening compare editor for file: " + filePath, e);
			}
		});
	}

	/**
	 * Handler for keeping changes to a file.
	 * Removes file from tracking (file is already modified).
	 * 
	 * @param filePath workspace-relative path
	 */
	public void onKeepFile(String filePath)
	{
		FileModificationTracker.getInstance().keepFile(filePath);
		logger.info("File kept: " + filePath);
	}

	/**
	 * Handler for undoing changes to a file.
	 * Restores original content and removes from tracking.
	 * 
	 * @param filePath workspace-relative path
	 */
	public void onUndoFile(String filePath)
	{
		logger.info("Undoing file: " + filePath);

		uiSync.asyncExec(() -> {
			try
			{
				String originalContent = FileModificationTracker.getInstance().getOriginalContent(filePath);
				if (originalContent == null)
				{
					logger.error("No original content found for file: " + filePath);
					FileModificationTracker.getInstance().keepFile(filePath);
					return;
				}

				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
				if (file.exists())
				{
					// Restore original content
					try (InputStream stream = new ByteArrayInputStream(originalContent.getBytes(StandardCharsets.UTF_8)))
					{
						file.setContents(stream, true, true, null);
						logger.info("File restored to original content: " + filePath);
					}
				}
				else
				{
					logger.error("File does not exist, cannot restore: " + filePath);
				}

				// Remove from tracking
				FileModificationTracker.getInstance().keepFile(filePath);
			}
			catch (Exception e)
			{
				logger.error("Error restoring file: " + filePath, e);
				// Still remove from tracking even if restoration failed
				FileModificationTracker.getInstance().keepFile(filePath);
			}
		});
	}

	/**
	 * Handler for removing/dismissing a file from tracking.
	 * File stays in its current modified state.
	 * 
	 * @param filePath workspace-relative path
	 */
	public void onRemoveFile(String filePath)
	{
		FileModificationTracker.getInstance().removeFile(filePath);
		logger.info("File dismissed from tracking: " + filePath);
	}

	/**
	 * Handler for keeping all modified files.
	 * Clears all tracking (files are already modified).
	 */
	public void onKeepAll()
	{
		FileModificationTracker.getInstance().keepAll();
		logger.info("All files kept");
	}

	/**
	 * Handler for undoing all modified files.
	 * Restores all files to original content and clears tracking.
	 */
	public void onUndoAll()
	{
		logger.info("Undoing all files");

		uiSync.asyncExec(() -> {
			java.util.Map<String, String> files = FileModificationTracker.getInstance().getModifiedFiles();

			for (java.util.Map.Entry<String, String> entry : files.entrySet())
			{
				String filePath = entry.getKey();
				String originalContent = entry.getValue();

				try
				{
					IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
					if (file.exists())
					{
						// Restore original content
						try (InputStream stream = new ByteArrayInputStream(originalContent.getBytes(StandardCharsets.UTF_8)))
						{
							file.setContents(stream, true, true, null);
							logger.info("File restored to original content: " + filePath);
						}
					}
					else
					{
						logger.error("File does not exist, cannot restore: " + filePath);
					}
				}
				catch (Exception e)
				{
					logger.error("Error restoring file: " + filePath, e);
				}
			}

			// Clear all tracking after restoration attempts
			FileModificationTracker.getInstance().keepAll();
			logger.info("All files restoration complete");
		});
	}

	/**
	 * Reads the content of a file as a String.
	 * 
	 * @param file the IFile to read
	 * @return the file content as a String
	 */
	private String readFileContent(IFile file)
	{
		if (file != null && file.exists())
		{
			try (InputStream is = file.getContents())
			{
				return new String(is.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (Exception e)
			{
				logger.error("Error reading file content: " + file.getFullPath(), e);
			}
		}
		return "";
	}
}
