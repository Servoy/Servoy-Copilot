package com.servoy.eclipse.servoypilot.preferences; // Use your package

import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;

/**
 * A ComboFieldEditor that allows updating the options dynamically.
 */
public class DynamicComboFieldEditor extends FieldEditor {

    private Combo fCombo;
    private String fValue;
    private String[][] fEntryNamesAndValues;

    public DynamicComboFieldEditor(String name, String labelText, Composite parent) {
        init(name, labelText);
        // Default to empty, can be set later
        this.fEntryNamesAndValues = new String[0][2];
        createControl(parent);
    }

    public DynamicComboFieldEditor(String name, String labelText, String[][] entryNamesAndValues, Composite parent) {
        init(name, labelText);
        this.fEntryNamesAndValues = entryNamesAndValues;
        createControl(parent);
    }
    
    public boolean isDisposed() {
    	return fCombo == null || fCombo.isDisposed();
    }

    /**
     * Update the combo options dynamically.
     * @param newOptions String[][] { { "Label", "Value" }, ... }
     */
    public void updateOptions(String[][] newOptions) {
        this.fEntryNamesAndValues = newOptions;
        
        if (fCombo != null && !fCombo.isDisposed()) {
            // 1. Remember current value to try and restore it
            String oldValue = fValue;
            
            // 2. Update the SWT widget
            fCombo.removeAll();
            for (String[] entry : fEntryNamesAndValues) {
                fCombo.add(entry[0]);
            }
            
            // 3. Try to re-select the previous value if it exists in new options
            fValue = oldValue;
            updateComboForValue(fValue);
        }
    }

    @Override
    protected void adjustForNumColumns(int numColumns) {
        GridData gd = (GridData) fCombo.getLayoutData();
        gd.horizontalSpan = numColumns - 1;
        gd.grabExcessHorizontalSpace = true;
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        getLabelControl(parent);

        fCombo = new Combo(parent, SWT.READ_ONLY);
        GridData gd = new GridData();
        gd.horizontalSpan = numColumns - 1;
        gd.horizontalAlignment = GridData.FILL;
        gd.grabExcessHorizontalSpace = true;
        fCombo.setLayoutData(gd);
        
        // Populate initial items
        for (String[] entry : fEntryNamesAndValues) {
            fCombo.add(entry[0]);
        }
        
        fCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent evt) {
                String oldValue = fValue;
                String name = fCombo.getText();
                fValue = getValueForName(name);
                setPresentsDefaultValue(false);
                fireValueChanged(VALUE, oldValue, fValue);
            }
        });
    }

    @Override
    protected void doLoad() {
        updateComboForValue(getPreferenceStore().getString(getPreferenceName()));
    }

    @Override
    protected void doLoadDefault() {
        updateComboForValue(getPreferenceStore().getDefaultString(getPreferenceName()));
    }

    @Override
    protected void doStore() {
        if (fValue == null) {
            getPreferenceStore().setToDefault(getPreferenceName());
            return;
        }
        getPreferenceStore().setValue(getPreferenceName(), fValue);
    }

    @Override
    public int getNumberOfControls() {
        return 2;
    }

    /* Helper: Sets the combo selection based on the storage value */
    private void updateComboForValue(String value) {
        fValue = value;
        if (fCombo == null || fCombo.isDisposed()) return;
        
        for (int i = 0; i < fEntryNamesAndValues.length; i++) {
            if (fEntryNamesAndValues[i][1].equals(value)) {
                fCombo.setText(fEntryNamesAndValues[i][0]);
                return;
            }
        }
        // If value not found, deselect
        fCombo.deselectAll();
    }

    /* Helper: lookup value by label */
    private String getValueForName(String name) {
        for (int i = 0; i < fEntryNamesAndValues.length; i++) {
            String[] entry = fEntryNamesAndValues[i];
            if (name.equals(entry[0])) {
                return entry[1];
            }
        }
        return fEntryNamesAndValues.length > 0 ? fEntryNamesAndValues[0][1] : "";
    }

    public Combo getComboControl() {
        return fCombo;
    }
    
    public String getValue() {
		return fValue;
	}
}
