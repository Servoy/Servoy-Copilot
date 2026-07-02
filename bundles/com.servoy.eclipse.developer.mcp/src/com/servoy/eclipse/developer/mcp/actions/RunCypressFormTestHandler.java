package com.servoy.eclipse.developer.mcp.actions;

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
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.handlers.HandlerUtil;

import com.servoy.eclipse.core.resource.PersistEditorInput;
import com.servoy.eclipse.developer.mcp.services.CypressTestDiscoveryService;
import com.servoy.eclipse.developer.mcp.services.CypressTestDiscoveryService.TestType;
import com.servoy.eclipse.developer.mcp.services.FormSpecRunner;

public class RunCypressFormTestHandler extends AbstractHandler {
	private final CypressTestDiscoveryService discoveryService = new CypressTestDiscoveryService();

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		String formName = getFormNameFromSelection(event);
		if (formName == null) {
			formName = getFormNameFromActiveEditor(event);
		}
		if (formName == null) {
			Display.getDefault()
					.asyncExec(() -> MessageDialog.openError(null, "Cypress Form Test", "No active Servoy project"));
			return null;
		}

		String targetFormName = formName;

		Job job = new Job("Running Cypress Form Test: " + targetFormName) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Running Cypress test for " + targetFormName, IProgressMonitor.UNKNOWN);
				try {
					MessageConsole console = CypressConsoleUtil.findOrCreateConsole();
					console.clearConsole();
					CypressConsoleUtil.showConsole(console);

					enableTestingMode();

					String result = runFormTestCore(targetFormName, new FormSpecRunner());

					try (MessageConsoleStream stream = console.newMessageStream()) {
						stream.println(result);
					}
				} catch (Exception e) {
					Display.getDefault().asyncExec(() -> MessageDialog.openError(null, "Cypress Form Test",
							"Error running test: " + e.getMessage()));
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
	 * Runs the appropriate test type (form test or E2E test) and returns the result string.
	 * Package-private for testability.
	 */
	public String runFormTestCore(String formName, FormSpecRunner runner) {
		if (formName == null || formName.isBlank()) {
			return "Error: No form name specified.";
		}
		TestType testType = discoveryService.getTestType(formName);
		if (testType == TestType.E2E) {
			return runner.runE2ESpec(formName, false);
		}
		return runner.runSpec(formName, false);
	}

	/**
	 * Enables Servoy NG client testing mode.
	 * Package-private for testability.
	 */
	public void enableTestingMode() {
		com.servoy.j2db.util.Settings.getInstance().setProperty("servoy.ngclient.testingMode", "true");
	}

	/**
	 * Returns the discovery service instance.
	 * Package-private for testability.
	 */
	CypressTestDiscoveryService getDiscoveryService() {
		return discoveryService;
	}

	private String getFormNameFromSelection(ExecutionEvent event) {
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (!(selection instanceof IStructuredSelection structuredSelection) || structuredSelection.isEmpty()) {
			return null;
		}

		Object element = structuredSelection.getFirstElement();
		Object adapted = Platform.getAdapterManager().getAdapter(element, CypressFormTestTarget.class);
		if (adapted instanceof CypressFormTestTarget target && target.getFormName() != null) {
			return target.getFormName();
		}
		return null;
	}

	private String getFormNameFromActiveEditor(ExecutionEvent event) {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		if (editor == null) {
			return null;
		}
		IEditorInput input = editor.getEditorInput();
		if (input instanceof PersistEditorInput persistInput) {
			String name = persistInput.getName();
			if (name != null && discoveryService.hasTest(name)) {
				return name;
			}
		}
		return null;
	}
}
