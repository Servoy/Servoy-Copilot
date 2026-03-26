/**
 * Default datasource for customer data.
 *
 * @private
 * @type {String}
 */
var CUSTOMER_DATASOURCE = 'db:/example_data/customers';

/**
 * Default datasource for order data.
 *
 * @private
 * @type {String}
 */
var ORDER_DATASOURCE = 'db:/example_data/orders';

/**
 * @type {JSDataSet}
 */
var lastQueryResult = null;

/**
 * @type {Number}
 */
var lastRecordCount = 0;

/**
 * Gets a record from a foundset at the given index.
 *
 * @param {JSFoundSet} foundset - The foundset to read from
 * @param {Number} index - 1-based record index
 * @return {JSRecord} The record at the given index, or null if out of bounds
 */
function getRecord(foundset, index) {
	if (foundset && index >= 1 && index <= foundset.getSize()) {
		return foundset.getRecord(index);
	}
	return null;
}

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
 */
function saveRecord(record) {
	if (record) {
		return databaseManager.saveData(record);
	}
	return false;
}

function buildQuery(datasource) {
	var query = databaseManager.createSelect(datasource);
	return query;
}

function getDataSet(query) {
	if (query) {
		var result = databaseManager.getDataSetByQuery(query, MAX_RECORDS);
		lastQueryResult = result;
		return result;
	}
	return null;
}

function countRecords(foundset) {
	if (foundset) {
		return foundset.getSize();
	}
	return 0;
}