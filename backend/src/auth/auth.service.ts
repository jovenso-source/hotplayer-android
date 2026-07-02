import { Injectable } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { ConfigService } from '@nestjs/config';
import { createHash } from 'crypto';
import { DatabaseService } from '../database/database.service';

export interface JwtPayload {
  sub:        string;   // device DB id (UUID)
  device_id:  string;   // UUID v4 côté client
  session_id: string;   // UUID de la session
  user_id?:   string;
}

@Injectable()
export class AuthService {
  constructor(
    private readonly jwt: JwtService,
    private readonly db: DatabaseService,
    private readonly config: ConfigService,
  ) {}

  /** Génère un JWT de session et persiste son hash. */
  async issueToken(payload: JwtPayload, sessionId: string): Promise<{ token: string; expiresAt: Date }> {
    const expiresIn = this.config.get<string>('jwt.expiresIn') ?? '24h';
    const token     = this.jwt.sign(payload, { expiresIn });
    const expiresAt = this.tokenExpiry(expiresIn);

    // On stocke le hash SHA-256 — jamais le token brut
    const hash = createHash('sha256').update(token).digest('hex');
    await this.db.query(
      `INSERT INTO device_tokens (session_id, device_id, token_hash, expires_at)
       VALUES ($1, $2, $3, $4)`,
      [sessionId, payload.sub, hash, expiresAt],
    );

    return { token, expiresAt };
  }

  /** Rotation : révoque le token courant, émet un nouveau. */
  async rotateToken(
    currentToken: string,
    payload: JwtPayload,
    sessionId: string,
  ): Promise<{ token: string; expiresAt: Date } | null> {
    const hash = createHash('sha256').update(currentToken).digest('hex');

    const { rows } = await this.db.query<{ id: string; token_family: string; generation: number }>(
      'SELECT id, token_family, generation FROM device_tokens WHERE token_hash = $1 AND is_active = TRUE',
      [hash],
    );
    if (!rows[0]) return null;

    const { id: tokenId, token_family, generation } = rows[0];

    // Réutilisation d'un token révoqué → toute la famille est compromise
    const { rows: revokedRows } = await this.db.query<{ id: string }>(
      'SELECT id FROM device_tokens WHERE token_hash = $1 AND is_active = FALSE',
      [hash],
    );
    if (revokedRows.length > 0) {
      await this.db.query(
        "UPDATE device_tokens SET is_active = FALSE, revoke_reason = 'compromise' WHERE token_family = $1",
        [token_family],
      );
      return null;
    }

    // Révoquer le token courant
    await this.db.query(
      "UPDATE device_tokens SET is_active = FALSE, revoked_at = NOW(), revoke_reason = 'rotation' WHERE id = $1",
      [tokenId],
    );

    // Émettre un nouveau token dans la même famille
    const expiresIn = this.config.get<string>('jwt.expiresIn') ?? '24h';
    const newToken  = this.jwt.sign(payload, { expiresIn });
    const expiresAt = this.tokenExpiry(expiresIn);
    const newHash   = createHash('sha256').update(newToken).digest('hex');

    await this.db.query(
      `INSERT INTO device_tokens (session_id, device_id, token_hash, token_family, generation, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [sessionId, payload.sub, newHash, token_family, generation + 1, expiresAt],
    );

    return { token: newToken, expiresAt };
  }

  /** Révoque tous les tokens actifs d'une session. */
  async revokeSessionTokens(sessionId: string, reason: string): Promise<void> {
    await this.db.query(
      `UPDATE device_tokens
       SET is_active = FALSE, revoked_at = NOW(), revoke_reason = $1
       WHERE session_id = $2 AND is_active = TRUE`,
      [reason, sessionId],
    );
  }

  private tokenExpiry(expiresIn: string): Date {
    const map: Record<string, number> = { h: 3600, d: 86400, m: 60 };
    const match = expiresIn.match(/^(\d+)([hdm])$/);
    if (!match) return new Date(Date.now() + 86400 * 1000);
    const seconds = parseInt(match[1], 10) * (map[match[2]] ?? 3600);
    return new Date(Date.now() + seconds * 1000);
  }
}
