/**
 * The currently selected customer record.
 *
 * @type {JSRecord}
 *
 * @properties={typeid:35,uuid:"95D87293-8F2E-4796-9458-C8BAB32FDB08",variableType:-4}
 */
var selectedCustomer = null;

/**
 * @type {String}
 *
 * @properties={typeid:35,uuid:"05318336-7300-462D-9360-AAA7585DA399"}
 */
var filterText = null;

/**
 * Callback method when the form is loaded.
 *
 * @param {JSEvent} event - The event that triggered the action
 *
 * @properties={typeid:24,uuid:"FD68666A-EF7C-4F85-8D9E-E361E0368564"}
 */
function onLoad(event) {
	foundset.loadAllRecords();
	application.output('customerList loaded, ' + foundset.getSize() + ' records.', LOGGINGLEVEL.DEBUG);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"362E1778-6D88-43F6-8917-C0CA37400B8B"}
 */
function onRecordSelection(event) {
	selectedCustomer = scopes.dataUtils.getRecord(foundset, foundset.getSelectedIndex());
	scopes.globals.activeCustomer = selectedCustomer;
}

/**
 * Handles the search action triggered by the user.
 *
 * @param {JSEvent} event - The event that triggered the action
 *
 * @properties={typeid:24,uuid:"35205324-9460-482F-8318-0A5CCF392AC3"}
 */
function onSearchAction(event) {
	if (scopes.utils.isEmptyString(filterText)) {
		foundset.loadAllRecords();
	} else {
		var query = databaseManager.createSelect('db:/example_data/customer');
		query.where.add(query.columns.companyname.lower.like('%' + filterText.toLowerCase() + '%'));
		foundset.loadRecords(query);
	}
	application.output('Search applied, ' + foundset.getSize() + ' results.', LOGGINGLEVEL.DEBUG);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"5FDB7B5B-6D8D-42DE-91B1-0D83C760FA08"}
 */
function onActionEdit(event) {
	if (selectedCustomer) {
		scopes.globals.showForm(forms.customerEdit, selectedCustomer);
	} else {
		scopes.globals.showMessage('Please select a customer first.', 'No Selection');
	}
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"A00BF866-6B7A-4778-A0BD-F9248528093A"}
 */
function onActionNewRecord(event) {
	foundset.newRecord();
	scopes.globals.showForm(forms.customerEdit);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"4D48D8BF-908A-491F-9659-EB2439495A8C"}
 */
function onActionBack(event) {
	scopes.globals.showForm(forms.mainNav);
}