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

const HTML_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
};

// Certains outils tiers (scanners de chaînes, exports XML/EPG) livrent des noms
// HTML-échappés ("MAISON &amp; TRAVAUX") alors que la playlist M3U réelle côté
// Android ne l'est pas ("MAISON & TRAVAUX") — sans décodage, le fallback par nom
// échoue silencieusement. Décodé une seule fois, à l'import, avant stockage.
function decodeHtmlEntities(input) {
  if (!input) return '';
  return String(input).replace(/&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);/g, (match, ent) => {
    if (ent[0] === '#') {
      const code = ent[1] === 'x' || ent[1] === 'X'
        ? parseInt(ent.slice(2), 16)
        : parseInt(ent.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : match;
    }
    return HTML_ENTITIES[ent.toLowerCase()] ?? match;
  });
}

module.exports = { normalizeName, normalizeTvgId, decodeHtmlEntities };
