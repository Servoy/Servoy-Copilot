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
package com.servoy.eclipse.servoypilot.context;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.servoy.eclipse.ui.tweaks.IconPreferences;

/**
 * Dynamic context menu contribution for Servoy AI code analysis features.
 * Appears in JavaScript editor context menus.
 * 
 * Pattern follows AI Bridge implementation - self-contained, no circular dependencies.
 */
public class ServoyAiContextMenu extends CompoundContributionItem
{
	@Override
	protected IContributionItem[] getContributionItems()
	{
		if (!shouldBeVisible())
		{
			return new IContributionItem[0];
		}

		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		boolean isDarkTheme = IconPreferences.getInstance().getUseDarkThemeIcons();
		String iconFolder = isDarkTheme ? "darkicons" : "icons";
		ImageDescriptor icon = AbstractUIPlugin.imageDescriptorFromPlugin(
			"com.servoy.eclipse.servoypilot", iconFolder + "/aichat.png");
		MenuManager subMenuManager = new MenuManager("Servoy AI", icon, "com.servoy.eclipse.servoypilot.contextmenu");

		// Debug command
		CommandContributionItemParameter debugParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.debug",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(debugParam));
		
		// Explain command
		CommandContributionItemParameter explainParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.explain",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(explainParam));

		// Review command
		CommandContributionItemParameter reviewParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.review",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(reviewParam));

		// Separator
		subMenuManager.add(new Separator());

		// Generate Docs command
		CommandContributionItemParameter docsParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.generateDocs",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(docsParam));

		// Generate Tests command
		CommandContributionItemParameter testsParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.generateTests",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(testsParam));

		// Separator
		subMenuManager.add(new Separator());

		// Query Builder command
		CommandContributionItemParameter queryBuilderParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.querybuilder",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(queryBuilderParam));

		return new IContributionItem[] { subMenuManager };
	}

	/**
	 * Menu is visible when there is an active editor.
	 * Works for both text selection and full file analysis.
	 */
	private boolean shouldBeVisible()
	{
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null)
		{
			ISelection selection = window.getSelectionService().getSelection();
			// Show menu if there's any text selection (including empty selection in active editor)
			return selection instanceof ITextSelection;
		}
		return false;
	}
}
