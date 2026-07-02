import { Injectable } from '@nestjs/common';
import { DatabaseService } from '../database/database.service';

export type AuditEventType =
  | 'device.register'
  | 'device.authenticate'
  | 'device.heartbeat'
  | 'device.logout'
  | 'device.revoke'
  | 'clone.detected'
  | 'replay.detected'
  | 'session.conflict'
  | 'token.rotated'
  | 'token.compromised';

@Injectable()
export class AuditService {
  constructor(private readonly db: DatabaseService) {}

  async log(event: {
    type:      AuditEventType;
    deviceId?: string;
    userId?:   string;
    sessionId?: string;
    ip?:       string;
    riskScore?: number;
    metadata?: Record<string, unknown>;
  }): Promise<void> {
    await this.db.query(
      `INSERT INTO audit_logs (event_type, device_id, user_id, session_id, ip_address, risk_score, metadata)
       VALUES ($1, $2, $3, $4, $5::inet, $6, $7)`,
      [
        event.type,
        event.deviceId  ?? null,
        event.userId    ?? null,
        event.sessionId ?? null,
        event.ip        ?? null,
        event.riskScore ?? null,
        JSON.stringify(event.metadata ?? {}),
      ],
    );
  }
}
