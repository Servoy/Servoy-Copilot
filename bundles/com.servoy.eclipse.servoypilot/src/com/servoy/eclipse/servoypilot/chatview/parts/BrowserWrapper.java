package com.servoy.eclipse.servoypilot.chatview.parts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jface.util.Util;
import org.eclipse.swt.widgets.Composite;

/**
 * Wrapper class for browser functionality that automatically selects between
 * SWT Browser (Windows, macOS) and Chromium Browser (Linux) based on the platform.
 */
public class BrowserWrapper
{
	private final Object browser;
	private Path tempHtmlFile;

	/**
	 * Creates a new browser instance appropriate for the current platform.
	 * On Linux, tries to use Chromium browser first, but falls back to SWT browser
	 * if Chromium is not available.
	 * 
	 * @param parent the parent composite
	 * @param style the SWT style flags
	 */
	public BrowserWrapper(Composite parent, int style)
	{
		Object browserInstance = null;
		
		if (Util.isLinux())
		{
			// Try to create Chromium browser first on Linux
			try
			{
				browserInstance = new com.equo.chromium.swt.Browser(parent, style);
			}
			catch (NoClassDefFoundError | Exception e)
			{
				// Chromium dependency not available, fall back to SWT browser
				System.out.println("Chromium browser not available on Linux, falling back to SWT browser: " + e.getMessage());
				browserInstance = new org.eclipse.swt.browser.Browser(parent, style);
			}
		}
		else
		{
			browserInstance = new org.eclipse.swt.browser.Browser(parent, style);
		}
		
		this.browser = browserInstance;
	}

	/**
	 * Executes JavaScript code in the browser.
	 * 
	 * @param script the JavaScript code to execute
	 * @return the result of the script execution
	 */
	public Object execute(String script)
	{
		if (browser instanceof org.eclipse.swt.browser.Browser)
		{
			return ((org.eclipse.swt.browser.Browser)browser).execute(script);
		}
		else if (browser instanceof com.equo.chromium.swt.Browser)
		{
			return ((com.equo.chromium.swt.Browser)browser).execute(script);
		}
		return null;
	}

	/**
	 * Sets the HTML content of the browser.
	 * For Chromium on Linux, if the content is large, it uses a temporary file approach
	 * instead of setText to avoid size limitations.
	 * 
	 * @param html the HTML content to display
	 * @return true if the operation was successful, false otherwise
	 */
	public boolean setText(String html)
	{
		if (browser instanceof org.eclipse.swt.browser.Browser)
		{
			return ((org.eclipse.swt.browser.Browser)browser).setText(html);
		}
		else if (browser instanceof com.equo.chromium.swt.Browser)
		{
			// Chromium has size limitations with setText for large content
			// If content is larger than 500KB, use a file-based approach
			if (html.length() > 500_000)
			{
				return setTextViaFile(html);
			}
			else
			{
				return ((com.equo.chromium.swt.Browser)browser).setText(html);
			}
		}
		return false;
	}

	/**
	 * Sets HTML content via a temporary file for large content on Chromium.
	 * This avoids the size limitations of setText().
	 * 
	 * @param html the HTML content to display
	 * @return true if successful, false otherwise
	 */
	private boolean setTextViaFile(String html)
	{
		try
		{
			// Clean up previous temp file if it exists
			if (tempHtmlFile != null && Files.exists(tempHtmlFile))
			{
				try
				{
					Files.delete(tempHtmlFile);
				}
				catch (IOException e)
				{
					// Ignore cleanup errors
				}
			}

			// Create a new temporary file
			tempHtmlFile = Files.createTempFile("servoy-ai-chat-", ".html");
			Files.write(tempHtmlFile, html.getBytes(StandardCharsets.UTF_8));

			// Load the file via URL
			String fileUrl = tempHtmlFile.toUri().toString();
			return setUrl(fileUrl);
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Cleans up temporary resources.
	 * Should be called when the browser is disposed.
	 */
	public void dispose()
	{
		if (tempHtmlFile != null && Files.exists(tempHtmlFile))
		{
			try
			{
				Files.delete(tempHtmlFile);
			}
			catch (IOException e)
			{
				// Ignore cleanup errors
			}
		}
	}

	/**
	 * Sets the URL of the browser.
	 * 
	 * @param url the URL to navigate to
	 * @return true if the operation was successful, false otherwise
	 */
	public boolean setUrl(String url)
	{
		if (browser instanceof org.eclipse.swt.browser.Browser)
		{
			return ((org.eclipse.swt.browser.Browser)browser).setUrl(url);
		}
		else if (browser instanceof com.equo.chromium.swt.Browser)
		{
			return ((com.equo.chromium.swt.Browser)browser).setUrl(url);
		}
		return false;
	}

	/**
	 * Gets the current HTML text from the browser.
	 * 
	 * @return the current HTML text
	 */
	public String getText()
	{
		if (browser instanceof org.eclipse.swt.browser.Browser)
		{
			return ((org.eclipse.swt.browser.Browser)browser).getText();
		}
		else if (browser instanceof com.equo.chromium.swt.Browser)
		{
			return ((com.equo.chromium.swt.Browser)browser).getText();
		}
		return null;
	}

	/**
	 * Gets the underlying browser instance.
	 * This is needed for creating BrowserFunction instances.
	 * 
	 * @return the underlying browser object
	 */
	public Object getBrowserInstance()
	{
		return browser;
	}

	/**
	 * Checks if the browser is disposed.
	 * 
	 * @return true if the browser is disposed, false otherwise
	 */
	public boolean isDisposed()
	{
		if (browser instanceof org.eclipse.swt.browser.Browser)
		{
			return ((org.eclipse.swt.browser.Browser)browser).isDisposed();
		}
		else if (browser instanceof com.equo.chromium.swt.Browser)
		{
			return ((com.equo.chromium.swt.Browser)browser).isDisposed();
		}
		return true;
	}
}
