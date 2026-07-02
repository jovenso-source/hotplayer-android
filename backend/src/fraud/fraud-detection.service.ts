import { Injectable, Logger } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';

export interface FraudCheckResult {
  allowed:   boolean;
  riskScore: number;        // 0-100
  reasons:   string[];
}

/**
 * Détection de fraude en temps réel.
 *
 * Contrôles effectués à chaque authentification :
 *  1. Changement d'empreinte hardware → risque de clonage
 *  2. Sessions simultanées actives → partage de compte
 *  3. Dépassement du nombre max d'appareils → limite de plan
 *  4. Réutilisation d'un nonce → replay attack
 *  5. Timestamp hors fenêtre → replay attack
 */
@Injectable()
export class FraudDetectionService {
  private readonly logger = new Logger(FraudDetectionService.name);

  constructor(private readonly db: DatabaseService) {}

  // ── Vérification anti-replay ──────────────────────────────────────────────

  /**
   * Vérifie que le timestamp et le nonce sont valides.
   * @param timestamp  Unix ms envoyé par le client
   * @param nonce      UUID v4 aléatoire envoyé par le client
   * @param sessionId  Session courante (pour le registre des nonces)
   * @param windowMs   Fenêtre de validité en ms (défaut ±5 min)
   */
  async checkReplay(
    timestamp: number,
    nonce: string,
    sessionId: string | null,
    windowMs = 300_000,
  ): Promise<{ valid: boolean; reason?: string }> {
    const now = Date.now();
    if (Math.abs(now - timestamp) > windowMs) {
      return { valid: false, reason: 'TIMESTAMP_OUT_OF_WINDOW' };
    }

    if (sessionId) {
      const { rows } = await this.db.query<{ used_nonces: string[] }>(
        'SELECT used_nonces FROM sessions WHERE id = $1',
        [sessionId],
      );
      if (rows[0]?.used_nonces?.includes(nonce)) {
        return { valid: false, reason: 'NONCE_REUSED' };
      }
      // Enregistrer le nonce (sliding window de 100 nonces max)
      await this.db.query(
        `UPDATE sessions
         SET used_nonces = (
           CASE WHEN array_length(used_nonces, 1) >= 100
                THEN used_nonces[2:] || $2::text
                ELSE used_nonces || $2::text
           END
         )
         WHERE id = $1`,
        [sessionId, nonce],
      );
    }
    return { valid: true };
  }

  // ── Analyse de risque ─────────────────────────────────────────────────────

  /**
   * Analyse le risque global pour un appareil.
   * Appelé après authentification réussie pour décider de suspendre ou non.
   */
  async analyzeRisk(deviceDbId: string, incomingFingerprint: string, ip: string): Promise<FraudCheckResult> {
    const reasons: string[] = [];
    let riskScore = 0;

    // 1. Empreinte hardware différente de la dernière connue
    const { rows: [device] } = await this.db.query<{
      last_known_fingerprint: string | null;
      fingerprint: string;
      user_id: string | null;
      max_connections: number;
    }>(
      'SELECT last_known_fingerprint, fingerprint, user_id, max_connections FROM devices WHERE id = $1',
      [deviceDbId],
    );

    if (device) {
      if (
        device.last_known_fingerprint &&
        device.last_known_fingerprint !== incomingFingerprint &&
        incomingFingerprint !== device.fingerprint
      ) {
        riskScore += 60;
        reasons.push('FINGERPRINT_MISMATCH');
        this.logger.warn(`Clone risk: fingerprint mismatch for device ${deviceDbId}`);
      }

      // 2. Sessions simultanées (plus que max_connections actives sur ce device)
      const { rows: [{ count }] } = await this.db.query<{ count: string }>(
        `SELECT COUNT(*) FROM sessions
         WHERE device_id = $1 AND is_active = TRUE AND expires_at > NOW()`,
        [deviceDbId],
      );
      const activeSessions = parseInt(count, 10);
      if (activeSessions > device.max_connections) {
        riskScore += 30;
        reasons.push('CONCURRENT_SESSIONS_EXCEEDED');
      }

      // 3. Même IP que d'autres appareils du même utilisateur dans les 5 dernières minutes
      if (device.user_id) {
        const { rows: [{ count: concurrentDeviceCount }] } = await this.db.query<{ count: string }>(
          `SELECT COUNT(DISTINCT d.id) FROM sessions s
           JOIN devices d ON s.device_id = d.id
           WHERE d.user_id = $1 AND d.id != $2
             AND s.ip_address = $3 AND s.is_active = TRUE
             AND s.last_heartbeat > NOW() - INTERVAL '5 minutes'`,
          [device.user_id, deviceDbId, ip],
        );
        if (parseInt(concurrentDeviceCount, 10) > 0) {
          riskScore += 10;
          reasons.push('SAME_IP_CONCURRENT_DEVICE');
        }
      }
    }

    // Mise à jour du score et de la dernière empreinte connue
    await this.db.query(
      `UPDATE devices
       SET clone_risk_score = $1,
           last_known_fingerprint = $2,
           last_known_ip = $3,
           last_active_at = NOW()
       WHERE id = $4`,
      [Math.min(riskScore, 100), incomingFingerprint, ip, deviceDbId],
    );

    const allowed = riskScore < 80;
    if (!allowed) {
      await this.db.query(
        "UPDATE devices SET status = 'suspended', is_flagged = TRUE WHERE id = $1",
        [deviceDbId],
      );
      this.logger.warn(`Device ${deviceDbId} auto-suspended (score=${riskScore})`);
    }

    return { allowed, riskScore: Math.min(riskScore, 100), reasons };
  }

  // ── Limite d'appareils par utilisateur ───────────────────────────────────

  async checkDeviceLimit(userId: string): Promise<{ allowed: boolean; current: number; max: number }> {
    const { rows: [user] } = await this.db.query<{ max_devices: number }>(
      'SELECT max_devices FROM users WHERE id = $1',
      [userId],
    );
    const max = user?.max_devices ?? 1;

    const { rows: [{ count }] } = await this.db.query<{ count: string }>(
      "SELECT COUNT(*) FROM devices WHERE user_id = $1 AND status NOT IN ('revoked')",
      [userId],
    );
    const current = parseInt(count, 10);

    return { allowed: current < max, current, max };
  }
}
