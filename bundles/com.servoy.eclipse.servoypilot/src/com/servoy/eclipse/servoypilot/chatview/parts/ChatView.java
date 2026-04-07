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

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.context.SelectionTracker;
import com.servoy.eclipse.ui.browser.BrowserFactory;
import com.servoy.eclipse.ui.browser.IBrowser;
import com.servoy.eclipse.ui.tweaks.IconPreferences;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

public class ChatView
{
	public enum NotificationType
	{
		INFO, WARNING, ERROR
	}

	@Inject
	private ILog logger;

	@Inject
	private UISynchronize uiSync;

	@Inject
	private AssistaiSharedFiles sharedFiles;

	@Inject
	private AssistaiSharedFonts sharedFonts;

	@Inject
	private ChatViewPresenter presenter;

	private IBrowser browser;
	private Text inputArea;
	private Combo assistantSelector;
	private boolean autoScrollEnabled = true;
	private int notificationIdCounter = 0;
	private ToolBar actionToolBar;

	private Runnable chatModelListener = () -> {
		uiSync.asyncExec(() -> {
			boolean hasModel = Activator.getDefault().getAiConfiguration().isValid();
			inputArea.setEditable(hasModel);
			if (assistantSelector != null && !assistantSelector.isDisposed())
			{
				assistantSelector.setEnabled(hasModel);
			}
			if (actionToolBar != null && !actionToolBar.isDisposed())
			{
				actionToolBar.setEnabled(hasModel);
			}
			if (!hasModel)
			{
				inputArea.setText("No AI model configured. Please set up an AI model in preferences. (Window -> Preferences -> Servoy-> Servoy AI Pilot)");
			}
			else
			{
				inputArea.setText("");
			}
		});
	};

	@PostConstruct
	public void createPartControl(Composite parent)
	{
		presenter.setChatView(this);

		// Initialize SelectionTracker to start monitoring selections
		SelectionTracker.getInstance();

		// Create a SashForm to act as the split pane
		SashForm sashForm = new SashForm(parent, SWT.VERTICAL);
		sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		Composite browserContainer = new Composite(sashForm, SWT.NONE);
		browserContainer.setLayout(new FillLayout());
		// Create the browser component on top
		browser = createChatView(browserContainer);

		Composite controls = new Composite(sashForm, SWT.NONE);
		GridLayout controlsLayout = new GridLayout(1, false);
		controlsLayout.marginWidth = 5;
		controlsLayout.marginHeight = 5;
		controls.setLayout(controlsLayout);

		// Create attachments panel at the top
//	        Composite attachmentsPanel = createAttachmentsPanel( controls );
//	        attachmentsPanel.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, false) );

		// Create input area with attachment button
		Composite inputContainer = new Composite(controls, SWT.NONE);
		GridLayout inputLayout = new GridLayout(2, false);
		inputLayout.marginWidth = 0;
		inputLayout.marginHeight = 0;
		inputLayout.horizontalSpacing = 5;
		inputContainer.setLayout(inputLayout);
		inputContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		// Create the text input area
		inputArea = createUserInput(inputContainer);
		inputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		// enabled it only if a model is configured
		chatModelListener.run();
//	        setupAutocomplete( inputArea );

		// Create button bar at the bottom with model selector on left, action buttons
		// on right
		Composite buttonBar = new Composite(controls, SWT.NONE);
		GridLayout buttonBarLayout = new GridLayout(2, false);
		buttonBarLayout.marginHeight = 0;
		buttonBarLayout.marginWidth = 0;
		buttonBar.setLayout(buttonBarLayout);
		buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		// Left side: Assistant selector
		assistantSelector = new Combo(buttonBar, SWT.READ_ONLY | SWT.DROP_DOWN);
		assistantSelector.select(0); // Default to Chat Assistant
		GridData comboLayoutData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		comboLayoutData.widthHint = 200; // Set minimum width for display names to fit
		assistantSelector.setLayoutData(comboLayoutData);
		assistantSelector.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				int selectedIndex = assistantSelector.getSelectionIndex();
				presenter.onAssistantChanged(selectedIndex);
			}
		});
		// Populate combo after presenter initialization
		presenter.populateAssistantSelector();

		// Right side: Action buttons - Use ToolBar instead of Composite
		actionToolBar = new ToolBar(buttonBar, SWT.FLAT | SWT.RIGHT);
		actionToolBar.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));

		// Add toolbar items instead of buttons
//	        modelDropdownItem = createModelSelectorComposite(actionToolBar);
//	        createAttachmentToolItem(actionToolBar);
//	        createReplayToolItem(actionToolBar);
		createClearChatToolItem(actionToolBar);
		createStopToolItem(actionToolBar);
		createSendToolItem(actionToolBar);

		// Adjust the weights of the SashForm to allocate space
		sashForm.setWeights(new int[] { 3, 1 }); // 3:1 ratio for browser and input field

		Activator.getDefault().addServoyAiModelChangeListener(chatModelListener);
	}

	@Focus
	public void setFocus()
	{
		inputArea.setFocus();
	}

	@PreDestroy
	public void dispose()
	{
		Activator.getDefault().removeServoyAiModelChangeListener(chatModelListener);
		if (browser != null)
		{
			browser.dispose();
		}

		// Dispose SelectionTracker to clean up selection listeners
		SelectionTracker.getInstance().dispose();
	}

	public void clearChatView()
	{
		// Synchronously clear the message container (no asyncExec needed)
		// This prevents race conditions with refreshViewFromMemory()
		browser.execute("var content = document.getElementById('content'); if (content) { content.innerHTML = ''; }");
	}

	public void clearUserInput()
	{
		uiSync.asyncExec(() -> inputArea.setText(""));
	}

	public void setAssistantSelectorItems(String[] assistantNames)
	{
		uiSync.asyncExec(() -> {
			if (assistantSelector != null && !assistantSelector.isDisposed())
			{
				assistantSelector.setItems(assistantNames);
				assistantSelector.select(0);
			}
		});
	}

	public void setAssistantSelectorIndex(int index)
	{
		uiSync.asyncExec(() -> {
			if (assistantSelector != null && !assistantSelector.isDisposed())
			{
				assistantSelector.select(index);
			}
		});
	}

	public ChatViewPresenter getPresenter()
	{
		return presenter;
	}

	public void setInputEnabled(boolean enabled)
	{
		uiSync.asyncExec(() -> {
			boolean realEnabled = Activator.getDefault().getAiConfiguration().isValid() && enabled;
			inputArea.setEnabled(realEnabled);
			if (assistantSelector != null && !assistantSelector.isDisposed())
			{
				assistantSelector.setEnabled(realEnabled);
			}
			if (actionToolBar != null && !actionToolBar.isDisposed())
			{
				actionToolBar.setEnabled(realEnabled);
			}
			if (realEnabled)
			{
				// Restore focus after a small delay to ensure browser operations complete
				Display.getCurrent().timerExec(100, () -> {
					if (!inputArea.isDisposed())
					{
						inputArea.setFocus();
					}
				});
			}
		});
	}

	/**
	 * Creates a toolbar item that allows the user to clear the conversation.
	 * 
	 * @param toolbar The parent toolbar
	 * @return The created toolbar item
	 */
	private ToolItem createClearChatToolItem(ToolBar toolbar)
	{
		ToolItem item = new ToolItem(toolbar, SWT.PUSH);
		try
		{
			// Use the erase/clear icon
			Image clearIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ETOOL_CLEAR);
			item.setImage(clearIcon);
		}
		catch (Exception e)
		{
			logger.error(e.getMessage(), e);
		}
		item.setToolTipText("Clear conversation");
		item.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				presenter.onClear();
			}
		});
		return item;
	}

	/**
	 * Creates a toolbar item that allows the user to stop the generation.
	 * 
	 * @param toolbar The parent toolbar
	 * @return The created toolbar item
	 */
	private ToolItem createStopToolItem(ToolBar toolbar)
	{
		ToolItem item = new ToolItem(toolbar, SWT.PUSH);

		// Use the built-in 'IMG_ELCL_STOP' icon
		Image stopIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_STOP);
		item.setImage(stopIcon);
		item.setToolTipText("Stop generation");

		item.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				presenter.onStop();
			}
		});
		return item;
	}

	/**
	 * Creates a toolbar item that allows the user to send the message.
	 * 
	 * @param toolbar The parent toolbar
	 * @return The created toolbar item
	 */
	private ToolItem createSendToolItem(ToolBar toolbar)
	{
		ToolItem item = new ToolItem(toolbar, SWT.PUSH);

		try
		{
			// Use the forward/send icon
			Image sendIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_FORWARD);
			item.setImage(sendIcon);
		}
		catch (Exception e)
		{
			logger.error(e.getMessage(), e);
		}

		item.setToolTipText("Send message (Ctrl+Enter)");

		item.addSelectionListener(new SelectionAdapter()
		{
			@Override
			public void widgetSelected(SelectionEvent e)
			{
				String text = inputArea.getText().trim();
				if (!text.isEmpty())
				{
					presenter.onSendUserMessage(text);
				}
			}
		});

		return item;
	}

	private Text createUserInput(Composite parent)
	{
		Text inputArea = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);

		// Set a prompt message
		inputArea.setMessage("Type a message or question here... (Press Ctrl+Enter to send)");

		// Add a key listener to handle Ctrl+Enter to send the message
		inputArea.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.keyCode == SWT.CR && (e.stateMask & SWT.CTRL) != 0)
				{
					e.doit = false; // Prevent default behavior
					// Only send if there's actual text to send
					String text = inputArea.getText().trim();
					if (!text.isEmpty())
					{
						presenter.onSendUserMessage(text);
					}
				}
			}
		});

		createCustomMenu(inputArea);
		return inputArea;
	}

	/**
	 * Dynamically creates and assigns a custom context menu to the input area.
	 * <p>
	 * This method constructs a context menu with "Cut", "Copy", and "Paste" actions
	 * for the text input area. The "Paste" action is conditionally enabled based on
	 * the current content of the clipboard: it's enabled if the clipboard contains
	 * either text or image data. When triggered, the "Paste" action checks the
	 * clipboard content type and handles it accordingly - pasting text directly
	 * into the input area or invoking a custom handler for image data.
	 *
	 * @param inputArea The Text widget to which the custom context menu will be
	 *                  attached.
	 */
	private void createCustomMenu(Text inputArea)
	{
		Menu menu = new Menu(inputArea);
		inputArea.setMenu(menu);
		menu.addMenuListener(new MenuAdapter()
		{
			@Override
			public void menuShown(MenuEvent e)
			{
				// Dynamically adjust the context menu
				MenuItem[] items = menu.getItems();
				for (MenuItem item : items)
				{
					item.dispose();
				}
				// Add Cut, Copy, Paste items
				addMenuItem(menu, "Cut", () -> inputArea.cut());
				addMenuItem(menu, "Copy", () -> inputArea.copy());
				MenuItem pasteItem = addMenuItem(menu, "Paste", () -> handlePasteOperation());
				// Enable or disable paste based on clipboard content
				Clipboard clipboard = new Clipboard(Display.getCurrent());
				boolean enablePaste = clipboard.getContents(TextTransfer.getInstance()) != null || clipboard.getContents(ImageTransfer.getInstance()) != null;
				pasteItem.setEnabled(enablePaste);
				clipboard.dispose();
			}
		});
	}

	private MenuItem addMenuItem(Menu parent, String text, Runnable action)
	{
		MenuItem item = new MenuItem(parent, SWT.NONE);
		item.setText(text);
		item.addListener(SWT.Selection, e -> action.run());
		return item;
	}

	private void handlePasteOperation()
	{
		Clipboard clipboard = new Clipboard(Display.getCurrent());

		if (clipboard.getContents(ImageTransfer.getInstance()) != null)
		{
			ImageData imageData = (ImageData)clipboard.getContents(ImageTransfer.getInstance());
			presenter.onAttachmentAdded(imageData);
		}
		else
		{
			String textData = (String)clipboard.getContents(TextTransfer.getInstance());
			if (textData != null)
			{
				inputArea.insert(textData); // Manually insert text at the
											// current caret position
			}

		}
	}

	private IBrowser createChatView(Composite parent)
	{
		IBrowser browser = BrowserFactory.createBrowser(parent);
		initializeChatView(browser);
		initializeFunctions(browser);
		return browser;
	}

	private void initializeFunctions(IBrowser browser)
	{
		new CopyCodeFunction(browser, "eclipseCopyCode");
		new ApplyPatchFunction(browser, "eclipseApplyPatch");
		new DiffCodeFunction(browser, "eclipseDiffCode");
		new InsertCodeFunction(browser, "eclipseInsertCode");
		new NewFileFunction(browser, "eclipseNewFile");
		new ApplyErrorFixFunction(browser, "eclipseApplyErrorFix");
		new ScrollInteractionFunction(browser, "eclipseScrollInteraction");
		// Modified files tracking functions
		new OnFileClickFunction(browser, "onFileClick");
		new OnKeepFileFunction(browser, "onKeepFile");
		new OnUndoFileFunction(browser, "onUndoFile");
		new OnRemoveFileFunction(browser, "onRemoveFile");
		new OnKeepAllFunction(browser, "onKeepAll");
		new OnUndoAllFunction(browser, "onUndoAll");
	}

	private void initializeChatView(IBrowser browser)
	{
		String htmlTemplate = """
			<!DOCTYPE html>
			<html>
				<head>
			        <meta charset="UTF-8">
			    	<style>${css}</style>
			    	<style>${fonts}</style>
			    	<style>${modifiedFilesCSS}</style>
			    	<script>${js}</script>
			    	<script>${modifiedFilesJS}</script>
			    	<script>
			    		// Initialize error fix buttons after content loads
			    		function initErrorFixButtons() {
			    			document.querySelectorAll('error_fix_data').forEach(function(errorData) {
			    				var prevElem = errorData.previousElementSibling;
			    				if (prevElem && prevElem.classList.contains('codeBlock')) {
			    					var btn = prevElem.querySelector('.error-fix-only');
			    					if (btn) {
			    						var file = errorData.getAttribute('file');
			    						var line = parseInt(errorData.getAttribute('line'));
			    						var errorText = errorData.getAttribute('error_text');
			    						var fixText = errorData.getAttribute('fix_text');

			    						btn.onclick = function() {
			    							eclipseApplyErrorFix(file, line, errorText, fixText);
			    						};
			    						btn.style.display = 'inline-block';
			    					}
			    				}
			    			});
			    		}

			    		// Call after content is rendered
			    		if (typeof MutationObserver !== 'undefined') {
			    			var observer = new MutationObserver(function() {
			    				initErrorFixButtons();
			    			});
			    			observer.observe(document.body, { childList: true, subtree: true });
			    		}
			    	</script>
				</head>
			    <body>
			            <div id="notification-container"></div>
			            <div id="content">
			            </div>
			            <!-- Modified Files Section -->
			            <div id="modified-files-section" class="modified-files-section" style="display: none;">
			                <div class="modified-files-header">
			                    <span class="toggle-icon" id="modified-files-toggle" onclick="toggleModifiedFiles()">▼</span>
			                    <span class="section-title">Modified files</span>
			                    <div class="header-buttons">
			                        <button onclick="keepAllFiles()" class="action-btn keep-all-btn" title="Keep all changes">Keep All</button>
			                        <button onclick="undoAllFiles()" class="action-btn undo-all-btn" title="Undo all changes">Undo All</button>
			                    </div>
			                </div>
			                <div id="modified-files-list" class="modified-files-list">
			                    <!-- Dynamically populated with file entries -->
			                </div>
			            </div>
			            <div id="welcome" style="position: absolute;top: 50%;left: 50%;transform: translate(-50%, -50%);text-align: center;">
							This is the Servoy AI Assistant.<br/> Ask me anything related to Servoy Development
			            </div>
			    </body>
			</html>
			""";

		String js = loadJavaScripts();
		String css = loadCss();
		String fonts = loadFonts();
		String modifiedFilesCSS = getModifiedFilesCSS();
		String modifiedFilesJS = getModifiedFilesJavaScript();
		htmlTemplate = htmlTemplate.replace("${css}", css).replace("${fonts}", fonts).replace("${js}", js)
			.replace("${modifiedFilesCSS}", modifiedFilesCSS).replace("${modifiedFilesJS}", modifiedFilesJS);

		htmlTemplate = htmlTemplate.replace("${css}", css).replace("${fonts}", fonts).replace("${js}", js);

		// Initialize the browser with base HTML and CSS
		browser.setText(htmlTemplate);
	}

	private String loadFonts()
	{
		return sharedFonts.loadFontsCss();
	}

	/**
	 * Loads the CSS files for the ChatGPTViewPart component.
	 *
	 * @return A concatenated string containing the content of the loaded CSS files.
	 */
	private String loadCss()
	{
		String[] cssFiles = { "textview_dark.css", "dark.min.css", "fa6.all.min.css", "katex.min.css" };
		if (!IconPreferences.getInstance().getUseDarkThemeIcons())
		{
			cssFiles = new String[] { "textview_light.css", "fa6.all.min.css", "katex.min.css" };
		}
		var cssContent = Arrays.stream(cssFiles).map(file -> "css/" + file).map(sharedFiles::readFile)
			.collect(Collectors.joining("\n"));
		return cssContent;
	}

	/**
	 * Loads the JavaScript files for the ChatGPTViewPart component.
	 *
	 * @return A concatenated string containing the content of the loaded JavaScript
	 *         files.
	 */
	private String loadJavaScripts()
	{
		String[] jsFiles = { "highlight.min.js", "textview.js", "katex.min.js" };

		var jsContent = Arrays.stream(jsFiles).map(file -> "js/" + file).map(sharedFiles::readFile)
			.collect(Collectors.joining("\n\n"));
		return jsContent;
	}

	public void setMessageHtml(String messageId, String messageBody)
	{
		uiSync.asyncExec(() -> {
			String msg = messageBody == null ? "" : messageBody;

			MarkdownParser parser = new MarkdownParser(msg);

			String fixedHtml = escapeHtmlQuotes(fixLineBreaks(parser.parseToHtml()));
			// inject and highlight html message
			browser.execute("var target = document.getElementById(\"message-content-" + messageId + "\") || document.getElementById(\"message-" + messageId +
				"\"); if (target) { target.innerHTML = '" + fixedHtml + "'; } renderCode();");
			// Scroll down only if auto-scroll is enabled
			if (autoScrollEnabled)
			{
				browser.execute("window.scrollTo(0, document.body.scrollHeight);");
			}
		});
	}

	/**
	 * Appends content to an existing message.
	 * 
	 * @param messageId The ID of the message to append to
	 * @param currentContent The current full content of the message
	 * @param additionalContent The content to append (markdown format)
	 */
	public void appendToMessage(String messageId, String currentContent, String additionalContent)
	{
		String newContent = currentContent + additionalContent;
		setMessageHtml(messageId, newContent);
	}

	/**
	 * Appends content with error fix context to enable Apply Error Fix button.
	 * 
	 * @param messageId The ID of the message to append to
	 * @param currentContent The current full content of the message
	 * @param additionalMarkdown The markdown content to append
	 * @param filePath Error file path
	 * @param lineNumber Error line number
	 * @param errorText Text to find and replace
	 * @param fixText Replacement text
	 */
	public void appendMessageWithErrorContext(String messageId, String currentContent, String additionalMarkdown,
		String filePath, int lineNumber, String errorText, String fixText)
	{
		String newContent = currentContent + additionalMarkdown;
		setMessageHtml(messageId, newContent);

		// Inject error_fix_data element and wire up button
		uiSync.asyncExec(() -> {
			String escapedFile = filePath.replace("\\", "\\\\").replace("'", "\\'");
			String escapedError = errorText.replace("\\", "\\\\").replace("'", "\\'");
			String escapedFix = fixText.replace("\\", "\\\\").replace("'", "\\'");

			browser.execute(String.format(
				"var target = document.getElementById('message-content-%s') || document.getElementById('message-%s');" +
					"if (target) {" +
					"  var lastBlock = target.querySelector('.codeBlock:last-of-type');" +
					"  if (lastBlock) {" +
					"    var errorData = document.createElement('error_fix_data');" +
					"    errorData.setAttribute('file', '%s');" +
					"    errorData.setAttribute('line', '%d');" +
					"    errorData.setAttribute('error_text', '%s');" +
					"    errorData.setAttribute('fix_text', '%s');" +
					"    errorData.style.display = 'none';" +
					"    lastBlock.parentNode.insertBefore(errorData, lastBlock.nextSibling);" +
					"    if (typeof initErrorFixButtons === 'function') initErrorFixButtons();" +
					"  }" +
					"}",
				messageId, messageId, escapedFile, lineNumber, escapedError, escapedFix));
		});
	}

	/**
	 * Injects error_fix_data element into a message to enable the "Apply Error Fix" button.
	 * This method only injects the data WITHOUT modifying the message HTML content.
	 * 
	 * @param messageId The ID of the message
	 * @param filePath The file path where the fix should be applied
	 * @param lineNumber The line number
	 * @param errorText The incorrect code (what needs to be replaced)
	 * @param fixText The correct code (what to replace it with)
	 * @param codeBlockIndex The index of the code block (0-based) to associate with this fix (0 = first, 1 = second, etc.)
	 */
	public void injectErrorFixData(String messageId, String filePath, int lineNumber, String errorText, String fixText, int codeBlockIndex)
	{
		uiSync.asyncExec(() -> {
			String escapedFile = filePath.replace("\\", "\\\\").replace("'", "\\'");
			String escapedError = errorText.replace("\\", "\\\\").replace("'", "\\'");
			String escapedFix = fixText.replace("\\", "\\\\").replace("'", "\\'");

			browser.execute(String.format(
				"var target = document.getElementById('message-content-%s') || document.getElementById('message-%s');" +
					"if (target) {" +
					"  var codeBlocks = target.querySelectorAll('.codeBlock');" +
					"  if (codeBlocks && codeBlocks.length > %d) {" +
					"    var targetBlock = codeBlocks[%d];" +
					"    var errorData = document.createElement('error_fix_data');" +
					"    errorData.setAttribute('file', '%s');" +
					"    errorData.setAttribute('line', '%d');" +
					"    errorData.setAttribute('error_text', '%s');" +
					"    errorData.setAttribute('fix_text', '%s');" +
					"    errorData.style.display = 'none';" +
					"    targetBlock.parentNode.insertBefore(errorData, targetBlock.nextSibling);" +
					"    console.log('Injected fix_data after code block ' + %d);" +
					"    if (typeof initErrorFixButtons === 'function') initErrorFixButtons();" +
					"  } else {" +
					"    console.error('Code block ' + %d + ' not found. Total blocks: ' + (codeBlocks ? codeBlocks.length : 0));" +
					"  }" +
					"}",
				messageId, messageId, codeBlockIndex, codeBlockIndex,
				escapedFile, lineNumber, escapedError, escapedFix,
				codeBlockIndex, codeBlockIndex));
		});
	}

	/**
	 * Removes a DOM element by ID.
	 * 
	 * @param elementId The ID of the element to remove
	 */
	public void removeElementById(String elementId)
	{
		uiSync.asyncExec(() -> {
			browser.execute("var elem = document.getElementById('" + elementId + "'); if (elem) { elem.remove(); }");
		});
	}

	/**
	 * Replaces newline characters with line break escape sequences in the given
	 * string.
	 *
	 * @param html The input string containing newline characters.
	 * @return A string with newline characters replaced by line break escape
	 *         sequences.
	 */
	private String fixLineBreaks(String html)
	{
		return html.replace("\n", "\\n").replace("\r", "");
	}

	/**
	 * Escapes HTML quotation marks in the given string.
	 * 
	 * @param html The input string containing HTML.
	 * @return A string with escaped quotation marks for proper HTML handling.
	 */
	private String escapeHtmlQuotes(String html)
	{
		return html.replace("\"", "\\\"").replace("'", "\\'");
	}

	public void addMessage(String messageId, String role)
	{
		String cssClass = "user".equals(role) ? "chat-bubble me" : "chat-bubble you";
		uiSync.asyncExec(() -> {
			browser.execute("""
				var node = document.getElementById("welcome");
				if(node) {
				    node.remove();
				}
				var node = document.createElement("div");
				node.setAttribute("id", "message-${id}");
				node.setAttribute("class", "${cssClass}");

				var content = document.createElement('div');
				content.setAttribute('id', 'message-content-${id}');

				node.appendChild(content);

				document.getElementById("content").appendChild(node);
					""".replace("${id}", messageId).replace("${cssClass}", cssClass));
			// Scroll down only if auto-scroll is enabled
			if (autoScrollEnabled)
			{
				browser.execute("window.scrollTo(0, document.body.scrollHeight);");
			}
		});
	}

	/**
	 * Shows tool execution progress indicator.
	 * 
	 * @param messageId The message where progress should be shown
	 * @param progressText The progress text (e.g., "Reading lines 1-100...")
	 * @param chunkNumber The current chunk number
	 */
	public void showToolProgress(String messageId, String progressText, int chunkNumber)
	{
		uiSync.asyncExec(() -> {
			// Remove existing progress indicator if present
			browser.execute("var existing = document.getElementById('tool-progress-" + messageId + "'); if (existing) existing.remove();");

			// Create new progress indicator with blue styling
			String script = String.format(
				"var progress = document.createElement('div');" +
					"progress.id = 'tool-progress-%s';" +
					"progress.className = 'tool-progress';" +
					"progress.style.cssText = 'color: #0066cc; font-style: italic; margin: 8px 0; padding: 8px; background: #e6f2ff; border-left: 3px solid #0066cc; border-radius: 4px;';" +
					"progress.textContent = '%s';" +
					"var target = document.getElementById('message-content-%s');" +
					"if (target) { target.parentNode.insertBefore(progress, target); }",
				messageId, progressText.replace("'", "\\'"), messageId);
			browser.execute(script);

			// Auto-scroll if enabled
			if (autoScrollEnabled)
			{
				browser.execute("window.scrollTo(0, document.body.scrollHeight);");
			}
		});
	}

	/**
	 * Hides tool execution progress indicator.
	 * 
	 * @param messageId The message where progress was shown
	 */
	public void hideToolProgress(String messageId)
	{
		uiSync.asyncExec(() -> {
			browser.execute("var progress = document.getElementById('tool-progress-" + messageId + "'); if (progress) progress.remove();");
		});
	}

	// Add a method to hide the tool use message
	public void hideMessage(String messageId)
	{
		uiSync.asyncExec(() -> {
			browser.execute("""
				var node = document.getElementById("message-${id}");
				if(node) {
				    node.classList.add("hidden");
				}
				""".replace("${id}", messageId));
		});
	}

	public void removeMessage(String messageId)
	{
		uiSync.asyncExec(() -> {
			browser.execute("""
				var node = document.getElementById("message-${id}");
				if(node) {
				    node.remove();
				}
				""".replace("${id}", messageId));
		});
	}

	/**
	 * Shows a notification bar at the top of the browser window. Multiple
	 * notifications can be displayed simultaneously and will stack vertically. Each
	 * notification includes an icon, message, and close button.
	 * 
	 * Example usage:
	 * 
	 * <pre>
	 * // Show an info notification for 5 seconds
	 * showNotification("Operation completed successfully", 5000, NotificationType.INFO);
	 * 
	 * // Show a warning that stays until manually closed
	 * showNotification("Please check your settings", 0, NotificationType.WARNING);
	 * 
	 * // Show an error for 10 seconds
	 * showNotification("Failed to connect to server", 10000, NotificationType.ERROR);
	 * </pre>
	 * 
	 * @param message  The notification message to display
	 * @param duration The duration to show the notification
	 * @param type     The type of notification (INFO, WARNING, ERROR)
	 */
	public void showNotification(String message, Duration duration, NotificationType type)
	{
		uiSync.asyncExec(() -> {
			String notificationId = "notification-" + (notificationIdCounter++);

			// Determine icon and color based on type
			String icon, bgColor, textColor;
			switch (type)
			{
				case INFO :
					icon = "fa-solid fa-circle-info";
					bgColor = "#1f6feb";
					textColor = "#ffffff";
					break;
				case WARNING :
					icon = "fa-solid fa-triangle-exclamation";
					bgColor = "#d29922";
					textColor = "#000000";
					break;
				case ERROR :
					icon = "fa-solid fa-circle-xmark";
					bgColor = "#da3633";
					textColor = "#ffffff";
					break;
				default :
					icon = "fa-solid fa-circle-info";
					bgColor = "#1f6feb";
					textColor = "#ffffff";
			}

			// Escape message for JavaScript
			String escapedMessage = escapeJavaScript(message);

			// Call JavaScript function to create notification
			browser.execute(String.format("showNotification('%s', '%s', '%s', '%s', '%s');", notificationId, icon,
				bgColor, textColor, escapedMessage));

			// Schedule removal after duration
			if (duration.toMillis() > 0)
			{
				Display.getDefault().timerExec((int)duration.toMillis(), () -> {
					uiSync.asyncExec(() -> {
						browser.execute(String.format("removeNotification('%s');", notificationId));
					});
				});
			}
		});
	}

	/**
	 * Escapes special characters in a string for safe use in JavaScript.
	 * 
	 * @param text The text to escape
	 * @return The escaped text safe for JavaScript strings
	 */
	private String escapeJavaScript(String text)
	{
		return text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
			.replace("\r", "\\r").replace("\t", "\\t");
	}

	/**
	 * This method is kept for E3 compatibility. You can remove it if you do not mix
	 * E3 and E4 code. <br/>
	 * With E4 code you will set directly the selection in ESelectionService and you
	 * do not receive a ISelection
	 * 
	 * @param s the selection received from JFace (E3 mode)
	 */
	@Inject
	@Optional
	public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) ISelection s)
	{
		if (s == null || s.isEmpty())
		{
			return;
		}

		if (s instanceof IStructuredSelection)
		{
			IStructuredSelection iss = (IStructuredSelection)s;
			if (iss.size() == 1)
			{
				setSelection(iss.getFirstElement());
			}
			else
			{
				setSelection(iss.toArray());
			}
		}
	}

	/**
	 * This method manages the multiple selection of your current objects. <br/>
	 * You should change the parameter type of your array of Objects to manage your
	 * specific selection
	 * 
	 * @param o : the current array of objects received in case of multiple
	 *          selection
	 */
	@Inject
	@Optional
	public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object[] selectedObjects)
	{
	}

	/**
	 * This method manages the selection of your current object. In this example we
	 * listen to a single Object (even the ISelection already captured in E3 mode).
	 * <br/>
	 * You should change the parameter type of your received Object to manage your
	 * specific selection
	 * 
	 * @param o : the current object received
	 */
	@Inject
	@Optional
	public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object o)
	{

		// Remove the 2 following lines in pure E4 mode, keep them in mixed mode
		if (o instanceof ISelection)
		{ // Already captured
			return;
		}
	}

	/**
	 * This function establishes a JavaScript-to-Java callback for the browser,
	 * allowing the IDE to copy code. It is invoked from JavaScript when the user
	 * interacts with the chat view to copy a code block.
	 */
	private class CopyCodeFunction extends BrowserFunctionWrapper
	{
		public CopyCodeFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String)
			{
				String codeBlock = (String)arguments[0];
				presenter.onCopyCode(codeBlock);
			}
			return null;
		}
	}

	/**
	 * This function establishes a JavaScript-to-Java callback for the browser,
	 * allowing the IDE to copy code. It is invoked from JavaScript when the user
	 * interacts with the chat view to copy a code block.
	 */
	private class ApplyPatchFunction extends BrowserFunctionWrapper
	{
		public ApplyPatchFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String)
			{
				String codeBlock = (String)arguments[0];
				presenter.onApplyPatch(codeBlock);
			}
			return null;
		}
	}

	private class InsertCodeFunction extends BrowserFunctionWrapper
	{
		public InsertCodeFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String)
			{
				String codeBlock = (String)arguments[0];
				presenter.onInsertCode(codeBlock);
			}
			return null;
		}
	}

	private class DiffCodeFunction extends BrowserFunctionWrapper
	{
		public DiffCodeFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String)
			{
				String codeBlock = (String)arguments[0];
				presenter.onDiffCode(codeBlock);
			}
			return null;
		}
	}

	private class NewFileFunction extends BrowserFunctionWrapper
	{
		public NewFileFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && Arrays.stream(arguments).allMatch(s -> s instanceof String))
			{
				String codeBlock = (String)arguments[0];
				String lang = (String)arguments[1];
				presenter.onNewFile(codeBlock, lang);
			}
			return null;
		}
	}

	private class ApplyErrorFixFunction extends BrowserFunctionWrapper
	{
		public ApplyErrorFixFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length >= 4 && Arrays.stream(arguments).allMatch(s -> s instanceof String || s instanceof Number))
			{
				String filePath = (String)arguments[0];
				int lineNumber = ((Number)arguments[1]).intValue();
				String errorText = (String)arguments[2];
				String fixText = (String)arguments[3];
				presenter.onApplyErrorFix(filePath, lineNumber, errorText, fixText);
			}
			return null;
		}
	}

	private class ScrollInteractionFunction extends BrowserFunctionWrapper
	{
		public ScrollInteractionFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof Boolean)
			{
				autoScrollEnabled = (Boolean)arguments[0];
			}
			return null;
		}
	}

	// ========== Modified Files Tracking BrowserFunctions ==========

	private class OnFileClickFunction extends BrowserFunctionWrapper
	{
		public OnFileClickFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String filePath)
			{
				presenter.onFileClick(filePath);
			}
			return null;
		}
	}

	private class OnKeepFileFunction extends BrowserFunctionWrapper
	{
		public OnKeepFileFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String filePath)
			{
				presenter.onKeepFile(filePath);
			}
			return null;
		}
	}

	private class OnUndoFileFunction extends BrowserFunctionWrapper
	{
		public OnUndoFileFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String filePath)
			{
				presenter.onUndoFile(filePath);
			}
			return null;
		}
	}

	private class OnRemoveFileFunction extends BrowserFunctionWrapper
	{
		public OnRemoveFileFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			if (arguments.length > 0 && arguments[0] instanceof String filePath)
			{
				presenter.onRemoveFile(filePath);
			}
			return null;
		}
	}

	private class OnKeepAllFunction extends BrowserFunctionWrapper
	{
		public OnKeepAllFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			presenter.onKeepAll();
			return null;
		}
	}

	private class OnUndoAllFunction extends BrowserFunctionWrapper
	{
		public OnUndoAllFunction(IBrowser browser, String name)
		{
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments)
		{
			presenter.onUndoAll();
			return null;
		}
	}

	// ========== Modified Files Section UI Methods ==========

	/**
	 * Updates the modified files section with current tracked files.
	 * Called by presenter when files are modified or removed from tracking.
	 */
	public void updateModifiedFilesSection()
	{
		if (browser != null && !browser.isDisposed())
		{
			java.util.Map<String, String> files = FileModificationTracker.getInstance().getModifiedFiles();

			// Convert to JSON array: [{path: "...", name: "..."}]
			StringBuilder json = new StringBuilder("[");
			int index = 0;
			for (String filePath : files.keySet())
			{
				if (index > 0)
				{
					json.append(",");
				}
				String fileName = extractFileName(filePath);
				json.append("{\"path\":\"").append(escapeJson(filePath)).append("\",");
				json.append("\"name\":\"").append(escapeJson(fileName)).append("\"}");
				index++;
			}
			json.append("]");

			browser.execute("updateModifiedFilesList('" + json.toString() + "');");
		}
	}

	/**
	 * Clears the modified files section (hides it and removes all entries).
	 * Called by presenter when all files are cleared from tracking.
	 */
	public void clearModifiedFilesSection()
	{
		if (browser != null && !browser.isDisposed())
		{
			browser.execute("clearModifiedFilesSection();");
		}
	}

	/**
	 * Extracts the file name from a workspace-relative path.
	 * Example: "/ProjectName/path/to/file.js" -> "file.js"
	 */
	private String extractFileName(String filePath)
	{
		if (filePath == null || filePath.isEmpty())
		{
			return "";
		}
		int lastSlash = filePath.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < filePath.length() - 1)
		{
			return filePath.substring(lastSlash + 1);
		}
		return filePath;
	}

	/**
	 * Escapes a string for safe inclusion in JSON.
	 */
	private String escapeJson(String str)
	{
		if (str == null)
		{
			return "";
		}
		return str.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t");
	}

	/**
	 * Returns CSS for the modified files section.
	 * Includes styles for both light and dark themes.
	 */
	private String getModifiedFilesCSS()
	{
		boolean isDark = IconPreferences.getInstance().getUseDarkThemeIcons();

		if (isDark)
		{
			return """
				/* Modified Files Section - Dark Theme */
				.modified-files-section {
				    border-top: 1px solid #3c3c3c;
				    border-bottom: 1px solid #3c3c3c;
				    background-color: #252526;
				    padding: 8px 12px;
				    margin-bottom: 8px;
				}
				.modified-files-header {
				    display: flex;
				    align-items: center;
				    gap: 8px;
				    user-select: none;
				}
				.toggle-icon {
				    cursor: pointer;
				    font-size: 12px;
				    color: #cccccc;
				    transition: transform 0.2s;
				}
				.toggle-icon.collapsed {
				    transform: rotate(-90deg);
				}
				.section-title {
				    font-weight: 600;
				    font-size: 13px;
				    color: #cccccc;
				    flex: 1;
				}
				.header-buttons {
				    display: flex;
				    gap: 6px;
				}
				.action-btn {
				    padding: 4px 10px;
				    font-size: 12px;
				    border: 1px solid #3c3c3c;
				    border-radius: 3px;
				    background-color: #2d2d30;
				    color: #cccccc;
				    cursor: pointer;
				    transition: background-color 0.2s;
				}
				.action-btn:hover {
				    background-color: #3e3e42;
				}
				.keep-all-btn {
				    color: #4ec9b0;
				    border-color: #4ec9b0;
				}
				.keep-all-btn:hover {
				    background-color: #1e3a35;
				}
				.undo-all-btn {
				    color: #f48771;
				    border-color: #f48771;
				}
				.undo-all-btn:hover {
				    background-color: #3a1e1e;
				}
				.modified-files-list {
				    margin-top: 8px;
				    display: flex;
				    flex-direction: column;
				    gap: 4px;
				}
				.modified-files-list.collapsed {
				    display: none;
				}
				.file-entry {
				    display: flex;
				    align-items: center;
				    padding: 6px 8px;
				    background-color: #2d2d30;
				    border: 1px solid #3c3c3c;
				    border-radius: 3px;
				    cursor: pointer;
				    transition: background-color 0.2s;
				    position: relative;
				}
				.file-entry:hover {
				    background-color: #3e3e42;
				}
				.file-entry:hover .file-actions {
				    display: flex;
				}
				.file-name {
				    flex: 1;
				    font-size: 13px;
				    color: #cccccc;
				    overflow: hidden;
				    text-overflow: ellipsis;
				    white-space: nowrap;
				}
				.file-actions {
				    display: none;
				    gap: 6px;
				    margin-left: 8px;
				}
				.file-action-icon {
				    width: 20px;
				    height: 20px;
				    display: flex;
				    align-items: center;
				    justify-content: center;
				    border-radius: 3px;
				    cursor: pointer;
				    font-size: 14px;
				}
				.file-action-icon.keep {
				    color: #4ec9b0;
				}
				.file-action-icon.keep:hover {
				    background-color: #1e3a35;
				}
				.file-action-icon.undo {
				    color: #f48771;
				}
				.file-action-icon.undo:hover {
				    background-color: #3a1e1e;
				}
				.file-action-icon.remove {
				    color: #999999;
				}
				.file-action-icon.remove:hover {
				    background-color: #3e3e42;
				}
				""";
		}

		// Light theme
		return """
			/* Modified Files Section - Light Theme */
			.modified-files-section {
			    border-top: 1px solid #e0e0e0;
			    border-bottom: 1px solid #e0e0e0;
			    background-color: #f8f8f8;
			    padding: 8px 12px;
			    margin-bottom: 8px;
			}
			.modified-files-header {
			    display: flex;
			    align-items: center;
			    gap: 8px;
			    user-select: none;
			}
			.toggle-icon {
			    cursor: pointer;
			    font-size: 12px;
			    color: #666;
			    transition: transform 0.2s;
			}
			.toggle-icon.collapsed {
			    transform: rotate(-90deg);
			}
			.section-title {
			    font-weight: 600;
			    font-size: 13px;
			    color: #333;
			    flex: 1;
			}
			.header-buttons {
			    display: flex;
			    gap: 6px;
			}
			.action-btn {
			    padding: 4px 10px;
			    font-size: 12px;
			    border: 1px solid #ccc;
			    border-radius: 3px;
			    background-color: #fff;
			    cursor: pointer;
			    transition: background-color 0.2s;
			}
			.action-btn:hover {
			    background-color: #e8e8e8;
			}
			.keep-all-btn {
			    color: #0078d4;
			    border-color: #0078d4;
			}
			.keep-all-btn:hover {
			    background-color: #e6f2ff;
			}
			.undo-all-btn {
			    color: #d13438;
			    border-color: #d13438;
			}
			.undo-all-btn:hover {
			    background-color: #ffe6e6;
			}
			.modified-files-list {
			    margin-top: 8px;
			    display: flex;
			    flex-direction: column;
			    gap: 4px;
			}
			.modified-files-list.collapsed {
			    display: none;
			}
			.file-entry {
			    display: flex;
			    align-items: center;
			    padding: 6px 8px;
			    background-color: #fff;
			    border: 1px solid #e0e0e0;
			    border-radius: 3px;
			    cursor: pointer;
			    transition: background-color 0.2s;
			    position: relative;
			}
			.file-entry:hover {
			    background-color: #f0f0f0;
			}
			.file-entry:hover .file-actions {
			    display: flex;
			}
			.file-name {
			    flex: 1;
			    font-size: 13px;
			    color: #333;
			    overflow: hidden;
			    text-overflow: ellipsis;
			    white-space: nowrap;
			}
			.file-actions {
			    display: none;
			    gap: 6px;
			    margin-left: 8px;
			}
			.file-action-icon {
			    width: 20px;
			    height: 20px;
			    display: flex;
			    align-items: center;
			    justify-content: center;
			    border-radius: 3px;
			    cursor: pointer;
			    font-size: 14px;
			}
			.file-action-icon.keep {
			    color: #0078d4;
			}
			.file-action-icon.keep:hover {
			    background-color: #e6f2ff;
			}
			.file-action-icon.undo {
			    color: #d13438;
			}
			.file-action-icon.undo:hover {
			    background-color: #ffe6e6;
			}
			.file-action-icon.remove {
			    color: #666;
			}
			.file-action-icon.remove:hover {
			    background-color: #f0f0f0;
			}
			""";
	}

	/**
	 * Returns JavaScript for the modified files section functionality.
	 */
	private String getModifiedFilesJavaScript()
	{
		return """
			// Modified Files Section JavaScript
			function updateModifiedFilesList(filesJson) {
			    const files = JSON.parse(filesJson);
			    const section = document.getElementById('modified-files-section');
			    const list = document.getElementById('modified-files-list');

			    if (files.length > 0) {
			        section.style.display = 'block';
			        list.innerHTML = '';
			        files.forEach(file => {
			            const entry = createFileEntry(file);
			            list.appendChild(entry);
			        });
			    } else {
			        section.style.display = 'none';
			    }
			}

			function createFileEntry(file) {
			    const entry = document.createElement('div');
			    entry.className = 'file-entry';

			    const fileName = document.createElement('span');
			    fileName.className = 'file-name';
			    fileName.textContent = file.name;
			    fileName.title = file.path;

			    const actions = document.createElement('div');
			    actions.className = 'file-actions';

			    const keepIcon = document.createElement('div');
			    keepIcon.className = 'file-action-icon keep';
			    keepIcon.innerHTML = '✓';
			    keepIcon.title = 'Keep changes';
			    keepIcon.onclick = (e) => {
			        e.stopPropagation();
			        window.onKeepFile(file.path);
			    };

			    const undoIcon = document.createElement('div');
			    undoIcon.className = 'file-action-icon undo';
			    undoIcon.innerHTML = '✗';
			    undoIcon.title = 'Undo changes';
			    undoIcon.onclick = (e) => {
			        e.stopPropagation();
			        window.onUndoFile(file.path);
			    };

			    const removeIcon = document.createElement('div');
			    removeIcon.className = 'file-action-icon remove';
			    removeIcon.innerHTML = '🗑️';
			    removeIcon.title = 'Dismiss tracking';
			    removeIcon.onclick = (e) => {
			        e.stopPropagation();
			        window.onRemoveFile(file.path);
			    };

			    actions.appendChild(keepIcon);
			    actions.appendChild(undoIcon);
			    actions.appendChild(removeIcon);

			    entry.appendChild(fileName);
			    entry.appendChild(actions);

			    entry.onclick = () => {
			        console.log('[DEBUG] File entry clicked, path:', file.path);
			        console.log('[DEBUG] Calling window.onFileClick');
			        window.onFileClick(file.path);
			        console.log('[DEBUG] window.onFileClick called');
			    };

			    return entry;
			}

			function toggleModifiedFiles() {
			    const toggle = document.getElementById('modified-files-toggle');
			    const list = document.getElementById('modified-files-list');

			    if (list.classList.contains('collapsed')) {
			        list.classList.remove('collapsed');
			        toggle.classList.remove('collapsed');
			        toggle.textContent = '▼';
			    } else {
			        list.classList.add('collapsed');
			        toggle.classList.add('collapsed');
			        toggle.textContent = '▶';
			    }
			}

			function keepAllFiles() {
			    window.onKeepAll();
			}

			function undoAllFiles() {
			    window.onUndoAll();
			}

			function clearModifiedFilesSection() {
			    const section = document.getElementById('modified-files-section');
			    section.style.display = 'none';
			    const list = document.getElementById('modified-files-list');
			    list.innerHTML = '';
			}
			""";
	}

}
