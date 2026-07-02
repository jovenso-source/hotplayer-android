-- ═══════════════════════════════════════════════════════════════════════════
-- Migration 001 — Compatibilité avec l'ancien système MAC
-- À exécuter UNE SEULE FOIS lors du déploiement du nouveau backend.
--
-- Ce que fait cette migration :
--   1. Ajoute mac_address à la table devices (pour retrouver les anciens comptes)
--   2. Rend device_id nullable (pour les appareils non encore migrés)
--   3. Crée les index nécessaires
--   4. Pour une migration depuis l'ancien backend : importer les devices existants
--      avec leur MAC dans la colonne mac_address (script d'import séparé)
-- ═══════════════════════════════════════════════════════════════════════════

BEGIN;

-- 1. Rendre device_id nullable pendant la transition
--    Les anciens devices reçoivent leur UUID lors de leur prochaine connexion.
ALTER TABLE devices
  ALTER COLUMN device_id    DROP NOT NULL,
  ALTER COLUMN installation_id DROP NOT NULL,
  ALTER COLUMN fingerprint  DROP NOT NULL;

-- 2. Ajouter mac_address pour la rétrocompatibilité
ALTER TABLE devices
  ADD COLUMN IF NOT EXISTS mac_address VARCHAR(17);

-- 3. Index unique sur mac_address (nullable — n'indexe que les valeurs non nulles)
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_mac_address
  ON devices(mac_address)
  WHERE mac_address IS NOT NULL;

-- 4. Index sur device_id devenu nullable
DROP INDEX IF EXISTS devices_device_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_device_id
  ON devices(device_id)
  WHERE device_id IS NOT NULL;

-- 5. Contrainte d'intégrité : un device doit avoir au moins un identifiant
--    (mac_address OU device_id) — vérifiée à l'application, pas en DB car
--    trop complexe à gérer lors de la migration progressive.

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- Script d'import depuis l'ancien backend (à adapter selon votre schéma)
-- Exemples de requêtes à exécuter si vous avez un dump de l'ancien backend :
-- ─────────────────────────────────────────────────────────────────────────────
-- INSERT INTO devices (mac_address, status, plan, label, expiration_date, max_connections, created_at)
-- SELECT mac, status, plan, label, expiration_date, max_connections, created_at
-- FROM old_devices
-- ON CONFLICT (mac_address) DO NOTHING;
--
-- INSERT INTO playlists (device_id, type, url, xtream_server, xtream_user, xtream_pass)
-- SELECT d.id, op.type, op.url, op.server, op.user, op.pass
-- FROM old_playlists op
-- JOIN devices d ON d.mac_address = op.mac;
