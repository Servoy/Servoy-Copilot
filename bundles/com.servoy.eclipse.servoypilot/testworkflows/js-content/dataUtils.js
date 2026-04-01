/**
 * Default datasource for customer data.
 *
 * @private
 * @type {String}
 *
 * @properties={typeid:35,uuid:"5B706835-2CDC-4748-A2AD-2FE8916D400D"}
 */
var CUSTOMER_DATASOURCE = 'db:/example_data/customers';

/**
 * Default datasource for order data.
 *
 * @private
 * @type {String}
 *
 * @properties={typeid:35,uuid:"7A87F37B-0F44-44FB-949B-FF834390DB4B"}
 */
var ORDER_DATASOURCE = 'db:/example_data/orders';

/**
 * @type {JSDataSet}
 *
 * @properties={typeid:35,uuid:"7F2CB942-B81C-4FFE-8833-49F84C00E000",variableType:-4}
 */
var lastQueryResult = null;

/**
 * @type {Number}
 *
 * @properties={typeid:35,uuid:"FB7412B1-DA3F-4A69-B28D-5AAA34AFBC68",variableType:8}
 */
var lastRecordCount = 0;

/**
 * Gets a record from a foundset at the given index.
 *
 * @param {JSFoundSet} foundset - The foundset to read from
 * @param {Number} index - 1-based record index
 * @return {JSRecord} The record at the given index, or null if out of bounds
 *
 * @properties={typeid:24,uuid:"EFDB7823-1959-45B9-9AA0-A9D06D2C95FF"}
 */
function getRecord(foundset, index) {
	if (foundset && index >= 1 && index <= foundset.getSize()) {
		return foundset.getRecord(index);
	}
	return null;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param datasource
 * @param query
 *
 * @properties={typeid:24,uuid:"3261149A-17F4-40A8-8E20-A8F9A3FC552E"}
 */
function loadRecords(datasource, query) {
	var fs = databaseManager.getFoundSet(datasource);
	if (fs) {
		if (query) {
			fs.loadRecords(query);
		} else {
			fs.loadAllRecords();
		}
		lastRecordCount = fs.getSize();
	}
	return fs;
}

/**
 * Saves changes to the given record.
 *
 * @param {JSRecord} record - The record to save
 * @return {Boolean} True if save was successful
 *
 * @properties={typeid:24,uuid:"AC1B3696-AFDA-41D9-A68F-2D794436AB2C"}
 */
function saveRecord(record) {
	if (record) {
		return databaseManager.saveData(record);
	}
	return false;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param datasource
 *
 * @properties={typeid:24,uuid:"FFA17455-D255-4CE1-8E59-A42028118103"}
 */
function buildQuery(datasource) {
	var query = databaseManager.createSelect(datasource);
	return query;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param query
 *
 * @properties={typeid:24,uuid:"89AF78EB-7115-4998-B76E-FB56089B3679"}
 */
function getDataSet(query) {
	if (query) {
		var result = databaseManager.getDataSetByQuery(query, scopes.globals.getMaxRecords());
		lastQueryResult = result;
		return result;
	}
	return null;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param foundset
 *
 * @properties={typeid:24,uuid:"51C98527-8B5A-4C2B-8B3B-6BA26A2EDBA8"}
 */
function countRecords(foundset) {
	if (foundset) {
		return foundset.getSize();
	}
	return 0;
}