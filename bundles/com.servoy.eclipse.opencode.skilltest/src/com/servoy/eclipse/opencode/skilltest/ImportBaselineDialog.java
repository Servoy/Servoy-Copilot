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
import java.util.List;
import java.util.Locale;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeAssertion;
import com.servoy.eclipse.opencode.skilltest.outcome.OutcomeInference;

/**
 * Single dialog for importing an opencode {@code export.json} as a new baseline
 * (SVY-21366). Replaces the previous three-step {@code FileDialog} &rarr;
 * {@code InputDialog} (id) &rarr; {@code InputDialog} (title) chain with one
 * form:
 * <ul>
 * <li>a file field + <b>Browse&hellip;</b> button to pick the export;</li>
 * <li>an id field, seeded from the export title / filename and live-validated
 * (charset + collision with an existing baseline) into the title-area error
 * banner, which keeps <b>OK</b> disabled until valid;</li>
 * <li>a title field, seeded from the export {@code info.title};</li>
 * <li>a read-only preview of what the picked export contains (prompt, agent,
 * model, tool-call count) so the user knows they picked the right file.</li>
 * </ul>
 * <p>
 * On <b>OK</b> the export's expected outcomes are inferred
 * ({@link OutcomeInference}) and the {@link PersistAssertionEditorDialog} is
 * shown so the user can confirm/edit them before the baseline is written
 * (Option C). The confirmed assertions are exposed via
 * {@link #getInferredAssertions()}.
 * </p>
 */
public class ImportBaselineDialog extends TitleAreaDialog {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final File baselinesRoot;

	private Text fileText;
	private Text idText;
	private Text titleText;
	private Label previewLabel;

	private File exportFile;
	private String baselineId = ""; //$NON-NLS-1$
	private String baselineTitle = ""; //$NON-NLS-1$
	private List<OutcomeAssertion> inferredAssertions = List.of();

	/** Set once the user has manually edited the id, so a later file pick won't clobber it. */
	private boolean idEditedByUser;

	public ImportBaselineDialog(Shell parentShell, File baselinesRoot) {
		super(parentShell);
		this.baselinesRoot = baselinesRoot;
		setHelpAvailable(false);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Import baseline"); //$NON-NLS-1$
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle("Import an opencode session export"); //$NON-NLS-1$
		setMessage("Pick an opencode export.json to register as a new skill-test baseline.", //$NON-NLS-1$
				IMessageProvider.INFORMATION);

		Composite area = (Composite) super.createDialogArea(parent);
		Composite form = new Composite(area, SWT.NONE);
		form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		form.setLayout(new GridLayout(3, false));

		// --- Export file row ---
		new Label(form, SWT.NONE).setText("Export file:"); //$NON-NLS-1$
		fileText = new Text(form, SWT.BORDER | SWT.READ_ONLY);
		fileText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button browse = new Button(form, SWT.PUSH);
		browse.setText("Browse\u2026"); //$NON-NLS-1$
		browse.addListener(SWT.Selection, e -> chooseFile());

		// --- Id row ---
		new Label(form, SWT.NONE).setText("Baseline id:"); //$NON-NLS-1$
		idText = new Text(form, SWT.BORDER);
		GridData idData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		idData.horizontalSpan = 2;
		idText.setLayoutData(idData);
		idText.addListener(SWT.Modify, e -> {
			idEditedByUser = true;
			validate();
		});

		// --- Title row ---
		new Label(form, SWT.NONE).setText("Title:"); //$NON-NLS-1$
		titleText = new Text(form, SWT.BORDER);
		GridData titleData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		titleData.horizontalSpan = 2;
		titleText.setLayoutData(titleData);

		// --- Preview ---
		Label previewCaption = new Label(form, SWT.NONE);
		previewCaption.setText("Export preview:"); //$NON-NLS-1$
		GridData captionData = new GridData(SWT.LEFT, SWT.TOP, false, false);
		captionData.horizontalSpan = 3;
		previewCaption.setLayoutData(captionData);

		previewLabel = new Label(form, SWT.WRAP);
		GridData previewData = new GridData(SWT.FILL, SWT.FILL, true, true);
		previewData.horizontalSpan = 3;
		previewData.widthHint = 420;
		previewData.heightHint = 90;
		previewLabel.setLayoutData(previewData);
		previewLabel.setText("(no file selected)"); //$NON-NLS-1$

		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		super.createButtonsForButtonBar(parent);
		validate();
	}

	private void chooseFile() {
		FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
		fd.setText("Select opencode export.json"); //$NON-NLS-1$
		fd.setFilterExtensions(new String[] { "*.json", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
		fd.setFilterNames(new String[] { "JSON files (*.json)", "All files (*.*)" }); //$NON-NLS-1$ //$NON-NLS-2$
		String path = fd.open();
		if (path == null) {
			return;
		}
		exportFile = new File(path);
		fileText.setText(path);
		applyExport(exportFile);
		validate();
	}

	/**
	 * Parses the picked export to seed the id/title fields and render the preview.
	 * Parse failures surface in the error banner via {@link #validate()}; here we
	 * only best-effort populate the form.
	 */
	private void applyExport(File file) {
		String derivedTitle = null;
		String previewText;
		try {
			String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			JsonNode node = MAPPER.readTree(BaselineLoader.stripLeadingNonJson(raw));
			JsonNode info = node.get("info"); //$NON-NLS-1$
			if (info != null) {
				JsonNode t = info.get("title"); //$NON-NLS-1$
				if (t != null && t.isValueNode() && !t.asText().isBlank()) {
					derivedTitle = t.asText().trim();
				}
			}
			SessionTranscript transcript = SessionTranscript.fromExport(node);
			previewText = buildPreview(transcript);
		} catch (Exception ex) {
			previewText = "Could not read export: " + ex.getMessage(); //$NON-NLS-1$
		}

		String seedTitle = derivedTitle != null ? derivedTitle : stripExtension(file.getName());
		if (titleText.getText().isBlank()) {
			titleText.setText(seedTitle);
		}
		if (!idEditedByUser) {
			idText.setText(slug(seedTitle));
			idEditedByUser = false; // setText fired Modify; keep it as auto-derived
		}
		previewLabel.setText(previewText);
		previewLabel.requestLayout();
	}

	private static String buildPreview(SessionTranscript transcript) {
		StringBuilder sb = new StringBuilder();
		String agent = transcript.getAgent();
		String model = transcript.getModel();
		sb.append("Agent: ").append(agent != null ? agent : "(default)"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append("    Model: ").append(model != null ? model : "(default)"); //$NON-NLS-1$ //$NON-NLS-2$
		sb.append('\n');
		sb.append("Tool calls: ").append(transcript.getToolCalls().size()).append('\n'); //$NON-NLS-1$
		String prompt = transcript.getPrompt();
		sb.append("Prompt: ").append(prompt != null ? oneLine(prompt, 200) : "(none found)"); //$NON-NLS-1$ //$NON-NLS-2$
		return sb.toString();
	}

	private static String oneLine(String s, int max) {
		String flat = s.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
		return flat.length() > max ? flat.substring(0, max) + "\u2026" : flat; //$NON-NLS-1$
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/**
	 * Turns arbitrary text into a valid baseline id: lower-case, non
	 * {@code [A-Za-z0-9._-]} runs collapsed to a single {@code -}, trimmed of
	 * leading/trailing separators.
	 */
	private static String slug(String text) {
		if (text == null) {
			return ""; //$NON-NLS-1$
		}
		String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-"); //$NON-NLS-1$ //$NON-NLS-2$
		s = s.replaceAll("^[-._]+", "").replaceAll("[-._]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		return s;
	}

	/**
	 * Recomputes validity and pushes any problem into the title-area error banner,
	 * toggling the OK button accordingly.
	 */
	private void validate() {
		Button ok = getButton(IDialogConstants.OK_ID);
		String error = firstError();
		setErrorMessage(error);
		if (ok != null) {
			ok.setEnabled(error == null);
		}
	}

	private String firstError() {
		if (exportFile == null || !exportFile.isFile()) {
			return "Choose an opencode export.json file."; //$NON-NLS-1$
		}
		String id = idText.getText().trim();
		if (id.isEmpty()) {
			return "Baseline id must not be empty."; //$NON-NLS-1$
		}
		if (!id.matches("[A-Za-z0-9._-]+")) { //$NON-NLS-1$
			return "Baseline id may only contain letters, digits, '.', '_' and '-'."; //$NON-NLS-1$
		}
		if (baselinesRoot != null && new File(baselinesRoot, id).exists()) {
			return "A baseline with id '" + id + "' already exists."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return null;
	}

	@Override
	protected void okPressed() {
		baselineId = idText.getText().trim();
		baselineTitle = titleText.getText().trim();
		// Infer the expected outcomes from the export, then let the user
		// confirm/edit them before the baseline is written (Option C).
		List<OutcomeAssertion> inferred = OutcomeInference.infer(exportFile);
		PersistAssertionEditorDialog editor = new PersistAssertionEditorDialog(getShell(),
				"Confirm expected outcomes for '" + baselineId + "'", inferred); //$NON-NLS-1$ //$NON-NLS-2$
		if (editor.open() != OK) {
			// user cancelled the confirmation step: abort the whole import
			return;
		}
		inferredAssertions = editor.getAssertions();
		super.okPressed();
	}

	/** @return the chosen export file (only valid after OK) */
	public File getExportFile() {
		return exportFile;
	}

	/** @return the baseline id (only valid after OK) */
	public String getBaselineId() {
		return baselineId;
	}

	/** @return the baseline title (may be blank; only valid after OK) */
	public String getBaselineTitle() {
		return baselineTitle;
	}

	/**
	 * @return the outcome assertions the user confirmed/edited for this baseline
	 *         (inferred from the export, then reviewed); only valid after OK
	 */
	public List<OutcomeAssertion> getInferredAssertions() {
		return inferredAssertions;
	}
}
