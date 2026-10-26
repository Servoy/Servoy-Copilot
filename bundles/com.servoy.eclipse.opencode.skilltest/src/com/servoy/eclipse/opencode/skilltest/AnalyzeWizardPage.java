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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;

import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;
import com.servoy.eclipse.opencode.skilltest.outcome.VerificationPlan;
import com.servoy.eclipse.opencode.skilltest.outcome.VerificationPlanAgent;

/**
 * Page 2 of {@link ImportBaselineWizard}: runs the AI baseline-authoring agent
 * (SVY-21366) in a <b>background {@link Job}</b> so the wizard stays fully
 * responsive (no frozen UI), with a live progress bar and status line. When it
 * finishes it shows the agent's rationale as styled, readable text and offers
 * the proposed checks as <b>checkboxes</b> the user can override - enabling a
 * check the agent did not propose, or dropping one it did - which drives which
 * editor pages the wizard then visits.
 * <p>
 * The analysis runs automatically when the page is entered from page 1, but only
 * when the inputs changed or it has not run yet, so navigating Back to page 1 and
 * forward again without changing the export does not re-run the agent.
 * </p>
 */
public class AnalyzeWizardPage extends WizardPage {

	private final ImportBaselineWizard wizard;

	private Label statusLabel;
	private ProgressBar progressBar;
	private Button backgroundButton;
	private Composite resultArea;

	private Button persistsCheck;
	private Button acceptanceCheck;
	private Button jsUnitCheck;
	private StyledText rationaleText;

	private Font boldFont;
	private Color headerColor;
	private Job analysisJob;

	protected AnalyzeWizardPage(ImportBaselineWizard wizard) {
		super("analyze"); //$NON-NLS-1$
		this.wizard = wizard;
		setTitle("Analyze the session"); //$NON-NLS-1$
		setMessage("An AI agent reads the export and the artifacts the run built and proposes which " //$NON-NLS-1$
				+ "verification checks fit. You can adjust the proposal before configuring each check."); //$NON-NLS-1$
	}

	@Override
	public void createControl(Composite parent) {
		Composite body = new Composite(parent, SWT.NONE);
		body.setLayout(new GridLayout(1, false));

		// --- progress row (visible while analyzing) ---
		statusLabel = new Label(body, SWT.WRAP);
		statusLabel.setLayoutData(wide());
		statusLabel.setText("Waiting to analyze\u2026"); //$NON-NLS-1$

		progressBar = new ProgressBar(body, SWT.HORIZONTAL | SWT.INDETERMINATE);
		progressBar.setLayoutData(wide());
		progressBar.setVisible(false);

		// "Run in background": close the wizard and let the analysis finish off-screen;
		// the view reopens the wizard, primed with the result, when it is done. Shown
		// only while analysis is running.
		backgroundButton = new Button(body, SWT.PUSH);
		backgroundButton.setText("Run in background\u2026"); //$NON-NLS-1$
		backgroundButton.setToolTipText("Close this dialog and keep analyzing. It reopens here when the " //$NON-NLS-1$
				+ "analysis finishes."); //$NON-NLS-1$
		backgroundButton.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
		backgroundButton.setVisible(false);
		backgroundButton.addListener(SWT.Selection, e -> {
			wizard.requestBackground();
			getShell().close(); // closes the wizard; the view takes over (SVY-21366)
		});

		// --- result area (populated when analysis finishes) ---
		resultArea = new Composite(body, SWT.NONE);
		GridData raData = new GridData(SWT.FILL, SWT.FILL, true, true);
		raData.widthHint = 560;
		resultArea.setLayoutData(raData);
		resultArea.setLayout(new GridLayout(1, false));

		Label checksCaption = header(resultArea, "Verification checks to configure"); //$NON-NLS-1$
		checksCaption.setLayoutData(wide());
		Label checksHint = new Label(resultArea, SWT.WRAP);
		checksHint.setText("The agent pre-selects the checks it judged appropriate. Tick or untick to " //$NON-NLS-1$
				+ "configure your own - each ticked check gets an editor step next."); //$NON-NLS-1$
		checksHint.setLayoutData(wide());

		persistsCheck = check(resultArea, "Expected persists", () -> wizard.setPersistsEnabled( //$NON-NLS-1$
				persistsCheck.getSelection()));
		acceptanceCheck = check(resultArea, "Acceptance criteria (LLM judge)", //$NON-NLS-1$
				() -> wizard.setAcceptanceEnabled(acceptanceCheck.getSelection()));
		jsUnitCheck = check(resultArea, "JSUnit script", () -> wizard.setJsUnitEnabled(jsUnitCheck.getSelection())); //$NON-NLS-1$

		Label whyCaption = header(resultArea, "Why the agent proposed this"); //$NON-NLS-1$
		GridData whyCapData = wide();
		whyCapData.verticalIndent = 8;
		whyCaption.setLayoutData(whyCapData);

		rationaleText = new StyledText(resultArea, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
		GridData rData = new GridData(SWT.FILL, SWT.FILL, true, true);
		rData.widthHint = 560;
		rData.heightHint = 120;
		rationaleText.setLayoutData(rData);
		rationaleText.setMargins(6, 6, 6, 6);
		rationaleText.setCaret(null);
		rationaleText.setBackground(resultArea.getBackground());

		resultArea.setVisible(false);
		setControl(body);
		setPageComplete(false);
	}

	private Label header(Composite parent, String text) {
		Label l = new Label(parent, SWT.NONE);
		l.setText(text);
		l.setFont(boldFont(l));
		return l;
	}

	private Button check(Composite parent, String text, Runnable onToggle) {
		Button b = new Button(parent, SWT.CHECK);
		b.setText(text);
		GridData d = wide();
		d.horizontalIndent = 8;
		b.setLayoutData(d);
		b.addListener(SWT.Selection, e -> {
			onToggle.run();
			updateState();
		});
		return b;
	}

	private static GridData wide() {
		GridData d = new GridData(SWT.FILL, SWT.TOP, true, false);
		d.widthHint = 540;
		return d;
	}

	private Font boldFont(org.eclipse.swt.widgets.Control owner) {
		if (boldFont == null || boldFont.isDisposed()) {
			FontData[] fd = owner.getFont().getFontData();
			for (FontData f : fd) {
				f.setStyle(SWT.BOLD);
			}
			boldFont = new Font(owner.getDisplay(), fd);
		}
		return boldFont;
	}

	@Override
	public void setVisible(boolean visible) {
		super.setVisible(visible);
		if (visible && (!wizard.isAnalysisDone() || wizard.isInputsChanged())) {
			runAnalysis();
		} else if (visible) {
			// Re-entered (Back from an editor page, or a primed reopen after a
			// background run): show the already-computed proposal, no re-run.
			showExistingResult();
		}
	}

	/** Renders the stored proposal and finalizes the page (no re-analysis). */
	private void showExistingResult() {
		progressBar.setVisible(false);
		backgroundButton.setVisible(false);
		statusLabel.setText("Analysis complete. Adjust the checks below if needed, then Next."); //$NON-NLS-1$
		renderProposal(wizard.getProposedPlan());
		resultArea.setVisible(true);
		resultArea.requestLayout();
		setPageComplete(wizard.isAnalysisDone());
		if (getContainer() != null) {
			getContainer().updateButtons();
		}
	}

	/** Runs the analysis on a background Job; the wizard stays responsive. */
	private void runAnalysis() {
		setPageComplete(false);
		resultArea.setVisible(false);
		progressBar.setVisible(true);
		backgroundButton.setVisible(true);
		statusLabel.setText("Analyzing the session to propose verification checks\u2026"); //$NON-NLS-1$
		if (analysisJob != null) {
			analysisJob.cancel();
		}
		final AtomicReference<VerificationPlan> out = new AtomicReference<>();
		analysisJob = new Job("Analyzing the session to propose skill-test checks\u2026") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Analyzing the session", IProgressMonitor.UNKNOWN); //$NON-NLS-1$
				try {
					setStatusAsync("Contacting the embedded opencode server\u2026"); //$NON-NLS-1$
					VerificationPlanAgent agent = VerificationPlanAgent.ready(this::log);
					VerificationPlan plan;
					if (agent != null) {
						setStatusAsync("The authoring agent is reading the export and the built artifacts\u2026"); //$NON-NLS-1$
						plan = agent.analyze(wizard.getExportFile(),
								wizard.getMockupFile() != null ? wizard.getMockupFile().getName() : null,
								monitor::isCanceled);
						if (plan.hasPersists() && !monitor.isCanceled()) {
							setStatusAsync("Loosening the proposed expected persists\u2026"); //$NON-NLS-1$
							com.servoy.eclipse.opencode.skilltest.outcome.ExpectationLlmGenerator loosener = com.servoy.eclipse.opencode.skilltest.outcome.ExpectationLlmGenerator
									.ready(this::log);
							if (loosener != null) {
								java.util.List<OutcomeAssertion> loosened = loosener.generate(plan.persists(),
										readMockupHtml(), monitor::isCanceled);
								plan = new VerificationPlan(loosened, plan.acceptance(), plan.jsunit(),
										plan.rationale());
							}
						}
					} else {
						setStatusAsync("opencode server not available - proposing an offline draft\u2026"); //$NON-NLS-1$
						plan = VerificationPlanAgent.fallback(wizard.getExportFile());
					}
					out.set(plan);
					return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
				} finally {
					monitor.done();
				}
			}

			private void log(String message) {
				com.servoy.eclipse.opencode.Activator a = com.servoy.eclipse.opencode.Activator.getInstance();
				if (a != null) {
					a.logToConsole(message);
				}
				setStatusAsync(message);
			}
		};
		analysisJob.addJobChangeListener(new org.eclipse.core.runtime.jobs.JobChangeAdapter() {
			@Override
			public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event) {
				VerificationPlan plan = out.get() != null ? out.get()
						: VerificationPlanAgent.fallback(wizard.getExportFile());
				finishAnalysisAsync(plan, event.getResult() != null && event.getResult().getSeverity() == IStatus.CANCEL);
			}
		});
		analysisJob.setUser(false);
		analysisJob.schedule();
	}

	private void finishAnalysisAsync(VerificationPlan plan, boolean cancelled) {
		Display display = statusLabel != null && !statusLabel.isDisposed() ? statusLabel.getDisplay()
				: Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (statusLabel == null || statusLabel.isDisposed()) {
				return;
			}
			wizard.setProposedPlan(plan);
			wizard.seedEnabledChecksFrom(plan);
			wizard.seedConfirmedFrom(plan);
			wizard.setAnalysisDone(true);
			wizard.clearInputsChanged();
			wizard.getPersistsPage().setProposal(plan);
			wizard.getAcceptancePage().setProposal(plan);
			wizard.getJsUnitPage().setProposal(plan);
			progressBar.setVisible(false);
			backgroundButton.setVisible(false);
			statusLabel.setText(cancelled ? "Analysis cancelled; showing an offline draft you can adjust." //$NON-NLS-1$
					: "Analysis complete. Adjust the checks below if needed, then Next."); //$NON-NLS-1$
			renderProposal(plan);
			resultArea.setVisible(true);
			resultArea.requestLayout();
			updateState();
		});
	}

	private void renderProposal(VerificationPlan plan) {
		if (persistsCheck == null || persistsCheck.isDisposed()) {
			return;
		}
		persistsCheck.setSelection(wizard.isPersistsEnabled());
		persistsCheck.setText(plan != null && plan.hasPersists()
				? "Expected persists (" + plan.persists().size() + " proposed)" //$NON-NLS-1$ //$NON-NLS-2$
				: "Expected persists"); //$NON-NLS-1$
		acceptanceCheck.setSelection(wizard.isAcceptanceEnabled());
		acceptanceCheck.setText(plan != null && plan.hasAcceptance()
				? "Acceptance criteria (" + plan.acceptance().criteria().size() + " proposed)" //$NON-NLS-1$ //$NON-NLS-2$
				: "Acceptance criteria (LLM judge)"); //$NON-NLS-1$
		jsUnitCheck.setSelection(wizard.isJsUnitEnabled());
		jsUnitCheck.setText("JSUnit script"); //$NON-NLS-1$
		setRationale(plan != null && plan.rationale() != null ? plan.rationale().strip()
				: "(the agent gave no rationale)"); //$NON-NLS-1$
		resultArea.requestLayout();
	}

	/**
	 * Renders the rationale as a readable bulleted list: the prose is split into
	 * sentences, each shown as its own "\u2022 " bullet line, so a long paragraph
	 * is scannable instead of a wall of text.
	 */
	private void setRationale(String text) {
		java.util.List<String> points = splitSentences(text);
		StringBuilder sb = new StringBuilder();
		for (String p : points) {
			sb.append("\u2022  ").append(p).append('\n'); //$NON-NLS-1$
		}
		String body = sb.length() > 0 ? sb.substring(0, sb.length() - 1) : text;
		rationaleText.setText(body);
		// Tint each bullet glyph so the list structure reads at a glance.
		java.util.List<StyleRange> ranges = new java.util.ArrayList<>();
		int idx = 0;
		while ((idx = body.indexOf('\u2022', idx)) >= 0) {
			StyleRange bullet = new StyleRange();
			bullet.start = idx;
			bullet.length = 1;
			bullet.fontStyle = SWT.BOLD;
			bullet.foreground = headerColor(rationaleText);
			ranges.add(bullet);
			idx++;
		}
		rationaleText.setStyleRanges(ranges.toArray(new StyleRange[0]));
	}

	/**
	 * Splits a rationale paragraph into sentence-sized bullet points. Breaks on
	 * {@code . }/{@code ; } boundaries that are not inside parentheses or a
	 * decimal/abbreviation, trimming each piece; a text with no clear breaks
	 * comes back as a single point.
	 */
	private static java.util.List<String> splitSentences(String text) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (text == null || text.isBlank()) {
			return out;
		}
		String flat = text.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
		StringBuilder cur = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < flat.length(); i++) {
			char c = flat.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')' && depth > 0) {
				depth--;
			}
			cur.append(c);
			boolean boundary = (c == '.' || c == ';') && depth == 0;
			boolean nextIsSpace = i + 1 < flat.length() && flat.charAt(i + 1) == ' ';
			boolean prevIsDigit = i > 0 && Character.isDigit(flat.charAt(i - 1));
			boolean nextIsDigit = i + 2 < flat.length() && Character.isDigit(flat.charAt(i + 2));
			if (boundary && nextIsSpace && !(prevIsDigit && nextIsDigit)) {
				String piece = cur.toString().trim();
				if (!piece.isEmpty()) {
					out.add(piece);
				}
				cur.setLength(0);
				i++; // skip the separating space
			}
		}
		String tail = cur.toString().trim();
		if (!tail.isEmpty()) {
			out.add(tail);
		}
		if (out.isEmpty()) {
			out.add(flat);
		}
		return out;
	}

	private Color headerColor(org.eclipse.swt.widgets.Control owner) {
		if (headerColor == null || headerColor.isDisposed()) {
			headerColor = owner.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW);
		}
		return headerColor;
	}

	private void updateState() {
		setPageComplete(wizard.isAnalysisDone());
		if (getContainer() != null) {
			getContainer().updateButtons();
		}
	}

	private String readMockupHtml() {
		java.io.File f = wizard.getMockupFile();
		if (f == null || !f.isFile()) {
			return null;
		}
		try {
			return java.nio.file.Files.readString(f.toPath());
		} catch (Exception e) {
			return null;
		}
	}

	private void setStatusAsync(String message) {
		Display display = statusLabel != null && !statusLabel.isDisposed() ? statusLabel.getDisplay()
				: Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (statusLabel != null && !statusLabel.isDisposed()) {
				statusLabel.setText(oneLine(message));
				statusLabel.requestLayout();
			}
		});
	}

	private static String oneLine(String s) {
		String flat = s == null ? "" : s.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return flat.length() > 160 ? flat.substring(0, 160) + "\u2026" : flat; //$NON-NLS-1$
	}

	@Override
	public void dispose() {
		if (analysisJob != null) {
			analysisJob.cancel();
		}
		if (boldFont != null && !boldFont.isDisposed()) {
			boldFont.dispose();
		}
		super.dispose();
	}
}
