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
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
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
import org.eclipse.ui.IEditorPart;
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
import com.servoy.eclipse.servoypilot.ai.QuickFixAssistant;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.servoypilot.quickfix.QuickFixPresenter;
import com.servoy.eclipse.servoypilot.tools.ResourceUtilities;
import com.servoy.eclipse.servoypilot.tools.dto.CodeChanges;
import com.servoy.eclipse.servoypilot.tools.dto.SourceEdit;

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

	// Apply Fix validation metrics
	private int validationsPassed = 0;
	private int validationsFailed = 0;

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
	private AssistantType currentAssistant = AssistantType.VIBE_CODING; // Currently active assistant
	private AssistantType[] availableAssistants = AssistantType.values(); // Array of available assistants for combo population

	public static final String JOB_PREFIX = "ServoyAI: ";

	@PostConstruct
	public void init()
	{
		// Initialize available assistants

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

				// Check if a solution is already active (listener only fires on changes, not initial state)
				ServoyProject activeProject = servoyModel.getActiveProject();
				if (activeProject != null)
				{
					String projectName = activeProject.getProject().getName();
					solutionName = projectName;
					currentMemoryId = solutionName + currentAssistant.getMemorySuffix();
					logger.info("Initialized with already-active solution: " + solutionName + " (memoryId: " + currentMemoryId + ")");
				}
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
			currentMemoryId = solutionName + currentAssistant.getMemorySuffix();

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
			if (availableAssistants[i] == assistantType)
			{
				final int index = i;

				// Check if already on the requested assistant
				if (currentAssistant != null && currentAssistant == assistantType)
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

			// Skip empty messages to prevent blank lines in UI
			if (text == null || text.trim().isEmpty())
			{
				continue;
			}

			// Clean user messages if they contain analyze, error context, or "explain"
			if (role.equals("user") && isExplainMessage(text))
			{
				text = cleanExplainMessage(text);
			}

			// For AI messages, check if they contain error_context tags and recreate buttons
			final String originalText = text; // Keep original with tags for button creation
			if (role.equals("assistant") && text.contains("<error_context"))
			{
				text = cleanExplainMessage(text);
			}

			// Generate UI message ID
			String messageId = "msg-" + filteredIndex;
			filteredIndex++;

			final String displayText = text; // Make effectively final for lambda
			// Render in UI
			applyToView(view -> {
				view.addMessage(messageId, role);
				view.setMessageHtml(messageId, displayText); // Markdown→HTML conversion happens in setMessageHtml
			});

			// If this AI message has error_context tags, recreate the "Quick Fix" buttons
			if (role.equals("assistant") && originalText.contains("<error_context"))
			{
				invokeQuickFixForError(originalText, messageId, originalText);
			}
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

	/**
	 * The method looks for key indicators like "analyze", "explain", or the presence of <error_context> tags to identify these messages.
	 * 
	 * @param message The user message text to analyze
	 * */
	private boolean isExplainMessage(String message)
	{
		if (message == null)
		{
			return false;
		}

		String lower = message.toLowerCase();
		return lower.contains("analyze") || lower.contains("<error_context>") || lower.toLowerCase().contains("explain");
	}

	/**
	 * Cleans explain message for display by removing implementation details like
	 * error tag and context hints that are meant for AI only.
	 * 
	 * @param message The original message with AI instructions
	 * @return Cleaned message suitable for UI display
	 */
	private String cleanExplainMessage(String message)
	{
		if (message == null)
		{
			return "";
		}

		//return message; // For debugging

		// Remove <error_context>...</error_context> tags and content user message
		String cleaned = message.replaceAll("<error_context>[\\s\\S]*?</error_context>", "");
		// Also remove self-closing tags if AI used that format: <error_context ... />
		cleaned = cleaned.replaceAll("<error_context(?:\\s+[^>]*)?/>", "");

		// Remove context hints section: **Context hints:**\n```\n...\n``` (including newlines)
		cleaned = cleaned.replaceAll("\\n\\n\\*\\*Context hints:\\*\\*\\n```\\n[\\s\\S]*?\\n```", "");

		// Ensure a blank line before tool result headers (=== ... ===) so the
		// intro sentence and the tool result render as separate paragraphs
		cleaned = cleaned.replaceAll("([^\\n])(\\n)(===)", "$1\n\n$3");

		// Clean up multiple consecutive newlines and trim
		cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();

		return cleaned;
	}

	/**
	 * Parses error_context tags from Explain response and adds "Apply Error Fix" buttons.
	 * Does NOT call QuickFix - Explain already provides the fixes in code blocks.
	 * 
	 * @param responseText The Explain assistant's response containing error_context tags
	 * @param explainMessageId The ID of the Explain assistant's message
	 * @param explainResponse The full Explain response (same as responseText)
	 */
	private void invokeQuickFixForError(String responseText, String explainMessageId, String explainResponse)
	{
		// Extract ALL error_context tags with their associated fix code
		java.util.List<ErrorContextWithFix> errorContexts = parseAllErrorContextsWithFixes(responseText);

		if (errorContexts.isEmpty())
		{
			logger.info("No error_context tags found in Explain response");
			return;
		}

		logger.info("Found " + errorContexts.size() + " error_context tag(s), adding buttons...");

		// Add buttons directly on UI thread - no QuickFix needed!
		uiSync.asyncExec(() -> {
			applyToView(view -> {
				for (int i = 0; i < errorContexts.size(); i++)
				{
					ErrorContextWithFix context = errorContexts.get(i);

					logger.info("Adding button #" + (i + 1) + " for " + context.filePath + ":" + context.lineNumber);
					logger.info("  Error text: " + context.errorText);
					logger.info("  Fix code: " + context.fixCode);

					// Inject button data directly (Explain already provided the fix)
					view.injectErrorFixData(explainMessageId, context.filePath, context.lineNumber,
						context.errorText, context.fixCode, i);
				}
			});
		});
	}

	/**
	 * Parses ALL error_context tags and extracts the fix code that follows each tag.
	 * Pattern: <error_context ... />\n...\nSuggested Fix (Line XX):\nCODE
	 */
	private java.util.List<ErrorContextWithFix> parseAllErrorContextsWithFixes(String message)
	{
		java.util.List<ErrorContextWithFix> contexts = new java.util.ArrayList<>();
		String lastFilePath = null; // Track file path for "Additional" sections

		// Pattern to match: <error_context tag> followed later by "Suggested Fix (Line XX): CODE"
		// First, find all error_context tags
		java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile(
			"<error_context\\s+file=\"([^\"]+)\"\\s+line=\"(\\d+)\"\\s+error=\"([^\"]+)\"\\s*/>");

		java.util.regex.Matcher tagMatcher = tagPattern.matcher(message);

		while (tagMatcher.find())
		{
			String filePath = tagMatcher.group(1);
			int lineNumber = Integer.parseInt(tagMatcher.group(2));
			String errorText = tagMatcher.group(3);

			lastFilePath = filePath; // Remember for "Additional" sections

			// Extract key term from error description for button text
			java.util.regex.Pattern termPattern = java.util.regex.Pattern.compile("'([^']+)'");
			java.util.regex.Matcher termMatcher = termPattern.matcher(errorText);
			String keyTerm = termMatcher.find() ? termMatcher.group(1) : errorText;

			// Now find the corresponding "Suggested Fix (Line XX):" code block
			String fixCode = findFixCodeForLine(message, lineNumber);

			if (fixCode != null && !fixCode.isEmpty())
			{
				// Validate before adding
				if (validateErrorContext(filePath, lineNumber, fixCode))
				{
					contexts.add(new ErrorContextWithFix(filePath, lineNumber, keyTerm, fixCode));
					logger.info("Parsed error with fix: file=" + filePath + ", line=" + lineNumber + ", fix=" + fixCode);
					validationsPassed++;
				}
				else
				{
					logger.warn("Skipping invalid error context: file=" + filePath + ", line=" + lineNumber);
					validationsFailed++;
				}
			}
			else
			{
				// If no fix code found, use a simple replacement (extract from error description)
				String simpleFix = extractSimpleFix(errorText);
				if (validateErrorContext(filePath, lineNumber, simpleFix))
				{
					contexts.add(new ErrorContextWithFix(filePath, lineNumber, keyTerm, simpleFix));
					logger.info("Parsed error with simple fix: file=" + filePath + ", line=" + lineNumber);
					validationsPassed++;
				}
				else
				{
					logger.warn("Skipping invalid simple fix: file=" + filePath + ", line=" + lineNumber);
					validationsFailed++;
				}
			}
		}

		// Also look for "Additional Typo/Error found on Line XX:" sections without error_context tags
		java.util.regex.Pattern additionalPattern = java.util.regex.Pattern.compile(
			"Additional (?:Typo|Error)[^:]*on Line (\\d+):");
		java.util.regex.Matcher additionalMatcher = additionalPattern.matcher(message);

		while (additionalMatcher.find())
		{
			int lineNumber = Integer.parseInt(additionalMatcher.group(1));

			// Check if we already have an error for this line
			boolean alreadyExists = contexts.stream().anyMatch(ctx -> ctx.lineNumber == lineNumber);
			if (alreadyExists)
			{
				continue; // Skip duplicates
			}

			// Try to find the fix code
			String fixCode = findFixCodeForLine(message, lineNumber);

			if (fixCode != null && !fixCode.isEmpty() && lastFilePath != null)
			{
				// Use a generic error text for the button
				String errorText = "Line " + lineNumber + " issue";
				contexts.add(new ErrorContextWithFix(lastFilePath, lineNumber, errorText, fixCode));
				logger.info("Parsed additional error: file=" + lastFilePath + ", line=" + lineNumber + ", fix=" + fixCode);
			}
		}

		// Log validation metrics
		logger.info(
			"Apply Fix validation complete: " + validationsPassed + " passed, " + validationsFailed + " failed, " + contexts.size() + " total error contexts");

		return contexts;
	}

	/**
	 * Finds the fix code for a specific line number.
	 * Looks for "Suggested Fix (Line XX):" followed by code.
	 */
	private String findFixCodeForLine(String message, int lineNumber)
	{
		logger.info("Searching for fix code for line " + lineNumber);

		// Debug: Print a sample of the message to see the actual format
		int searchStart = message.indexOf("Line " + lineNumber);
		if (searchStart >= 0)
		{
			int sampleStart = Math.max(0, searchStart - 50);
			int sampleEnd = Math.min(message.length(), searchStart + 200);
			logger.info("Sample around 'Line " + lineNumber + "':\n" + message.substring(sampleStart, sampleEnd));
		}
		else
		{
			logger.warn("Could not find 'Line " + lineNumber + "' in AI response - printing first 500 chars:");
			logger.info(message.substring(0, Math.min(500, message.length())));
		}

		// Pattern to match: **Suggested Fix (Line XX):** or Suggested Fix for file.js (Line XX): (with or without bold)
		// followed by ```javascript\nCODE\n``` or just CODE
		// Multiple strategies to handle different AI output formats:

		// Strategy 1: Code block with backticks (handle optional markdown bold ** and optional filename)
		String pattern1 = "\\*{0,2}Suggested Fix(?:\\s+for\\s+[^(]+)?\\s*\\(Line " + lineNumber + "\\):\\*{0,2}\\s*```(?:javascript)?\\s*([^`]+)```";
		java.util.regex.Pattern fixPattern1 = java.util.regex.Pattern.compile(pattern1, java.util.regex.Pattern.DOTALL);
		java.util.regex.Matcher fixMatcher1 = fixPattern1.matcher(message);

		if (fixMatcher1.find())
		{
			String fixCode = fixMatcher1.group(1).trim();
			logger.info("Found fix code (strategy 1 - code block): " + fixCode);
			return fixCode;
		}

		// Strategy 2: Look for code line that starts with common JS keywords after the header
		// This handles cases where AI adds explanation text before the code
		String pattern2 = "\\*{0,2}Suggested Fix(?:\\s+for\\s+[^(]+)?\\s*\\(Line " + lineNumber +
			"\\):\\*{0,2}[\\s\\S]*?^\\s*((?:var|const|let|function|if|for|while|return|application|plugins|forms)[^\\n;]+;?)";
		java.util.regex.Pattern fixPattern2 = java.util.regex.Pattern.compile(pattern2, java.util.regex.Pattern.MULTILINE);
		java.util.regex.Matcher fixMatcher2 = fixPattern2.matcher(message);

		if (fixMatcher2.find())
		{
			String fixCode = fixMatcher2.group(1).trim();
			// Remove inline comments from fix code
			if (fixCode.contains("//"))
			{
				fixCode = fixCode.substring(0, fixCode.indexOf("//")).trim();
			}
			logger.info("Found fix code (strategy 2 - keyword match): " + fixCode);
			return fixCode;
		}

		// Strategy 3: Fallback - get the last non-empty line before error_context tag or next section
		String pattern3 = "\\*{0,2}Suggested Fix(?:\\s+for\\s+[^(]+)?\\s*\\(Line " + lineNumber +
			"\\):\\*{0,2}[\\s\\S]*?\\n([^\\n]+?)\\s*(?:<error_context|Additional|$)";
		java.util.regex.Pattern fixPattern3 = java.util.regex.Pattern.compile(pattern3);
		java.util.regex.Matcher fixMatcher3 = fixPattern3.matcher(message);

		if (fixMatcher3.find())
		{
			String fixCode = fixMatcher3.group(1).trim();
			// Remove inline comments
			if (fixCode.contains("//"))
			{
				fixCode = fixCode.substring(0, fixCode.indexOf("//")).trim();
			}
			// Ignore if it's just explanation text (doesn't contain code characters)
			if (fixCode.contains("(") || fixCode.contains(";") || fixCode.contains("="))
			{
				logger.info("Found fix code (strategy 3 - last line): " + fixCode);
				return fixCode;
			}
		}

		logger.warn("No fix code found for line " + lineNumber);
		return null;
	}

	/**
	 * Extracts a simple fix from error description like "'aplication' should be 'application'".
	 * Returns the corrected code line.
	 */
	private String extractSimpleFix(String errorText)
	{
		// Pattern: 'wrong' should be 'correct'
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("'([^']+)'\\s+should be\\s+'([^']+)'");
		java.util.regex.Matcher matcher = pattern.matcher(errorText);

		if (matcher.find())
		{
			String wrong = matcher.group(1);
			String correct = matcher.group(2);
			// Return a simple replacement hint
			return correct;
		}

		return "application"; // Default fallback
	}

	/**
	 * Validates an error context before creating Apply Fix button.
	 * Checks: file exists, line number in bounds, fix code matches actual line.
	 * @return true if valid, false if validation fails
	 */
	private boolean validateErrorContext(String filePath, int lineNumber, String fixCode)
	{
		try
		{
			// Resolve file
			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
			IFile file = root.getFile(new Path(filePath));

			if (!file.exists())
			{
				// Try project-relative path
				String[] parts = filePath.split("/", 3);
				if (parts.length >= 2)
				{
					String projectName = parts[1];
					String restOfPath = parts.length > 2 ? parts[2] : "";
					IProject project = root.getProject(projectName);
					if (project.exists())
					{
						file = project.getFile(new Path(restOfPath));
					}
				}

				if (!file.exists())
				{
					logger.warn("Apply Fix validation failed: file not found: " + filePath);
					return false;
				}
			}

			// Count lines in file
			int actualLineCount = 0;
			String[] allLines = null;
			try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
			{
				allLines = reader.lines().toArray(String[]::new);
				actualLineCount = allLines.length;
			}

			// Validate line number
			if (lineNumber < 1 || lineNumber > actualLineCount)
			{
				logger.warn("Apply Fix validation failed: line " + lineNumber +
					" exceeds file length " + actualLineCount + " in " + filePath);
				return false;
			}

			// Validate fix code matches actual line (compare first line of fix with actual line)
			if (fixCode != null && !fixCode.isEmpty() && allLines != null)
			{
				String actualLine = allLines[lineNumber - 1].trim();
				String fixFirstLine = fixCode.split("\\n")[0].trim();

				// Compare ignoring differences in whitespace
				String normalizedActual = actualLine.replaceAll("\\s+", " ");
				String normalizedFix = fixFirstLine.replaceAll("\\s+", " ");

				// Check if they're similar (Levenshtein distance check)
				int distance = levenshteinDistance(normalizedActual, normalizedFix);
				int maxLength = Math.max(normalizedActual.length(), normalizedFix.length());
				double similarity = maxLength > 0 ? 1.0 - ((double)distance / maxLength) : 1.0;

				if (similarity < 0.5) // Less than 50% similar
				{
					logger.warn("Apply Fix validation failed: line " + lineNumber +
						" fix code doesn't match actual line. Actual: '" + actualLine +
						"', Fix: '" + fixFirstLine + "', similarity: " + (similarity * 100) + "%");
					return false;
				}
			}

			return true;
		}
		catch (Exception e)
		{
			logger.error("Error validating error context: " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Calculates Levenshtein distance between two strings.
	 */
	private int levenshteinDistance(String s1, String s2)
	{
		int[][] dp = new int[s1.length() + 1][s2.length() + 1];

		for (int i = 0; i <= s1.length(); i++)
		{
			for (int j = 0; j <= s2.length(); j++)
			{
				if (i == 0)
				{
					dp[i][j] = j;
				}
				else if (j == 0)
				{
					dp[i][j] = i;
				}
				else
				{
					int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
					dp[i][j] = Math.min(Math.min(
						dp[i - 1][j] + 1,
						dp[i][j - 1] + 1),
						dp[i - 1][j - 1] + cost);
				}
			}
		}

		return dp[s1.length()][s2.length()];
	}

	/**
	 * Holds error context with associated fix code from Explain assistant.
	 */
	private static class ErrorContextWithFix
	{
		final String filePath;
		final int lineNumber;
		final String errorText;
		final String fixCode;

		ErrorContextWithFix(String filePath, int lineNumber, String errorText, String fixCode)
		{
			this.filePath = filePath;
			this.lineNumber = lineNumber;
			this.errorText = errorText;
			this.fixCode = fixCode;
		}
	}

	public void onSendUserMessage(String userMessage)
	{
		// Record prompt timestamp for Documentation assistant (used by applyDocumentations for change detection)
		if (currentAssistant == AssistantType.DOCUMENTATION)
		{
			SelectionTracker.getInstance().setPromptTimestamp(System.currentTimeMillis());
		}
		// Generate temporary IDs for streaming display
		String userMsgId = UUID.randomUUID().toString();
		String assistantMsgId = UUID.randomUUID().toString();
		// Clean message if it contains large file notice, error context, or "explain" (Explain assistant)
		String displayMessage = isExplainMessage(userMessage) ? cleanExplainMessage(userMessage) : userMessage;

		// Show user message immediately
		applyToView(part -> {
			part.clearUserInput();
			part.addMessage(userMsgId, "user");
			part.setMessageHtml(userMsgId, displayMessage); // Show cleaned version if applicable
			part.addMessage(assistantMsgId, "assistant");
			part.setMessageHtml(assistantMsgId, "Thinking..."); // Show placeholder while waiting for response
		});

		// Accumulate streaming tokens
		StringBuilder accumulatedResponse = new StringBuilder();

		// LangChain4j automatically adds user message to store before calling LLM
		currentAssistant.getModel().executeRequest(currentMemoryId, userMessage) // Send full message to AI
			.onPartialResponse(partial -> {
				// Accumulate tokens and update display
				accumulatedResponse.append(partial);

				if (currentAssistant == AssistantType.QUICKFIX)
				{
					//do not show partial response for QuickFix because it contains unformatted json
					return;
				}
				applyToView(part -> {
					// Clean error_context tags from display while keeping them for parsing
					String cleanedResponse = cleanExplainMessage(accumulatedResponse.toString());
					part.setMessageHtml(assistantMsgId, cleanedResponse);
				});
			})
			.onCompleteResponse(fullResponse -> {
				// LangChain4j automatically added AI response to store
				// No refresh needed - streaming already shows full response
				// Refresh would cause flickering by clearing and rebuilding UI

				String responseText = accumulatedResponse.toString();

				// If Explain assistant found issues (error_context tags), invoke QuickFix
				if (currentAssistant == AssistantType.EXPLAIN)
				{
					// Parse error_context tags from AI response (not user message)
					invokeQuickFixForError(responseText, assistantMsgId, responseText);
				}
				else if (currentAssistant == AssistantType.QUICKFIX)
			{
				QuickFixAssistant quickFixAssistant = Activator.getDefault().getServoyAiModel().getQuickFixAssistant();
				CodeChanges newFix = quickFixAssistant.fix(userMessage);

				if (newFix != null && !newFix.codeChanges().isEmpty())
				{
					String readableResponse = formatQuickFixForChat(newFix);
					applyToView(part -> {
						// human readable response
						part.setMessageHtml(assistantMsgId, readableResponse);
					});
					QuickFixPresenter.getInstance().previewFix(userMessage, newFix);
				}
			}
			})
			.onError(error -> {
				applyToView(part -> {
					part.setMessageHtml(assistantMsgId, "Error: " + error.getMessage());
				});
				logger.error("Error getting assistant response", error);
			})
			.start();
	}

	private String formatQuickFixForChat(CodeChanges result)
	{
		if (result == null || result.codeChanges() == null || result.codeChanges().isEmpty())
		{
			return "No fixes suggested.";
		}

		StringBuilder md = new StringBuilder();
		md.append("### Suggested Fixes\n\n");

		// 1. Group edits by File Path
		Map<String, List<SourceEdit>> editsByFile = result.codeChanges().stream()
			.collect(Collectors.groupingBy(SourceEdit::filePath, LinkedHashMap::new, Collectors.toList()));

		for (Map.Entry<String, List<SourceEdit>> entry : editsByFile.entrySet())
		{
			String path = entry.getKey();
			if (path.startsWith("L/"))
			{
				path = path.substring(2);
			}

			md.append("**File:** `").append(path).append("`\n\n");

			for (SourceEdit edit : entry.getValue())
			{
				String replacement = edit.replacement() != null ? edit.replacement().trim() : "";

				// 2. Handle Deletions
				if (replacement.isEmpty())
				{
					String lineInfo = (edit.startLine() == edit.endLine())
						? "Line " + edit.startLine()
						: "Lines " + edit.startLine() + "-" + edit.endLine();
					md.append("*").append(lineInfo).append(":* (Code should be removed)\n\n");
					continue;
				}
				md.append("```javascript\n");

				String[] lines = edit.replacement().split("\\R");
				int currentLine = edit.startLine();

				for (int i = 0; i < lines.length; i++)
				{
					md.append(String.format("%2d: %s\n", currentLine + i, lines[i]));
				}

				md.append("```\n\n");
			}
			md.append("---\n\n");
		}

		// Clean up trailing horizontal rule
		String finalMarkdown = md.toString();
		return finalMarkdown.endsWith("---\n\n")
			? finalMarkdown.substring(0, finalMarkdown.length() - 5).trim()
			: finalMarkdown.trim();
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

	public void onApplyErrorFix(String filePath, int lineNumber, String errorText, String fixText)
	{
		uiSync.asyncExec(() -> {
			try
			{
				logger.info("=== APPLY ERROR FIX CALLED ===");
				logger.info("  File: " + filePath);
				logger.info("  Line: " + lineNumber);
				logger.info("  Error: " + errorText);
				logger.info("  Fix: " + fixText);
				logger.info("================================");


				IFile file = null;

				// Try to find the file in the workspace
				// Strategy 1: Convert absolute path to workspace path
				IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
				java.io.File javaFile = new java.io.File(filePath);

				if (javaFile.isAbsolute())
				{
					// Try to find workspace file from absolute path
					IPath path = new org.eclipse.core.runtime.Path(javaFile.getAbsolutePath());
					IFile[] files = workspaceRoot.findFilesForLocationURI(path.toFile().toURI());
					if (files != null && files.length > 0)
					{
						file = files[0];
						logger.info("Found file via absolute path: " + file.getFullPath());
					}
				}

				// Strategy 2: Try to match by path suffix (e.g., "main/forms/navigate.js")
				if (file == null)
				{
					IProject project = Optional.ofNullable(PlatformUI.getWorkbench())
						.map(IWorkbench::getActiveWorkbenchWindow)
						.map(IWorkbenchWindow::getActivePage)
						.map(IWorkbenchPage::getActiveEditor)
						.map(editor -> editor.getEditorInput())
						.filter(input -> input instanceof org.eclipse.ui.part.FileEditorInput)
						.map(input -> ((org.eclipse.ui.part.FileEditorInput)input).getFile().getProject())
						.orElse(null);

					if (project != null)
					{
						// Extract path suffix from absolute path (e.g., "main/forms/navigate.js" from "C:\...\main\forms\navigate.js")
						String pathSuffix = filePath.replace('\\', '/');
						int lastSegmentStart = Math.max(
							pathSuffix.lastIndexOf("/main/"),
							Math.max(pathSuffix.lastIndexOf("/forms/"), pathSuffix.lastIndexOf("/modules/")));

						if (lastSegmentStart >= 0)
						{
							pathSuffix = pathSuffix.substring(lastSegmentStart + 1); // Remove leading slash
							file = findFileByPathSuffix(project, pathSuffix);
							if (file != null)
							{
								logger.info("Found file via path suffix '" + pathSuffix + "': " + file.getFullPath());
							}
						}

						// Strategy 3: Fallback to filename-only search
						if (file == null)
						{
							String fileName = javaFile.getName();
							file = findFileInProject(project, fileName);
							if (file != null)
							{
								logger.info("Found file via filename '" + fileName + "': " + file.getFullPath());
							}
						}
					}
				}

				if (file == null || !file.exists())
				{
					logger.error("File not found in workspace: " + filePath);
					return;
				}

				// Open the file in an editor
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				org.eclipse.ui.ide.IDE.openEditor(page, file);

				// Get the active text editor
				IEditorPart editor = page.getActiveEditor();
				if (editor != null)
				{
					org.eclipse.ui.texteditor.ITextEditor textEditor = editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
					if (textEditor != null)
					{
						org.eclipse.jface.text.IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());

						if (document != null)
						{
							// Navigate to the line (lineNumber is 1-based)
							int offset = document.getLineOffset(lineNumber - 1);
							int lineLength = document.getLineLength(lineNumber - 1);
							String lineContent = document.get(offset, lineLength);

							// Strategy 1: If fixText is a complete line of code, replace the entire line's code content
							if (fixText != null && !fixText.isEmpty() &&
								(fixText.contains("(") || fixText.contains("=") || fixText.contains(".")))
							{
								// Get the indentation of the current line
								String trimmedLine = lineContent.stripLeading();
								int indentLength = lineContent.length() - trimmedLine.length();
								String indent = lineContent.substring(0, indentLength);

								// Trim the current line content (remove leading/trailing whitespace but keep newline)
								String newlineChars = "";
								if (lineContent.endsWith("\r\n"))
								{
									newlineChars = "\r\n";
								}
								else if (lineContent.endsWith("\n"))
								{
									newlineChars = "\n";
								}

								// Replace entire line with indented fix code
								String replacementText = indent + fixText.trim() + newlineChars;
								document.replace(offset, lineLength, replacementText);

								// Select the replaced text
								textEditor.selectAndReveal(offset, replacementText.length());

								logger.info("Applied fix: replaced entire line " + lineNumber + " with: " + fixText.trim());
							}
							// Strategy 2: Try to extract wrong/right words from error text (e.g., "'aaplication' should be 'application'")
							else
							{
								java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("'([^']+)'\\s+should be\\s+'([^']+)'");
								java.util.regex.Matcher matcher = pattern.matcher(errorText);

								if (matcher.find())
								{
									String wrongText = matcher.group(1);
									String rightText = matcher.group(2);

									// Find and replace the wrong text in the line
									int errorIndex = lineContent.indexOf(wrongText);
									if (errorIndex >= 0)
									{
										int errorOffset = offset + errorIndex;
										document.replace(errorOffset, wrongText.length(), rightText);

										textEditor.selectAndReveal(errorOffset, rightText.length());
										logger.info("Applied fix: replaced '" + wrongText + "' with '" + rightText + "' at line " + lineNumber);
									}
									else
									{
										logger.warn("Wrong text '" + wrongText + "' not found in line " + lineNumber);
										textEditor.selectAndReveal(offset, 0);
									}
								}
								else
								{
									logger.warn("Could not parse fix from error: " + errorText);
									textEditor.selectAndReveal(offset, 0);
								}
							}
						}
					}
				}
			}
			catch (Exception e)
			{
				logger.error("Error applying fix at line " + lineNumber, e);
			}
		});
	}

	/**
	 * Recursively find a file by name in a project
	 */
	private IFile findFileInProject(IProject project, String fileName)
	{
		try
		{
			IFile[] foundFile = new IFile[1];
			project.accept(resource -> {
				if (resource instanceof IFile && resource.getName().equals(fileName))
				{
					foundFile[0] = (IFile)resource;
					return false; // stop visiting
				}
				return true; // continue visiting
			});
			return foundFile[0];
		}
		catch (Exception e)
		{
			logger.error("Error finding file in project", e);
			return null;
		}
	}

	/**
	 * Find a file by matching its path suffix (e.g., "main/forms/navigate.js")
	 */
	private IFile findFileByPathSuffix(IProject project, String pathSuffix)
	{
		try
		{
			String normalizedSuffix = pathSuffix.replace('\\', '/');
			IFile[] foundFile = new IFile[1];
			project.accept(resource -> {
				if (resource instanceof IFile)
				{
					String resourcePath = resource.getProjectRelativePath().toString().replace('\\', '/');
					if (resourcePath.endsWith(normalizedSuffix))
					{
						foundFile[0] = (IFile)resource;
						return false; // stop visiting
					}
				}
				return true; // continue visiting
			});
			return foundFile[0];
		}
		catch (Exception e)
		{
			logger.error("Error finding file by path suffix", e);
			return null;
		}
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
		currentMemoryId = solutionName + currentAssistant.getMemorySuffix();

		applyToView(view -> {
			view.clearChatView();

			// Show notification at the top (not in chat content)
			view.showNotification(
				"New session started - Solution: " + projectName + " - Conversation history has been reset.",
				Duration.ofSeconds(5),
				ChatView.NotificationType.INFO);
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
		logger.info("Opening compare editor for file: " + filePath);

		uiSync.asyncExec(() -> {
			try
			{
				String originalContent = FileModificationTracker.getInstance().getOriginalContent(filePath);
				if (originalContent == null)
				{
					logger.error("No original content found for file: " + filePath);
					return;
				}

				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
				if (!file.exists())
				{
					logger.error("File does not exist: " + filePath);
					return;
				}

				// Get current (modified) content
				String currentContent = new String(file.getContents().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

				// Use CompareEditorService for reusable compare functionality
				com.servoy.eclipse.servoypilot.services.CompareEditorService compareService = com.servoy.eclipse.servoypilot.services.CompareEditorService
					.getInstance();

				compareService.openCompareEditor(file.getName(), originalContent, currentContent);
			}
			catch (Exception e)
			{
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
}
