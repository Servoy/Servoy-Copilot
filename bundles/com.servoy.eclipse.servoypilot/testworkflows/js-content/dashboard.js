/**
 * Total number of customers in the system.
 *
 * @type {Number}
 */
var totalCustomers = 0;

/**
 * @type {Number}
 */
var totalOrders = 0;

/**
 * @type {Date}
 */
var lastRefreshed = null;

function onLoad(event) {
	refreshStats();
}

function onShow(firstShow, event) {
	if (!firstShow) {
		refreshStats();
	}
}

function refreshStats() {
	var customerDs = scopes.dataUtils.loadRecords('db:/example_data/customers', null);
	totalCustomers = scopes.dataUtils.countRecords(customerDs);

	var orderQuery = scopes.dataUtils.buildQuery('db:/example_data/orders');
	var orderData = scopes.dataUtils.getDataSet(orderQuery);
	totalOrders = orderData ? orderData.getMaxRowIndex() : 0;

	lastRefreshed = new Date();

	application.output(
		'Dashboard refreshed: ' + totalCustomers + ' customers, ' + totalOrders + ' orders.',
		LOGGINGLEVEL.DEBUG
	);
}

function formatSummary(value, label) {
	var formatted = scopes.utils.formatCurrency(value, '');
	return label + ': ' + formatted;
}

function onActionRefresh(event) {
	refreshStats();
	scopes.globals.showMessage('Dashboard refreshed on ' + scopes.utils.formatDate(lastRefreshed), 'Refresh');
}

function onActionGoToCustomers(event) {
	scopes.globals.showForm(forms.customerList);
}

function onActionGoToOrders(event) {
	scopes.globals.showForm(forms.orderList);
}