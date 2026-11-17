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
 * Edits a baseline's WARN-only soft budgets (SVY-21660): one row per metric
 * (tokens, tool-calls, credits), each a checkbox to enable it plus a
 * {@code baselineValue} and a {@code tolerancePct}. A budget warns (never
 * fails) when the run's metric exceeds {@code baselineValue * (1 +
 * tolerancePct/100)}.
 * <p>
 * The {@code baselineValue} fields are pre-filled from the baseline's last run
 * metrics when available (the tool-call count, the token total and the
 * per-session Kiro credits the report already shows), so a user can anchor the
 * budget on the observed run instead of copying numbers by hand. A "use last
 * run" button per row re-applies that figure on demand.
 * </p>
 * <p>
 * Only the {@code baselineValue}+{@code tolerancePct} form is edited here (not
 * the absolute {@code max} ceiling); an unchecked metric is written as no
 * budget for it. The result is the complete new budget list, which the caller
 * persists via {@link BaselineLoader#writeBudgets(java.io.File, List)}.
 * </p>
 */
public class BudgetEditorDialog extends TitleAreaDialog {

	/** The budgetable metrics, in display order. */
	private static final String[] METRICS = { "tokens", "toolCalls", "credits" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final String[] METRIC_LABELS = { "Tokens", "Tool calls", "Credits (per-session est.)" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private final String baselineId;
	private final List<Baseline.Budget> current;
	/** Observed last-run anchor per metric, or {@code null} when unknown. */
	private final Long[] lastRun;

	private final Button[] enabled = new Button[METRICS.length];
	private final Text[] valueText = new Text[METRICS.length];
	private final Text[] toleranceText = new Text[METRICS.length];

	/** result */
	private List<Baseline.Budget> result;

	/**
	 * @param parentShell the parent shell
	 * @param baselineId  the baseline id (shown in the title)
	 * @param currentList the baseline's current budgets (may be empty/null)
	 * @param lastMetrics the baseline's last-run metrics to pre-fill anchors from,
	 *                    or {@code null} when it has not been run
	 */
	public BudgetEditorDialog(Shell parentShell, String baselineId, List<Baseline.Budget> currentList,
			RunMetrics lastMetrics) {
		super(parentShell);
		this.baselineId = baselineId;
		this.current = currentList != null ? currentList : List.of();
		this.lastRun = new Long[METRICS.length];
		if (lastMetrics != null) {
			this.lastRun[0] = lastMetrics.tokens() != null ? Long.valueOf(lastMetrics.tokens().total()) : null;
			this.lastRun[1] = Long.valueOf(lastMetrics.toolCalls());
			this.lastRun[2] = lastMetrics.kiro() != null ? Long.valueOf(Math.round(lastMetrics.kiro().estCredits()))
					: null;
		}
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Edit budget"); //$NON-NLS-1$
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle("Soft budget for '" + baselineId + "'"); //$NON-NLS-1$ //$NON-NLS-2$
		setMessage("WARN-only: a run over budget is flagged but never fails. " //$NON-NLS-1$
				+ "Warns when the metric exceeds baseline + tolerance%.", IMessageProvider.INFORMATION);

		Composite area = (Composite) super.createDialogArea(parent);
		Composite form = new Composite(area, SWT.NONE);
		form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		form.setLayout(new GridLayout(5, false));

		// Header row.
		new Label(form, SWT.NONE).setText("Metric"); //$NON-NLS-1$
		new Label(form, SWT.NONE).setText("Baseline value"); //$NON-NLS-1$
		new Label(form, SWT.NONE).setText("Tolerance %"); //$NON-NLS-1$
		new Label(form, SWT.NONE).setText(""); //$NON-NLS-1$
		new Label(form, SWT.NONE).setText(""); //$NON-NLS-1$

		for (int i = 0; i < METRICS.length; i++) {
			Baseline.Budget existing = find(METRICS[i]);

			enabled[i] = new Button(form, SWT.CHECK);
			enabled[i].setText(METRIC_LABELS[i]);
			enabled[i].setSelection(existing != null);

			valueText[i] = new Text(form, SWT.BORDER);
			GridData vgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
			vgd.widthHint = 90;
			valueText[i].setLayoutData(vgd);
			if (existing != null && existing.baselineValue() != null) {
				valueText[i].setText(existing.baselineValue().toString());
			} else if (lastRun[i] != null) {
				valueText[i].setText(lastRun[i].toString());
			}

			toleranceText[i] = new Text(form, SWT.BORDER);
			GridData tgd = new GridData(SWT.FILL, SWT.CENTER, false, false);
			tgd.widthHint = 50;
			toleranceText[i].setLayoutData(tgd);
			toleranceText[i].setText(
					existing != null && existing.tolerancePct() != null ? existing.tolerancePct().toString() : "25"); //$NON-NLS-1$

			Label lastLabel = new Label(form, SWT.NONE);
			lastLabel.setText(lastRun[i] != null ? "last run: " + lastRun[i] : "last run: unknown"); //$NON-NLS-1$ //$NON-NLS-2$

			final int idx = i;
			Button useLast = new Button(form, SWT.PUSH);
			useLast.setText("Use last run"); //$NON-NLS-1$
			useLast.setEnabled(lastRun[i] != null);
			useLast.addListener(SWT.Selection, e -> {
				if (lastRun[idx] != null) {
					valueText[idx].setText(lastRun[idx].toString());
					enabled[idx].setSelection(true);
				}
			});
		}

		return area;
	}

	/** @return the current budget for {@code metric}, or {@code null}. */
	private Baseline.Budget find(String metric) {
		for (Baseline.Budget b : current) {
			if (b != null && metric.equalsIgnoreCase(b.metric())) {
				return b;
			}
		}
		return null;
	}

	@Override
	protected void okPressed() {
		List<Baseline.Budget> budgets = new ArrayList<>();
		for (int i = 0; i < METRICS.length; i++) {
			if (!enabled[i].getSelection()) {
				continue;
			}
			Integer value;
			Integer tolerance;
			try {
				value = Integer.valueOf(valueText[i].getText().trim());
			} catch (NumberFormatException nfe) {
				setErrorMessage(METRIC_LABELS[i] + ": baseline value must be a whole number."); //$NON-NLS-1$
				return;
			}
			if (value.intValue() < 0) {
				setErrorMessage(METRIC_LABELS[i] + ": baseline value must not be negative."); //$NON-NLS-1$
				return;
			}
			String tolRaw = toleranceText[i].getText().trim();
			try {
				tolerance = tolRaw.isEmpty() ? Integer.valueOf(0) : Integer.valueOf(tolRaw);
			} catch (NumberFormatException nfe) {
				setErrorMessage(METRIC_LABELS[i] + ": tolerance % must be a whole number."); //$NON-NLS-1$
				return;
			}
			if (tolerance.intValue() < 0) {
				setErrorMessage(METRIC_LABELS[i] + ": tolerance % must not be negative."); //$NON-NLS-1$
				return;
			}
			budgets.add(new Baseline.Budget(METRICS[i], null, value, tolerance));
		}
		result = budgets;
		super.okPressed();
	}

	/**
	 * @return the complete new budget list (only valid after OK); empty when the
	 *         user disabled every metric, which clears the budget block
	 */
	public List<Baseline.Budget> getBudgets() {
		return result;
	}
}
