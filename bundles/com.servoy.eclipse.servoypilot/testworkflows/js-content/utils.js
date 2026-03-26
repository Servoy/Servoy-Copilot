/**
 * Default date display format.
 *
 * @private
 * @type {String}
 */
var DEFAULT_DATE_FORMAT = 'dd/MM/yyyy';

/**
 * Default currency symbol.
 *
 * @private
 * @type {String}
 */
var DEFAULT_CURRENCY_SYMBOL = '$';

function formatDate(date, format) {
	if (!date) return '';
	var fmt = format ? format : DEFAULT_DATE_FORMAT;
	return utils.dateFormat(date, fmt);
}

function formatCurrency(value, symbol) {
	if (value === null || value === undefined) return '';
	var sym = symbol ? symbol : DEFAULT_CURRENCY_SYMBOL;
	return sym + utils.numberFormat(value, '###,##0.00');
}

function isEmptyString(value) {
	if (value === null || value === undefined) return true;
	return String(value).trim().length === 0;
}

function truncateText(text, maxLength) {
	if (!text) return '';
	if (text.length <= maxLength) return text;
	return text.substring(0, maxLength) + '...';
}

function parseNumber(value) {
	if (value === null || value === undefined || value === '') return 0;
	var parsed = parseFloat(String(value).replace(/[^0-9.-]/g, ''));
	return isNaN(parsed) ? 0 : parsed;
}

function buildErrorMessage(errors) {
	if (!errors || !errors.length) return '';
	var result = '';
	for (var i = 0; i < errors.length; i++) {
		result += '- ' + errors[i] + '\n';
	}
	return result.trim();
}