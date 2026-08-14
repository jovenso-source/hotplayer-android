const router = require('express').Router();
const { db } = require('../config/database');
const { requireAdmin } = require('../middleware/auth');
const { normalizeName, normalizeTvgId, decodeHtmlEntities } = require('../helpers/normalizeName');

const ANOMALY_FACTOR = 1.5;

// Certains exports tiers (ex: scanners de chaînes) livrent un seul champ "id" préfixé
// par type plutôt que des champs séparés channel_id/tvg_id, ex: "tvg:france2.fr" ou
// "hash:b42819f70603f465b03d2c6b". "hash:" est un identifiant local à l'outil source,
// non reproductible côté Android — il ne doit jamais être utilisé pour le matching,
// seul le fallback par nom s'applique alors.
function parsePrefixedId(raw) {
  if (raw == null) return {};
  const s = String(raw).trim();
  if (!s) return {};
  const m = s.match(/^([a-zA-Z]+):(.+)$/);
  if (!m) return { channelId: s };
  const prefix = m[1].toLowerCase();
  const value = m[2].trim();
  if (!value) return {};
  if (prefix === 'tvg') return { tvgId: value };
  if (prefix === 'hash') return {};
  if (prefix === 'id' || prefix === 'stream' || prefix === 'channel') return { channelId: value };
  return { channelId: s };
}

// Tolérant aux différentes clés racines rencontrées en pratique : { entries: [...] }
// (format admin interne), { channels: [...] } (exemple de la spec), { hidden_candidates:
// [...] } (export HotPlayer Channel Scanner), ou un tableau JSON brut.
function extractEntriesArray(body) {
  if (Array.isArray(body)) return body;
  if (!body || typeof body !== 'object') return null;
  const keys = ['entries', 'channels', 'hidden_candidates', 'candidates', 'items', 'data', 'results'];
  for (const key of keys) {
    if (Array.isArray(body[key])) return body[key];
  }
  return null;
}

function slugify(name) {
  const base = normalizeName(name).replace(/\s+/g, '-');
  return base || 'liste';
}

function generateListKey(name) {
  const base = slugify(name);
  let key = base;
  let n = 2;
  while (db.prepare('SELECT id FROM channel_filter_lists WHERE list_key=?').get(key)) {
    key = `${base}-${n}`;
    n++;
  }
  return key;
}

function normalizeToken(v) {
  return v ? String(v).trim().toUpperCase() : '';
}

function getListOr404(req, res) {
  const list = db.prepare('SELECT * FROM channel_filter_lists WHERE list_key=?').get(req.params.id);
  if (!list) { res.status(404).json({ error: 'Liste introuvable.' }); return null; }
  return list;
}

function bumpGlobalVersion() {
  db.prepare(`UPDATE channel_filter_settings SET global_version=global_version+1, updated_at=datetime('now') WHERE id=1`).run();
}

function serializeList(list) {
  return {
    id: list.list_key,
    name: list.name,
    category: list.category,
    enabled: list.enabled === 1,
    version: list.version,
    hidden_count: list.hidden_count,
    updated_at: list.updated_at,
    created_at: list.created_at,
  };
}

// ── Global settings (killswitch) — déclaré AVANT /:id pour éviter toute collision de route ──

router.get('/settings', requireAdmin, (req, res) => {
  const settings = db.prepare('SELECT * FROM channel_filter_settings WHERE id=1').get();
  return res.json({ enabled: settings.enabled === 1, global_version: settings.global_version });
});

router.patch('/settings', requireAdmin, (req, res) => {
  const { enabled } = req.body || {};
  if (typeof enabled !== 'boolean') return res.status(400).json({ error: 'Champ "enabled" (boolean) requis.' });
  db.prepare(`UPDATE channel_filter_settings SET enabled=?, global_version=global_version+1, updated_at=datetime('now') WHERE id=1`)
    .run(enabled ? 1 : 0);
  return res.json({ message: 'Paramètres mis à jour.' });
});

// ── Listes ─────────────────────────────────────────────────────────────────

router.get('/', requireAdmin, (req, res) => {
  const lists = db.prepare('SELECT * FROM channel_filter_lists ORDER BY created_at DESC').all();
  const settings = db.prepare('SELECT * FROM channel_filter_settings WHERE id=1').get();
  return res.json({
    lists: lists.map(serializeList),
    global: { enabled: settings.enabled === 1, global_version: settings.global_version },
  });
});

router.post('/', requireAdmin, (req, res) => {
  const { name, category } = req.body || {};
  if (!name || !String(name).trim()) return res.status(400).json({ error: 'Nom requis.' });
  if (!category || !String(category).trim()) return res.status(400).json({ error: 'Catégorie requise.' });
  const listKey = generateListKey(name);
  const { lastInsertRowid: id } = db.prepare(
    `INSERT INTO channel_filter_lists (list_key, name, category, enabled, version, hidden_count) VALUES (?,?,?,1,0,0)`
  ).run(listKey, String(name).trim(), String(category).trim());
  bumpGlobalVersion();
  const list = db.prepare('SELECT * FROM channel_filter_lists WHERE id=?').get(id);
  return res.status(201).json(serializeList(list));
});

router.patch('/:id', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  const sets = []; const vals = [];
  if (req.body?.name !== undefined) {
    if (!String(req.body.name).trim()) return res.status(400).json({ error: 'Nom invalide.' });
    sets.push('name=?'); vals.push(String(req.body.name).trim());
  }
  if (req.body?.category !== undefined) {
    if (!String(req.body.category).trim()) return res.status(400).json({ error: 'Catégorie invalide.' });
    sets.push('category=?'); vals.push(String(req.body.category).trim());
  }
  if (!sets.length) return res.status(400).json({ error: 'Rien à mettre à jour.' });
  sets.push(`updated_at=datetime('now')`); vals.push(list.id);
  db.prepare(`UPDATE channel_filter_lists SET ${sets.join(',')} WHERE id=?`).run(...vals);
  return res.json({ message: 'Liste mise à jour.' });
});

router.post('/:id/activate', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  db.prepare(`UPDATE channel_filter_lists SET enabled=1, updated_at=datetime('now') WHERE id=?`).run(list.id);
  bumpGlobalVersion();
  return res.json({ message: 'Liste activée.' });
});

router.post('/:id/deactivate', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  db.prepare(`UPDATE channel_filter_lists SET enabled=0, updated_at=datetime('now') WHERE id=?`).run(list.id);
  bumpGlobalVersion();
  return res.json({ message: 'Liste désactivée.' });
});

router.delete('/:id', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  db.prepare('DELETE FROM channel_filter_lists WHERE id=?').run(list.id);
  bumpGlobalVersion();
  return res.json({ message: 'Liste supprimée.' });
});

router.get('/:id/entries', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  const { search = '', page = 1, limit = 50 } = req.query;
  const offset = (parseInt(page) - 1) * parseInt(limit);
  const params = [list.id]; let where = 'list_id=?';
  if (search) {
    where += ' AND (channel_id LIKE ? OR tvg_id LIKE ? OR name LIKE ?)';
    const s = `%${search}%`;
    params.push(s, s, s);
  }
  const entries = db.prepare(`SELECT id, channel_id, tvg_id, name, source_status, created_at FROM channel_filter_entries WHERE ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`)
    .all(...params, parseInt(limit), offset);
  const { cnt: total } = db.prepare(`SELECT COUNT(*) as cnt FROM channel_filter_entries WHERE ${where}`).get(...params);
  return res.json({ entries, total, page: parseInt(page), pages: Math.ceil(total / limit) || 1 });
});

router.delete('/:id/entries/:entryId', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  const entry = db.prepare('SELECT id FROM channel_filter_entries WHERE id=? AND list_id=?').get(req.params.entryId, list.id);
  if (!entry) return res.status(404).json({ error: 'Chaîne introuvable dans cette liste.' });
  db.prepare('DELETE FROM channel_filter_entries WHERE id=?').run(entry.id);
  const { cnt: hiddenCount } = db.prepare('SELECT COUNT(*) as cnt FROM channel_filter_entries WHERE list_id=?').get(list.id);
  db.prepare(`UPDATE channel_filter_lists SET hidden_count=?, version=version+1, updated_at=datetime('now') WHERE id=?`).run(hiddenCount, list.id);
  bumpGlobalVersion();
  return res.json({ message: 'Chaîne supprimée.', hidden_count: hiddenCount });
});

router.post('/:id/clear', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;
  db.prepare('DELETE FROM channel_filter_entries WHERE list_id=?').run(list.id);
  db.prepare(`UPDATE channel_filter_lists SET hidden_count=0, version=version+1, updated_at=datetime('now') WHERE id=?`).run(list.id);
  bumpGlobalVersion();
  return res.json({ message: 'Liste vidée.' });
});

// ── Import JSON (remplace intégralement le contenu de la liste) ────────────

router.post('/:id/import', requireAdmin, (req, res) => {
  const list = getListOr404(req, res);
  if (!list) return;

  const body = req.body || {};
  const entries = extractEntriesArray(body);
  const confirm = body.confirm;
  if (!Array.isArray(entries) || !entries.length) {
    return res.status(400).json({ error: 'Aucune entrée fournie ou JSON invalide.' });
  }

  const byMatchKey = new Map();
  let ignored = 0;
  let skippedDuplicate = 0;
  const errors = [];

  entries.forEach((row, index) => {
    if (!row || typeof row !== 'object') {
      errors.push({ row: index, reason: 'Entrée invalide (pas un objet).' });
      return;
    }
    let channelId = row.channel_id ?? row.stream_id ?? null;
    let tvgId     = row.tvg_id ?? row['tvg-id'] ?? null;
    if (channelId == null && tvgId == null && row.id != null) {
      const parsed = parsePrefixedId(row.id);
      channelId = parsed.channelId ?? null;
      tvgId     = parsed.tvgId ?? null;
    }
    const nameRaw   = row.name ?? row.channel_name ?? row.title ?? null;
    const name      = nameRaw != null ? decodeHtmlEntities(String(nameRaw)) : null;
    const status    = row.status ?? null;
    const action    = row.action ?? null;

    const shouldHide = normalizeToken(status) === 'INACTIVE' || normalizeToken(action) === 'HIDE';
    if (!shouldHide) { ignored++; return; }

    let matchKey = null;
    if (channelId != null && String(channelId).trim()) {
      matchKey = `id:${String(channelId).trim()}`;
    } else if (tvgId != null && String(tvgId).trim()) {
      matchKey = `tvg:${normalizeTvgId(tvgId)}`;
    } else if (name != null && String(name).trim()) {
      matchKey = `name:${normalizeName(name)}`;
    }

    if (!matchKey) {
      errors.push({ row: index, reason: 'Aucun identifiant exploitable (channel_id/tvg_id/name).' });
      return;
    }

    if (byMatchKey.has(matchKey)) { skippedDuplicate++; return; }

    byMatchKey.set(matchKey, {
      channel_id: channelId != null ? String(channelId).trim() : null,
      tvg_id: tvgId != null ? String(tvgId).trim() : null,
      name: name != null ? String(name).trim() : null,
      match_key: matchKey,
      source_status: status || action || null,
    });
  });

  const created = [...byMatchKey.values()];

  if (created.length === 0) {
    return res.status(400).json({
      error: 'Aucune entrée valide à masquer dans ce fichier.',
      counts: { created: 0, ignored, skipped_duplicate: skippedDuplicate, errors: errors.length, total: entries.length },
    });
  }

  const previousHiddenCount = list.hidden_count;
  if (previousHiddenCount > 0 && created.length > previousHiddenCount * ANOMALY_FACTOR && confirm !== true) {
    return res.status(409).json({
      warning: true,
      message: `Cet import masquerait ${created.length} chaînes contre ${previousHiddenCount} actuellement — proportion anormalement élevée. Confirmez pour appliquer quand même.`,
      previous_hidden_count: previousHiddenCount,
      new_valid_count: created.length,
    });
  }

  const applyImport = db.transaction(() => {
    db.prepare('DELETE FROM channel_filter_entries WHERE list_id=?').run(list.id);
    const insert = db.prepare(
      `INSERT INTO channel_filter_entries (list_id, channel_id, tvg_id, name, match_key, source_status) VALUES (?,?,?,?,?,?)`
    );
    for (const e of created) {
      insert.run(list.id, e.channel_id, e.tvg_id, e.name, e.match_key, e.source_status);
    }
    db.prepare(`UPDATE channel_filter_lists SET hidden_count=?, version=version+1, updated_at=datetime('now') WHERE id=?`)
      .run(created.length, list.id);
    db.prepare(`UPDATE channel_filter_settings SET global_version=global_version+1, updated_at=datetime('now') WHERE id=1`).run();
  });
  applyImport();

  const updatedList = db.prepare('SELECT version FROM channel_filter_lists WHERE id=?').get(list.id);

  return res.json({
    message: 'Import appliqué.',
    created: created.length,
    ignored,
    skipped_duplicate: skippedDuplicate,
    errors,
    total: entries.length,
    hidden_count: created.length,
    version: updatedList.version,
  });
});

module.exports = router;
