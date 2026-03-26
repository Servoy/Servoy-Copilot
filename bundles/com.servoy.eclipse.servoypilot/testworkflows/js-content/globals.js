/**
 * Application version string.
 *
 * @private
 * @type {String}
 */
var APP_VERSION = '1.0.0';

/**
 * Maximum number of records to load in a single foundset.
 *
 * @private
 * @type {Number}
 */
var MAX_RECORDS = 500;

/**
 * @private
 * @type {Boolean}
 */
var isGridConfigured = false;

/**
 * The currently active customer record.
 *
 * @type {JSRecord}
 */
var activeCustomer = null;

/**
 * @type {JSFoundSet}
 */
var activeFoundset = null;

/**
 * @type {String}
 */
var currentUserName = null;

/**
 * Whether the solution has been initialized.
 *
 * @private
 * @type {Boolean}
 */
var initialized = false;

/**
 * Shows a form using simple navigation.
 *
 * @public
 * @param {RuntimeForm} form - The form to show
 * @param {JSRecord} [record] - Optional record to select in the form
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
 */
function showMessage(message, title) {
	var dialogTitle = title ? title : 'Information';
	plugins.dialogs.showInfoDialog(dialogTitle, message);
}

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
 */
function getCurrentUser() {
	if (currentUserName) {
		return currentUserName;
	}
	return security.getUserName() || '';
}

function isInitialized() {
	return initialized;
}

function setInitialized(value) {
	initialized = value;
	application.output('Initialized state set to: ' + value, LOGGINGLEVEL.DEBUG);
}

function onSolutionOpen(arg, queryParams) {
	databaseManager.setAutoSave(false);
	databaseManager.nullColumnValidatorEnabled = false;

	configGrid();

	application.output('svyPilotTest v' + APP_VERSION + ' started.', LOGGINGLEVEL.INFO);
	clearState();
}

/**
 * Callback method when solution is closed.
 */
function onSolutionClose() {
	application.output('svyPilotTest closing.', LOGGINGLEVEL.INFO);
}

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
 */
function getVersion() {
	return APP_VERSION;
}

function getMaxRecords() {
	return MAX_RECORDS;
}
