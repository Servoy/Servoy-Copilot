/**
 * The currently selected customer record.
 *
 * @type {JSRecord}
 */
var selectedCustomer = null;

/**
 * @type {String}
 */
var filterText = null;

/**
 * Callback method when the form is loaded.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onLoad(event) {
	foundset.loadAllRecords();
	application.output('customerList loaded, ' + foundset.getSize() + ' records.', LOGGINGLEVEL.DEBUG);
}

function onRecordSelection(event) {
	selectedCustomer = scopes.dataUtils.getRecord(foundset, foundset.getSelectedIndex());
	scopes.globals.activeCustomer = selectedCustomer;
}

/**
 * Handles the search action triggered by the user.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onSearchAction(event) {
	if (scopes.utils.isEmptyString(filterText)) {
		foundset.loadAllRecords();
	} else {
		var query = databaseManager.createSelect(CUSTOMER_DATASOURCE);
		query.where.add(query.columns.company_name.lower.like('%' + filterText.toLowerCase() + '%'));
		foundset.loadRecords(query);
	}
	application.output('Search applied, ' + foundset.getSize() + ' results.', LOGGINGLEVEL.DEBUG);
}

function onActionEdit(event) {
	if (selectedCustomer) {
		scopes.globals.showForm(forms.customerEdit, selectedCustomer);
	} else {
		scopes.globals.showMessage('Please select a customer first.', 'No Selection');
	}
}

function onActionNewRecord(event) {
	foundset.newRecord();
	scopes.globals.showForm(forms.customerEdit);
}

function onActionBack(event) {
	scopes.globals.showForm(forms.mainNav);
}