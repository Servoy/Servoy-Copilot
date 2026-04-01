/**
 * @type {JSRecord}
 *
 * @properties={typeid:35,uuid:"340B1982-500A-4728-9475-E84263B88F59",variableType:-4}
 */
var currentCustomer = null;

/**
 * TODO generated, please specify type and doc for the params
 * @param firstShow
 * @param event
 *
 * @properties={typeid:24,uuid:"14BED867-1AC0-4C52-ACEC-CD62D5CC07DC"}
 */
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

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"8E37BF5B-BEE4-4FD7-8403-00EBFDC9597C"}
 */
function onRecordSelection(event) {
	application.output('Order selected: ' + foundset.orderid, LOGGINGLEVEL.DEBUG);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param foundsetindex
 * @param columnindex
 * @param record
 * @param event
 *
 * @properties={typeid:24,uuid:"DA6C05C0-057A-43ED-8051-175EA4007B7F"}
 */
function onCellDoubleClick(foundsetindex, columnindex, record, event) {
	var col = elements.table.getColumn(columnindex);
	if (col && col.id === 'customer') {
		scopes.globals.showForm(forms.customerEdit, record.orders_to_customers.getSelectedRecord());
	} else {
		scopes.globals.showForm(forms.customerList);
	}
}

/**
 * TODO generated, please specify type and doc for the params
 * @param query
 * @param dataprovider
 * @param operator
 * @param values
 * @param filter
 *
 * @properties={typeid:24,uuid:"63D8459A-939F-4243-B55E-BEF656EC93AE"}
 */
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

/**
 * TODO generated, please specify type and doc for the params
 * @param event
 *
 * @properties={typeid:24,uuid:"D0D328F6-823E-484B-BE11-1A4D435B7D63"}
 */
function onActionBack(event) {
	scopes.globals.showForm(forms.customerList);
}