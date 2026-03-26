/**
 * @type {JSRecord}
 */
var currentCustomer = null;

function onShow(firstShow, event) {
	currentCustomer = scopes.globals.activeCustomer;
	if (currentCustomer) {
		var query = scopes.dataUtils.buildQuery(ORDER_DATASOURCE);
		query.where.add(query.columns.customerid.eq(currentCustomer.customerid));
		foundset.loadRecords(query);
		application.output('orderList loaded ' + foundset.getSize() + ' orders for customer ' + currentCustomer.company_name, LOGGINGLEVEL.DEBUG);
	} else {
		foundset.loadAllRecords();
	}
}

function onRecordSelection(event) {
	application.output('Order selected: ' + foundset.orderid, LOGGINGLEVEL.DEBUG);
}

function onCellDoubleClick(foundsetindex, columnindex, record, event) {
	var col = elements.table.getColumn(columnindex);
	if (col && col.id === 'customer') {
		scopes.globals.showForm(forms.customerEdit, record.orders_to_customers.getSelectedRecord());
	} else {
		scopes.globals.showForm(forms.customerList);
	}
}

function onFilterQueryCondition(query, dataprovider, operator, values, filter) {
	if (!values || !values.length) return true;

	if (dataprovider === 'orderStatus') {
		var or = query.or;
		if (values.indexOf('new') > -1) {
			or.add(query.columns.requireddate.isNull);
		}
		if (values.indexOf('planned') > -1) {
			or.add(query.and.add(query.columns.requireddate.not.isNull).add(query.columns.shippeddate.isNull));
		}
		if (values.indexOf('completed') > -1) {
			or.add(query.and.add(query.columns.requireddate.not.isNull).add(query.columns.shippeddate.not.isNull));
		}
		query.where.add(or);
		return false;
	}
	return true;
}

function onActionBack(event) {
	scopes.globals.showForm(forms.customerList);
}