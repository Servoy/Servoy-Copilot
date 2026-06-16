package com.servoy.eclipse.developer.mcp.actions;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;

public final class CypressConsoleUtil {
	private static final String CONSOLE_NAME = "Cypress Form Tests";

	private CypressConsoleUtil() {
	}

	public static MessageConsole findOrCreateConsole() {
		IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
		for (IConsole existing : consoleManager.getConsoles()) {
			if (CONSOLE_NAME.equals(existing.getName()) && existing instanceof MessageConsole mc) {
				return mc;
			}
		}
		MessageConsole console = new MessageConsole(CONSOLE_NAME, null);
		consoleManager.addConsoles(new IConsole[] { console });
		return console;
	}

	public static void showConsole(MessageConsole console) {
		Display.getDefault().asyncExec(() -> {
			IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
			consoleManager.showConsoleView(console);
		});
	}
}
