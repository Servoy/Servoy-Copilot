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
import java.util.List;
import java.util.Locale;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.extensions.AbstractServoyModel;
import com.servoy.eclipse.model.nature.ServoyProject;

/**
 * Page 1 of {@link ImportBaselineWizard}: pick the opencode {@code export.json}
 * (+ optional UI mockup), set the baseline id/title, and preview what the export
 * contains. Validates the id (charset + collision) live and tells the user what
 * the next step will do, so the AI analysis is not a surprise (SVY-21366).
 */
public class ImportDetailsWizardPage extends WizardPage {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ImportBaselineWizard wizard;

	private Text fileText;
	private Text titleText;
	private Text mockupText;
	private Text previewText;

	private Button emptyRadio;
	private Button gitRadio;
	private Button folderRadio;
	private Combo solutionCombo;
	private Label locationLabel;
	private Text locationText;
	private Label refLabel;
	private Text refText;
	private Spinner runsSpinner;

	protected ImportDetailsWizardPage(ImportBaselineWizard wizard) {
		super("importDetails"); //$NON-NLS-1$
		this.wizard = wizard;
		setTitle("Import an opencode session export"); //$NON-NLS-1$
		setMessage("Pick an opencode export.json to register as a new skill-test baseline. " //$NON-NLS-1$
				+ "Next, an AI agent analyzes it and proposes the verification checks to configure " //$NON-NLS-1$
				+ "(expected persists, acceptance criteria and/or a JSUnit script)."); //$NON-NLS-1$
	}

	@Override
	public void createControl(Composite parent) {
		Composite form = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(3, false);
		layout.marginWidth = 12;
		layout.marginHeight = 12;
		layout.horizontalSpacing = 8;
		layout.verticalSpacing = 8;
		form.setLayout(layout);

		// --- Source group: the export + optional mockup ---
		Group source = new Group(form, SWT.NONE);
		source.setText("Source"); //$NON-NLS-1$
		source.setLayoutData(span(3));
		GridLayout sl = new GridLayout(3, false);
		sl.marginWidth = 8;
		sl.marginHeight = 8;
		sl.horizontalSpacing = 8;
		source.setLayout(sl);

		new Label(source, SWT.NONE).setText("Export file:"); //$NON-NLS-1$
		fileText = new Text(source, SWT.BORDER | SWT.READ_ONLY);
		fileText.setLayoutData(fill());
		fileText.setMessage("Pick an opencode export.json\u2026"); //$NON-NLS-1$
		Button browse = new Button(source, SWT.PUSH);
		browse.setText("Browse\u2026"); //$NON-NLS-1$
		browse.addListener(SWT.Selection, e -> chooseFile());

		Label mockupLabel = new Label(source, SWT.NONE);
		mockupLabel.setText("UI mockup:"); //$NON-NLS-1$
		mockupText = new Text(source, SWT.BORDER | SWT.READ_ONLY);
		mockupText.setLayoutData(fill());
		mockupText.setMessage("optional \u2013 an HTML mockup the form reproduces (helps the analysis)"); //$NON-NLS-1$
		mockupText.setToolTipText("An HTML mockup the form reproduces. Handed to the analysis agent."); //$NON-NLS-1$
		Button mockupBrowse = new Button(source, SWT.PUSH);
		mockupBrowse.setText("Browse\u2026"); //$NON-NLS-1$
		mockupBrowse.addListener(SWT.Selection, e -> chooseMockup());

		// --- Identity group: title (the id is auto-generated from it) ---
		Group identity = new Group(form, SWT.NONE);
		identity.setText("Baseline"); //$NON-NLS-1$
		identity.setLayoutData(span(3));
		GridLayout il = new GridLayout(2, false);
		il.marginWidth = 8;
		il.marginHeight = 8;
		il.horizontalSpacing = 8;
		identity.setLayout(il);

		new Label(identity, SWT.NONE).setText("Title:"); //$NON-NLS-1$
		titleText = new Text(identity, SWT.BORDER);
		titleText.setLayoutData(fill());
		GridData titleData = fill();
		titleData.horizontalSpan = 2;
		titleText.setLayoutData(titleData);
		titleText.addListener(SWT.Modify, e -> {
			wizard.setBaselineTitle(titleText.getText().trim());
			regenerateId();
			validate();
		});
		// The folder id is generated from the title (made unique) and kept off the UI.

		// --- Solution & runs group: how the baseline's starting solution is set up
		// and how many replay trials to run (written into the sidecar's precondition
		// + maxAttempts). ---
		Group setup = new Group(form, SWT.NONE);
		setup.setText("Solution & runs"); //$NON-NLS-1$
		setup.setLayoutData(span(3));
		GridLayout stl = new GridLayout(3, false);
		stl.marginWidth = 8;
		stl.marginHeight = 8;
		stl.horizontalSpacing = 8;
		setup.setLayout(stl);

		new Label(setup, SWT.NONE).setText("Start from:"); //$NON-NLS-1$
		Composite sourceRow = new Composite(setup, SWT.NONE);
		sourceRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		GridLayout srl = new GridLayout(3, false);
		srl.marginWidth = 0;
		srl.marginHeight = 0;
		sourceRow.setLayout(srl);
		emptyRadio = sourceRadio(sourceRow, "Empty solution", Baseline.Source.Type.EMPTY); //$NON-NLS-1$
		gitRadio = sourceRadio(sourceRow, "Git repository", Baseline.Source.Type.GIT); //$NON-NLS-1$
		folderRadio = sourceRadio(sourceRow, "Folder", Baseline.Source.Type.FOLDER); //$NON-NLS-1$

		new Label(setup, SWT.NONE).setText("Solution:"); //$NON-NLS-1$
		solutionCombo = new Combo(setup, SWT.DROP_DOWN);
		solutionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		for (String name : solutionNames()) {
			solutionCombo.add(name);
		}
		solutionCombo.setText(wizard.getSolution() != null ? wizard.getSolution() : ""); //$NON-NLS-1$
		solutionCombo.addListener(SWT.Modify, e -> {
			wizard.setSolution(solutionCombo.getText().trim());
			validate();
		});
		solutionCombo.setToolTipText("For Empty: the new solution's name. For Git/Folder: the solution project " //$NON-NLS-1$
				+ "to activate from the checkout."); //$NON-NLS-1$

		locationLabel = new Label(setup, SWT.NONE);
		locationLabel.setText("Location:"); //$NON-NLS-1$
		locationText = new Text(setup, SWT.BORDER);
		locationText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		locationText.setMessage("git repo URL, or a folder path on disk"); //$NON-NLS-1$
		locationText.addListener(SWT.Modify, e -> {
			wizard.setLocation(locationText.getText().trim());
			validate();
		});

		refLabel = new Label(setup, SWT.NONE);
		refLabel.setText("Git ref:"); //$NON-NLS-1$
		refText = new Text(setup, SWT.BORDER);
		refText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		refText.setMessage("optional branch / tag / commit (default branch when blank)"); //$NON-NLS-1$
		refText.addListener(SWT.Modify, e -> wizard.setRef(refText.getText().trim()));

		new Label(setup, SWT.NONE).setText("Runs:"); //$NON-NLS-1$
		runsSpinner = new Spinner(setup, SWT.BORDER);
		runsSpinner.setMinimum(1);
		runsSpinner.setMaximum(20);
		runsSpinner.setSelection(wizard.getMaxAttempts() >= 1 ? wizard.getMaxAttempts() : 3);
		runsSpinner.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
		runsSpinner.setToolTipText("How many replay trials to run (the AI is non-deterministic; the result shows " //$NON-NLS-1$
				+ "the distribution)."); //$NON-NLS-1$
		runsSpinner.addListener(SWT.Modify, e -> wizard.setMaxAttempts(runsSpinner.getSelection()));

		selectSourceRadio(wizard.getSourceType());
		syncSourceEnabled();

		// --- Preview group ---
		Group preview = new Group(form, SWT.NONE);
		preview.setText("Export preview"); //$NON-NLS-1$
		GridData pgData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
		preview.setLayoutData(pgData);
		preview.setLayout(new GridLayout(1, false));

		previewText = new Text(preview, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
		GridData previewData = new GridData(SWT.FILL, SWT.FILL, true, true);
		previewData.widthHint = 480;
		previewData.heightHint = 96;
		previewText.setLayoutData(previewData);
		previewText.setText("(no file selected)"); //$NON-NLS-1$
		previewText.setBackground(preview.getBackground());

		setControl(form);
		setPageComplete(false);
	}

	private static GridData span(int cols) {
		return new GridData(SWT.FILL, SWT.TOP, true, false, cols, 1);
	}

	private static GridData fill() {
		return new GridData(SWT.FILL, SWT.CENTER, true, false);
	}

	/** A source-type radio button that updates the wizard + the enabled fields when picked. */
	private Button sourceRadio(Composite parent, String text, Baseline.Source.Type type) {
		Button b = new Button(parent, SWT.RADIO);
		b.setText(text);
		b.setData(type);
		b.addListener(SWT.Selection, e -> {
			if (b.getSelection()) {
				wizard.setSourceType(type);
				syncSourceEnabled();
				validate();
			}
		});
		return b;
	}

	private void selectSourceRadio(Baseline.Source.Type type) {
		Baseline.Source.Type t = type != null ? type : Baseline.Source.Type.EMPTY;
		emptyRadio.setSelection(t == Baseline.Source.Type.EMPTY);
		gitRadio.setSelection(t == Baseline.Source.Type.GIT);
		folderRadio.setSelection(t == Baseline.Source.Type.FOLDER);
	}

	/** Location is for git/folder; ref only for git. */
	private void syncSourceEnabled() {
		Baseline.Source.Type t = wizard.getSourceType();
		boolean needsLocation = t != Baseline.Source.Type.EMPTY;
		boolean needsRef = t == Baseline.Source.Type.GIT;
		locationLabel.setEnabled(needsLocation);
		locationText.setEnabled(needsLocation);
		refLabel.setEnabled(needsRef);
		refText.setEnabled(needsRef);
	}

	/** The open Servoy solution project names, for the solution picker (best-effort). */
	private static List<String> solutionNames() {
		List<String> names = new ArrayList<>();
		try {
			if (ServoyModelFinder.getServoyModel() instanceof AbstractServoyModel model) {
				for (ServoyProject p : model.getServoyProjects()) {
					if (p != null && p.getProject() != null) {
						names.add(p.getProject().getName());
					}
				}
			}
		} catch (RuntimeException | LinkageError ignore) {
			// no workbench / model unavailable: leave the combo editable+empty
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
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
		File file = new File(path);
		wizard.setExportFile(file);
		fileText.setText(path);
		applyExport(file);
		validate();
	}

	private void chooseMockup() {
		FileDialog fd = new FileDialog(getShell(), SWT.OPEN);
		fd.setText("Select UI mockup HTML"); //$NON-NLS-1$
		fd.setFilterExtensions(new String[] { "*.html;*.htm", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
		fd.setFilterNames(new String[] { "HTML files (*.html, *.htm)", "All files (*.*)" }); //$NON-NLS-1$ //$NON-NLS-2$
		String path = fd.open();
		if (path == null) {
			return;
		}
		File file = new File(path);
		wizard.setMockupFile(file);
		mockupText.setText(path);
	}

	private void applyExport(File file) {
		String derivedTitle = null;
		String previewBody;
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
			previewBody = buildPreview(transcript);
		} catch (Exception ex) {
			previewBody = "Could not read export: " + ex.getMessage(); //$NON-NLS-1$
		}
		String seedTitle = derivedTitle != null ? derivedTitle : stripExtension(file.getName());
		if (titleText.getText().isBlank()) {
			titleText.setText(seedTitle);
			wizard.setBaselineTitle(seedTitle);
		}
		regenerateId();
		this.previewText.setText(previewBody);
		this.previewText.requestLayout();
	}

	/**
	 * Regenerates the baseline id from the current title: a slug, made unique
	 * against the existing baselines by appending {@code -2}, {@code -3}, … The id
	 * is no longer user-entered (SVY-21366) - the user owns the title; the folder
	 * name is derived and shown read-only.
	 */
	private void regenerateId() {
		String base = slug(titleText.getText().trim());
		if (base.isEmpty()) {
			base = "baseline"; //$NON-NLS-1$
		}
		wizard.setBaselineId(uniqueId(base));
	}

	/** A slug not colliding with an existing baseline folder (suffixes -2, -3, …). */
	private String uniqueId(String base) {
		File root = wizard.getBaselinesRoot();
		if (root == null || !new File(root, base).exists()) {
			return base;
		}
		for (int n = 2; n < 1000; n++) {
			String candidate = base + "-" + n; //$NON-NLS-1$
			if (!new File(root, candidate).exists()) {
				return candidate;
			}
		}
		return base + "-" + System.currentTimeMillis(); //$NON-NLS-1$
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

	private static String slug(String text) {
		if (text == null) {
			return ""; //$NON-NLS-1$
		}
		String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-"); //$NON-NLS-1$ //$NON-NLS-2$
		s = s.replaceAll("^[-._]+", "").replaceAll("[-._]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		return s;
	}

	private void validate() {
		String error = firstError();
		setErrorMessage(error);
		setPageComplete(error == null);
	}

	private String firstError() {
		File exportFile = wizard.getExportFile();
		if (exportFile == null || !exportFile.isFile()) {
			return "Choose an opencode export.json file."; //$NON-NLS-1$
		}
		if (titleText.getText().trim().isEmpty()) {
			return "Give the baseline a title (the folder id is generated from it)."; //$NON-NLS-1$
		}
		// The id is auto-generated + de-duplicated (regenerateId), so it is always
		// valid and unique here - no need to validate user input for it.
		if (wizard.getSolution() == null || wizard.getSolution().isBlank()) {
			return "Choose or type the solution name."; //$NON-NLS-1$
		}
		Baseline.Source.Type t = wizard.getSourceType();
		if (t != Baseline.Source.Type.EMPTY && (wizard.getLocation() == null || wizard.getLocation().isBlank())) {
			return "Provide the " + (t == Baseline.Source.Type.GIT ? "git repository URL" : "folder path") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					+ " for a " + t.name().toLowerCase(Locale.ROOT) + " source."; //$NON-NLS-1$ //$NON-NLS-2$
		}
		return null;
	}

	@Override
	public void setVisible(boolean visible) {
		super.setVisible(visible);
		if (visible) {
			// Reflect primed state (a reopened background import) and re-validate.
			if (wizard.getExportFile() != null && fileText.getText().isBlank()) {
				fileText.setText(wizard.getExportFile().getPath());
				applyExport(wizard.getExportFile());
			}
			if (wizard.getMockupFile() != null && mockupText.getText().isBlank()) {
				mockupText.setText(wizard.getMockupFile().getPath());
			}
			if (!wizard.getBaselineTitle().isBlank() && titleText.getText().isBlank()) {
				titleText.setText(wizard.getBaselineTitle());
			}
			validate();
		}
	}
}
