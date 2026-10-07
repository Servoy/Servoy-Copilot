/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

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

package com.servoy.eclipse.opencode.skilltest;

import com.servoy.eclipse.model.util.ServoyLog;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.part.ViewPart;

import com.servoy.eclipse.ui.browser.BrowserFactory;
import com.servoy.eclipse.ui.browser.IBrowser;
import com.servoy.eclipse.opencode.Activator;

/**
 * Dedicated Eclipse view for the Servoy AI skill-test baselines (SVY-21366).
 * <p>
 * The top table lists every baseline found under the baselines root
 * ({@code ~/.servoy/opencode/skilltests}) with its id, title, active flag, and
 * the status of the most recent run. The bottom panel shows the selected
 * baseline's last result in detail: status, attempts, and the tool-call diff.
 * </p>
 * <p>
 * The toolbar exposes three actions:
 * </p>
 * <ul>
 * <li><b>Import export…</b> — pick an opencode {@code export.json} from disk
 * and register it as a new baseline (via
 * {@link BaselineLoader#importExport}).</li>
 * <li><b>Record baseline…</b> — run a fresh prompt against the live server and
 * capture its transcript as a golden baseline.</li>
 * <li><b>Run</b> — run all active baselines and populate the results.</li>
 * </ul>
 */
public class SkillTestView extends ViewPart {

	public static final String ID = "com.servoy.eclipse.opencode.skilltest.SkillTestView"; //$NON-NLS-1$

	private TableViewer viewer;
	private IBrowser detail;

	/** Last run result per baseline id, so selecting a row shows its detail. */
	private final Map<String, SkillTestResult> lastResults = new HashMap<>();

	/** The currently running test Job, or {@code null} when idle. Used by Stop. */
	private volatile Job runJob;

	private Action runAction;
	private Action stopAction;
	private Action editJsUnitAction;

	@Override
	public void createPartControl(Composite parent) {
		SashForm sash = new SashForm(parent, SWT.VERTICAL);
		sash.setLayout(new FillLayout());

		Composite tableHolder = new Composite(sash, SWT.NONE);
		tableHolder.setLayout(new FillLayout());
		viewer = new TableViewer(tableHolder, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
		Table table = viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		viewer.setContentProvider(ArrayContentProvider.getInstance());

		createColumn("Baseline", 360, e -> baselineLabel(e.baseline())); //$NON-NLS-1$
		createColumn("Active", 60, e -> e.baseline().active() ? "yes" : "no"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		createColumn("Attempts", 70, e -> Integer.toString(e.baseline().maxAttempts())); //$NON-NLS-1$
		createColumn("Last result", 120, e -> { //$NON-NLS-1$
			SkillTestResult r = lastResults.get(e.baseline().id());
			return r != null ? r.getStatus().toString() : "—"; //$NON-NLS-1$
		});

		viewer.addSelectionChangedListener(event -> showDetail());
		// Double-click the Active cell (or a row) toggles the active flag.
		viewer.addDoubleClickListener(event -> toggleActive());

		Composite detailHolder = new Composite(sash, SWT.NONE);
		detailHolder.setLayout(new FillLayout());
		detail = BrowserFactory.createBrowser(detailHolder);
		detail.setText(SkillTestHtml.placeholder());

		sash.setWeights(new int[] { 60, 40 });

		createActions();
		createContextMenu();
		lastResults.putAll(SkillTestReporter.loadLastResults());
		refresh();
	}

	/**
	 * The label shown in the single "Baseline" column: the human title when the
	 * baseline has one, otherwise its id. Avoids showing both id and title.
	 */
	private static String baselineLabel(Baseline baseline) {
		String title = baseline.title();
		return title != null && !title.isBlank() ? title : baseline.id();
	}

	private void createColumn(String title, int width,
			java.util.function.Function<BaselineLoader.Entry, String> value) {
		TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
		col.getColumn().setText(title);
		col.getColumn().setWidth(width);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				return value.apply((BaselineLoader.Entry) element);
			}

			@Override
			public org.eclipse.swt.graphics.Color getForeground(Object element) {
				// Gray out deactivated baselines so it is obvious at a glance which
				// are excluded from "Run all active".
				if (element instanceof BaselineLoader.Entry entry && !entry.baseline().active()) {
					return Display.getDefault().getSystemColor(SWT.COLOR_GRAY);
				}
				return null;
			}
		});
	}

	private void createActions() {
		IToolBarManager tb = getViewSite().getActionBars().getToolBarManager();

		Action importAction = new Action("Add session export…") { //$NON-NLS-1$
			@Override
			public void run() {
				importExport();
			}
		};
		importAction.setToolTipText("Add an opencode session export.json from disk as a new baseline"); //$NON-NLS-1$
		importAction.setImageDescriptor(imageDescriptor("icons/skilltest_import.png")); //$NON-NLS-1$

		runAction = new Action("Run") { //$NON-NLS-1$
			@Override
			public void run() {
				runActive();
			}
		};
		runAction.setToolTipText("Run all active baselines"); //$NON-NLS-1$
		runAction.setImageDescriptor(imageDescriptor("icons/skilltest_run.png")); //$NON-NLS-1$

		stopAction = new Action("Stop") { //$NON-NLS-1$
			@Override
			public void run() {
				stopRun();
			}
		};
		stopAction.setToolTipText("Stop the running skill tests"); //$NON-NLS-1$
		stopAction.setImageDescriptor(org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(org.eclipse.ui.ISharedImages.IMG_ELCL_STOP));
		stopAction.setDisabledImageDescriptor(org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(org.eclipse.ui.ISharedImages.IMG_ELCL_STOP_DISABLED));
		stopAction.setEnabled(false);

		Action deleteAction = new Action("Delete baseline…") { //$NON-NLS-1$
			@Override
			public void run() {
				deleteBaseline();
			}
		};
		deleteAction.setToolTipText("Delete the selected baseline (removes its folder from disk)"); //$NON-NLS-1$
		org.eclipse.jface.resource.ImageDescriptor deleteIcon = imageDescriptor("icons/skilltest_delete.png"); //$NON-NLS-1$
		if (deleteIcon == null) {
			// Fall back to the shared platform "delete" icon so the toolbar button is
			// always visible even without a bundled skilltest_delete.png.
			deleteIcon = org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages()
					.getImageDescriptor(org.eclipse.ui.ISharedImages.IMG_TOOL_DELETE);
		}
		deleteAction.setImageDescriptor(deleteIcon);

		Action editExpectedAction = new Action("Edit expected outcomes…") { //$NON-NLS-1$
			@Override
			public void run() {
				editExpectedOutcomes();
			}
		};
		editExpectedAction.setToolTipText("Edit the expected Servoy persist outcomes for the selected baseline"); //$NON-NLS-1$
		editExpectedAction.setImageDescriptor(org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(org.eclipse.ui.ISharedImages.IMG_OBJ_ELEMENT));

		editJsUnitAction = new Action("Edit JSUnit verification…") { //$NON-NLS-1$
			@Override
			public void run() {
				editJsUnitVerify();
			}
		};
		editJsUnitAction.setToolTipText("Edit the JSUnit tests run after the skill run for the selected baseline"); //$NON-NLS-1$
		editJsUnitAction.setImageDescriptor(org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(org.eclipse.ui.ISharedImages.IMG_OBJ_FILE));

		Action refreshAction = new Action("Refresh") { //$NON-NLS-1$
			@Override
			public void run() {
				refresh();
			}
		};
		refreshAction.setToolTipText("Reload baselines from disk"); //$NON-NLS-1$
		refreshAction.setImageDescriptor(imageDescriptor("icons/skilltest_refresh.png")); //$NON-NLS-1$

		tb.add(importAction);
		tb.add(runAction);
		tb.add(stopAction);
		tb.add(editExpectedAction);
		tb.add(editJsUnitAction);
		tb.add(deleteAction);
		tb.add(refreshAction);
	}

	/**
	 * Builds an {@link org.eclipse.jface.resource.ImageDescriptor} for a
	 * bundle-relative icon path, or {@code null} if the entry cannot be resolved.
	 * Eclipse automatically resolves the {@code @2x} variant for HiDPI displays.
	 *
	 * @param bundlePath the icon path relative to the bundle root (e.g.
	 *                   {@code "icons/skilltest_run.png"})
	 * @return the image descriptor, or {@code null}
	 */
	private static org.eclipse.jface.resource.ImageDescriptor imageDescriptor(String bundlePath) {
		// Resolve icons from THIS (skilltest) bundle - not from the imported opencode
		// Activator's bundle, which has no icons/skilltest_*.png (that was the cause of
		// the text-only Import/Run toolbar buttons).
		org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(SkillTestView.class);
		if (bundle == null) {
			return null;
		}
		// FileLocator.find resolves the entry (and its @2x sibling) to a loadable URL.
		java.net.URL entry = org.eclipse.core.runtime.FileLocator.find(bundle,
				new org.eclipse.core.runtime.Path(bundlePath), null);
		if (entry == null) {
			entry = bundle.getEntry(bundlePath);
		}
		return entry != null ? org.eclipse.jface.resource.ImageDescriptor.createFromURL(entry) : null;
	}

	private void refresh() {
		List<BaselineLoader.Entry> entries = BaselineLoader.loadAllEntries(resolveBaselinesRoot());
		viewer.setInput(entries);
		showDetail();
	}

	private void showDetail() {
		if (detail == null || detail.isDisposed()) {
			return;
		}
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			detail.setText(SkillTestHtml.placeholder());
			return;
		}
		SkillTestResult r = lastResults.get(entry.baseline().id());
		if (r == null) {
			detail.setText(SkillTestHtml.notRun(entry.baseline().id()));
			return;
		}
		detail.setText(SkillTestHtml.render(r));
	}

	private BaselineLoader.Entry selectedEntry() {
		IStructuredSelection sel = viewer.getStructuredSelection();
		Object first = sel.getFirstElement();
		return first instanceof BaselineLoader.Entry entry ? entry : null;
	}

	// --- actions ---

	private void importExport() {
		File baselinesRoot = resolveBaselinesRoot();
		ImportBaselineDialog dialog = new ImportBaselineDialog(getSite().getShell(), baselinesRoot);
		if (dialog.open() != Window.OK) {
			return;
		}
		File exportFile = dialog.getExportFile();
		String id = dialog.getBaselineId();
		String title = dialog.getBaselineTitle();
		try {
			BaselineLoader.importExport(exportFile, baselinesRoot, id, title, dialog.getInferredAssertions());
			log("Imported baseline '" + id + "' from " + exportFile); //$NON-NLS-1$ //$NON-NLS-2$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Import failed", e.getMessage()); //$NON-NLS-1$
			log("Import failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	private void runActive() {
		final File baselinesRoot = resolveBaselinesRoot();
		startRun("Servoy AI skill tests", () -> { //$NON-NLS-1$
			List<Baseline> baselines = BaselineLoader.loadActive(baselinesRoot);
			if (baselines.isEmpty()) {
				log("No active skill-test baselines found under " + baselinesRoot); //$NON-NLS-1$
			}
			return baselines;
		});
	}

	/**
	 * Runs only the baseline selected in the table, regardless of its
	 * {@code active} flag (so a deactivated baseline can still be run on demand).
	 */
	private void runSelected() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Run selected", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		final Baseline baseline = entry.baseline();
		startRun("Servoy AI skill test: " + baseline.id(), () -> List.of(baseline)); //$NON-NLS-1$
	}

	/**
	 * Shared run driver: guards on server-readiness and a run already in progress,
	 * then runs the baselines supplied by {@code baselineSupplier} on a background
	 * Job, records results, persists a report, and refreshes the view. The supplier
	 * is evaluated on the Job thread.
	 *
	 * @param jobLabel         the Job label shown in the progress view
	 * @param baselineSupplier supplies the baselines to run (evaluated off the UI
	 *                         thread)
	 */
	private void startRun(String jobLabel, java.util.function.Supplier<List<Baseline>> baselineSupplier) {
		Activator activator = Activator.getInstance();
		if (activator == null || !activator.isServerReady()) {
			MessageDialog.openInformation(getSite().getShell(), "Servoy AI", //$NON-NLS-1$
					"Servoy AI server not ready - cannot run skill tests yet."); //$NON-NLS-1$
			return;
		}
		if (runJob != null) {
			log("A skill-test run is already in progress."); //$NON-NLS-1$
			return;
		}
		Job job = new Job(jobLabel) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				SkillTestRunner runner = new SkillTestRunner(SkillTestView.this::log);
				List<Baseline> baselines = baselineSupplier.get();
				if (baselines == null || baselines.isEmpty()) {
					return Status.OK_STATUS;
				}
				// Per-baseline setup (clean the solution project + materialize the
				// declared precondition.source: empty/git/folder), then replay. Without
				// this the view run replays against whatever the previous run left in the
				// workspace, so the agent sees the artifacts already present and does
				// nothing ("no tool calls"). The headless CI runner does the same.
				int webPort = resolveWebServerPort();
				com.servoy.eclipse.opencode.skilltest.headless.SolutionSetup setup = //
						new com.servoy.eclipse.opencode.skilltest.headless.SolutionSetup(SkillTestView.this::log,
								webPort > 0
										? new com.servoy.eclipse.opencode.skilltest.headless.McpToolClient(webPort,
												SkillTestView.this::log)
										: null);
				List<SkillTestResult> results = new java.util.ArrayList<>();
				for (Baseline baseline : baselines) {
					if (monitor.isCanceled()) {
						break;
					}
					try {
						setup.prepare(baseline);
						results.add(runner.runBaseline(baseline, monitor::isCanceled));
					} catch (Exception setupEx) {
						// Log the FULL stack trace (not just getMessage) so the real failing
						// frame is visible - a bare message like "Cannot invoke
						// Boolean.booleanValue()" is useless without the frame that threw it.
						ServoyLog.logError("[skilltest] " + baseline.id() + " setup failed", setupEx); //$NON-NLS-1$ //$NON-NLS-2$
						StringBuilder trace = new StringBuilder(
								"[skilltest] " + baseline.id() + " setup failed: " + setupEx); //$NON-NLS-1$ //$NON-NLS-2$
						for (StackTraceElement f : setupEx.getStackTrace()) {
							trace.append("\n    at ").append(f); //$NON-NLS-1$
						}
						Throwable cause = setupEx.getCause();
						while (cause != null) {
							trace.append("\n  Caused by: ").append(cause); //$NON-NLS-1$
							for (StackTraceElement f : cause.getStackTrace()) {
								trace.append("\n    at ").append(f); //$NON-NLS-1$
							}
							cause = cause.getCause();
						}
						log(trace.toString());
						results.add(SkillTestResult.error(baseline.id(), 0, "setup failed: " + setupEx.getMessage())); //$NON-NLS-1$
					}
				}
				for (SkillTestResult r : results) {
					lastResults.put(r.getBaselineId(), r);
				}
				String report = runner.formatResults(results);
				log(report);
				try {
					File reportFile = SkillTestReporter.writeRun(results, report);
					log("Wrote skill-test report: " + reportFile); //$NON-NLS-1$
				} catch (IOException e) {
					log("Could not persist skill-test results: " + e.getMessage()); //$NON-NLS-1$
				}
				long passed = results.stream().filter(SkillTestResult::isPass).count();
				log("Servoy AI skill tests: " + passed + "/" + results.size() //$NON-NLS-1$ //$NON-NLS-2$
						+ (monitor.isCanceled() ? " passed (run stopped)." : " passed.")); //$NON-NLS-1$ //$NON-NLS-2$
				asyncRefresh();
				return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.addJobChangeListener(new org.eclipse.core.runtime.jobs.JobChangeAdapter() {
			@Override
			public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event) {
				runJob = null;
				updateRunStopEnablement();
			}
		});
		runJob = job;
		updateRunStopEnablement();
		job.schedule();
	}

	/**
	 * Resolves the embedded Servoy web-server port (used by the {@code empty}-source
	 * MCP {@code createSolution} call), or -1 when the app server is not up.
	 */
	private static int resolveWebServerPort() {
		try {
			com.servoy.j2db.server.shared.IApplicationServerSingleton as = //
					com.servoy.j2db.server.shared.ApplicationServerRegistry.get();
			return as != null ? as.getWebServerPort() : -1;
		} catch (RuntimeException | LinkageError ex) {
			return -1;
		}
	}

	/**
	 * Requests cancellation of the in-progress run. The runner checks the Job's
	 * monitor between attempts and on every status poll, and aborts the in-flight
	 * opencode session, so Stop takes effect within one poll interval.
	 */
	private void stopRun() {
		Job job = runJob;
		if (job != null) {
			log("Stopping skill-test run…"); //$NON-NLS-1$
			job.cancel();
		}
	}

	/**
	 * Enables Run when idle and Stop when a run is in progress, on the UI thread.
	 */
	private void updateRunStopEnablement() {
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			boolean running = runJob != null;
			if (runAction != null) {
				runAction.setEnabled(!running);
			}
			if (stopAction != null) {
				stopAction.setEnabled(running);
			}
		});
	}

	/**
	 * Adds a right-click context menu to the baselines table with a Delete entry
	 * (enabled only when a row is selected), so a baseline can be removed without
	 * relying on the toolbar button.
	 */
	private void createContextMenu() {
		org.eclipse.jface.action.MenuManager menuManager = new org.eclipse.jface.action.MenuManager();
		menuManager.setRemoveAllWhenShown(true);
		menuManager.addMenuListener(manager -> {
			BaselineLoader.Entry entry = selectedEntry();

			Action runOne = new Action("Run") { //$NON-NLS-1$
				@Override
				public void run() {
					runSelected();
				}
			};
			runOne.setEnabled(entry != null && runJob == null);
			manager.add(runOne);

			boolean isActive = entry != null && entry.baseline().active();
			Action toggle = new Action(isActive ? "Deactivate" : "Activate") { //$NON-NLS-1$ //$NON-NLS-2$
				@Override
				public void run() {
					toggleActive();
				}
			};
			toggle.setEnabled(entry != null);
			manager.add(toggle);

			Action setAttempts = new Action("Set max attempts…") { //$NON-NLS-1$
				@Override
				public void run() {
					editMaxAttempts();
				}
			};
			setAttempts.setEnabled(entry != null);
			manager.add(setAttempts);

			Action editSource = new Action("Edit test solution source…") { //$NON-NLS-1$
				@Override
				public void run() {
					editPrecondition();
				}
			};
			editSource.setEnabled(entry != null);
			manager.add(editSource);

			manager.add(new org.eclipse.jface.action.Separator());

			Action editOutcomes = new Action("Edit expected outcomes…") { //$NON-NLS-1$
				@Override
				public void run() {
					editExpectedOutcomes();
				}
			};
			editOutcomes.setEnabled(entry != null);
			manager.add(editOutcomes);

			Action editJsUnit = new Action("Edit JSUnit verification…") { //$NON-NLS-1$
				@Override
				public void run() {
					editJsUnitVerify();
				}
			};
			editJsUnit.setEnabled(entry != null);
			manager.add(editJsUnit);

			manager.add(new org.eclipse.jface.action.Separator());

			Action delete = new Action("Delete baseline…") { //$NON-NLS-1$
				@Override
				public void run() {
					deleteBaseline();
				}
			};
			delete.setEnabled(entry != null);
			delete.setImageDescriptor(org.eclipse.ui.PlatformUI.getWorkbench().getSharedImages()
					.getImageDescriptor(org.eclipse.ui.ISharedImages.IMG_TOOL_DELETE));
			manager.add(delete);
		});
		org.eclipse.swt.widgets.Menu menu = menuManager.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);
	}

	/**
	 * Opens the persist-assertion editor (Option C) on the selected baseline's
	 * expected outcomes and, on OK, writes them back into its {@code baseline.json}
	 * {@code expect} block.
	 */
	private void editExpectedOutcomes() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Edit expected outcomes", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		PersistAssertionEditorDialog editor = new PersistAssertionEditorDialog(getSite().getShell(),
				"Expected outcomes for '" + entry.baseline().id() + "'", entry.baseline().expected()); //$NON-NLS-1$ //$NON-NLS-2$
		if (editor.open() != Window.OK) {
			return;
		}
		try {
			BaselineLoader.writeExpected(entry.folder(), editor.getAssertions());
			log("Updated expected outcomes for baseline '" + entry.baseline().id() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Edit failed", e.getMessage()); //$NON-NLS-1$
			log("Edit expected outcomes failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Opens the JSUnit verification editor for the selected baseline and, on OK,
	 * writes the {@code verify.jsunit} sidecar block + the injected test script (or
	 * removes the block when disabled).
	 */
	private void editJsUnitVerify() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Edit JSUnit verification", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		Baseline.JsUnitVerify current = entry.baseline().jsUnitVerify();
		String currentScript = BaselineLoader.readVerifyScript(entry.folder(), current);
		String currentScriptName = current != null && !current.scripts().isEmpty()
				? JsUnitVerifier.toSolutionRelativePath(current.scripts().get(0))
				: null;
		java.io.File solutionSourceFolder = com.servoy.eclipse.opencode.skilltest.headless.SolutionSetup
				.resolveSolutionSourceFolder(entry.baseline().source(), entry.baseline().solution());
		JsUnitVerifyEditorDialog dialog = new JsUnitVerifyEditorDialog(getSite().getShell(),
				entry.baseline().id(), current, currentScript, currentScriptName, solutionSourceFolder);
		if (dialog.open() != Window.OK) {
			return;
		}
		try {
			BaselineLoader.writeJsUnitVerify(entry.folder(), dialog.getVerify(), dialog.getScriptName(),
					dialog.getScriptSource());
			log((dialog.isEnabled() ? "Updated" : "Removed") + " JSUnit verification for baseline '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ entry.baseline().id() + "'"); //$NON-NLS-1$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Edit failed", e.getMessage()); //$NON-NLS-1$
			log("Edit JSUnit verification failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Flips the selected baseline's {@code active} flag in its {@code baseline.json}
	 * and refreshes the table. A deactivated baseline is skipped by "Run all active"
	 * but can still be run via "Run selected".
	 */
	private void toggleActive() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Activate / deactivate", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		boolean newActive = !entry.baseline().active();
		try {
			BaselineLoader.setActive(entry.folder(), newActive);
			log((newActive ? "Activated" : "Deactivated") + " baseline '" + entry.baseline().id() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Toggle failed", e.getMessage()); //$NON-NLS-1$
			log("Toggle active failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Prompts for the selected baseline's max replay attempts and persists it to
	 * the sidecar ({@code maxAttempts}). The runner replays up to this many times,
	 * passing as soon as one attempt matches.
	 */
	private void editMaxAttempts() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Set max attempts", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		int current = entry.baseline().maxAttempts();
		org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
				getSite().getShell(), "Set max attempts", //$NON-NLS-1$
				"Number of times to replay '" + entry.baseline().id() + "' before failing\n" //$NON-NLS-1$ //$NON-NLS-2$
						+ "(it passes as soon as one attempt matches):", //$NON-NLS-1$
				Integer.toString(current), input -> {
					try {
						int v = Integer.parseInt(input.trim());
						return v >= 1 ? null : "Must be at least 1."; //$NON-NLS-1$
					} catch (NumberFormatException nfe) {
						return "Enter a whole number."; //$NON-NLS-1$
					}
				});
		if (dialog.open() != Window.OK) {
			return;
		}
		int newValue = Integer.parseInt(dialog.getValue().trim());
		try {
			BaselineLoader.setMaxAttempts(entry.folder(), newValue);
			log("Set max attempts for baseline '" + entry.baseline().id() + "' to " + newValue); //$NON-NLS-1$ //$NON-NLS-2$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Set max attempts failed", e.getMessage()); //$NON-NLS-1$
			log("Set max attempts failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Opens the precondition editor for the selected baseline (solution name +
	 * source: empty/folder/git) and persists it to the sidecar on OK.
	 */
	private void editPrecondition() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Edit test solution source", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		PreconditionEditorDialog dialog = new PreconditionEditorDialog(getSite().getShell(),
				entry.baseline().id(), entry.baseline().solution(), entry.baseline().source());
		if (dialog.open() != Window.OK) {
			return;
		}
		try {
			BaselineLoader.writePrecondition(entry.folder(), dialog.getSolution(), dialog.getSource());
			log("Updated test solution source for baseline '" + entry.baseline().id() + "' to " //$NON-NLS-1$ //$NON-NLS-2$
					+ dialog.getSource().type().name().toLowerCase()); //$NON-NLS-1$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Edit failed", e.getMessage()); //$NON-NLS-1$
			log("Edit test solution source failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	private void deleteBaseline() {
		BaselineLoader.Entry entry = selectedEntry();
		if (entry == null) {
			MessageDialog.openInformation(getSite().getShell(), "Delete baseline", //$NON-NLS-1$
					"Select a baseline in the table first."); //$NON-NLS-1$
			return;
		}
		String id = entry.baseline().id();
		File folder = entry.folder();
		boolean confirmed = MessageDialog.openConfirm(getSite().getShell(), "Delete baseline", //$NON-NLS-1$
				"Delete baseline '" + id + "'?\n\nThis permanently removes:\n" + folder //$NON-NLS-1$ //$NON-NLS-2$
						+ "\n\n(the baseline.json + export.json). This cannot be undone."); //$NON-NLS-1$
		if (!confirmed) {
			return;
		}
		try {
			BaselineLoader.deleteBaseline(folder);
			lastResults.remove(id);
			log("Deleted baseline '" + id + "' (" + folder + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			refresh();
		} catch (IOException e) {
			MessageDialog.openError(getSite().getShell(), "Delete failed", e.getMessage()); //$NON-NLS-1$
			log("Delete baseline failed: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	private void asyncRefresh() {
		Display display = Display.getDefault();
		if (display != null && !display.isDisposed()) {
			display.asyncExec(() -> {
				if (!viewer.getControl().isDisposed()) {
					refresh();
				}
			});
		}
	}

	private void log(String message) {
		Activator activator = Activator.getInstance();
		if (activator != null) {
			activator.logToConsole(message);
		}
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s; //$NON-NLS-1$
	}

	/**
	 * Resolves the baselines root under the workspace
	 * ({@code <workspace>/servoy_ai_skilltests/baselines}).
	 *
	 * @return the baselines root directory
	 */
	private static File resolveBaselinesRoot() {
		return SkillTestPaths.baselinesRoot();
	}

	@Override
	public void setFocus() {
		viewer.getControl().setFocus();
	}
}
