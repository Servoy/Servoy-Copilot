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

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.Wizard;

import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;
import com.servoy.eclipse.opencode.skilltest.outcome.VerificationPlan;

/**
 * Wizard for importing an opencode session export as a new skill-test baseline
 * (SVY-21366), replacing the blocking one-shot dialog. Its pages give the user
 * progress and navigation across the whole import, instead of the dialog going
 * silent while the AI baseline-authoring agent runs (which can take minutes):
 * <ol>
 * <li>{@link ImportDetailsWizardPage} - pick the export + mockup, set id/title,
 * and see the export preview and what the later steps will offer;</li>
 * <li>{@link AnalyzeWizardPage} - runs the authoring agent with a live progress
 * bar (the UI stays responsive and Cancel works), then shows the agent's
 * rationale and <b>which checks it proposed</b>;</li>
 * <li>{@link PersistsWizardPage} / {@link AcceptanceCriteriaWizardPage} /
 * {@link JsUnitWizardPage} - one editor page per <b>proposed</b> check; a check
 * the agent did not propose is skipped (dynamic {@link #getNextPage}).</li>
 * </ol>
 * The wizard holds the shared state the pages read and write: the import inputs,
 * the {@link VerificationPlan proposed plan}, and the human's confirmed checks.
 * {@link #performFinish()} writes the baseline through
 * {@link BaselineLoader#importExport(File, File, String, String, VerificationPlan)}.
 */
public class ImportBaselineWizard extends Wizard {

	private final File baselinesRoot;

	// Page 1 inputs.
	private File exportFile;
	private String baselineId = ""; //$NON-NLS-1$
	private String baselineTitle = ""; //$NON-NLS-1$
	private File mockupFile;

	// Page 1 run configuration (starting-solution source + runs): written into the
	// sidecar's precondition + maxAttempts.
	private Baseline.Source.Type sourceType = Baseline.Source.Type.EMPTY;
	private String solution = "aiTestSolution"; //$NON-NLS-1$
	private String location = ""; //$NON-NLS-1$
	private String ref = ""; //$NON-NLS-1$
	private int maxAttempts = 3;

	// Set true by the details page when an input that affects analysis changed
	// (the export file). The analyze page re-runs only when this is set or the
	// analysis has not run yet, so navigating Back to page 1 and forward again
	// without changing the export does NOT re-run the agent.
	private boolean inputsChanged = true;
	private boolean analysisDone;

	// The agent's proposal (page 2) and the human's confirmed checks (pages 3-5).
	private VerificationPlan proposedPlan;
	private List<OutcomeAssertion> confirmedPersists = List.of();
	private Baseline.AcceptanceVerify confirmedAcceptance;
	private VerificationPlan.JsUnitPlan confirmedJsUnit;

	// Which checks the user wants to configure: seeded from the agent's proposal
	// on the analyze page, then driven by the checkboxes so the user can override
	// it - enable a check the agent did not propose, or drop one it did.
	private boolean enablePersists;
	private boolean enableAcceptance;
	private boolean enableJsUnit;

	private final ImportDetailsWizardPage detailsPage;
	private final AnalyzeWizardPage analyzePage;
	private final PersistsWizardPage persistsPage;
	private final AcceptanceCriteriaWizardPage acceptancePage;
	private final JsUnitWizardPage jsUnitPage;

	private File createdFolder;

	// Set when the user chooses "Run in background" on the analyze page: the dialog
	// closes and the view re-runs the analysis off-screen, reopening a primed
	// wizard when it finishes.
	private boolean backgrounded;

	// Set by prime(): a reopened background import that already has its plan, so the
	// dialog opens straight on the analyze/review page instead of page 1.
	private boolean primed;

	public ImportBaselineWizard(File baselinesRoot) {
		this.baselinesRoot = baselinesRoot;
		setWindowTitle("Import skill-test baseline"); //$NON-NLS-1$
		setNeedsProgressMonitor(true);
		detailsPage = new ImportDetailsWizardPage(this);
		analyzePage = new AnalyzeWizardPage(this);
		persistsPage = new PersistsWizardPage(this);
		acceptancePage = new AcceptanceCriteriaWizardPage(this);
		jsUnitPage = new JsUnitWizardPage(this);
	}

	@Override
	public void addPages() {
		addPage(detailsPage);
		addPage(analyzePage);
		addPage(persistsPage);
		addPage(acceptancePage);
		addPage(jsUnitPage);
	}

	/**
	 * Dynamic page order: after the analyze page, visit only the editor pages for
	 * the checks the agent actually proposed (per the "do not always show" rule),
	 * in the fixed order persists &rarr; acceptance &rarr; jsunit.
	 */
	@Override
	public IWizardPage getNextPage(IWizardPage page) {
		if (page == detailsPage) {
			return analyzePage;
		}
		if (page == analyzePage) {
			return firstProposedFrom(0);
		}
		if (page == persistsPage) {
			return firstProposedFrom(1);
		}
		if (page == acceptancePage) {
			return firstProposedFrom(2);
		}
		return null; // jsUnitPage or unknown => last
	}

	@Override
	public IWizardPage getPreviousPage(IWizardPage page) {
		if (page == analyzePage) {
			return detailsPage;
		}
		if (page == persistsPage || page == acceptancePage || page == jsUnitPage) {
			// Walk back to the previous proposed editor page, else the analyze page.
			int here = page == persistsPage ? 0 : page == acceptancePage ? 1 : 2;
			for (int i = here - 1; i >= 0; i--) {
				IWizardPage p = editorPageIfProposed(i);
				if (p != null) {
					return p;
				}
			}
			return analyzePage;
		}
		return null;
	}

	/** The first proposed editor page at index >= from (0=persists,1=acceptance,2=jsunit), or null. */
	private IWizardPage firstProposedFrom(int from) {
		for (int i = from; i <= 2; i++) {
			IWizardPage p = editorPageIfProposed(i);
			if (p != null) {
				return p;
			}
		}
		return null;
	}

	private IWizardPage editorPageIfProposed(int index) {
		switch (index) {
			case 0:
				return enablePersists ? persistsPage : null;
			case 1:
				return enableAcceptance ? acceptancePage : null;
			case 2:
				return enableJsUnit ? jsUnitPage : null;
			default:
				return null;
		}
	}

	@Override
	public boolean canFinish() {
		// Finishable once analysis is done. We validate the confirmed STATE the pages
		// wrote (seeded from the proposal), not each page's isPageComplete() flag:
		// a shown-but-not-yet-visited editor page has no control and reports
		// incomplete, which would wrongly disable Finish for a primed/background
		// reopen or when the user skips straight to Finish (SVY-21366).
		if (!analysisDone) {
			return false;
		}
		if (enableAcceptance && (confirmedAcceptance == null || !confirmedAcceptance.hasCriteria())) {
			return false;
		}
		if (enableJsUnit && (confirmedJsUnit == null || !confirmedJsUnit.hasScript())) {
			return false;
		}
		// expect.persists may legitimately be empty (judge-authoritative), so it does
		// not gate Finish.
		return true;
	}

	private boolean isPageShown(IWizardPage page) {
		// Driven by the user's check toggles on the analyze page (which start from
		// the agent's proposal but can be overridden), not by the raw proposal.
		if (page == persistsPage) {
			return enablePersists;
		}
		if (page == acceptancePage) {
			return enableAcceptance;
		}
		if (page == jsUnitPage) {
			return enableJsUnit;
		}
		return true;
	}

	@Override
	public boolean performFinish() {
		List<OutcomeAssertion> persists = isPageShown(persistsPage) ? confirmedPersists : List.of();
		Baseline.AcceptanceVerify acceptance = isPageShown(acceptancePage) ? confirmedAcceptance : null;
		VerificationPlan.JsUnitPlan jsunit = isPageShown(jsUnitPage) ? confirmedJsUnit : null;
		String rationale = proposedPlan != null ? proposedPlan.rationale() : null;
		VerificationPlan finalPlan = new VerificationPlan(persists, acceptance, jsunit, rationale);
		BaselineLoader.ImportConfig config = new BaselineLoader.ImportConfig(sourceType, solution, location, ref,
				maxAttempts);
		try {
			createdFolder = BaselineLoader.importExport(exportFile, baselinesRoot, baselineId, baselineTitle,
					finalPlan, config);
			return true;
		} catch (IOException | RuntimeException e) {
			// Surface the failure LOUDLY: returning false keeps the wizard open, and a
			// banner on the (possibly not-current) details page is invisible on a
			// primed reopen - which looks like "Finish does nothing" (SVY-21366). Show
			// a modal error so the reason is always visible.
			String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			if (detailsPage != null) {
				detailsPage.setErrorMessage("Import failed: " + message); //$NON-NLS-1$
			}
			org.eclipse.swt.widgets.Shell shell = getShell();
			if (shell != null && !shell.isDisposed()) {
				org.eclipse.jface.dialogs.MessageDialog.openError(shell, "Import failed", //$NON-NLS-1$
						"Could not write the baseline:\n\n" + message); //$NON-NLS-1$
			}
			return false;
		}
	}

	// --- shared state accessors used by the pages ---

	File getBaselinesRoot() {
		return baselinesRoot;
	}

	File getExportFile() {
		return exportFile;
	}

	void setExportFile(File file) {
		if (file != this.exportFile && (file == null || !file.equals(this.exportFile))) {
			this.inputsChanged = true;
		}
		this.exportFile = file;
	}

	String getBaselineId() {
		return baselineId;
	}

	void setBaselineId(String id) {
		this.baselineId = id;
	}

	String getBaselineTitle() {
		return baselineTitle;
	}

	void setBaselineTitle(String title) {
		this.baselineTitle = title;
	}

	File getMockupFile() {
		return mockupFile;
	}

	void setMockupFile(File file) {
		this.mockupFile = file;
	}

	// --- run configuration (page 1) ---

	Baseline.Source.Type getSourceType() {
		return sourceType;
	}

	void setSourceType(Baseline.Source.Type type) {
		this.sourceType = type != null ? type : Baseline.Source.Type.EMPTY;
	}

	String getSolution() {
		return solution;
	}

	void setSolution(String name) {
		this.solution = name;
	}

	String getLocation() {
		return location;
	}

	void setLocation(String value) {
		this.location = value;
	}

	String getRef() {
		return ref;
	}

	void setRef(String value) {
		this.ref = value;
	}

	int getMaxAttempts() {
		return maxAttempts;
	}

	void setMaxAttempts(int value) {
		this.maxAttempts = value;
	}

	boolean isInputsChanged() {
		return inputsChanged;
	}

	void clearInputsChanged() {
		this.inputsChanged = false;
	}

	boolean isAnalysisDone() {
		return analysisDone;
	}

	void setAnalysisDone(boolean done) {
		this.analysisDone = done;
	}

	VerificationPlan getProposedPlan() {
		return proposedPlan;
	}

	void setProposedPlan(VerificationPlan plan) {
		this.proposedPlan = plan;
	}

	/** Seeds the enabled-checks from a fresh proposal (called when analysis finishes). */
	void seedEnabledChecksFrom(VerificationPlan plan) {
		this.enablePersists = plan != null && plan.hasPersists();
		this.enableAcceptance = plan != null && plan.hasAcceptance();
		this.enableJsUnit = plan != null && plan.hasJsUnit();
	}

	/**
	 * Seeds the <b>confirmed</b> checks from the proposal so they are valid before
	 * their editor pages are ever opened. Without this, Finish stays disabled for a
	 * proposed-but-unvisited check (e.g. a primed background reopen the user
	 * finishes straight away). The editor pages overwrite these when visited/edited.
	 */
	void seedConfirmedFrom(VerificationPlan plan) {
		this.confirmedPersists = plan != null ? plan.persists() : List.of();
		this.confirmedAcceptance = plan != null ? plan.acceptance() : null;
		this.confirmedJsUnit = plan != null ? plan.jsunit() : null;
	}

	boolean isPersistsEnabled() {
		return enablePersists;
	}

	void setPersistsEnabled(boolean on) {
		this.enablePersists = on;
	}

	boolean isAcceptanceEnabled() {
		return enableAcceptance;
	}

	void setAcceptanceEnabled(boolean on) {
		this.enableAcceptance = on;
	}

	boolean isJsUnitEnabled() {
		return enableJsUnit;
	}

	void setJsUnitEnabled(boolean on) {
		this.enableJsUnit = on;
	}

	void setConfirmedPersists(List<OutcomeAssertion> persists) {
		this.confirmedPersists = persists != null ? persists : List.of();
	}

	void setConfirmedAcceptance(Baseline.AcceptanceVerify acceptance) {
		this.confirmedAcceptance = acceptance;
	}

	void setConfirmedJsUnit(VerificationPlan.JsUnitPlan jsunit) {
		this.confirmedJsUnit = jsunit;
	}

	AnalyzeWizardPage getAnalyzePage() {
		return analyzePage;
	}

	PersistsWizardPage getPersistsPage() {
		return persistsPage;
	}

	AcceptanceCriteriaWizardPage getAcceptancePage() {
		return acceptancePage;
	}

	JsUnitWizardPage getJsUnitPage() {
		return jsUnitPage;
	}

	/** @return the created baseline folder after a successful finish, else {@code null}. */
	public File getCreatedFolder() {
		return createdFolder;
	}

	// --- run-in-background handoff ---

	/** @return {@code true} when the user chose "Run in background" (dialog closed to hand off). */
	public boolean isBackgrounded() {
		return backgrounded;
	}

	/**
	 * Marks the import as backgrounded: the analyze page calls this and closes the
	 * dialog, after which the view re-runs the analysis off-screen and reopens a
	 * {@link #prime primed} wizard when it finishes.
	 */
	void requestBackground() {
		this.backgrounded = true;
	}

	/**
	 * Pre-seeds a reopened wizard with the inputs the user already set and the plan
	 * the background analysis produced, so the reopened dialog shows the proposal
	 * immediately without re-running the agent.
	 */
	public void prime(File exportFile, String id, String title, File mockupFile, Baseline.Source.Type sourceType,
			String solution, String location, String ref, int maxAttempts, VerificationPlan plan) {
		this.exportFile = exportFile;
		this.baselineId = id != null ? id : ""; //$NON-NLS-1$
		this.baselineTitle = title != null ? title : ""; //$NON-NLS-1$
		this.mockupFile = mockupFile;
		setSourceType(sourceType);
		this.solution = solution;
		this.location = location != null ? location : ""; //$NON-NLS-1$
		this.ref = ref != null ? ref : ""; //$NON-NLS-1$
		this.maxAttempts = maxAttempts;
		this.proposedPlan = plan;
		seedEnabledChecksFrom(plan);
		seedConfirmedFrom(plan);
		this.analysisDone = true;
		this.inputsChanged = false;
		this.primed = true;
		// Seed the editor pages (they buffer until their controls exist).
		persistsPage.setProposal(plan);
		acceptancePage.setProposal(plan);
		jsUnitPage.setProposal(plan);
	}

	/** @return {@code true} when the wizard was {@link #prime primed} for a background reopen. */
	public boolean isPrimed() {
		return primed;
	}
}
