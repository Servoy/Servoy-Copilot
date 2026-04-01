/**
 * Application version string.
 *
 * @private
 * @type {String}
 *
 * @properties={typeid:35,uuid:"7E8FCB72-022F-4572-A76B-D87DA9C1B28F"}
 */
var APP_VERSION = '1.0.0';

/**
 * Maximum number of records to load in a single foundset.
 *
 * @private
 * @type {Number}
 *
 * @properties={typeid:35,uuid:"222BE0BF-ABAA-4ADD-8F59-42A486314D5D",variableType:8}
 */
var MAX_RECORDS = 500;

/**
 * @private
 * @type {Boolean}
 *
 * @properties={typeid:35,uuid:"46CDC717-C1EC-4FE2-BF39-9D31235EA011",variableType:-4}
 */
var isGridConfigured = false;

/**
 * The currently active customer record.
 *
 * @type {JSRecord}
 *
 * @properties={typeid:35,uuid:"E17818AF-FA91-46F2-A0D0-8BF72414EA8A",variableType:-4}
 */
var activeCustomer = null;

/**
 * @type {JSFoundSet}
 *
 * @properties={typeid:35,uuid:"DA6AD986-AAFB-4477-B2FB-03BA8C43F01C",variableType:-4}
 */
var activeFoundset = null;

/**
 * @type {String}
 *
 * @properties={typeid:35,uuid:"E0A89B9B-5B12-415A-B712-90145503632D"}
 */
var currentUserName = null;

/**
 * Whether the solution has been initialized.
 *
 * @private
 * @type {Boolean}
 *
 * @properties={typeid:35,uuid:"B345B7CE-5D99-423E-9E28-9FC9B3B3BE1D",variableType:-4}
 */
var initialized = false;

/**
 * Shows a form using simple navigation.
 *
 * @public
 * @param {RuntimeForm} form - The form to show
 * @param {JSRecord} [record] - Optional record to select in the form
 *
 * @properties={typeid:24,uuid:"4BBFDDA6-554B-49F2-B8A5-E65F891ACE14"}
 */
function showForm(form, record) {
	if (record) {
		activeCustomer = record;
	}
	application.showForm(form);
}

/**
 * Displays an info message dialog to the user.
 *
 * @public
 * @param {String} message - The message text to display
 * @param {String} [title] - Optional dialog title
 *
 * @properties={typeid:24,uuid:"B7A7EA20-4900-496B-93F3-0E018D3A4DB8"}
 */
function showMessage(message, title) {
	var dialogTitle = title ? title : 'Information';
	plugins.dialogs.showInfoDialog(dialogTitle, message);
}

/**
 * @properties={typeid:24,uuid:"0CE3A66F-DCC3-416F-80E2-BC9A06C6FF60"}
 */
function clearState() {
	activeCustomer = null;
	activeFoundset = null;
	initialized = false;
	application.output('Global state cleared.', LOGGINGLEVEL.DEBUG);
}

/**
 * Returns the currently logged-in username.
 *
 * @public
 * @return {String} The current username, or empty string if not logged in
 *
 * @properties={typeid:24,uuid:"69D52C75-9CA0-462C-96BD-66AE1CC7651C"}
 */
function getCurrentUser() {
	if (currentUserName) {
		return currentUserName;
	}
	return security.getUserName() || '';
}

/**
 * @properties={typeid:24,uuid:"002044B6-7EB6-43E5-9380-040F73B727CF"}
 */
function isInitialized() {
	return initialized;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param value
 *
 * @properties={typeid:24,uuid:"8E1A95A6-E7D8-477E-B742-F86F45800084"}
 */
function setInitialized(value) {
	initialized = value;
	application.output('Initialized state set to: ' + value, LOGGINGLEVEL.DEBUG);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param arg
 * @param queryParams
 *
 * @properties={typeid:24,uuid:"D8BF45CA-5379-45A7-9C91-6C6138D72C18"}
 */
function onSolutionOpen(arg, queryParams) {
	databaseManager.setAutoSave(false);
	databaseManager.nullColumnValidatorEnabled = false;

	configGrid();

	application.output('svyPilotTest v' + APP_VERSION + ' started.', LOGGINGLEVEL.INFO);
	clearState();
}

/**
 * Callback method when solution is closed.
 *
 * @properties={typeid:24,uuid:"6F7EE496-0882-415E-9C21-0B674BD65B68"}
 */
function onSolutionClose() {
	application.output('svyPilotTest closing.', LOGGINGLEVEL.INFO);
}

/**
 * @properties={typeid:24,uuid:"EF7A7B84-7284-4509-8D3F-266B92D29081"}
 */
function configGrid() {
	if (isGridConfigured) return;

	plugins.ngDataGrid.gridOptions = {
		headerHeight: 10,
		rowHeight: 48
	};

	plugins.ngDataGrid.columnOptions = {
		menuTabs: ['generalMenuTab']
	};

	var toolPanelOptions = plugins.ngDataGrid.createToolPanelConfig();
	toolPanelOptions.suppressColumnFilter = true;
	toolPanelOptions.suppressColumnSelectAll = true;
	toolPanelOptions.suppressRowGroups = true;
	plugins.ngDataGrid.toolPanelConfig = toolPanelOptions;

	isGridConfigured = true;
}

/**
 * Returns the current application version.
 *
 * @public
 * @return {String} The version string
 *
 * @properties={typeid:24,uuid:"76D9FAAE-2045-42B3-B8A9-6A19F746D994"}
 */
function getVersion() {
	return APP_VERSION;
}

/**
 * @properties={typeid:24,uuid:"22F999FD-9BD0-4C0A-8ADA-95F999B1390D"}
 */
function getMaxRecords() {
	return MAX_RECORDS;
}
