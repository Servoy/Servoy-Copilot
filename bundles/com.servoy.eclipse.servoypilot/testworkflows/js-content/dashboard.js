/**
 * Total number of customers in the system.
 *
 * @type {Number}
 *
 * @properties={typeid:35,uuid:"1C561E64-58D6-499E-9D81-63180A7F43E3",variableType:8}
 */
var totalCustomers = 0;

/**
 * @type {Number}
 *
 * @properties={typeid:35,uuid:"6CEF9BA1-DF2F-49BC-9471-56D7F994562D",variableType:8}
 */
var totalOrders = 0;

/**
 * @type {Date}
 *
 * @properties={typeid:35,uuid:"AD8921BD-BAA4-496E-9EC2-AC352D7B5F54",variableType:93}
 */
var lastRefreshed = null;

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"1765F8C3-067C-4111-847A-7CF9C5A04001"}
 */
function onLoad(event) {
	refreshStats();
}

/**
 * TODO generated, please specify type and doc for the params
 * @param firstShow
 * @param event
 *
 * @properties={typeid:24,uuid:"010E04AC-F794-4DAE-A211-E978EBF062AF"}
 */
function onShow(firstShow, event) {
	if (!firstShow) {
		refreshStats();
	}
}

/**
 * @properties={typeid:24,uuid:"C3D977C7-25DC-4348-99B3-DCDC087A0BBF"}
 */
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

/**
 * TODO generated, please specify type and doc for the params
 * @param value
 * @param label
 *
 * @properties={typeid:24,uuid:"5E15CF9C-2E35-49FD-ADC1-776423BE724B"}
 */
function formatSummary(value, label) {
	var formatted = scopes.utils.formatCurrency(value, '');
	return label + ': ' + formatted;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"AA7E22C4-35E4-4FB3-A7F8-3BE277727FC9"}
 */
function onActionRefresh(event) {
	refreshStats();
	scopes.globals.showMessage('Dashboard refreshed on ' + scopes.utils.formatDate(lastRefreshed), 'Refresh');
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"5B97972B-EC17-461D-B0EE-C1ADAF3AC47A"}
 */
function onActionGoToCustomers(event) {
	scopes.globals.showForm(forms.customerList);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"E7A3E1F4-9131-4531-9584-D144426FE459"}
 */
function onActionGoToOrders(event) {
	scopes.globals.showForm(forms.orderList);
}