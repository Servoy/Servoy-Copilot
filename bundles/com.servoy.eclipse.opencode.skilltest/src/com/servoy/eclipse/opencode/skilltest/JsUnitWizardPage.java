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

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.servoy.eclipse.opencode.skilltest.outcome.VerificationPlan;

/**
 * Editor page of {@link ImportBaselineWizard} for the proposed JSUnit check
 * (SVY-21366), the wizard equivalent of {@code JsUnitScriptEditorDialog}. Shown
 * only when the analysis proposed a script. The human edits the generated
 * {@code test_} script source, its scope, an optional single method, and
 * {@code required}, or excludes the check with the include toggle; the result is
 * stored on the wizard as a {@link VerificationPlan.JsUnitPlan} (or {@code null}
 * when excluded). The script must be name-tolerant (query the model).
 */
public class JsUnitWizardPage extends WizardPage {

	private final ImportBaselineWizard wizard;

	private Label rationaleLabel;
	private Button includeCheck;
	private Text scopeText;
	private Text methodText;
	private Text scriptText;
	private Button requiredCheck;
	private Font monoFont;

	private VerificationPlan.JsUnitPlan pending;

	protected JsUnitWizardPage(ImportBaselineWizard wizard) {
		super("jsunit"); //$NON-NLS-1$
		this.wizard = wizard;
		setTitle("JSUnit verification"); //$NON-NLS-1$
		setMessage("A name-tolerant test_ script injected into the active solution and run after the build. " //$NON-NLS-1$
				+ "It MUST query solutionModel/runtime, never hard-code the builder's element names."); //$NON-NLS-1$
	}

	/** Seeds the editor from the agent's proposal; called when analysis finishes. */
	void setProposal(VerificationPlan plan) {
		VerificationPlan.JsUnitPlan js = plan != null ? plan.jsunit() : null;
		if (scriptText == null || scriptText.isDisposed()) {
			this.pending = js;
			return;
		}
		apply(js);
	}

	private void apply(VerificationPlan.JsUnitPlan js) {
		scopeText.setText(js != null && js.scope() != null ? js.scope() : "skilltest_verify"); //$NON-NLS-1$
		methodText.setText(js != null && js.method() != null ? js.method() : ""); //$NON-NLS-1$
		scriptText.setText(js != null && js.scriptSource() != null ? js.scriptSource() : ""); //$NON-NLS-1$
		requiredCheck.setSelection(js == null || js.required());
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
		includeCheck.setText("Include this JSUnit check in the baseline"); //$NON-NLS-1$
		includeCheck.setSelection(true);
		includeCheck.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		includeCheck.addListener(SWT.Selection, e -> syncEnabled());

		new Label(body, SWT.NONE).setText("Scope / script name:"); //$NON-NLS-1$
		scopeText = new Text(body, SWT.BORDER);
		scopeText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		scopeText.setText("skilltest_verify"); //$NON-NLS-1$
		scopeText.addListener(SWT.Modify, e -> validate());

		new Label(body, SWT.NONE).setText("Single method (optional):"); //$NON-NLS-1$
		methodText = new Text(body, SWT.BORDER);
		methodText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		methodText.setToolTipText("A single test_ method to run, or blank to run every test_ in the scope."); //$NON-NLS-1$

		Label scriptLabel = new Label(body, SWT.NONE);
		scriptLabel.setText("Script source (test_ functions):"); //$NON-NLS-1$
		scriptLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

		scriptText = new Text(body, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData stData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
		stData.widthHint = 640;
		stData.heightHint = 300;
		scriptText.setLayoutData(stData);
		monoFont = monospaceFont(scriptText);
		if (monoFont != null) {
			scriptText.setFont(monoFont);
		}
		scriptText.addListener(SWT.Modify, e -> validate());

		requiredCheck = new Button(body, SWT.CHECK);
		requiredCheck.setText("Required (a JSUnit FAIL fails the baseline; unchecked = advisory)"); //$NON-NLS-1$
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

	private static Font monospaceFont(Text owner) {
		try {
			FontData base = owner.getFont().getFontData()[0];
			return new Font(owner.getDisplay(), new FontData("Consolas", base.getHeight(), SWT.NORMAL)); //$NON-NLS-1$
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private void syncEnabled() {
		boolean on = includeCheck.getSelection();
		scopeText.setEnabled(on);
		methodText.setEnabled(on);
		scriptText.setEnabled(on);
		requiredCheck.setEnabled(on);
		validate();
	}

	private void validate() {
		String error = null;
		if (includeCheck.getSelection()) {
			if (scriptText.getText().isBlank()) {
				error = "Provide the script source, or uncheck 'Include this check'."; //$NON-NLS-1$
			} else if (scopeText.getText().isBlank()) {
				error = "Provide a scope / script name."; //$NON-NLS-1$
			}
		}
		setErrorMessage(error);
		setPageComplete(error == null);
		if (error == null) {
			store();
		}
	}

	private void store() {
		if (!includeCheck.getSelection()) {
			wizard.setConfirmedJsUnit(null);
			return;
		}
		String scope = scopeText.getText().trim();
		String method = methodText.getText().trim();
		wizard.setConfirmedJsUnit(new VerificationPlan.JsUnitPlan(scope, scope, scriptText.getText(),
				method.isEmpty() ? null : method, requiredCheck.getSelection()));
	}

	@Override
	public void dispose() {
		if (monoFont != null && !monoFont.isDisposed()) {
			monoFont.dispose();
		}
		super.dispose();
	}
}
