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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Edits a baseline's JSUnit behavioural verification (SVY-21366 §3.4b). Two modes:
 * <ul>
 * <li><b>Inject</b> (the default, and the only option for an {@code empty}-source
 * baseline): the test script is authored here, stored with the baseline under
 * {@code <baseline>/verify/scopes/<scriptName>}, and injected into the active
 * solution before running.</li>
 * <li><b>Use existing solution test files</b> (offered only when the baseline's
 * source is a non-empty git/folder solution): nothing is injected; the user picks
 * one or more {@code test_}-bearing {@code .js} files that already exist in the
 * solution, and each is run in turn. The selected paths are stored
 * solution-relative.</li>
 * </ul>
 */
public class JsUnitVerifyEditorDialog extends TitleAreaDialog {

	private final String baselineId;
	/**
	 * The solution source folder on disk (git checkout / folder), used only to
	 * DISCOVER existing test files to offer; {@code null} for an empty-source
	 * baseline or when the folder is not yet available (e.g. a git source that has
	 * not been cloned by a run yet).
	 */
	private final File solutionSourceFolder;

	private Button enabledCheck;
	private Button useExistingCheck;
	private Label scriptNameLabel;
	private Text scriptNameText;
	private Label scopeLabel;
	private Text scopeText;
	private Label methodLabel;
	private Text methodText;
	private Text timeoutText;
	private Button requiredCheck;
	private Label scriptLabel;
	private Text scriptText;
	private Label existingLabel;
	private Table existingTable;

	// result
	private boolean enabled;
	private boolean useExisting;
	private String scriptName;
	private String scope;
	private String method;
	private int timeoutSeconds;
	private int warmupTimeoutSeconds;
	private boolean required;
	private String scriptSource;
	private List<String> existingScripts = new ArrayList<>();
	/** Whether the user actually typed in the script area (vs. the pre-filled value). */
	private boolean scriptEdited = false;
	/** The script source the dialog was opened with (to detect real edits). */
	private final String originalScriptSource;

	/**
	 * @param parentShell         the parent shell
	 * @param baselineId          the baseline id (for the title)
	 * @param verify              the current JSUnit config, or {@code null} if none
	 * @param currentScript       the current injected script source (may be empty)
	 * @param currentScriptName   the current script file name (may be {@code null})
	 * @param solutionSourceFolder the solution's source folder on disk for
	 *                            discovering existing test files, or {@code null}
	 *                            when the source is empty / not available
	 */
	public JsUnitVerifyEditorDialog(Shell parentShell, String baselineId, Baseline.JsUnitVerify verify,
			String currentScript, String currentScriptName, File solutionSourceFolder) {
		super(parentShell);
		this.baselineId = baselineId;
		this.solutionSourceFolder = solutionSourceFolder;
		this.enabled = verify != null;
		this.useExisting = verify != null && verify.usesExistingScripts();
		this.scriptName = currentScriptName != null && !currentScriptName.isBlank() ? currentScriptName
				: "scopes/skilltest_verify.js"; //$NON-NLS-1$
		this.scope = verify != null ? verify.scope() : "skilltest_verify"; //$NON-NLS-1$
		this.method = verify != null ? verify.method() : null;
		this.timeoutSeconds = verify != null ? verify.timeoutSeconds() : 180;
		// Preserved across the round-trip; the one-time NG-bundle-build warmup budget
		// is not exposed as a dialog field (SVY-21366).
		this.warmupTimeoutSeconds = verify != null ? verify.warmupTimeoutSeconds() : 300;
		this.required = verify == null || verify.required();
		this.scriptSource = currentScript != null ? currentScript : DEFAULT_SCRIPT;
		this.originalScriptSource = this.scriptSource;
		if (verify != null) {
			this.existingScripts = new ArrayList<>(verify.existingScripts());
		}
		setHelpAvailable(false);
	}

	/** @return whether the baseline's source can offer existing solution test files. */
	private boolean canUseExisting() {
		return solutionSourceFolder != null && solutionSourceFolder.isDirectory();
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
		setMessage("Runs after the skill run and the expected-outcome check. Inject a baseline-owned test " //$NON-NLS-1$
				+ "script, or run test files that already exist in the solution.", IMessageProvider.INFORMATION);

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

		// Mode toggle - only meaningful when the solution source can offer existing files.
		useExistingCheck = new Button(form, SWT.CHECK);
		useExistingCheck.setText("Use test files that already exist in the solution (do not inject)"); //$NON-NLS-1$
		useExistingCheck.setSelection(useExisting && canUseExisting());
		GridData uxData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		uxData.horizontalSpan = 2;
		useExistingCheck.setLayoutData(uxData);
		useExistingCheck.setEnabled(canUseExisting());
		if (!canUseExisting()) {
			useExistingCheck.setToolTipText("Available only for a non-empty (git/folder) solution source " //$NON-NLS-1$
					+ "whose files are present on disk."); //$NON-NLS-1$
		}
		useExistingCheck.addListener(SWT.Selection, e -> updateEnablement());

		// --- existing-files picker (shown in 'use existing' mode) ---
		existingLabel = new Label(form, SWT.NONE);
		existingLabel.setText("Existing test files to run (test_ functions):"); //$NON-NLS-1$
		GridData elData = new GridData(SWT.LEFT, SWT.TOP, false, false);
		elData.horizontalSpan = 2;
		existingLabel.setLayoutData(elData);

		existingTable = new Table(form, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.MULTI);
		GridData etData = new GridData(SWT.FILL, SWT.FILL, true, true);
		etData.horizontalSpan = 2;
		etData.heightHint = 140;
		etData.widthHint = 560;
		existingTable.setLayoutData(etData);
		populateExistingTable();

		// --- injected-script fields (shown in 'inject' mode) ---
		scriptNameLabel = new Label(form, SWT.NONE);
		scriptNameLabel.setText("Script file (solution-relative):"); //$NON-NLS-1$
		scriptNameText = new Text(form, SWT.BORDER);
		scriptNameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		scriptNameText.setText(scriptName);

		scopeLabel = new Label(form, SWT.NONE);
		scopeLabel.setText("Scope / form to run:"); //$NON-NLS-1$
		scopeText = new Text(form, SWT.BORDER);
		scopeText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		scopeText.setText(scope != null ? scope : ""); //$NON-NLS-1$

		methodLabel = new Label(form, SWT.NONE);
		methodLabel.setText("Single test method (optional):"); //$NON-NLS-1$
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

		scriptLabel = new Label(form, SWT.NONE);
		scriptLabel.setText("Test script (JSUnit test_ functions):"); //$NON-NLS-1$
		GridData slData = new GridData(SWT.LEFT, SWT.TOP, false, false);
		slData.horizontalSpan = 2;
		scriptLabel.setLayoutData(slData);

		scriptText = new Text(form, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData stData = new GridData(SWT.FILL, SWT.FILL, true, true);
		stData.horizontalSpan = 2;
		stData.widthHint = 560;
		stData.heightHint = 220;
		scriptText.setLayoutData(stData);
		scriptText.setText(scriptSource != null ? scriptSource : ""); //$NON-NLS-1$
		// Track whether the user actually edited the script, so OK never overwrites a real
		// stored script with the pre-filled default just because the editor was opened and
		// closed (SVY-21366).
		scriptText.addListener(SWT.Modify, e -> scriptEdited = true);

		updateEnablement();
		return area;
	}

	/**
	 * Fills the picker with the solution's {@code test_}-bearing {@code .js} files
	 * (root-level global scopes and {@code forms/*.js}), checking the ones already
	 * selected. No-op when the source folder is unavailable.
	 */
	private void populateExistingTable() {
		existingTable.removeAll();
		if (!canUseExisting()) {
			return;
		}
		Set<String> selected = new LinkedHashSet<>(existingScripts);
		for (String rel : discoverTestFiles(solutionSourceFolder)) {
			TableItem item = new TableItem(existingTable, SWT.NONE);
			item.setText(rel);
			item.setChecked(selected.contains(rel));
		}
	}

	private void updateEnablement() {
		boolean on = enabledCheck.getSelection();
		// The injected script and the existing-files picker are INDEPENDENT (SVY-21366: "allow
		// both to coexist"). The checkbox only enables the picker; it does NOT disable the
		// injected-script fields. A baseline can inject its own script, pick existing solution
		// test files, or both - and OK preserves whichever are set, so turning on the picker no
		// longer wipes the injected script.
		boolean existing = on && useExistingCheck.getSelection() && canUseExisting();

		useExistingCheck.setEnabled(on && canUseExisting());

		// Existing-files picker: enabled when the checkbox is on.
		existingLabel.setEnabled(existing);
		existingTable.setEnabled(existing);

		// Injected-script fields: always available when verification is on (independent of the
		// existing-files picker). Leave the script name blank to not inject.
		scriptNameLabel.setEnabled(on);
		scriptNameText.setEnabled(on);
		scriptLabel.setEnabled(on);
		scriptText.setEnabled(on);
		scopeLabel.setEnabled(on);
		scopeText.setEnabled(on);
		methodLabel.setEnabled(on);
		methodText.setEnabled(on);
		timeoutText.setEnabled(on);
		requiredCheck.setEnabled(on);
	}

	@Override
	protected void okPressed() {
		enabled = enabledCheck.getSelection();
		if (enabled) {
			useExisting = useExistingCheck.getSelection() && canUseExisting();
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

			// Independent sections (SVY-21366 "allow both"): collect the existing-file selection
			// AND the injected-script fields; neither clears the other.
			existingScripts = new ArrayList<>();
			if (useExisting) {
				for (TableItem item : existingTable.getItems()) {
					if (item.getChecked()) {
						existingScripts.add(item.getText());
					}
				}
			}
			scriptName = scriptNameText.getText().trim();
			scope = scopeText.getText().trim();
			// Only take the textarea's content when the user actually edited it; otherwise keep
			// the source the dialog opened with, so an unedited open+OK never clobbers a real
			// stored script with the pre-filled default (SVY-21366).
			scriptSource = scriptEdited ? scriptText.getText() : originalScriptSource;

			boolean injecting = !scriptName.isEmpty();
			// At least one of the two must be configured.
			if (!injecting && existingScripts.isEmpty()) {
				setErrorMessage("Enter a script file to inject, and/or select existing solution test file(s)."); //$NON-NLS-1$
				return;
			}
			// When injecting, scope must be set (it is the scope the injected script runs under).
			if (injecting && scope.isEmpty()) {
				setErrorMessage("Scope / form to run must not be empty when a script is injected."); //$NON-NLS-1$
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
		// Independent sections: inject a script when a script file name is given, and/or run the
		// selected existing solution test files. Either or both may be set (SVY-21366).
		List<String> scripts = scriptName.isEmpty() ? java.util.List.of()
				: java.util.List.of("verify/" + scriptName); //$NON-NLS-1$
		String runScope = scriptName.isEmpty() ? "ALL" : scope; //$NON-NLS-1$
		return new Baseline.JsUnitVerify(scripts, existingScripts, runScope, method, timeoutSeconds,
				warmupTimeoutSeconds, required);
	}

	/** @return the solution-relative script file name (only valid after OK) */
	public String getScriptName() {
		return scriptName;
	}

	/** @return the edited test script source (only valid after OK) */
	public String getScriptSource() {
		return scriptSource;
	}

	/**
	 * Finds the solution's own JSUnit test files under {@code sourceFolder}: root-level
	 * {@code <scope>.js} global scopes and {@code forms/<form>.js} form scripts whose content
	 * declares at least one {@code function test_...}. Returns their solution-relative paths
	 * (forward slashes), sorted, deduplicated.
	 */
	static List<String> discoverTestFiles(File sourceFolder) {
		Set<String> out = new java.util.TreeSet<>();
		if (sourceFolder == null || !sourceFolder.isDirectory()) {
			return new ArrayList<>(out);
		}
		// Root-level global scopes: <scope>.js directly in the solution project root.
		File[] rootFiles = sourceFolder.listFiles((d, n) -> n.toLowerCase().endsWith(".js")); //$NON-NLS-1$
		if (rootFiles != null) {
			for (File f : rootFiles) {
				if (f.isFile() && containsTestFunction(f)) {
					out.add(f.getName());
				}
			}
		}
		// Form scripts: forms/<form>.js
		File formsDir = new File(sourceFolder, "forms"); //$NON-NLS-1$
		File[] formFiles = formsDir.isDirectory()
				? formsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".js")) //$NON-NLS-1$
				: null;
		if (formFiles != null) {
			for (File f : formFiles) {
				if (f.isFile() && containsTestFunction(f)) {
					out.add("forms/" + f.getName()); //$NON-NLS-1$
				}
			}
		}
		return new ArrayList<>(out);
	}

	/** Whether a {@code .js} file declares at least one JSUnit {@code function test_...}. */
	private static boolean containsTestFunction(File js) {
		try {
			String content = Files.readString(js.toPath(), StandardCharsets.UTF_8);
			return java.util.regex.Pattern.compile("(?m)function\\s+test_\\w+\\s*\\(").matcher(content).find(); //$NON-NLS-1$
		} catch (java.io.IOException | RuntimeException e) {
			return false;
		}
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
