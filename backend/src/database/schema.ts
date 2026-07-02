export const SCHEMA_SQL = `
-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- USERS
CREATE TABLE IF NOT EXISTS users (
    id            UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255)  UNIQUE NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    role          VARCHAR(20)   NOT NULL DEFAULT 'subscriber'
                  CHECK (role IN ('admin', 'subscriber')),
    plan          VARCHAR(50)   NOT NULL DEFAULT 'basic',
    max_devices   INTEGER       NOT NULL DEFAULT 1,
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- DEVICES
CREATE TABLE IF NOT EXISTS devices (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id       VARCHAR(36)   UNIQUE,
    mac_address     VARCHAR(17)   UNIQUE,
    installation_id VARCHAR(36),
    fingerprint     VARCHAR(64),
    user_id         UUID          REFERENCES users(id) ON DELETE SET NULL,
    label           VARCHAR(100),
    model           VARCHAR(150),
    os_version      VARCHAR(50),
    app_version     VARCHAR(20),
    status          VARCHAR(20)   NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'active', 'suspended', 'revoked', 'expired')),
    plan            VARCHAR(50)   NOT NULL DEFAULT 'basic',
    expiration_date TIMESTAMPTZ,
    max_connections INTEGER       NOT NULL DEFAULT 1,
    last_known_ip         INET,
    last_known_fingerprint VARCHAR(64),
    clone_risk_score      INTEGER   NOT NULL DEFAULT 0,
    is_flagged            BOOLEAN   NOT NULL DEFAULT FALSE,
    first_seen_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_active_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_device_id   ON devices(device_id);
CREATE INDEX IF NOT EXISTS idx_devices_user_id     ON devices(user_id);
CREATE INDEX IF NOT EXISTS idx_devices_status      ON devices(status);
CREATE INDEX IF NOT EXISTS idx_devices_fingerprint ON devices(fingerprint);

-- SESSIONS
CREATE TABLE IF NOT EXISTS sessions (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id       UUID          NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    user_id         UUID          REFERENCES users(id) ON DELETE SET NULL,
    ip_address      INET          NOT NULL,
    user_agent      VARCHAR(255),
    started_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_heartbeat  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    ended_at        TIMESTAMPTZ,
    end_reason      VARCHAR(50),
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    used_nonces     TEXT[]        NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_sessions_device_id ON sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user_id   ON sessions(user_id);

-- DEVICE_TOKENS
CREATE TABLE IF NOT EXISTS device_tokens (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id      UUID          NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    device_id       UUID          NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)   NOT NULL,
    token_family    UUID          NOT NULL DEFAULT uuid_generate_v4(),
    generation      INTEGER       NOT NULL DEFAULT 1,
    issued_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(50),
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_tokens_token_hash ON device_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_tokens_session    ON device_tokens(session_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tokens_family_gen ON device_tokens(token_family, generation);

-- AUDIT_LOGS
CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGSERIAL     PRIMARY KEY,
    event_type  VARCHAR(50)   NOT NULL,
    device_id   UUID          REFERENCES devices(id) ON DELETE SET NULL,
    user_id     UUID          REFERENCES users(id) ON DELETE SET NULL,
    session_id  UUID,
    ip_address  INET,
    metadata    JSONB         NOT NULL DEFAULT '{}',
    risk_score  INTEGER,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_device  ON audit_logs(device_id);
CREATE INDEX IF NOT EXISTS idx_audit_event   ON audit_logs(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at DESC);

-- PLAYLISTS
CREATE TABLE IF NOT EXISTS playlists (
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

CREATE INDEX IF NOT EXISTS idx_playlists_device ON playlists(device_id);
CREATE INDEX IF NOT EXISTS idx_playlists_user   ON playlists(user_id);

-- Trigger updated_at
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$;

DO $$ BEGIN
  CREATE TRIGGER trg_users_updated    BEFORE UPDATE ON users    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TRIGGER trg_devices_updated  BEFORE UPDATE ON devices  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TRIGGER trg_playlists_updated BEFORE UPDATE ON playlists FOR EACH ROW EXECUTE FUNCTION update_updated_at();
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
`;
