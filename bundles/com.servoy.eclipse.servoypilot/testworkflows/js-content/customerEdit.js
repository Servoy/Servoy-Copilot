/**
 * Whether the current record is a new (unsaved) record.
 *
 * @type {Boolean}
 */
var isNewRecord = false;

/**
 * @type {Array}
 */
var validationErrors = null;

/**
 * @type {String}
 */
var originalCompanyName = null;

/**
 * Callback method when the form is loaded.
 *
 * @param {JSEvent} event - The event that triggered the action
 */
function onLoad(event) {
	isNewRecord = databaseManager.hasNewRecords(foundset);
	originalCompanyName = foundset.company_name;
	validationErrors = [];
	application.output('customerEdit loaded. isNewRecord=' + isNewRecord, LOGGINGLEVEL.DEBUG);
}

/**
 * Callback method when form is shown.
 *
 * @param {Boolean} firstShow - True if form is shown for the first time
 * @param {JSEvent} event - The event that triggered the action
 */
function onShow(firstShow, event) {
	updateUI();
}

function onActionSave(event) {
	if (save()) {
		scopes.globals.showForm(forms.customerList);
	}
}

function onActionCancel(event) {
	databaseManager.rollbackEditedRecords();
	isNewRecord = false;
	validationErrors = [];
	scopes.globals.showForm(forms.customerList);
}

function save() {
	validationErrors = validate();
	if (validationErrors && validationErrors.length > 0) {
		var msg = scopes.utils.buildErrorMessage(validationErrors);
		scopes.globals.showMessage(msg, 'Validation Errors');
		return false;
	}
	return databaseManager.saveData();
}

function validate() {
	var errors = [];
	if (scopes.utils.isEmptyString(foundset.company_name)) {
		errors.push('Company name is required.');
	}
	if (scopes.utils.isEmptyString(foundset.city)) {
		errors.push('City is required.');
	}
	if (foundset.phone && foundset.phone.length > 50) {
		errors.push('Phone number is too long (max 50 characters).');
	}
	return errors;
}

function updateUI() {
	if (isNewRecord) {
		elements.titleLabel.text = 'New Customer';
	} else {
		var name = scopes.utils.truncateText(foundset.company_name, 40);
		elements.titleLabel.text = 'Edit: ' + name;
	}
}

function onHide(event) {
	validationErrors = [];
	return true;
}