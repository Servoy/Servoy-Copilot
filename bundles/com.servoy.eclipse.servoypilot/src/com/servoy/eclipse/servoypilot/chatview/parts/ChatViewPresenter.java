package com.servoy.eclipse.servoypilot.chatview.parts;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.IServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.tools.ResourceUtilities;

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
	private final List<ChatMessage> contents = new ArrayList<>();
	private String currentMemoryId = "default"; // Memory ID for conversation isolation
	private Object activeProjectListener; // IActiveProjectListener proxy

	public static final String JOB_PREFIX = "ServoyAI: ";
	
	@PostConstruct
	public void init()
	{
		// Register solution activation listener
		try
		{
			IServoyModel servoyModel = ServoyModelFinder.getServoyModel();
			if (servoyModel != null)
			{
				// Use reflection to avoid compile-time dependency on IActiveProjectListener
				Class<?> listenerClass = Class.forName("com.servoy.eclipse.core.IActiveProjectListener");
				
				Object listener = java.lang.reflect.Proxy.newProxyInstance(
					listenerClass.getClassLoader(),
					new Class<?>[] { listenerClass },
					(proxy, method, args) -> {
						if ("activeProjectChanged".equals(method.getName()) && args != null && args.length > 0)
						{
							ServoyProject project = (ServoyProject)args[0];
							if (project != null)
							{
								String projectName = project.getProject().getName();
								onSolutionActivated(projectName);
							}
						}
						else if ("activeProjectWillChange".equals(method.getName()))
						{
							return Boolean.TRUE;
						}
						return null;
					}
				);
				
				activeProjectListener = listener;
				
				// Add listener using reflection
				servoyModel.getClass().getMethod("addActiveProjectListener", listenerClass)
					.invoke(servoyModel, listener);
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
					Class<?> listenerClass = Class.forName("com.servoy.eclipse.core.IActiveProjectListener");
					servoyModel.getClass().getMethod("removeActiveProjectListener", listenerClass)
						.invoke(servoyModel, activeProjectListener);
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
		// TODO stop/clear any ongoing operations
		onStop();
		applyToView(view -> {
			view.clearChatView();
			view.clearUserInput();
//	            view.clearAttachments();
		});
	}

	public void applyToView(Consumer< ? super ChatView> consumer)
	{
		consumer.accept(chatView);
	}

	public void onStop()
	{
		contents.clear();
		var jobs = jobManager.find(null);
		Arrays.stream(jobs).filter(job -> job.getName().startsWith(JOB_PREFIX)).forEach(Job::cancel);

		applyToView(messageView -> {
			messageView.setInputEnabled(true);
		});

	}

	public void onSendUserMessage(String text)
	{
		ChatMessage message = new TextChatMessage(UUID.randomUUID().toString(), "user", text);
		contents.add(message);
		TextChatMessage assistantMessage = new TextChatMessage(UUID.randomUUID().toString(), "assistant");

		applyToView(part -> {
			part.clearUserInput();
//	            part.clearAttachments();
			part.addMessage(message.getId(), message.getRole());
			part.setMessageHtml(message.getId(), text);
			part.addMessage(assistantMessage.getId(), assistantMessage.getRole());
			part.setMessageHtml(assistantMessage.getId(), "...");
//	            attachments.clear();
		});

		contents.add(assistantMessage);
		
		// DEBUG: Log what we're sending
		System.out.println("=== SENDING MESSAGE ===");
		System.out.println("MemoryId: " + currentMemoryId);
		System.out.println("User message: " + text);
		System.out.println("UI contents count: " + contents.size());
		System.out.println("=======================");
		
		// CHANGED: Use new API with memoryId - ChatMemory handles history automatically
		Activator.getDefault().getChatModel().chat(currentMemoryId, text).onPartialResponse(partial -> {
			assistantMessage.appendContent(partial);
			applyToView(part -> {
				part.setMessageHtml(assistantMessage.getId(),
					assistantMessage.getContent().text() + partial.toString());
			});
		}).onCompleteResponse(fullResponse -> {
			assistantMessage.setContent(fullResponse.aiMessage().text());
			applyToView(part -> {
				part.setMessageHtml(assistantMessage.getId(), assistantMessage.getContent().text());
			});
			System.out.println("=== RESPONSE RECEIVED ===");
			System.out.println("Response length: " + fullResponse.aiMessage().text().length() + " chars");
			System.out.println("=========================");
		}).onError(error -> {
			applyToView(part -> {
				part.setMessageHtml(assistantMessage.getId(), "Error: " + error.getMessage());
			});
			logger.error("Error getting assistant response", error);
			System.out.println("=== ERROR ===");
			System.out.println("Error: " + error.getMessage());
			System.out.println("=============");
		}).start();

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

	public void onRemoveMessage(String messageId)
	{
		contents.stream().filter(message -> messageId.equals(message.getId())).findFirst()
			.ifPresent(messageToRemove -> contents.remove(messageToRemove));
		applyToView(view -> {
			view.removeMessage(messageId);
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
		// Clear LangChain4j chat memory for the old solution
		Activator.getDefault().getServoyAiModel().clearMemory(currentMemoryId);
		
		// Update memory ID to new solution
		currentMemoryId = projectName != null ? projectName : "default";
		
		// Clear UI conversation history
		contents.clear();
		
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
}
