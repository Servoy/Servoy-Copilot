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

import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Edits a baseline's {@code precondition}: the solution name and where its
 * starting state comes from — an {@code empty} solution, a {@code folder} on
 * disk, or a {@code git} repository (clone + optional ref). This is the UI for
 * "test a solution from git" (SVY-21366): choose GIT, enter the repo URL and
 * branch/tag, and the runner clones + imports it before each replay.
 */
public class PreconditionEditorDialog extends TitleAreaDialog {

	private final String baselineId;

	private Combo typeCombo;
	private Text solutionText;
	private Text locationText;
	private Text refText;
	private Label locationLabel;
	private Label refLabel;

	// result
	private String solution;
	private Baseline.Source source;

	public PreconditionEditorDialog(Shell parentShell, String baselineId, String currentSolution,
			Baseline.Source currentSource) {
		super(parentShell);
		this.baselineId = baselineId;
		this.solution = currentSolution;
		this.source = currentSource != null ? currentSource : Baseline.Source.empty();
		setHelpAvailable(false);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Edit test solution source"); //$NON-NLS-1$
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle("Test solution source for '" + baselineId + "'"); //$NON-NLS-1$ //$NON-NLS-2$
		setMessage("Where the solution under test comes from before each replay.", //$NON-NLS-1$
				IMessageProvider.INFORMATION);

		Composite area = (Composite) super.createDialogArea(parent);
		Composite form = new Composite(area, SWT.NONE);
		form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		form.setLayout(new GridLayout(2, false));

		new Label(form, SWT.NONE).setText("Source:"); //$NON-NLS-1$
		typeCombo = new Combo(form, SWT.READ_ONLY);
		typeCombo.setItems("empty (create a fresh solution)", //$NON-NLS-1$
				"folder (import from a local folder)", //$NON-NLS-1$
				"git (clone a repository)"); //$NON-NLS-1$
		typeCombo.select(switch (source.type()) {
		case EMPTY -> 0;
		case FOLDER -> 1;
		case GIT -> 2;
		});
		typeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		typeCombo.addListener(SWT.Selection, e -> updateEnablement());

		new Label(form, SWT.NONE).setText("Solution name:"); //$NON-NLS-1$
		solutionText = new Text(form, SWT.BORDER);
		solutionText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		solutionText.setText(solution != null ? solution : ""); //$NON-NLS-1$

		locationLabel = new Label(form, SWT.NONE);
		locationLabel.setText("Git URL / folder path:"); //$NON-NLS-1$
		locationText = new Text(form, SWT.BORDER);
		locationText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		locationText.setText(source.location() != null ? source.location() : ""); //$NON-NLS-1$

		refLabel = new Label(form, SWT.NONE);
		refLabel.setText("Git branch / tag / commit:"); //$NON-NLS-1$
		refText = new Text(form, SWT.BORDER);
		refText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		refText.setText(source.ref() != null ? source.ref() : ""); //$NON-NLS-1$

		updateEnablement();
		return area;
	}

	private Baseline.Source.Type selectedType() {
		return switch (typeCombo.getSelectionIndex()) {
		case 1 -> Baseline.Source.Type.FOLDER;
		case 2 -> Baseline.Source.Type.GIT;
		default -> Baseline.Source.Type.EMPTY;
		};
	}

	private void updateEnablement() {
		Baseline.Source.Type t = selectedType();
		boolean needsLocation = t != Baseline.Source.Type.EMPTY;
		boolean isGit = t == Baseline.Source.Type.GIT;
		locationLabel.setText(isGit ? "Git repository URL:" : "Folder path:"); //$NON-NLS-1$ //$NON-NLS-2$
		locationText.setEnabled(needsLocation);
		refLabel.setEnabled(isGit);
		refText.setEnabled(isGit);
		// An empty source needs a solution name (createSolution); folder/git derive it.
		setMessage(t == Baseline.Source.Type.EMPTY
				? "An empty source creates a fresh solution with the given name." //$NON-NLS-1$
				: (isGit ? "The repository is cloned (at the given ref) and its solution project(s) imported." //$NON-NLS-1$
						: "The folder's solution project(s) are imported."), //$NON-NLS-1$
				IMessageProvider.INFORMATION);
	}

	@Override
	protected void okPressed() {
		Baseline.Source.Type t = selectedType();
		solution = solutionText.getText().trim();
		String location = locationText.getText().trim();
		String ref = refText.getText().trim();

		if (t == Baseline.Source.Type.EMPTY) {
			if (solution.isEmpty()) {
				setErrorMessage("An empty source requires a solution name."); //$NON-NLS-1$
				return;
			}
			source = Baseline.Source.empty();
		} else {
			if (location.isEmpty()) {
				setErrorMessage((t == Baseline.Source.Type.GIT ? "Git URL" : "Folder path") //$NON-NLS-1$ //$NON-NLS-2$
						+ " must not be empty."); //$NON-NLS-1$
				return;
			}
			source = new Baseline.Source(t, location, t == Baseline.Source.Type.GIT && !ref.isEmpty() ? ref : null);
		}
		super.okPressed();
	}

	/** @return the solution name (only valid after OK) */
	public String getSolution() {
		return solution;
	}

	/** @return the configured source (only valid after OK) */
	public Baseline.Source getSource() {
		return source;
	}
}
