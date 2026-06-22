package com.servoy.eclipse.developer.mcp.actions;

import java.util.ArrayList;
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

	/**
	 * Holds the aggregate results of running multiple Cypress form tests.
	 */
	public static class TestRunResult {
		final int passed;
		final int failed;
		final int total;
		final boolean cancelled;
		final List<String> results;

		TestRunResult(int passed, int failed, int total, boolean cancelled, List<String> results) {
			this.passed = passed;
			this.failed = failed;
			this.total = total;
			this.cancelled = cancelled;
			this.results = results;
		}
	}

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

				try (MessageConsoleStream stream = console.newMessageStream()) {
					stream.println("Running " + testForms.size() + " Cypress form test(s)...\n");

					TestRunResult result = runTestsCore(testForms, runner, monitor);

					for (String line : result.results) {
						stream.println(line);
						stream.println("---\n");
					}

					if (result.cancelled) {
						stream.println("\nTest run cancelled.");
						return Status.CANCEL_STATUS;
					}

					stream.println("\n=== Aggregate Results ===");
					stream.println(formatAggregateResult(result));
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

	/**
	 * Core test execution logic separated from Eclipse UI concerns.
	 * Iterates through test forms, runs each spec, and counts pass/fail results.
	 * Package-private for testability.
	 */
	public TestRunResult runTestsCore(List<String> testForms, FormSpecRunner runner, IProgressMonitor monitor) {
		int passed = 0;
		int failed = 0;
		List<String> results = new ArrayList<>();

		for (String formName : testForms) {
			if (monitor != null && monitor.isCanceled()) {
				return new TestRunResult(passed, failed, testForms.size(), true, results);
			}

			if (monitor != null) {
				monitor.subTask("Testing: " + formName);
			}

			String result = runner.runSpec(formName, true);
			results.add(result);

			if (isTestPassed(result)) {
				passed++;
			} else {
				failed++;
			}

			if (monitor != null) {
				monitor.worked(1);
			}
		}

		return new TestRunResult(passed, failed, testForms.size(), false, results);
	}

	/**
	 * Determines whether a test result indicates success.
	 * Package-private for testability.
	 */
	public static boolean isTestPassed(String result) {
		return result != null && result.contains("All tests passed");
	}

	/**
	 * Formats the aggregate result summary line.
	 * Package-private for testability.
	 */
	public static String formatAggregateResult(TestRunResult result) {
		return "Total: " + result.total + " | Passed: " + result.passed + " | Failed: " + result.failed;
	}
}
