/**
 * Default date display format.
 *
 * @private
 * @type {String}
 *
 * @properties={typeid:35,uuid:"CAB89012-5FF6-4E7A-8B5F-AE10EC3E03D4"}
 */
var DEFAULT_DATE_FORMAT = 'dd/MM/yyyy';

/**
 * Default currency symbol.
 *
 * @private
 * @type {String}
 *
 * @properties={typeid:35,uuid:"E539EA87-9213-4B20-847A-4C23EBB7E270"}
 */
var DEFAULT_CURRENCY_SYMBOL = '$';

/**
 * TODO generated, please specify type and doc for the params
 * @param date
 * @param format
 *
 * @properties={typeid:24,uuid:"A8F5BDA8-D822-41C7-BE87-7727823970BD"}
 */
function formatDate(date, format) {
	if (!date) return '';
	var fmt = format ? format : DEFAULT_DATE_FORMAT;
	return utils.dateFormat(date, fmt);
}

/**
 * TODO generated, please specify type and doc for the params
 * @param value
 * @param symbol
 *
 * @properties={typeid:24,uuid:"71C32C8E-826B-4466-865A-89BA98F2F262"}
 */
function formatCurrency(value, symbol) {
	if (value === null || value === undefined) return '';
	var sym = symbol ? symbol : DEFAULT_CURRENCY_SYMBOL;
	return sym + utils.numberFormat(value, '###,##0.00');
}

/**
 * TODO generated, please specify type and doc for the params
 * @param value
 *
 * @properties={typeid:24,uuid:"CB4B872B-3A5A-4189-B657-3C3B3C6E4CD0"}
 */
function isEmptyString(value) {
	if (value === null || value === undefined) return true;
	return String(value).trim().length === 0;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param text
 * @param maxLength
 *
 * @properties={typeid:24,uuid:"40912BC6-21D8-4BE0-97B9-3A1700892665"}
 */
function truncateText(text, maxLength) {
	if (!text) return '';
	if (text.length <= maxLength) return text;
	return text.substring(0, maxLength) + '...';
}

/**
 * TODO generated, please specify type and doc for the params
 * @param value
 *
 * @properties={typeid:24,uuid:"4D8BAE73-1BB8-4668-BB0D-45FFB8CC242B"}
 */
function parseNumber(value) {
	if (value === null || value === undefined || value === '') return 0;
	var parsed = parseFloat(String(value).replace(/[^0-9.-]/g, ''));
	return isNaN(parsed) ? 0 : parsed;
}

/**
 * TODO generated, please specify type and doc for the params
 * @param errors
 *
 * @properties={typeid:24,uuid:"0D4080CB-AFA1-4887-A868-5E3A0F8E2180"}
 */
function buildErrorMessage(errors) {
	if (!errors || !errors.length) return '';
	var result = '';
	for (var i = 0; i < errors.length; i++) {
		result += '- ' + errors[i] + '\n';
	}
	return result.trim();
}