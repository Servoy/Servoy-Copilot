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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.servoy.eclipse.opencode.skilltest.outcome.VerificationPlan;

/**
 * Editor page of {@link ImportBaselineWizard} for the proposed acceptance-criteria
 * check (SVY-21366), the wizard equivalent of {@code AcceptanceCriteriaEditorDialog}.
 * Shown only when the analysis proposed criteria. The human edits the criteria
 * (one per line), the judge model and {@code required}, or excludes the check with
 * the include toggle; the result is stored on the wizard as a
 * {@link Baseline.AcceptanceVerify} (or {@code null} when excluded).
 */
public class AcceptanceCriteriaWizardPage extends WizardPage {

	private final ImportBaselineWizard wizard;

	private Label rationaleLabel;
	private Button includeCheck;
	private Text criteriaText;
	private Text judgeModelText;
	private Button requiredCheck;

	protected AcceptanceCriteriaWizardPage(ImportBaselineWizard wizard) {
		super("acceptanceCriteria"); //$NON-NLS-1$
		this.wizard = wizard;
		setTitle("Acceptance criteria (LLM judge)"); //$NON-NLS-1$
		setMessage("A separate LLM judge grades each criterion against the built form. Write each as an " //$NON-NLS-1$
				+ "OUTCOME statement, not an implementation prescription. One per line."); //$NON-NLS-1$
	}

	/** Seeds the editor from the agent's proposal; called when analysis finishes. */
	void setProposal(VerificationPlan plan) {
		Baseline.AcceptanceVerify av = plan != null ? plan.acceptance() : null;
		if (criteriaText == null || criteriaText.isDisposed()) {
			this.pending = av;
			return;
		}
		apply(av);
	}

	private Baseline.AcceptanceVerify pending;

	private void apply(Baseline.AcceptanceVerify av) {
		criteriaText.setText(av != null ? String.join("\n", av.criteria()) : ""); //$NON-NLS-1$ //$NON-NLS-2$
		judgeModelText.setText(av != null && av.judgeModel() != null ? av.judgeModel() : ""); //$NON-NLS-1$
		requiredCheck.setSelection(av == null || av.required());
		includeCheck.setSelection(true);
		syncEnabled();
	}

	@Override
	public void createControl(Composite parent) {
		Composite body = new Composite(parent, SWT.NONE);
		body.setLayout(new GridLayout(2, false));

		rationaleLabel = new Label(body, SWT.WRAP);
		rationaleLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		rationaleLabel.setText(""); //$NON-NLS-1$

		includeCheck = new Button(body, SWT.CHECK);
		includeCheck.setText("Include this acceptance-criteria check in the baseline"); //$NON-NLS-1$
		includeCheck.setSelection(true);
		includeCheck.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		includeCheck.addListener(SWT.Selection, e -> syncEnabled());

		Label criteriaLabel = new Label(body, SWT.NONE);
		criteriaLabel.setText("Criteria (one per line):"); //$NON-NLS-1$
		criteriaLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

		criteriaText = new Text(body, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
		GridData ctData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
		ctData.widthHint = 620;
		ctData.heightHint = 220;
		criteriaText.setLayoutData(ctData);
		criteriaText.addListener(SWT.Modify, e -> validate());

		new Label(body, SWT.NONE).setText("Judge model (optional):"); //$NON-NLS-1$
		judgeModelText = new Text(body, SWT.BORDER);
		judgeModelText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		judgeModelText.setToolTipText("Leave blank to let the runner pick a default; always forced to differ " //$NON-NLS-1$
				+ "from the builder model."); //$NON-NLS-1$

		requiredCheck = new Button(body, SWT.CHECK);
		requiredCheck.setText("Required (a judge FAIL fails the baseline; unchecked = advisory)"); //$NON-NLS-1$
		requiredCheck.setSelection(true);
		requiredCheck.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

		setControl(body);
		if (pending != null) {
			apply(pending);
			pending = null;
		}
		syncEnabled();
	}

	@Override
	public void setVisible(boolean visible) {
		super.setVisible(visible);
		if (visible) {
			VerificationPlan plan = wizard.getProposedPlan();
			rationaleLabel.setText(plan != null && plan.rationale() != null ? "Agent rationale: " + plan.rationale() //$NON-NLS-1$
					: ""); //$NON-NLS-1$
			rationaleLabel.requestLayout();
		}
	}

	private void syncEnabled() {
		boolean on = includeCheck.getSelection();
		criteriaText.setEnabled(on);
		judgeModelText.setEnabled(on);
		requiredCheck.setEnabled(on);
		validate();
	}

	private void validate() {
		String error = null;
		if (includeCheck.getSelection() && parseCriteria().isEmpty()) {
			error = "Add at least one criterion, or uncheck 'Include this check'."; //$NON-NLS-1$
		}
		setErrorMessage(error);
		setPageComplete(error == null);
		if (error == null) {
			store();
		}
	}

	private void store() {
		if (!includeCheck.getSelection()) {
			wizard.setConfirmedAcceptance(null);
			return;
		}
		String judge = judgeModelText.getText().trim();
		wizard.setConfirmedAcceptance(new Baseline.AcceptanceVerify(parseCriteria(), judge.isEmpty() ? null : judge,
				requiredCheck.getSelection()));
	}

	private List<String> parseCriteria() {
		List<String> out = new ArrayList<>();
		for (String line : criteriaText.getText().split("\\r?\\n")) { //$NON-NLS-1$
			String t = line.strip();
			if (!t.isEmpty()) {
				out.add(t);
			}
		}
		return out;
	}
}
