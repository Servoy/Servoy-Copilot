package com.servoy.eclipse.servoypilot.preferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.AIModelTools;
import com.servoy.eclipse.servoypilot.preferences.PreferenceConstants.ModelKind;

import dev.langchain4j.model.catalog.ModelDescription;

public class ServoyPilotPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	private static final String[][] KEY_REQUIRED_PLACEHOLDER = { { "Enter API key to load models", "" } };
	private static final String[][] LOADING_PLACEHOLDER = { { "Loading models...", "" } };
	private static final String[][] NO_MODELS_PLACEHOLDER = { { "No models available for the current key", "" } };
	private static final int FIELD_WIDTH_HINT = 420;
	private static final int FIELD_HORIZONTAL_INDENT = 10;

	private StringFieldEditor openAiKeyEditor;
	private DynamicComboFieldEditor openAiModelEditor;
	private StringFieldEditor geminiKeyEditor;
	private DynamicComboFieldEditor geminiModelEditor;
	private DynamicComboFieldEditor defaultModelEditor;

	private List<ModelDescription> openAiModels = List.of();
	private List<ModelDescription> geminiModels = List.of();
	private Group modelsGroup;

	public ServoyPilotPreferencePage() {
		super(GRID);
		setDescription("Configure the API keys and models used by the Servoy AI Pilot.");
	}

	@Override
	public void init(IWorkbench workbench) {
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		setPreferenceStore(store);
	}

	@Override
	protected void createFieldEditors() {
		defaultModelEditor = new DynamicComboFieldEditor(PreferenceConstants.DEFAULT_MODEL, "Default Model:",
				KEY_REQUIRED_PLACEHOLDER, getFieldEditorParent());
		addField(defaultModelEditor);
		constrainFieldEditor(defaultModelEditor, getFieldEditorParent());

		Label spacer = new Label(getFieldEditorParent(), SWT.NONE);
		GridData spacerData = new GridData(GridData.FILL_HORIZONTAL);
		spacerData.horizontalSpan = 2;
		spacerData.heightHint = 10;
		spacer.setLayoutData(spacerData);

		modelsGroup = new Group(getFieldEditorParent(), SWT.NONE);
		modelsGroup.setText("Models");
		GridData groupData = new GridData(GridData.FILL_HORIZONTAL);
		groupData.horizontalSpan = 2;
		modelsGroup.setLayoutData(groupData);

		openAiKeyEditor = new StringFieldEditor(PreferenceConstants.OPENAI_API_KEY, "OpenAI API Key:", modelsGroup);
		addField(openAiKeyEditor);
		constrainFieldEditor(openAiKeyEditor, modelsGroup);
		openAiModelEditor = new DynamicComboFieldEditor(PreferenceConstants.OPENAI_MODEL, "OpenAI Model:",
				KEY_REQUIRED_PLACEHOLDER, modelsGroup);
		addField(openAiModelEditor);
		constrainFieldEditor(openAiModelEditor, modelsGroup);

		geminiKeyEditor = new StringFieldEditor(PreferenceConstants.GEMINI_API_KEY, "Gemini API Key:", modelsGroup);
		addField(geminiKeyEditor);
		constrainFieldEditor(geminiKeyEditor, modelsGroup);
		geminiModelEditor = new DynamicComboFieldEditor(PreferenceConstants.GEMINI_MODEL, "Gemini Model:",
				KEY_REQUIRED_PLACEHOLDER, modelsGroup);
		addField(geminiModelEditor);
		constrainFieldEditor(geminiModelEditor, modelsGroup);

		GridLayout groupLayout = modelsGroup.getLayout() instanceof GridLayout ? (GridLayout) modelsGroup.getLayout()
				: new GridLayout(2, false);
		groupLayout.marginWidth = 10;
		groupLayout.marginHeight = 10;
		modelsGroup.setLayout(groupLayout);

		GridLayout parentLayout = getFieldEditorParent().getLayout() instanceof GridLayout
				? (GridLayout) getFieldEditorParent().getLayout()
				: new GridLayout(2, false);
		parentLayout.marginWidth = 10;
		parentLayout.marginHeight = 10;

		IPreferenceStore store = getPreferenceStore();
		refreshOpenAiModels(store.getString(PreferenceConstants.OPENAI_API_KEY));
		refreshGeminiModels(store.getString(PreferenceConstants.GEMINI_API_KEY));
		updateDefaultModelOptions();
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		super.propertyChange(event);
		if (event.getSource() == geminiKeyEditor) {
			refreshGeminiModels((String) event.getNewValue());
		} else if (event.getSource() == openAiKeyEditor) {
			refreshOpenAiModels((String) event.getNewValue());
		}
		if (event.getSource() != defaultModelEditor) {
			updateDefaultModelOptions();
		}
		Activator.getDefault().clearModels();
	}

	@Override
	public void dispose() {
		if (openAiKeyEditor != null) {
			openAiKeyEditor.setPropertyChangeListener(null);
		}
		if (geminiKeyEditor != null) {
			geminiKeyEditor.setPropertyChangeListener(null);
		}
		super.dispose();
	}

	private void refreshOpenAiModels(String apiKey) {
		updateModelEditor(openAiModelEditor, PreferenceConstants.OPENAI_MODEL, apiKey, AIModelTools::getOpenAIModels,
				ModelKind.OPENAI);
	}

	private void refreshGeminiModels(String apiKey) {
		updateModelEditor(geminiModelEditor, PreferenceConstants.GEMINI_MODEL, apiKey, AIModelTools::getGeminiModels,
				ModelKind.GEMINI);
	}

	private void updateModelEditor(DynamicComboFieldEditor editor, String preferenceKey, String apiKey,
			Function<String, List<ModelDescription>> loader, ModelKind source) {
		boolean hasApiKey = hasApiKey(apiKey);
		editor.setEnabled(hasApiKey, modelsGroup);
		if (!hasApiKey) {
			editor.updateOptions(KEY_REQUIRED_PLACEHOLDER);
			getPreferenceStore().setValue(preferenceKey, "");
			editor.load();
			updateDefaultModelOptions();
			return;
		}

		editor.updateOptions(LOADING_PLACEHOLDER);
		editor.load();
		List<ModelDescription> models = loader.apply(apiKey);
		if (source == ModelKind.OPENAI) {
			openAiModels = models;
		} else {
			geminiModels = models;
		}
		Display.getDefault().asyncExec(() -> applyModelEntries(editor, preferenceKey, models));
	}

	private void applyModelEntries(DynamicComboFieldEditor editor, String preferenceKey,
			List<ModelDescription> models) {
		if (editor.isDisposed()) {
			return;
		}

		String[][] entries = models.isEmpty() ? NO_MODELS_PLACEHOLDER : toEntries(models);
		editor.updateOptions(entries);

		IPreferenceStore store = getPreferenceStore();
		String storedValue = store.getString(preferenceKey);
		if (!contains(entries, storedValue)) {
			String fallback = models.isEmpty() ? "" : models.get(0).name();
			store.setValue(preferenceKey, fallback);
		}

		editor.load();
		updateDefaultModelOptions();
	}

	private void updateDefaultModelOptions() {
		List<String[]> options = new ArrayList<>();
		addConfiguredModel(options, ModelKind.OPENAI, openAiModelEditor.getValue(), openAiModels);
		addConfiguredModel(options, ModelKind.GEMINI, geminiModelEditor.getValue(), geminiModels);

		boolean hasOptions = !options.isEmpty();
		defaultModelEditor.setEnabled(hasOptions, getFieldEditorParent());
		if (hasOptions) {
			defaultModelEditor.updateOptions(options.toArray(new String[0][0]));
		} else {
			defaultModelEditor.updateOptions(KEY_REQUIRED_PLACEHOLDER);
			getPreferenceStore().setValue(PreferenceConstants.DEFAULT_MODEL, "");
		}
		defaultModelEditor.load();
	}

	private void addConfiguredModel(List<String[]> options, ModelKind source, String currentValue,
			List<ModelDescription> models) {
		if (currentValue == null || currentValue.isBlank()) {
			return;
		}

		String displayName = currentValue;
		for (ModelDescription model : models) {
			if (currentValue.equals(model.name())) {
				displayName = model.displayName();
				break;
			}
		}

		String label = source.toString() + " - " + displayName;
		String value = source.name();
		options.add(new String[] { label, value });
	}

	private static String[][] toEntries(List<ModelDescription> models) {
		return models.stream().sorted(Comparator.comparing(ModelDescription::displayName))
				.map(model -> new String[] { model.displayName(), model.name() }).toArray(String[][]::new);
	}

	private static boolean contains(String[][] entries, String value) {
		if (value == null) {
			return false;
		}
		for (String[] entry : entries) {
			if (entry.length >= 2 && value.equals(entry[1])) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasApiKey(String apiKey) {
		return apiKey != null && !apiKey.isBlank();
	}

	private void constrainFieldEditor(FieldEditor editor, Composite controlParent) {
		Control control = null;
		if (editor instanceof StringFieldEditor textEditor) {
			control = textEditor.getTextControl(controlParent);
		} else if (editor instanceof DynamicComboFieldEditor comboEditor) {
			control = comboEditor.getComboControl();
		}

		if (control == null) {
			return;
		}

		GridData data = new GridData(GridData.FILL_HORIZONTAL);
		data.widthHint = FIELD_WIDTH_HINT;
		data.grabExcessHorizontalSpace = true;
		data.horizontalAlignment = GridData.FILL;
		data.horizontalIndent = FIELD_HORIZONTAL_INDENT;
		control.setLayoutData(data);
	}
}