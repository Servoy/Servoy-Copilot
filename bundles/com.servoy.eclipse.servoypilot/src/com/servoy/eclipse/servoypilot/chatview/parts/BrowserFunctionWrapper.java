package com.servoy.eclipse.servoypilot.chatview.parts;

import com.servoy.eclipse.ui.browser.IBrowser;

/**
 * Abstract wrapper class for BrowserFunction that handles both SWT and Chromium browser types.
 * Subclasses only need to implement the function(Object[]) method.
 */
public abstract class BrowserFunctionWrapper
{
	/**
	 * Creates a new browser function for the given browser and registers it with the specified name.
	 * 
	 * @param browserWrapper the browser wrapper instance
	 * @param name the name of the JavaScript function
	 */
	public BrowserFunctionWrapper(IBrowser browserWrapper, String name)
	{
		Object browser = browserWrapper.getBrowserInstance();

		if (browser instanceof org.eclipse.swt.browser.Browser)
		{
			new org.eclipse.swt.browser.BrowserFunction((org.eclipse.swt.browser.Browser)browser, name)
			{
				@Override
				public Object function(Object[] arguments)
				{
					return BrowserFunctionWrapper.this.function(arguments);
				}
			};
		}
		else if (browser instanceof com.equo.chromium.swt.Browser)
		{
			new com.equo.chromium.swt.BrowserFunction((com.equo.chromium.swt.Browser)browser, name)
			{
				@Override
				public Object function(Object[] arguments)
				{
					return BrowserFunctionWrapper.this.function(arguments);
				}
			};
		}
	}

	/**
	 * The function to be called from JavaScript.
	 * Subclasses must implement this method to handle the function call.
	 * 
	 * @param arguments the arguments passed from JavaScript
	 * @return the return value to JavaScript
	 */
	public abstract Object function(Object[] arguments);
}
