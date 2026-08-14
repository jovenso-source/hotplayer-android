const router = require('express').Router();
const { db } = require('../config/database');
const { normalizeName, normalizeTvgId } = require('../helpers/normalizeName');

// Public endpoint — no auth required
// GET /api/channel-filters → renvoie uniquement les identifiants nécessaires au filtrage local,
// jamais d'URL de chaîne ni de credentials. Aucune liste désactivée n'est incluse.
router.get('/', (req, res) => {
  const settings = db.prepare('SELECT * FROM channel_filter_settings WHERE id=1').get();
  const globalVersion = settings ? settings.global_version : 0;

  if (!settings || settings.enabled !== 1) {
    return res.json({ enabled: false, global_version: globalVersion, lists: [] });
  }

  const lists = db.prepare('SELECT * FROM channel_filter_lists WHERE enabled=1').all();
  const payload = lists.map(list => {
    const entries = db.prepare('SELECT channel_id, tvg_id, name FROM channel_filter_entries WHERE list_id=?').all(list.id);
    const keys = new Set();
    for (const e of entries) {
      if (e.channel_id) keys.add(`id:${String(e.channel_id).trim()}`);
      if (e.tvg_id)      keys.add(`tvg:${normalizeTvgId(e.tvg_id)}`);
      if (e.name)        keys.add(`name:${normalizeName(e.name)}`);
    }
    return {
      id: list.list_key,
      name: list.name,
      playlist_category: list.category,
      version: list.version,
      hidden_channels: [...keys],
    };
  });

  return res.json({ enabled: true, global_version: globalVersion, lists: payload });
});

module.exports = router;
