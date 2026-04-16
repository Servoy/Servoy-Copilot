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
package com.servoy.eclipse.servoypilot.util;

import java.util.List;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.servoypilot.ai.AssistantType;
import com.servoy.eclipse.servoypilot.chatview.parts.ChatView;

/**
 * Utility class for opening and activating the Servoy AI Chat view.
 * Ensures the chat view is visible and ready to receive requests.
 */
public class ChatViewActivator
{
	// View ID from fragment.e4xmi - elementId="com.servoypilot.chatview"
	private static final String CHAT_VIEW_ID = "com.servoypilot.chatview";

	/**
	 * Opens the Servoy AI Chat view if not already open, or activates it if already visible.
	 * 
	 * @return true if the view was successfully opened/activated, false otherwise
	 */
	public static boolean openAndActivateChatView()
	{
		try
		{
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null)
			{
				IWorkbenchPage page = window.getActivePage();
				if (page != null)
				{
					// Try to find existing view first
					IViewPart viewPart = page.findView(CHAT_VIEW_ID);

					if (viewPart == null)
					{
						// View not open, show it
						viewPart = page.showView(CHAT_VIEW_ID);
					}

					// Activate the view (bring to front)
					if (viewPart != null)
					{
						page.activate(viewPart);
						return true;
					}
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError(e);
		}

		return false;
	}

	/**
	 * Gets the ChatView instance if the view is currently open.
	 * 
	 * @return the ChatView instance, or null if the view is not open
	 */
	public static ChatView getChatView()
	{
		try
		{
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window == null)
			{
				return null;
			}

			IWorkbenchPage page = window.getActivePage();
			if (page == null)
			{
				return null;
			}

			IViewPart viewPart = page.findView(CHAT_VIEW_ID);
			if (viewPart == null)
			{
				return null;
			}

			EPartService partService = window.getService(EPartService.class);
			if (partService != null)
			{
				MPart mPart = partService.findPart(CHAT_VIEW_ID);
				if (mPart != null)
				{
					Object obj = mPart.getObject();
					if (obj instanceof ChatView)
					{
						return (ChatView)obj;
					}
				}
			}
			if (viewPart instanceof ChatView)
			{
				return (ChatView)viewPart;
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError(e);
		}

		return null;
	}

	/**
	 * Opens the chat view, switches to the specified assistant, and optionally sends a message.
	 * <p>
	 * Note: This method schedules the assistant switch asynchronously. The return value indicates
	 * whether the operation was successfully scheduled, not whether it completed successfully.
	 * 
	 * @param assistantType The assistant type to switch to
	 * @param displayText Optional text to display in the UI (null to skip sending message)
	 * @return true if the operation was successfully scheduled, false if preconditions failed
	 */
	public static boolean openAndSwitchToAssistant(AssistantType assistantType, String displayText)
	{
		if (openAndActivateChatView())
		{
			ChatView chatView = getChatView();
			if (chatView != null)
			{
				// Schedule the switch and message send on the UI thread with proper sequencing
				Display.getDefault().asyncExec(() -> {
					// Ensure assistant selector is populated
					chatView.getPresenter().populateAssistantSelector();

					// Switch to specified assistant (will clear view if switching from another)
					chatView.getPresenter().switchToAssistant(assistantType);

					// If message provided, schedule message sending after assistant switch completes
					if (displayText != null)
					{
						// Schedule message sending after assistant switch completes
						Display.getCurrent().timerExec(150, () -> {
							// Send the message - display text in UI, full text (with context) to AI
							chatView.getPresenter().onSendUserMessage(displayText);
						});
					}
				});
				return true;
			}
		}
		return false;
	}

	/**
	 * Gets the icon URI based on the theme.
	 * 
	 * @param isDarkTheme true if dark theme is active, false for light theme
	 * @return the icon URI string
	 */
	private static String getIconURI(boolean isDarkTheme)
	{
		String iconFolder = isDarkTheme ? "darkicons" : "icons";
		return "platform:/plugin/com.servoy.eclipse.servoypilot/" + iconFolder + "/aichat.png";
	}

	/**
	 * Updates the ChatView icon based on the theme.
	 * 
	 * @param isDarkTheme true if dark theme is active, false for light theme
	 * @return true if the icon was successfully updated, false otherwise
	 */
	public static boolean updateChatViewIcon(boolean isDarkTheme)
	{
		try
		{
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null)
			{
				EPartService partService = window.getService(EPartService.class);
				if (partService != null)
				{
					MPart mPart = partService.findPart(CHAT_VIEW_ID);
					if (mPart != null)
					{
						mPart.setIconURI(getIconURI(isDarkTheme));
						return true;
					}
				}
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError(e);
		}
		return false;
	}

	/**
	 * Updates the ChatView PartDescriptor icon based on the theme.
	 * This is used by the model processor to update the icon in the Show View dialog.
	 * 
	 * @param application the E4 application model
	 * @param isDarkTheme true if dark theme is active, false for light theme
	 * @return true if the icon was successfully updated, false otherwise
	 */
	public static boolean updatePartDescriptorIcon(MApplication application, boolean isDarkTheme)
	{
		try
		{
			if (application == null)
			{
				return false;
			}

			// Get the descriptors list directly from application model
			List<MPartDescriptor> appDescriptors = application.getDescriptors();

			// Find the ChatView descriptor
			MPartDescriptor chatDescriptor = null;
			if (appDescriptors != null)
			{
				for (MPartDescriptor desc : appDescriptors)
				{
					if (CHAT_VIEW_ID.equals(desc.getElementId()))
					{
						chatDescriptor = desc;
						break;
					}
				}
			}

			if (chatDescriptor != null)
			{
				chatDescriptor.setIconURI(getIconURI(isDarkTheme));
				return true;
			}
		}
		catch (Exception e)
		{
			ServoyLog.logError(e);
		}
		return false;
	}
}
