/**
 * Whether the current record is a new (unsaved) record.
 *
 * @type {Boolean}
 *
 * @properties={typeid:35,uuid:"FFBA1256-314F-40CF-ABFF-172CA0AFFD0B",variableType:-4}
 */
var isNewRecord = false;

/**
 * @type {Array}
 *
 * @properties={typeid:35,uuid:"D06B48CB-7600-4308-B350-0EFA32F83866",variableType:-4}
 */
var validationErrors = null;

/**
 * @type {String}
 *
 * @properties={typeid:35,uuid:"EEA42B15-266C-45FE-B75E-6DA4D2379075"}
 */
var originalCompanyName = null;

/**
 * Callback method when the form is loaded.
 *
 * @param {JSEvent} event - The event that triggered the action
 *
 * @properties={typeid:24,uuid:"9A21DC95-EAB7-48E2-9C1F-A7DD8F8406A3"}
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
 *
 * @properties={typeid:24,uuid:"A9ADAFDD-131C-4B6A-BF12-798643F139F7"}
 */
function onShow(firstShow, event) {
	updateUI();
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"2604EFBC-4654-4A08-A059-99F77781C31A"}
 */
function onActionSave(event) {
	if (save()) {
		scopes.globals.showForm(forms.customerList);
	}
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"1ACE1C30-9D45-441F-9B8D-970AF7D6F094"}
 */
function onActionCancel(event) {
	databaseManager.rollbackEditedRecords();
	isNewRecord = false;
	validationErrors = [];
	scopes.globals.showForm(forms.customerList);
}

/**
 * @properties={typeid:24,uuid:"71AF6837-5BB5-4311-8F54-8A07CE0F5B5F"}
 */
function save() {
	validationErrors = validate();
	if (validationErrors && validationErrors.length > 0) {
		var msg = scopes.utils.buildErrorMessage(validationErrors);
		scopes.globals.showMessage(msg, 'Validation Errors');
		return false;
	}
	return databaseManager.saveData();
}

/**
 * @properties={typeid:24,uuid:"B6DD06D5-47BF-4499-97AE-D987665225C4"}
 */
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

/**
 * @properties={typeid:24,uuid:"7C72222B-4B4E-449A-8611-6F5D9398BB91"}
 */
function updateUI() {
	if (isNewRecord) {
		elements.titleLabel.text = 'New Customer';
	} else {
		var name = scopes.utils.truncateText(foundset.company_name, 40);
		elements.titleLabel.text = 'Edit: ' + name;
	}
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"63CAF8CF-D98E-4D38-9EE7-F2EE54A843DF"}
 */
function onHide(event) {
	validationErrors = [];
	return true;
}