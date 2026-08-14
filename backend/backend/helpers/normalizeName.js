const DIACRITICS_REGEX = new RegExp('[̀-ͯ]', 'g');

function normalizeName(input) {
  if (!input) return '';
  return String(input)
    .normalize('NFKD')
    .replace(DIACRITICS_REGEX, '')
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeTvgId(input) {
  return input ? String(input).trim().toLowerCase() : '';
}

module.exports = { normalizeName, normalizeTvgId };
