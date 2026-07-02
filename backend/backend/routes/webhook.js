const router = require('express').Router();
const { db } = require('../config/database');

// POST /api/webhook/release
// Appelé automatiquement par GitHub Actions après chaque release.
// Met à jour la table popups avec la nouvelle version → l'app affiche la notif.

router.post('/release', (req, res) => {
    const secret = req.headers['x-webhook-secret'];
    if (!secret || secret !== process.env.WEBHOOK_SECRET) {
        return res.status(401).json({ error: 'Non autorisé' });
    }

    const { version_name, version_code, download_url, changelog } = req.body || {};

    if (!version_name || !download_url) {
        return res.status(400).json({ error: 'version_name et download_url sont requis' });
    }

    const popupId = `update_v${version_name}`;
    const title   = `Mise à jour disponible`;
    const message = changelog || `HotPlayer v${version_name} est disponible. Téléchargez la nouvelle version.`;

    // Désactiver tous les anciens popups de mise à jour
    db.prepare(`UPDATE popups SET is_active=0 WHERE type='update'`).run();

    // Insérer ou remplacer le popup de la nouvelle version
    db.prepare(`
        INSERT INTO popups
            (popup_id, type, title, message, force, new_version, download_url, button_ok, button_action, is_active)
        VALUES
            (?, 'update', ?, ?, 0, ?, ?, 'Plus tard', 'Télécharger', 1)
        ON CONFLICT(popup_id) DO UPDATE SET
            title        = excluded.title,
            message      = excluded.message,
            new_version  = excluded.new_version,
            download_url = excluded.download_url,
            is_active    = 1,
            updated_at   = datetime('now')
    `).run(popupId, title, message, version_name, download_url);

    console.log(`[webhook] Release v${version_name} (code ${version_code}) activée → popup ${popupId}`);
    res.json({ ok: true, popup_id: popupId, version: version_name });
});

module.exports = router;
