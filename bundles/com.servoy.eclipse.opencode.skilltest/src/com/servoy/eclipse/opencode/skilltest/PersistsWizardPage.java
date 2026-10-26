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

import java.util.List;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertions;
import com.servoy.eclipse.opencode.skilltest.outcome.VerificationPlan;

/**
 * Editor page of {@link ImportBaselineWizard} for the proposed
 * {@code expect.persists} (SVY-21366): the assertions as live-validated JSON,
 * the wizard equivalent of {@code PersistAssertionEditorDialog}. Shown only when
 * the analysis proposed persists. On a valid edit it stores the parsed
 * assertions on the wizard.
 */
public class PersistsWizardPage extends WizardPage {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private final ImportBaselineWizard wizard;

	private Text jsonText;
	private Font monoFont;
	private String pendingInitialJson = "{\n  \"persists\": []\n}"; //$NON-NLS-1$

	protected PersistsWizardPage(ImportBaselineWizard wizard) {
		super("persists"); //$NON-NLS-1$
		this.wizard = wizard;
		setTitle("Expected persists"); //$NON-NLS-1$
		setMessage("Assert the deterministic persists the run must produce. A string value may be a SQL-LIKE " //$NON-NLS-1$
				+ "pattern (% = any run, _ = one char). Trim to what matters."); //$NON-NLS-1$
	}

	/** Seeds the editor from the agent's proposal; called when analysis finishes. */
	void setProposal(VerificationPlan plan) {
		List<com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion> persists = plan != null ? plan.persists()
				: List.of();
		String json = toJsonString(persists);
		this.pendingInitialJson = json;
		if (jsonText != null && !jsonText.isDisposed()) {
			jsonText.setText(json);
		}
	}

	private static String toJsonString(List<com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion> assertions) {
		try {
			ObjectNode expect = OutcomeAssertions.toJson(assertions != null ? assertions : List.of());
			return PRETTY.writeValueAsString(expect);
		} catch (Exception ex) {
			return "{\n  \"persists\": []\n}"; //$NON-NLS-1$
		}
	}

	@Override
	public void createControl(Composite parent) {
		Composite body = new Composite(parent, SWT.NONE);
		body.setLayout(new GridLayout(1, false));

		Label hint = new Label(body, SWT.WRAP);
		hint.setText("Shape: { \"persists\": [ { \"kind\": \"form\", \"name\": \"myForm\", " //$NON-NLS-1$
				+ "\"props\": { \"dataSource\": \"db:/server/table\" }, \"children\": [ \u2026 ] } ] }"); //$NON-NLS-1$
		GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false);
		hintData.widthHint = 620;
		hint.setLayoutData(hintData);

		jsonText = new Text(body, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData textData = new GridData(SWT.FILL, SWT.FILL, true, true);
		textData.widthHint = 620;
		textData.heightHint = 340;
		jsonText.setLayoutData(textData);
		jsonText.setText(pendingInitialJson);
		monoFont = monospaceFont(jsonText);
		if (monoFont != null) {
			jsonText.setFont(monoFont);
		}
		jsonText.addListener(SWT.Modify, e -> validate());

		setControl(body);
		validate();
	}

	private static Font monospaceFont(Text owner) {
		try {
			FontData base = owner.getFont().getFontData()[0];
			return new Font(owner.getDisplay(), new FontData("Consolas", base.getHeight(), SWT.NORMAL)); //$NON-NLS-1$
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private void validate() {
		String error = parseError(jsonText.getText());
		setErrorMessage(error);
		setPageComplete(error == null);
		if (error == null) {
			try {
				wizard.setConfirmedPersists(OutcomeAssertions.parse(MAPPER.readTree(jsonText.getText())));
			} catch (Exception ignore) {
				// validated above; unreachable
			}
		}
	}

	private static String parseError(String json) {
		if (json == null || json.isBlank()) {
			return "Provide a JSON object with a 'persists' array (or an empty { \"persists\": [] })."; //$NON-NLS-1$
		}
		try {
			JsonNode node = MAPPER.readTree(json);
			if (!node.isObject()) {
				return "Top level must be a JSON object with a 'persists' array."; //$NON-NLS-1$
			}
			JsonNode persists = node.get("persists"); //$NON-NLS-1$
			if (persists != null && !persists.isArray()) {
				return "'persists' must be an array."; //$NON-NLS-1$
			}
			return null;
		} catch (Exception ex) {
			return "Invalid JSON: " + ex.getMessage(); //$NON-NLS-1$
		}
	}

	@Override
	public void dispose() {
		if (monoFont != null && !monoFont.isDisposed()) {
			monoFont.dispose();
		}
		super.dispose();
	}
}
