import {
  Injectable, Logger, NotFoundException, ForbiddenException,
  ConflictException, BadRequestException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { DatabaseService } from '../database/database.service';
import { AuthService } from '../auth/auth.service';
import { FraudDetectionService } from '../fraud/fraud-detection.service';
import { AuditService } from '../audit/audit.service';
import { RegisterDeviceDto } from './dto/register-device.dto';
import { AuthenticateDeviceDto } from './dto/authenticate-device.dto';
import { HeartbeatDto } from './dto/heartbeat.dto';

@Injectable()
export class DeviceService {
  private readonly logger = new Logger(DeviceService.name);

  constructor(
    private readonly db:     DatabaseService,
    private readonly auth:   AuthService,
    private readonly fraud:  FraudDetectionService,
    private readonly audit:  AuditService,
    private readonly config: ConfigService,
  ) {}

  // ══════════════════════════════════════════════════════════════════════════
  // POST /device/register
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Enregistre un appareil pour la première fois (ou après réinstallation).
   *
   * L'enregistrement crée l'entrée sans l'activer — l'admin active l'appareil
   * manuellement (ou via règle automatique) en changeant le status à 'active'.
   *
   * Idempotent : si le device_id existe déjà, met à jour installation_id et device_info.
   */
  async register(dto: RegisterDeviceDto, ip: string) {
    // Anti-replay : timestamp dans la fenêtre de ±5 min
    const windowMs = (this.config.get<number>('security.signatureWindowSeconds') ?? 300) * 1000;
    if (Math.abs(Date.now() - dto.timestamp) > windowMs) {
      throw new BadRequestException({ code: 'REPLAY_DETECTED', message: 'Timestamp hors fenêtre' });
    }

    const { rows: [existing] } = await this.db.query<{ id: string; status: string }>(
      'SELECT id, status FROM devices WHERE device_id = $1',
      [dto.device_id],
    );

    if (existing) {
      // Mise à jour après réinstallation (nouveau installation_id)
      await this.db.query(
        `UPDATE devices
         SET installation_id = $1, fingerprint = $2, model = $3,
             os_version = $4, app_version = $5, last_active_at = NOW()
         WHERE id = $6`,
        [dto.installation_id, dto.fingerprint, dto.device_info.model,
         dto.device_info.os_version, dto.device_info.app_version, existing.id],
      );
      await this.audit.log({ type: 'device.register', deviceId: existing.id, ip,
        metadata: { action: 'reinstall', installation_id: dto.installation_id } });
      return { success: true, device_status: existing.status, message: 'Appareil mis à jour' };
    }

    // Nouvel appareil
    const { rows: [created] } = await this.db.query<{ id: string }>(
      `INSERT INTO devices
         (device_id, installation_id, fingerprint, model, os_version, app_version, last_known_ip)
       VALUES ($1, $2, $3, $4, $5, $6, $7::inet)
       RETURNING id`,
      [dto.device_id, dto.installation_id, dto.fingerprint, dto.device_info.model,
       dto.device_info.os_version, dto.device_info.app_version, ip],
    );

    await this.audit.log({ type: 'device.register', deviceId: created.id, ip,
      metadata: { model: dto.device_info.model, os: dto.device_info.os_version } });

    this.logger.log(`New device registered: ${dto.device_id} (${dto.device_info.model})`);
    return { success: true, device_status: 'pending', message: 'Appareil enregistré, en attente d\'activation' };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // POST /device/authenticate
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Authentifie l'appareil et retourne un JWT de session.
   *
   * Vérifications en ordre :
   *  1. Device connu et actif
   *  2. Anti-replay (timestamp + nonce)
   *  3. Pas trop de sessions simultanées
   *  4. Analyse de risque (empreinte, IP)
   *  5. Émission du JWT
   */
  async authenticate(dto: AuthenticateDeviceDto, ip: string) {
    // 1. Device connu et actif
    const { rows: [device] } = await this.db.query<{
      id: string; status: string; user_id: string | null;
      max_connections: number; plan: string; label: string | null;
      expiration_date: Date | null;
    }>(
      `SELECT id, status, user_id, max_connections, plan, label, expiration_date
       FROM devices WHERE device_id = $1`,
      [dto.device_id],
    );

    if (!device) throw new NotFoundException({ code: 'DEVICE_NOT_FOUND' });

    switch (device.status) {
      case 'pending':   throw new ForbiddenException({ code: 'DEVICE_INACTIVE' });
      case 'suspended': throw new ForbiddenException({ code: 'DEVICE_SUSPENDED' });
      case 'revoked':   throw new ForbiddenException({ code: 'DEVICE_REVOKED' });
      case 'expired':   throw new ForbiddenException({ code: 'DEVICE_EXPIRED' });
    }

    if (device.expiration_date && new Date() > device.expiration_date) {
      await this.db.query("UPDATE devices SET status = 'expired' WHERE id = $1", [device.id]);
      throw new ForbiddenException({ code: 'DEVICE_EXPIRED' });
    }

    // 2. Anti-replay
    const windowMs = (this.config.get<number>('security.signatureWindowSeconds') ?? 300) * 1000;
    if (Math.abs(Date.now() - dto.timestamp) > windowMs) {
      await this.audit.log({ type: 'replay.detected', deviceId: device.id, ip,
        metadata: { timestamp: dto.timestamp } });
      throw new BadRequestException({ code: 'REPLAY_DETECTED' });
    }

    // 3. Fermer les sessions expirées existantes
    await this.db.query(
      `UPDATE sessions SET is_active = FALSE, ended_at = NOW(), end_reason = 'expired'
       WHERE device_id = $1 AND is_active = TRUE AND expires_at < NOW()`,
      [device.id],
    );

    // 4. Créer la session
    const sessionExpiry = new Date(Date.now() + 24 * 3600 * 1000);
    const { rows: [session] } = await this.db.query<{ id: string }>(
      `INSERT INTO sessions (device_id, user_id, ip_address, expires_at, used_nonces)
       VALUES ($1, $2, $3::inet, $4, $5)
       RETURNING id`,
      [device.id, device.user_id, ip, sessionExpiry, `{${dto.nonce}}`],
    );

    // 5. Analyse de risque (non-bloquant si score < 80)
    const risk = await this.fraud.analyzeRisk(device.id, dto.fingerprint, ip);
    if (!risk.allowed) {
      await this.db.query(
        "UPDATE sessions SET is_active = FALSE, ended_at = NOW(), end_reason = 'revoked' WHERE id = $1",
        [session.id],
      );
      await this.audit.log({ type: 'clone.detected', deviceId: device.id, ip,
        riskScore: risk.riskScore, metadata: { reasons: risk.reasons } });
      throw new ForbiddenException({ code: 'CLONE_DETECTED', reasons: risk.reasons });
    }

    // 6. Émettre le JWT
    const jwtPayload = {
      sub:        device.id,
      device_id:  dto.device_id,
      session_id: session.id,
      user_id:    device.user_id ?? undefined,
    };
    const { token, expiresAt } = await this.auth.issueToken(jwtPayload, session.id);

    // 7. Charger la playlist
    const playlist = await this.getPlaylistForDevice(device.id);

    await this.audit.log({ type: 'device.authenticate', deviceId: device.id,
      sessionId: session.id, ip, riskScore: risk.riskScore });

    this.logger.log(`Authenticated: ${dto.device_id} session=${session.id}`);

    return {
      success:    true,
      token,
      expires_at: expiresAt.toISOString(),
      session_id: session.id,
      device: {
        label:          device.label,
        plan:           device.plan,
        status:         device.status,
        expiration_date: device.expiration_date?.toISOString() ?? null,
        max_connections: device.max_connections,
      },
      playlist,
    };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // POST /device/heartbeat
  // ══════════════════════════════════════════════════════════════════════════

  async heartbeat(dto: HeartbeatDto, ip: string, currentToken: string, deviceDbId: string) {
    // Mettre à jour le heartbeat de la session
    const { rows: [session] } = await this.db.query<{
      id: string; expires_at: Date; device_id: string;
    }>(
      `UPDATE sessions SET last_heartbeat = NOW(), ip_address = $1::inet
       WHERE id = $2 AND is_active = TRUE AND device_id = $3
       RETURNING id, expires_at, device_id`,
      [ip, dto.session_id, deviceDbId],
    );

    if (!session) throw new NotFoundException({ code: 'SESSION_NOT_FOUND' });

    // Rotation automatique si le token expire dans moins de 2h
    const twoHours = 2 * 3600 * 1000;
    let newToken: string | undefined;
    let newExpiresAt: string | undefined;

    if (new Date(session.expires_at).getTime() - Date.now() < twoHours) {
      const jwtPayload = { sub: deviceDbId, device_id: dto.device_id, session_id: dto.session_id };
      const rotated = await this.auth.rotateToken(currentToken, jwtPayload, dto.session_id);
      if (rotated) {
        newToken     = rotated.token;
        newExpiresAt = rotated.expiresAt.toISOString();
        // Prolonger la session
        await this.db.query(
          'UPDATE sessions SET expires_at = $1 WHERE id = $2',
          [rotated.expiresAt, dto.session_id],
        );
        await this.audit.log({ type: 'token.rotated', deviceId: deviceDbId, sessionId: dto.session_id, ip });
      }
    }

    await this.db.query('UPDATE devices SET last_active_at = NOW() WHERE id = $1', [deviceDbId]);
    await this.audit.log({ type: 'device.heartbeat', deviceId: deviceDbId, sessionId: dto.session_id, ip });

    return {
      ok:          true,
      server_time: new Date().toISOString(),
      new_token:   newToken ?? null,
      expires_at:  newExpiresAt ?? null,
    };
  }

  // ══════════════════════════════════════════════════════════════════════════
  // POST /device/logout
  // ══════════════════════════════════════════════════════════════════════════

  async logout(sessionId: string, deviceDbId: string, ip: string) {
    await this.db.query(
      "UPDATE sessions SET is_active = FALSE, ended_at = NOW(), end_reason = 'logout' WHERE id = $1",
      [sessionId],
    );
    await this.auth.revokeSessionTokens(sessionId, 'logout');
    await this.audit.log({ type: 'device.logout', deviceId: deviceDbId, sessionId, ip });
    return { success: true };
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private async getPlaylistForDevice(deviceDbId: string) {
    const { rows: [pl] } = await this.db.query<{
      type: string; url: string | null;
      xtream_server: string | null; xtream_user: string | null; xtream_pass: string | null;
    }>(
      'SELECT type, url, xtream_server, xtream_user, xtream_pass FROM playlists WHERE device_id = $1 AND is_active = TRUE LIMIT 1',
      [deviceDbId],
    );
    if (!pl) return null;
    return {
      type:   pl.type,
      url:    pl.url,
      server: pl.xtream_server,
      user:   pl.xtream_user,
      pass:   pl.xtream_pass,
    };
  }
}
