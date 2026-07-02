const router = require('express').Router();
const { db } = require('../config/database');

// Public endpoint — no auth required
// GET /api/popup  →  returns the currently active popup, or { popup: null }
router.get('/', (req, res) => {
  const row = db.prepare('SELECT * FROM popups WHERE is_active=1 LIMIT 1').get();
  if (!row) return res.json({ popup: null });

  const popup = {
    id:      row.popup_id,
    type:    row.type,
    title:   row.title,
    message: row.message,
    force:   row.force === 1,
    button_ok: row.button_ok || 'Fermer',
    ...(row.new_version     && { new_version:     row.new_version }),
    ...(row.current_version && { current_version: row.current_version }),
    ...(row.download_url    && { download_url:    row.download_url }),
    ...(row.button_action   && { button_action:   row.button_action }),
    ...(row.auto_close_sec  && { auto_close_sec:  row.auto_close_sec }),
  };

  return res.json({ popup });
});

module.exports = router;
