package com.servoy.eclipse.servoypilot.chatview.parts;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;


public class ChatView {
	public enum NotificationType {
		INFO, WARNING, ERROR
	}
	
    @Inject
    private ILog                 logger;
    
    @Inject
    private UISynchronize uiSync;
	
    @Inject
    private AssistaiSharedFiles  sharedFiles;
    
    @Inject
    private AssistaiSharedFonts sharedFonts;
    
    @Inject
    private ChatViewPresenter presenter;

    
	private Browser browser;
	private Text inputArea;
	private boolean autoScrollEnabled = true;
	private int notificationIdCounter = 0;
	private ToolBar actionToolBar;

	@PostConstruct
	public void createPartControl(Composite parent) {
		presenter.setChatView(this);
		// Create a SashForm to act as the split pane
		SashForm sashForm = new SashForm(parent, SWT.VERTICAL);
		sashForm.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );

        Composite browserContainer = new Composite( sashForm, SWT.NONE );
        browserContainer.setLayout( new FillLayout() );
		// Create the browser component on top
		browser =  createChatView(browserContainer);

		 Composite controls = new Composite( sashForm, SWT.NONE );
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
//	        setupAutocomplete( inputArea );
	        
	        
	        // Create button bar at the bottom with model selector on left, action buttons on right
	        Composite buttonBar = new Composite(controls, SWT.NONE);
	        GridLayout buttonBarLayout = new GridLayout(2, false);
	        buttonBarLayout.marginHeight = 0;
	        buttonBarLayout.marginWidth = 0;
	        buttonBar.setLayout(buttonBarLayout);
	        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
	        
	        // Left side: Model selector
	        
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
	}

	@Focus
	public void setFocus() {
		inputArea.setFocus();
	}
	
    public void clearChatView()
    {
        uiSync.asyncExec( () -> initializeChatView( browser ) );
    }

    public void clearUserInput()
    {
        uiSync.asyncExec( () -> inputArea.setText( "" ) );
    }
    
	public void setInputEnabled( boolean enabled )
	{
	    uiSync.asyncExec( () -> {
	        inputArea.setEnabled( enabled );
	        if ( enabled ) {
	            // Restore focus after a small delay to ensure browser operations complete
	            Display.getCurrent().timerExec( 100, () -> {
	                if ( !inputArea.isDisposed() ) {
	                    inputArea.setFocus();
	                }
	            } );
	        }
	    } );
	}
    
    /**
     * Creates a toolbar item that allows the user to clear the conversation.
     * 
     * @param toolbar The parent toolbar
     * @return The created toolbar item
     */
    private ToolItem createClearChatToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);
        try {
            // Use the erase/clear icon
            Image clearIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ETOOL_CLEAR);
            item.setImage(clearIcon);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        item.setToolTipText("Clear conversation");
        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
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
    private ToolItem createStopToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);

        // Use the built-in 'IMG_ELCL_STOP' icon
        Image stopIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_STOP);
        item.setImage(stopIcon);
        item.setToolTipText("Stop generation");

        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
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
    private ToolItem createSendToolItem(ToolBar toolbar) {
        ToolItem item = new ToolItem(toolbar, SWT.PUSH);
        
        try {
            // Use the forward/send icon
            Image sendIcon = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_FORWARD);
            item.setImage(sendIcon);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        
        item.setToolTipText("Send message (Ctrl+Enter)");
        
        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                String text = inputArea.getText().trim();
                if (!text.isEmpty()) {
                    presenter.onSendUserMessage(text);
                }
            }
        });
        
        return item;
    }

    private Text createUserInput( Composite parent )
    {
        Text inputArea = new Text( parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
        
        // Set a prompt message
        inputArea.setMessage("Type a message or question here... (Press Ctrl+Enter to send)");
        
        // Add a key listener to handle Ctrl+Enter to send the message
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.CR && (e.stateMask & SWT.CTRL) != 0) {
                    e.doit = false; // Prevent default behavior
                    // Only send if there's actual text to send
                    String text = inputArea.getText().trim();
                    if (!text.isEmpty()) {
                        presenter.onSendUserMessage( text );
                    }
                }
            }
        });
        
        createCustomMenu( inputArea );
        return inputArea;
    }
    
    /**
     * Dynamically creates and assigns a custom context menu to the input area.
     * <p>
     * This method constructs a context menu with "Cut", "Copy", and "Paste"
     * actions for the text input area. The "Paste" action is conditionally
     * enabled based on the current content of the clipboard: it's enabled if
     * the clipboard contains either text or image data. When triggered, the
     * "Paste" action checks the clipboard content type and handles it
     * accordingly - pasting text directly into the input area or invoking a
     * custom handler for image data.
     *
     * @param inputArea
     *            The Text widget to which the custom context menu will be
     *            attached.
     */
    private void createCustomMenu( Text inputArea )
    {
        Menu menu = new Menu( inputArea );
        inputArea.setMenu( menu );
        menu.addMenuListener( new MenuAdapter()
        {
            @Override
            public void menuShown( MenuEvent e )
            {
                // Dynamically adjust the context menu
                MenuItem[] items = menu.getItems();
                for ( MenuItem item : items )
                {
                    item.dispose();
                }
                // Add Cut, Copy, Paste items
                addMenuItem( menu, "Cut", () -> inputArea.cut() );
                addMenuItem( menu, "Copy", () -> inputArea.copy() );
                MenuItem pasteItem = addMenuItem( menu, "Paste", () -> handlePasteOperation() );
                // Enable or disable paste based on clipboard content
                Clipboard clipboard = new Clipboard( Display.getCurrent() );
                boolean enablePaste = clipboard.getContents( TextTransfer.getInstance() ) != null
                        || clipboard.getContents( ImageTransfer.getInstance() ) != null;
                pasteItem.setEnabled( enablePaste );
                clipboard.dispose();
            }
        } );
    }
    
    private MenuItem addMenuItem( Menu parent, String text, Runnable action )
    {
        MenuItem item = new MenuItem( parent, SWT.NONE );
        item.setText( text );
        item.addListener( SWT.Selection, e -> action.run() );
        return item;
    }
    
    private void handlePasteOperation()
    {
        Clipboard clipboard = new Clipboard( Display.getCurrent() );

        if ( clipboard.getContents( ImageTransfer.getInstance() ) != null )
        {
            ImageData imageData = (ImageData) clipboard.getContents( ImageTransfer.getInstance() );
            presenter.onAttachmentAdded( imageData );
        }
        else
        {
            String textData = (String) clipboard.getContents( TextTransfer.getInstance() );
            if ( textData != null )
            {
            	inputArea.insert( textData ); // Manually insert text at the
                                              // current caret position
            }

        }
    }

	private Browser createChatView(Composite parent) {
		Browser browser = new Browser(parent, SWT.NONE);
//		browser.setData("AUTOSCALE_DISABLED", Boolean.TRUE); 
		initializeChatView(browser);
		initializeFunctions(browser);
		return browser;
	}

	private void initializeFunctions(Browser browser) {
	        new CopyCodeFunction( browser, "eclipseCopyCode" );
	        new ApplyPatchFunction( browser, "eclipseApplyPatch" );
	        new DiffCodeFunction( browser, "eclipseDiffCode" );
	        new InsertCodeFunction( browser, "eclipseInsertCode" );
	        new NewFileFunction( browser, "eclipseNewFile" );
	        new ScrollInteractionFunction( browser, "eclipseScrollInteraction" );
	        new RemoveMessageFunction( browser, "eclipseRemoveMessage" );
	}

	private void initializeChatView(Browser browser) {
		String htmlTemplate = """
				<!DOCTYPE html>
				<html>
				    <style>${css}</style>
				    <style>${fonts}</style>
				    <script>${js}</script>
				    <body>
				            <div id="notification-container"></div>
				            <div id="content">
				            </div>
				    </body>
				</html>
				""";

		String js = loadJavaScripts();
		String css = loadCss();
		String fonts = loadFonts();
		htmlTemplate = htmlTemplate.replace("${css}", css).replace("${fonts}", fonts).replace("${js}", js);

		// Initialize the browser with base HTML and CSS
		browser.setText(htmlTemplate);
	}

	private String loadFonts() {
		return sharedFonts.loadFontsCss();
	}

	/**
	 * Loads the CSS files for the ChatGPTViewPart component.
	 *
	 * @return A concatenated string containing the content of the loaded CSS files.
	 */
	private String loadCss() {
		String[] cssFiles = { "textview_dark.css", "dark.min.css", "fa6.all.min.css", "katex.min.css" };
		if (true) {
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
	private String loadJavaScripts() {
		String[] jsFiles = { "highlight.min.js", "textview.js", "katex.min.js" };

		var jsContent = Arrays.stream(jsFiles).map(file -> "js/" + file).map(sharedFiles::readFile)
				.collect(Collectors.joining("\n\n"));
		return jsContent;
	}
	
    public void setMessageHtml( String messageId, String messageBody )
    {
        uiSync.asyncExec( () -> {
            MarkdownParser parser = new MarkdownParser( messageBody );

            String fixedHtml = escapeHtmlQuotes( fixLineBreaks( parser.parseToHtml() ) );
            // inject and highlight html message
            browser.execute( "var target = document.getElementById(\"message-content-" + messageId + "\") || document.getElementById(\"message-" + messageId + "\"); if (target) { target.innerHTML = '" + fixedHtml + "'; } renderCode();" );
            // Scroll down only if auto-scroll is enabled
            if ( autoScrollEnabled )
            {
                browser.execute( "window.scrollTo(0, document.body.scrollHeight);" );
            }
        } );
    }

    /**
     * Replaces newline characters with line break escape sequences in the given
     * string.
     *
     * @param html
     *            The input string containing newline characters.
     * @return A string with newline characters replaced by line break escape
     *         sequences.
     */
    private String fixLineBreaks( String html )
    {
        return html.replace( "\n", "\\n" ).replace( "\r", "" );
    }

    /**
     * Escapes HTML quotation marks in the given string.
     * 
     * @param html
     *            The input string containing HTML.
     * @return A string with escaped quotation marks for proper HTML handling.
     */
    private String escapeHtmlQuotes( String html )
    {
        return html.replace( "\"", "\\\"" ).replace( "'", "\\'" );
    }

    public void addMessage( String messageId, String role )
    {
        String cssClass = "user".equals( role ) ? "chat-bubble me" : "chat-bubble you";
        uiSync.asyncExec( () -> {
            browser.execute( """
                    var node = document.createElement("div");
                    node.setAttribute("id", "message-${id}");
                    node.setAttribute("class", "${cssClass}");
                    
                    var toolbar = document.createElement('div');
                    toolbar.setAttribute('class', 'message-toolbar');
                    
                    var trash = document.createElement('i');
                    trash.setAttribute('class', 'fa-solid fa-trash');
                    trash.onclick = function() { window.eclipseRemoveMessage('${id}'); };
                    
                    toolbar.appendChild(trash);
                    
                    var content = document.createElement('div');
                    content.setAttribute('id', 'message-content-${id}');
                    
                    node.appendChild(toolbar);
                    node.appendChild(content);
                    
                    document.getElementById("content").appendChild(node);
                    	""".replace( "${id}", messageId ).replace( "${cssClass}", cssClass ) );
            // Scroll down only if auto-scroll is enabled
            if ( autoScrollEnabled )
            {
                browser.execute( "window.scrollTo(0, document.body.scrollHeight);" );
            }
        } );
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


	public void removeMessage( String messageId )
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
	 * Shows a notification bar at the top of the browser window.
	 * Multiple notifications can be displayed simultaneously and will stack vertically.
	 * Each notification includes an icon, message, and close button.
	 * 
	 * Example usage:
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
	 * @param message The notification message to display
	 * @param duration The duration to show the notification
	 * @param type The type of notification (INFO, WARNING, ERROR)
	 */
	public void showNotification(String message, Duration duration, NotificationType type) {
	    uiSync.asyncExec(() -> {
	        String notificationId = "notification-" + (notificationIdCounter++);
	        
	        // Determine icon and color based on type
	        String icon, bgColor, textColor;
	        switch (type) {
	            case INFO:
	                icon = "fa-solid fa-circle-info";
	                bgColor = "#1f6feb";
	                textColor = "#ffffff";
	                break;
	            case WARNING:
	                icon = "fa-solid fa-triangle-exclamation";
	                bgColor = "#d29922";
	                textColor = "#000000";
	                break;
	            case ERROR:
	                icon = "fa-solid fa-circle-xmark";
	                bgColor = "#da3633";
	                textColor = "#ffffff";
	                break;
	            default:
	                icon = "fa-solid fa-circle-info";
	                bgColor = "#1f6feb";
	                textColor = "#ffffff";
	        }
	        
	        // Escape message for JavaScript
	        String escapedMessage = escapeJavaScript(message);
	        
	        // Call JavaScript function to create notification
	        browser.execute(String.format(
	            "showNotification('%s', '%s', '%s', '%s', '%s');",
	            notificationId, icon, bgColor, textColor, escapedMessage
	        ));
	        
	        // Schedule removal after duration
	        if (duration.toMillis()  > 0) {
	            Display.getDefault().timerExec((int) duration.toMillis(), () -> {
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
	private String escapeJavaScript(String text) {
	    return text.replace("\\", "\\\\")
	               .replace("'", "\\'")
	               .replace("\"", "\\\"")
	               .replace("\n", "\\n")
	               .replace("\r", "\\r")
	               .replace("\t", "\\t");
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
	public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) ISelection s) {
		if (s == null || s.isEmpty())
			return;

		if (s instanceof IStructuredSelection) {
			IStructuredSelection iss = (IStructuredSelection) s;
			if (iss.size() == 1)
				setSelection(iss.getFirstElement());
			else
				setSelection(iss.toArray());
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
	public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object[] selectedObjects) {
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
	public void setSelection(@Named(IServiceConstants.ACTIVE_SELECTION) Object o) {

		// Remove the 2 following lines in pure E4 mode, keep them in mixed mode
		if (o instanceof ISelection) // Already captured
			return;
	}

	/**
     * This function establishes a JavaScript-to-Java callback for the browser,
     * allowing the IDE to copy code. It is invoked from JavaScript when the
     * user interacts with the chat view to copy a code block.
     */
    private class CopyCodeFunction extends BrowserFunction
    {
        public CopyCodeFunction( Browser browser, String name )
        {
            super( browser, name );
        }

        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onCopyCode( codeBlock );
            }
            return null;
        }
    }
    
    /**
     * This function establishes a JavaScript-to-Java callback for the browser,
     * allowing the IDE to copy code. It is invoked from JavaScript when the
     * user interacts with the chat view to copy a code block.
     */
    private class ApplyPatchFunction extends BrowserFunction
    {
        public ApplyPatchFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onApplyPatch( codeBlock );
            }
            return null;
        }
    }
    private class InsertCodeFunction extends BrowserFunction
    {
        public InsertCodeFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onInsertCode( codeBlock );
            }
            return null;
        }
    }
    private class DiffCodeFunction extends BrowserFunction
    {
        public DiffCodeFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String codeBlock = (String) arguments[0];
                presenter.onDiffCode( codeBlock );
            }
            return null;
        }
    }
    private class NewFileFunction extends BrowserFunction
    {
        public NewFileFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && Arrays.stream( arguments ).allMatch( s -> s instanceof String ) )
            {
                String codeBlock = (String) arguments[0];
                String lang      = (String) arguments[1];
                presenter.onNewFile( codeBlock, lang );
            }
            return null;
        }
    }
    
    /**
     * This function establishes a JavaScript-to-Java callback for the browser,
     * allowing the browser to notify Java when the user scrolls. It is invoked 
     * from JavaScript when the scroll position changes.
     */
    private class RemoveMessageFunction extends BrowserFunction
    {
        public RemoveMessageFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof String )
            {
                String messageId = (String) arguments[0];
                presenter.onRemoveMessage( messageId );
            }
            return null;
        }
    }
    
    private class ScrollInteractionFunction extends BrowserFunction
    {
        public ScrollInteractionFunction( Browser browser, String name )
        {
            super( browser, name );
        }
        @Override
        public Object function( Object[] arguments )
        {
            if ( arguments.length > 0 && arguments[0] instanceof Boolean )
            {
                autoScrollEnabled = (Boolean) arguments[0];
            }
            return null;
        }
    }

}