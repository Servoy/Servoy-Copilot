package com.servoy.eclipse.servoypilot.context;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;

/**
 * Dynamic context menu contribution for Servoy AI code analysis features.
 * Appears in JavaScript editor context menus when text is selected.
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
		MenuManager subMenuManager = new MenuManager("Servoy AI", "com.servoy.eclipse.servoypilot.contextmenu");

		// Debug command
		CommandContributionItemParameter debugParam = new CommandContributionItemParameter(
			window,
			null,
			"com.servoy.eclipse.servoypilot.context.debug",
			CommandContributionItem.STYLE_PUSH);
		subMenuManager.add(new CommandContributionItem(debugParam));

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

		return new IContributionItem[] { subMenuManager };
	}

	/**
	 * Menu is visible only when there is a text selection.
	 */
	private boolean shouldBeVisible()
	{
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null)
		{
			ISelection selection = window.getSelectionService().getSelection();
			if (selection instanceof ITextSelection textSelection)
			{
				String text = textSelection.getText();
				return text != null && text.trim().length() > 0;
			}
		}
		return false;
	}
}
