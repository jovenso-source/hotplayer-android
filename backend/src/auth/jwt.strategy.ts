import { Injectable, UnauthorizedException } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { ExtractJwt, Strategy } from 'passport-jwt';
import { ConfigService } from '@nestjs/config';
import { DatabaseService } from '../database/database.service';
import type { JwtPayload } from './auth.service';

@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy) {
  constructor(config: ConfigService, private readonly db: DatabaseService) {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      ignoreExpiration: false,
      secretOrKey: config.get<string>('jwt.secret') ?? 'secret',
    });
  }

  /** Appelé après validation de la signature JWT.
   *  Vérifie que la session est toujours active en base. */
  async validate(payload: JwtPayload) {
    const { rows } = await this.db.query<{ is_active: boolean; status: string }>(
      `SELECT s.is_active, d.status
       FROM sessions s
       JOIN devices d ON s.device_id = d.id
       WHERE s.id = $1`,
      [payload.session_id],
    );

    const session = rows[0];
    if (!session || !session.is_active || session.status !== 'active') {
      throw new UnauthorizedException('Session inactive ou appareil révoqué');
    }
    return payload;
  }
}
