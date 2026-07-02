-- ═══════════════════════════════════════════════════════════════════════════
-- HotPlayer — Schéma de base de données (PostgreSQL 15+)
-- Système d'authentification par Device ID
-- ═══════════════════════════════════════════════════════════════════════════

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────
-- USERS
-- Table principale des utilisateurs / abonnés
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id            UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255)  UNIQUE NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    role          VARCHAR(20)   NOT NULL DEFAULT 'subscriber'  -- 'admin' | 'subscriber'
                  CHECK (role IN ('admin', 'subscriber')),
    plan          VARCHAR(50)   NOT NULL DEFAULT 'basic',      -- 'basic' | 'premium' | 'trial'
    max_devices   INTEGER       NOT NULL DEFAULT 1,
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);

-- ─────────────────────────────────────────────────────────────────────────────
-- DEVICES
-- Un device correspond à un appareil physique identifié par son UUID.
-- Un même utilisateur peut avoir plusieurs devices (limité par max_devices).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE devices (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id       VARCHAR(36)   UNIQUE NOT NULL,   -- UUID v4 généré sur l'appareil
    installation_id VARCHAR(36)   NOT NULL,           -- UUID v4 par installation
    fingerprint     VARCHAR(64)   NOT NULL,           -- SHA-256 des props hardware (32 chars)
    user_id         UUID          REFERENCES users(id) ON DELETE SET NULL,
    label           VARCHAR(100),                     -- nom lisible (ex: "TV Salon")
    model           VARCHAR(150),                     -- "Samsung Galaxy Tab S9"
    os_version      VARCHAR(50),                      -- "Android 14 (API 34)"
    app_version     VARCHAR(20),                      -- "1.4.1"
    status          VARCHAR(20)   NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'active', 'suspended', 'revoked', 'expired')),
    plan            VARCHAR(50)   NOT NULL DEFAULT 'basic',
    expiration_date TIMESTAMPTZ,
    max_connections INTEGER       NOT NULL DEFAULT 1,
    -- Anti-fraude
    last_known_ip         INET,
    last_known_fingerprint VARCHAR(64),
    clone_risk_score      INTEGER   NOT NULL DEFAULT 0,   -- 0-100 (0 = sain, 100 = cloné)
    is_flagged            BOOLEAN   NOT NULL DEFAULT FALSE,
    -- Dates
    first_seen_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_active_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_device_id   ON devices(device_id);
CREATE INDEX idx_devices_user_id     ON devices(user_id);
CREATE INDEX idx_devices_status      ON devices(status);
CREATE INDEX idx_devices_fingerprint ON devices(fingerprint);
CREATE INDEX idx_devices_flagged     ON devices(is_flagged) WHERE is_flagged = TRUE;

-- ─────────────────────────────────────────────────────────────────────────────
-- SESSIONS
-- Une session = une connexion active entre un device et le serveur.
-- Plusieurs sessions actives simultanées sur le même device → anomalie.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE sessions (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id       UUID          NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    user_id         UUID          REFERENCES users(id) ON DELETE SET NULL,
    -- Identification réseau au moment de la connexion
    ip_address      INET          NOT NULL,
    user_agent      VARCHAR(255),
    -- Cycle de vie
    started_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_heartbeat  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    ended_at        TIMESTAMPTZ,
    end_reason      VARCHAR(50),  -- 'logout' | 'expired' | 'revoked' | 'conflict'
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Nonces utilisés (anti-replay) stockés comme JSON array
    used_nonces     TEXT[]        NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_sessions_device_id ON sessions(device_id);
CREATE INDEX idx_sessions_active    ON sessions(is_active, expires_at) WHERE is_active = TRUE;
CREATE INDEX idx_sessions_user_id   ON sessions(user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- DEVICE_TOKENS
-- Stocke les refresh tokens pour la rotation JWT.
-- Un token est lié à une session — révoqué avec elle.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE device_tokens (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id      UUID          NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    device_id       UUID          NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)   NOT NULL,   -- SHA-256 du token JWT (on ne stocke jamais le JWT brut)
    token_family    UUID          NOT NULL DEFAULT uuid_generate_v4(),  -- détection de réutilisation
    generation      INTEGER       NOT NULL DEFAULT 1,   -- incrémenté à chaque rotation
    issued_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(50),  -- 'rotation' | 'logout' | 'admin' | 'compromise'
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_tokens_token_hash ON device_tokens(token_hash);
CREATE INDEX idx_tokens_session    ON device_tokens(session_id);
CREATE INDEX idx_tokens_active     ON device_tokens(is_active) WHERE is_active = TRUE;
CREATE UNIQUE INDEX idx_tokens_family_gen ON device_tokens(token_family, generation);

-- ─────────────────────────────────────────────────────────────────────────────
-- AUDIT_LOGS
-- Toutes les actions sensibles sont journalisées (immuable — pas de DELETE/UPDATE).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE audit_logs (
    id          BIGSERIAL     PRIMARY KEY,
    event_type  VARCHAR(50)   NOT NULL,
    -- 'device.register' | 'device.authenticate' | 'device.heartbeat' | 'device.logout'
    -- 'device.revoke' | 'clone.detected' | 'replay.detected' | 'session.conflict'
    -- 'token.rotated' | 'token.compromised'
    device_id   UUID          REFERENCES devices(id) ON DELETE SET NULL,
    user_id     UUID          REFERENCES users(id) ON DELETE SET NULL,
    session_id  UUID,
    ip_address  INET,
    metadata    JSONB         NOT NULL DEFAULT '{}',   -- données arbitraires selon l'événement
    risk_score  INTEGER,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_device    ON audit_logs(device_id);
CREATE INDEX idx_audit_event     ON audit_logs(event_type);
CREATE INDEX idx_audit_created   ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_risk      ON audit_logs(risk_score DESC) WHERE risk_score > 50;

-- ─────────────────────────────────────────────────────────────────────────────
-- PLAYLISTS
-- Playlist assignée à un device (ou à un utilisateur).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE playlists (
    id           UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id    UUID          REFERENCES devices(id) ON DELETE CASCADE,
    user_id      UUID          REFERENCES users(id) ON DELETE CASCADE,
    type         VARCHAR(10)   NOT NULL CHECK (type IN ('m3u', 'xtream')),
    url          TEXT,
    xtream_server TEXT,
    xtream_user   VARCHAR(100),
    xtream_pass   VARCHAR(100),
    is_active    BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CHECK (device_id IS NOT NULL OR user_id IS NOT NULL)
);

CREATE INDEX idx_playlists_device ON playlists(device_id);
CREATE INDEX idx_playlists_user   ON playlists(user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Trigger : updated_at automatique
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$;

CREATE TRIGGER trg_users_updated    BEFORE UPDATE ON users    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_devices_updated  BEFORE UPDATE ON devices  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_playlists_updated BEFORE UPDATE ON playlists FOR EACH ROW EXECUTE FUNCTION update_updated_at();
