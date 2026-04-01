/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"F7A22E91-FCAC-4039-B561-E274BE8933A7"}
 */
function onLoad(event) {
	scopes.globals.clearState();
	application.output('mainNav loaded.', LOGGINGLEVEL.DEBUG);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param firstShow
 * @param event
 *
 * @properties={typeid:24,uuid:"62907B92-8132-4D8E-ABD3-8553ACB0C323"}
 */
function onShow(firstShow, event) {
	if (firstShow) {
		application.output('mainNav shown for first time.', LOGGINGLEVEL.DEBUG);
	}
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"E17C0AF1-6859-4749-922B-8295781D8B4D"}
 */
function onActionCustomers(event) {
	scopes.globals.showForm(forms.customerList);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"7F52A3AE-C6E5-44FC-8437-CBC54F3317EA"}
 */
function onActionOrders(event) {
	scopes.globals.showForm(forms.orderList);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"156B3E9C-4934-47BE-B834-5D46BB0622BD"}
 */
function onActionDashboard(event) {
	scopes.globals.showForm(forms.dashboard);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"AFB034D3-141A-447B-A627-010D5B7EDB81"}
 */
function onHide(event) {
	return true;
}