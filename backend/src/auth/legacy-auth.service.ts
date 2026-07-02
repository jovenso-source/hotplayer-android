import {
  Injectable, Logger, NotFoundException, ForbiddenException,
} from '@nestjs/common';
import { DatabaseService } from '../database/database.service';
import { AuthService } from './auth.service';
import { AuditService } from '../audit/audit.service';
import { v4 as uuidv4 } from 'uuid';

/**
 * Service de compatibilité ascendante pour l'ancien système d'authentification par MAC.
 *
 * Gère les deux cas :
 *   - Ancienne app  : envoie X-MAC-Address + body { mac }          → retourne token + device_id (champ nouveau, ignoré)
 *   - Nouvelle app  : envoie X-Device-ID (UUID) + body { mac }     → migration transparente
 *
 * Après la migration, les clients n'utilisent plus ces endpoints.
 */
@Injectable()
export class LegacyAuthService {
  private readonly logger = new Logger(LegacyAuthService.name);

  constructor(
    private readonly db:    DatabaseService,
    private readonly auth:  AuthService,
    private readonly audit: AuditService,
  ) {}

  // ══════════════════════════════════════════════════════════════════════════
  // POST /auth/activate  (ancien endpoint, toujours supporté)
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Authentifie un appareil par son adresse MAC.
   * Si un device_id est proposé (nouvelle app), il est associé au device.
   * Le champ device_id est retourné dans la réponse — l'ancienne app l'ignore.
   *
   * @param mac             Adresse MAC de l'appareil (body)
   * @param proposedDeviceId UUID proposé par la nouvelle app (header X-Device-ID), peut être null
   * @param ip              IP du client
   * @param model           Modèle de l'appareil (header X-Device-Model), peut être null
   * @param fingerprint     Empreinte hardware (header X-Device-Fingerprint), peut être null
   */
  async activate(
    mac: string,
    proposedDeviceId: string | null,
    ip: string,
    model: string | null,
    fingerprint: string | null,
  ) {
    const normalizedMac = mac.toUpperCase().trim();

    // ── Chercher le device par MAC ─────────────────────────────────────────
    const { rows: [device] } = await this.db.query<{
      id: string; status: string; device_id: string | null;
      user_id: string | null; plan: string; label: string | null;
      expiration_date: Date | null; max_connections: number;
    }>(
      `SELECT id, status, device_id, user_id, plan, label, expiration_date, max_connections
       FROM devices WHERE mac_address = $1`,
      [normalizedMac],
    );

    if (!device) {
      // Appareil inconnu → enregistrer comme "pending" pour que l'admin l'active
      const newDeviceId = proposedDeviceId ?? uuidv4();
      await this.db.query(
        `INSERT INTO devices (device_id, mac_address, fingerprint, model, status, last_known_ip)
         VALUES ($1, $2, $3, $4, 'pending', $5::inet)
         ON CONFLICT (mac_address) DO NOTHING`,
        [newDeviceId, normalizedMac, fingerprint, model, ip],
      );
      this.logger.log(`Unknown MAC auto-registered as pending: ${normalizedMac}`);
      throw new NotFoundException({
        code: 'DEVICE_NOT_FOUND',
        message: 'Appareil non enregistré. Contactez votre administrateur.',
      });
    }

    // ── Vérifier le statut ────────────────────────────────────────────────
    if (device.expiration_date && new Date() > device.expiration_date) {
      await this.db.query("UPDATE devices SET status = 'expired' WHERE id = $1", [device.id]);
      throw new ForbiddenException({ code: 'DEVICE_EXPIRED', message: 'Abonnement expiré.' });
    }
    switch (device.status) {
      case 'pending':   throw new ForbiddenException({ code: 'DEVICE_INACTIVE',  message: 'Appareil non activé. Contactez votre administrateur.' });
      case 'suspended': throw new ForbiddenException({ code: 'DEVICE_SUSPENDED', message: 'Appareil suspendu. Contactez le support.' });
      case 'revoked':   throw new ForbiddenException({ code: 'DEVICE_REVOKED',   message: 'Appareil révoqué.' });
      case 'expired':   throw new ForbiddenException({ code: 'DEVICE_EXPIRED',   message: 'Abonnement expiré.' });
    }

    // ── Assigner le device_id si pas encore fait (migration) ─────────────
    let assignedDeviceId = device.device_id;
    if (!assignedDeviceId) {
      // Préférer l'UUID proposé par la nouvelle app, sinon en générer un
      assignedDeviceId = proposedDeviceId ?? uuidv4();
      try {
        await this.db.query(
          'UPDATE devices SET device_id = $1 WHERE id = $2',
          [assignedDeviceId, device.id],
        );
        this.logger.log(`Device ID assigned during migration: ${normalizedMac} → ${assignedDeviceId}`);
      } catch {
        // Conflit UUID (astronomiquement rare) → générer un nouveau
        assignedDeviceId = uuidv4();
        await this.db.query(
          'UPDATE devices SET device_id = $1 WHERE id = $2',
          [assignedDeviceId, device.id],
        );
      }
    } else if (proposedDeviceId && proposedDeviceId !== assignedDeviceId) {
      // Le device a déjà un device_id et la nouvelle app en propose un différent.
      // On retourne l'existant — la nouvelle app se met à jour.
      this.logger.debug(`Device already has ID ${assignedDeviceId}, ignoring proposed ${proposedDeviceId}`);
    }

    // Mettre à jour model/fingerprint/IP si fournis (nouvelle app)
    await this.db.query(
      `UPDATE devices SET
         last_known_ip = $1::inet,
         model         = COALESCE($2, model),
         fingerprint   = COALESCE($3, fingerprint),
         last_active_at = NOW()
       WHERE id = $4`,
      [ip, model, fingerprint, device.id],
    );

    // ── Fermer sessions expirées ──────────────────────────────────────────
    await this.db.query(
      `UPDATE sessions SET is_active = FALSE, ended_at = NOW(), end_reason = 'expired'
       WHERE device_id = $1 AND is_active = TRUE AND expires_at < NOW()`,
      [device.id],
    );

    // ── Créer session ────────────────────────────────────────────────────
    const sessionExpiry = new Date(Date.now() + 24 * 3600 * 1000);
    const { rows: [session] } = await this.db.query<{ id: string }>(
      `INSERT INTO sessions (device_id, user_id, ip_address, expires_at, used_nonces)
       VALUES ($1, $2, $3::inet, $4, '{}')
       RETURNING id`,
      [device.id, device.user_id, ip, sessionExpiry],
    );

    // ── Émettre JWT ───────────────────────────────────────────────────────
    const jwtPayload = {
      sub:        device.id,
      device_id:  assignedDeviceId,
      session_id: session.id,
      user_id:    device.user_id ?? undefined,
    };
    const { token, expiresAt } = await this.auth.issueToken(jwtPayload, session.id);

    // ── Playlist ─────────────────────────────────────────────────────────
    const playlist = await this.getPlaylistForDevice(device.id);

    await this.audit.log({
      type: 'device.authenticate', deviceId: device.id, sessionId: session.id, ip,
      metadata: { via: 'legacy_mac', mac: normalizedMac, device_id_assigned: assignedDeviceId },
    });

    return {
      success:    true,
      token,
      expires_at: expiresAt.toISOString(),
      session_id: session.id,   // UUID (format nouveau) — anciens clients stockent ça sans l'utiliser
      device_id:  assignedDeviceId,  // NOUVEAU CHAMP — anciens clients l'ignorent
      device: {
        label:           device.label,
        plan:            device.plan,
        status:          device.status,
        expiration_date: device.expiration_date?.toISOString() ?? null,
        max_connections: device.max_connections,
      },
      playlist,
    };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // GET /auth/status
  // ══════════════════════════════════════════════════════════════════════════

  async status(identifier: string) {
    const device = await this.findDeviceByIdentifier(identifier);
    if (!device) throw new NotFoundException({ code: 'DEVICE_NOT_FOUND' });
    return {
      activated:       device.status === 'active',
      status:          device.status,
      plan:            device.plan,
      expiration_date: device.expiration_date?.toISOString() ?? null,
      label:           device.label ?? null,
    };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // POST /auth/heartbeat  [JWT requis]
  // ══════════════════════════════════════════════════════════════════════════

  async heartbeat(deviceDbId: string, sessionId: string, ip: string) {
    await this.db.query(
      `UPDATE sessions SET last_heartbeat = NOW(), ip_address = $1::inet
       WHERE id = $2 AND is_active = TRUE`,
      [ip, sessionId],
    );
    await this.db.query(
      'UPDATE devices SET last_active_at = NOW() WHERE id = $1',
      [deviceDbId],
    );
    await this.audit.log({ type: 'device.heartbeat', deviceId: deviceDbId, sessionId, ip });
    return { ok: true, server_time: new Date().toISOString() };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // POST /auth/logout  [JWT requis]
  // ══════════════════════════════════════════════════════════════════════════

  async logout(deviceDbId: string, sessionId: string, ip: string) {
    await this.db.query(
      "UPDATE sessions SET is_active = FALSE, ended_at = NOW(), end_reason = 'logout' WHERE id = $1",
      [sessionId],
    );
    await this.auth.revokeSessionTokens(sessionId, 'logout');
    await this.audit.log({ type: 'device.logout', deviceId: deviceDbId, sessionId, ip });
    return { success: true };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // GET /stream/playlist  [JWT requis]
  // ══════════════════════════════════════════════════════════════════════════

  async getPlaylist(deviceDbId: string) {
    const playlist = await this.getPlaylistForDevice(deviceDbId);
    if (!playlist) throw new NotFoundException({ code: 'PLAYLIST_NOT_FOUND' });
    return playlist;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Trouve un device par MAC address (format XX:XX:XX:XX:XX:XX)
   * ou par device_id (format UUID v4).
   * Utilisé pour GET /auth/status où le client envoie son identifiant.
   */
  private async findDeviceByIdentifier(identifier: string) {
    const isMac = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/.test(identifier);
    const column = isMac ? 'mac_address' : 'device_id';
    const { rows: [device] } = await this.db.query<{
      id: string; status: string; plan: string;
      label: string | null; expiration_date: Date | null;
    }>(
      `SELECT id, status, plan, label, expiration_date FROM devices WHERE ${column} = $1`,
      [isMac ? identifier.toUpperCase() : identifier],
    );
    return device ?? null;
  }

  private async getPlaylistForDevice(deviceDbId: string) {
    const { rows: [pl] } = await this.db.query<{
      type: string; url: string | null;
      xtream_server: string | null; xtream_user: string | null; xtream_pass: string | null;
    }>(
      `SELECT type, url, xtream_server, xtream_user, xtream_pass
       FROM playlists WHERE device_id = $1 AND is_active = TRUE LIMIT 1`,
      [deviceDbId],
    );
    if (!pl) return null;
    return {
      type:     pl.type,
      url:      pl.url      ?? null,
      server:   pl.xtream_server ?? null,
      user:     pl.xtream_user   ?? null,
      username: pl.xtream_user   ?? null,
      pass:     pl.xtream_pass   ?? null,
      password: pl.xtream_pass   ?? null,
    };
  }
}
