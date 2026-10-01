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

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertions;

/**
 * Editor for a baseline's expected outcomes (SVY-21366, Option C) as raw
 * <b>JSON</b>. Presents the {@code expect} block ({@code { "persists": [ ... ] }})
 * in a monospace text area the user can edit directly, validated live: the OK
 * button is disabled while the JSON is malformed and the parse error is shown in
 * the title-area banner.
 * <p>
 * Used both when importing an export (pre-filled with the inferred assertions the
 * user confirms/edits) and when editing an existing baseline's assertions from
 * the view. On OK the JSON is parsed back into {@link OutcomeAssertion}s via
 * {@link OutcomeAssertions#parse(JsonNode)}.
 * </p>
 */
public class PersistAssertionEditorDialog extends TitleAreaDialog {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private final String heading;
	private final String initialJson;

	private Text jsonText;
	private Font monoFont;
	private List<OutcomeAssertion> result = List.of();

	public PersistAssertionEditorDialog(Shell parentShell, String heading, List<OutcomeAssertion> initial) {
		super(parentShell);
		this.heading = heading;
		this.initialJson = toJsonString(initial);
		setHelpAvailable(false);
	}

	private static String toJsonString(List<OutcomeAssertion> assertions) {
		try {
			ObjectNode expect = OutcomeAssertions.toJson(assertions != null ? assertions : List.of());
			return PRETTY.writeValueAsString(expect);
		} catch (Exception ex) {
			return "{\n  \"persists\": []\n}"; //$NON-NLS-1$
		}
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Expected outcomes"); //$NON-NLS-1$
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle(heading != null ? heading : "Expected outcomes"); //$NON-NLS-1$
		setMessage("Edit the expected Servoy persists as JSON. " //$NON-NLS-1$
				+ "Each entry has a kind (form, valuelist, component, scriptmethod, \u2026), an optional name/scope, " //$NON-NLS-1$
				+ "props (property matchers), and optional children.", IMessageProvider.INFORMATION);

		Composite area = (Composite) super.createDialogArea(parent);
		Composite body = new Composite(area, SWT.NONE);
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		body.setLayout(new GridLayout(1, false));

		Label hint = new Label(body, SWT.WRAP);
		hint.setText("Shape: { \"persists\": [ { \"kind\": \"form\", \"name\": \"myForm\", " //$NON-NLS-1$
				+ "\"props\": { \"dataSource\": \"db:/server/table\" }, " //$NON-NLS-1$
				+ "\"children\": [ { \"kind\": \"component\", " //$NON-NLS-1$
				+ "\"props\": { \"typeName\": \"bootstrapcomponents-textbox\", \"dataProviderID\": \"col\" } } ] } ] }"); //$NON-NLS-1$
		GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false);
		hintData.widthHint = 640;
		hint.setLayoutData(hintData);

		jsonText = new Text(body, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData textData = new GridData(SWT.FILL, SWT.FILL, true, true);
		textData.widthHint = 640;
		textData.heightHint = 380;
		jsonText.setLayoutData(textData);
		jsonText.setText(initialJson);
		monoFont = monospaceFont(jsonText);
		if (monoFont != null) {
			jsonText.setFont(monoFont);
		}
		jsonText.addListener(SWT.Modify, e -> validate());

		return area;
	}

	private static Font monospaceFont(Text owner) {
		try {
			FontData base = owner.getFont().getFontData()[0];
			return new Font(owner.getDisplay(), new FontData("Consolas", base.getHeight(), SWT.NORMAL)); //$NON-NLS-1$
		} catch (RuntimeException ex) {
			return null;
		}
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		super.createButtonsForButtonBar(parent);
		validate();
	}

	/** Live-validates the JSON, gating OK and surfacing any parse error. */
	private void validate() {
		String error = parseError(jsonText.getText());
		setErrorMessage(error);
		if (getButton(IDialogConstants.OK_ID) != null) {
			getButton(IDialogConstants.OK_ID).setEnabled(error == null);
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
	protected void okPressed() {
		try {
			JsonNode node = MAPPER.readTree(jsonText.getText());
			result = OutcomeAssertions.parse(node);
		} catch (Exception ex) {
			result = List.of();
		}
		super.okPressed();
	}

	@Override
	public boolean close() {
		boolean closed = super.close();
		if (closed && monoFont != null && !monoFont.isDisposed()) {
			monoFont.dispose();
		}
		return closed;
	}

	/** @return the edited assertions (only valid after OK) */
	public List<OutcomeAssertion> getAssertions() {
		return result;
	}
}
