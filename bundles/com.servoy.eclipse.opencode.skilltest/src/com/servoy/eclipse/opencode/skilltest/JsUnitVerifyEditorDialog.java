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

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Edits a baseline's JSUnit behavioural verification (SVY-21366 §3.4b): whether
 * it is enabled, the injected test script's file name + source, the scope/method
 * to run, timeout, and whether a JSUnit failure fails the baseline.
 * <p>
 * The tests belong to the baseline, not the solution, so the source is edited
 * here and written into {@code <baseline>/verify/scopes/<scriptName>} by the
 * caller ({@link BaselineLoader#writeJsUnitVerify}); at run time
 * {@link JsUnitVerifier} injects it into the active solution.
 * </p>
 */
public class JsUnitVerifyEditorDialog extends TitleAreaDialog {

	private final String baselineId;

	private Button enabledCheck;
	private Text scriptNameText;
	private Text scopeText;
	private Text methodText;
	private Text timeoutText;
	private Button requiredCheck;
	private Text scriptText;

	// result
	private boolean enabled;
	private String scriptName;
	private String scope;
	private String method;
	private int timeoutSeconds;
	private boolean required;
	private String scriptSource;

	/**
	 * @param parentShell   the parent shell
	 * @param baselineId    the baseline id (for the title)
	 * @param verify        the current JSUnit config, or {@code null} if none
	 * @param currentScript the current injected script source (may be empty)
	 * @param currentScriptName the current script file name (may be {@code null})
	 */
	public JsUnitVerifyEditorDialog(Shell parentShell, String baselineId, Baseline.JsUnitVerify verify,
			String currentScript, String currentScriptName) {
		super(parentShell);
		this.baselineId = baselineId;
		this.enabled = verify != null;
		this.scriptName = currentScriptName != null && !currentScriptName.isBlank() ? currentScriptName
				: "scopes/skilltest_verify.js"; //$NON-NLS-1$
		this.scope = verify != null ? verify.scope() : "skilltest_verify"; //$NON-NLS-1$
		this.method = verify != null ? verify.method() : null;
		this.timeoutSeconds = verify != null ? verify.timeoutSeconds() : 180;
		this.required = verify == null || verify.required();
		this.scriptSource = currentScript != null ? currentScript : DEFAULT_SCRIPT;
		setHelpAvailable(false);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Edit JSUnit verification"); //$NON-NLS-1$
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle("JSUnit verification for '" + baselineId + "'"); //$NON-NLS-1$ //$NON-NLS-2$
		setMessage("Runs after the skill run and the expected-outcome check. The test script is stored " //$NON-NLS-1$
				+ "with the baseline and injected into the active solution before running.", IMessageProvider.INFORMATION);

		Composite area = (Composite) super.createDialogArea(parent);
		Composite form = new Composite(area, SWT.NONE);
		form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		form.setLayout(new GridLayout(2, false));

		enabledCheck = new Button(form, SWT.CHECK);
		enabledCheck.setText("Run a JSUnit verification for this baseline"); //$NON-NLS-1$
		enabledCheck.setSelection(enabled);
		GridData ecData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		ecData.horizontalSpan = 2;
		enabledCheck.setLayoutData(ecData);
		enabledCheck.addListener(SWT.Selection, e -> updateEnablement());

		new Label(form, SWT.NONE).setText("Script file (solution-relative):"); //$NON-NLS-1$
		scriptNameText = new Text(form, SWT.BORDER);
		scriptNameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		scriptNameText.setText(scriptName);

		new Label(form, SWT.NONE).setText("Scope / form to run:"); //$NON-NLS-1$
		scopeText = new Text(form, SWT.BORDER);
		scopeText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		scopeText.setText(scope != null ? scope : ""); //$NON-NLS-1$

		new Label(form, SWT.NONE).setText("Single test method (optional):"); //$NON-NLS-1$
		methodText = new Text(form, SWT.BORDER);
		methodText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		methodText.setText(method != null ? method : ""); //$NON-NLS-1$

		new Label(form, SWT.NONE).setText("Timeout (seconds):"); //$NON-NLS-1$
		timeoutText = new Text(form, SWT.BORDER);
		GridData toData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		toData.widthHint = 80;
		timeoutText.setLayoutData(toData);
		timeoutText.setText(Integer.toString(timeoutSeconds));

		requiredCheck = new Button(form, SWT.CHECK);
		requiredCheck.setText("A JSUnit failure fails the baseline (uncheck to report only)"); //$NON-NLS-1$
		requiredCheck.setSelection(required);
		GridData rcData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		rcData.horizontalSpan = 2;
		requiredCheck.setLayoutData(rcData);

		Label scriptLabel = new Label(form, SWT.NONE);
		scriptLabel.setText("Test script (JSUnit test_ functions):"); //$NON-NLS-1$
		GridData slData = new GridData(SWT.LEFT, SWT.TOP, false, false);
		slData.horizontalSpan = 2;
		scriptLabel.setLayoutData(slData);

		scriptText = new Text(form, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData stData = new GridData(SWT.FILL, SWT.FILL, true, true);
		stData.horizontalSpan = 2;
		stData.widthHint = 560;
		stData.heightHint = 260;
		scriptText.setLayoutData(stData);
		scriptText.setText(scriptSource != null ? scriptSource : ""); //$NON-NLS-1$

		updateEnablement();
		return area;
	}

	private void updateEnablement() {
		boolean on = enabledCheck.getSelection();
		scriptNameText.setEnabled(on);
		scopeText.setEnabled(on);
		methodText.setEnabled(on);
		timeoutText.setEnabled(on);
		requiredCheck.setEnabled(on);
		scriptText.setEnabled(on);
	}

	@Override
	protected void okPressed() {
		enabled = enabledCheck.getSelection();
		if (enabled) {
			scriptName = scriptNameText.getText().trim();
			scope = scopeText.getText().trim();
			method = methodText.getText().trim();
			if (method.isEmpty()) {
				method = null;
			}
			try {
				timeoutSeconds = Integer.parseInt(timeoutText.getText().trim());
			} catch (NumberFormatException nfe) {
				setErrorMessage("Timeout must be a whole number of seconds."); //$NON-NLS-1$
				return;
			}
			required = requiredCheck.getSelection();
			scriptSource = scriptText.getText();
			if (scriptName.isEmpty()) {
				setErrorMessage("Script file name must not be empty."); //$NON-NLS-1$
				return;
			}
			if (scope.isEmpty()) {
				setErrorMessage("Scope / form to run must not be empty."); //$NON-NLS-1$
				return;
			}
		}
		super.okPressed();
	}

	/** @return whether JSUnit verification is enabled for the baseline (only valid after OK) */
	public boolean isEnabled() {
		return enabled;
	}

	/** @return the built {@link Baseline.JsUnitVerify}, or {@code null} when disabled (only valid after OK) */
	public Baseline.JsUnitVerify getVerify() {
		if (!enabled) {
			return null;
		}
		return new Baseline.JsUnitVerify(java.util.List.of("verify/" + scriptName), scope, method, //$NON-NLS-1$
				timeoutSeconds, required);
	}

	/** @return the solution-relative script file name (only valid after OK) */
	public String getScriptName() {
		return scriptName;
	}

	/** @return the edited test script source (only valid after OK) */
	public String getScriptSource() {
		return scriptSource;
	}

	private static final String DEFAULT_SCRIPT = "/**\n" //$NON-NLS-1$
			+ " * Skill-test behavioural verification. Injected into the active solution\n" //$NON-NLS-1$
			+ " * after the skill run, then run via JSUnit. Keep it NAME-TOLERANT: query\n" //$NON-NLS-1$
			+ " * the model/runtime rather than assuming the element names the model chose.\n" //$NON-NLS-1$
			+ " */\n" //$NON-NLS-1$
			+ "function test_example() {\n" //$NON-NLS-1$
			+ "\tjsunit.assertTrue('replace me with real assertions', true);\n" //$NON-NLS-1$
			+ "}\n"; //$NON-NLS-1$
}
