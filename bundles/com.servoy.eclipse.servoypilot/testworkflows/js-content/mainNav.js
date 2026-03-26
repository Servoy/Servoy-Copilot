function onLoad(event) {
	scopes.globals.clearState();
	application.output('mainNav loaded.', LOGGINGLEVEL.DEBUG);
}

function onShow(firstShow, event) {
	if (firstShow) {
		application.output('mainNav shown for first time.', LOGGINGLEVEL.DEBUG);
	}
}

function onActionCustomers(event) {
	scopes.globals.showForm(forms.customerList);
}

function onActionOrders(event) {
	scopes.globals.showForm(forms.orderList);
}

function onActionDashboard(event) {
	scopes.globals.showForm(forms.dashboard);
}

function onHide(event) {
	return true;
}