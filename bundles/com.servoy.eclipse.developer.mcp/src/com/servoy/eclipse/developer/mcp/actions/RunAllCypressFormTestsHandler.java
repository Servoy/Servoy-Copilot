package com.servoy.eclipse.developer.mcp.actions;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.handlers.HandlerUtil;

import com.servoy.eclipse.developer.mcp.services.FormSpecRunner;

public class RunAllCypressFormTestsHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (!(selection instanceof IStructuredSelection structuredSelection) || structuredSelection.isEmpty()) {
			return null;
		}

		Object element = structuredSelection.getFirstElement();
		Object adapted = Platform.getAdapterManager().getAdapter(element, CypressFormTestTarget.class);
		if (!(adapted instanceof CypressFormTestTarget target) || !target.isSolutionLevel()) {
			return null;
		}

		Job job = new Job("Running All Cypress Form Tests") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				List<String> testForms = target.getTestFormNames();
				if (testForms.isEmpty()) {
					Display.getDefault().asyncExec(() -> MessageDialog.openInformation(null, "Cypress Form Tests",
							"No Cypress form tests found."));
					return Status.OK_STATUS;
				}

				monitor.beginTask("Running Cypress form tests", testForms.size());

				MessageConsole console = CypressConsoleUtil.findOrCreateConsole();
				console.clearConsole();
				CypressConsoleUtil.showConsole(console);

				FormSpecRunner runner = new FormSpecRunner();
				int passed = 0;
				int failed = 0;

				try (MessageConsoleStream stream = console.newMessageStream()) {
					stream.println("Running " + testForms.size() + " Cypress form test(s)...\n");

					for (String formName : testForms) {
						if (monitor.isCanceled()) {
							stream.println("\nTest run cancelled.");
							return Status.CANCEL_STATUS;
						}

						monitor.subTask("Testing: " + formName);
						String result = runner.runSpec(formName, true);
						stream.println(result);
						stream.println("---\n");

						if (result.contains("All tests passed")) {
							passed++;
						} else {
							failed++;
						}
						monitor.worked(1);
					}

					stream.println("\n=== Aggregate Results ===");
					stream.println("Total: " + testForms.size() + " | Passed: " + passed + " | Failed: " + failed);
				} catch (Exception e) {
					Display.getDefault().asyncExec(() -> MessageDialog.openError(null, "Cypress Form Tests",
							"Error running tests: " + e.getMessage()));
				} finally {
					monitor.done();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();

		return null;
	}
}
