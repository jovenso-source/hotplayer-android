import {
  Controller, Post, Body, Req, UseGuards, HttpCode, HttpStatus,
} from '@nestjs/common';
import { Request } from 'express';
import { DeviceService } from './device.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RegisterDeviceDto } from './dto/register-device.dto';
import { AuthenticateDeviceDto } from './dto/authenticate-device.dto';
import { HeartbeatDto } from './dto/heartbeat.dto';
import type { JwtPayload } from '../auth/auth.service';

/** Extrait l'IP réelle même derrière un reverse proxy (Railway, Nginx…). */
function getIp(req: Request): string {
  const forwarded = req.headers['x-forwarded-for'];
  if (forwarded) return (Array.isArray(forwarded) ? forwarded[0] : forwarded).split(',')[0].trim();
  return req.socket.remoteAddress ?? '0.0.0.0';
}

/** Extrait le Bearer token brut de l'Authorization header. */
function extractBearer(req: Request): string {
  return (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
}

@Controller('device')
export class DeviceController {
  constructor(private readonly svc: DeviceService) {}

  /**
   * POST /device/register
   *
   * Payload :
   * {
   *   "device_id": "uuid-v4",
   *   "installation_id": "uuid-v4",
   *   "fingerprint": "sha256-hex-32-chars",
   *   "signature": "hmac-sha256-hex-64-chars",
   *   "timestamp": 1720000000000,
   *   "nonce": "uuid-v4",
   *   "device_info": { "model": "Samsung Galaxy", "os_version": "Android 14", "app_version": "1.4.1" }
   * }
   *
   * Réponse :
   * { "success": true, "device_status": "pending", "message": "..." }
   */
  @Post('register')
  @HttpCode(HttpStatus.CREATED)
  register(@Body() dto: RegisterDeviceDto, @Req() req: Request) {
    return this.svc.register(dto, getIp(req));
  }

  /**
   * POST /device/authenticate
   *
   * Payload :
   * {
   *   "device_id": "uuid-v4",
   *   "installation_id": "uuid-v4",
   *   "fingerprint": "sha256-hex",
   *   "signature": "hmac-sha256-hex-64",
   *   "timestamp": 1720000000000,
   *   "nonce": "uuid-v4"
   * }
   *
   * Réponse :
   * {
   *   "success": true,
   *   "token": "eyJ...",
   *   "expires_at": "2026-07-03T10:00:00.000Z",
   *   "session_id": "uuid",
   *   "device": { "label": "TV Salon", "plan": "premium", "status": "active", ... },
   *   "playlist": { "type": "xtream", "server": "...", "user": "...", "pass": "..." }
   * }
   */
  @Post('authenticate')
  @HttpCode(HttpStatus.OK)
  authenticate(@Body() dto: AuthenticateDeviceDto, @Req() req: Request) {
    return this.svc.authenticate(dto, getIp(req));
  }

  /**
   * POST /device/heartbeat  [JWT requis]
   *
   * Payload : { "device_id": "uuid-v4", "session_id": "uuid" }
   *
   * Réponse : { "ok": true, "server_time": "...", "new_token": null, "expires_at": null }
   * Si rotation : new_token et expires_at sont remplis.
   */
  @Post('heartbeat')
  @UseGuards(JwtAuthGuard)
  @HttpCode(HttpStatus.OK)
  heartbeat(@Body() dto: HeartbeatDto, @Req() req: Request & { user: JwtPayload }) {
    return this.svc.heartbeat(dto, getIp(req), extractBearer(req), req.user.sub);
  }

  /**
   * POST /device/logout  [JWT requis]
   *
   * Invalide la session et tous ses tokens.
   * Pas de body requis — session_id et device_id viennent du JWT.
   */
  @Post('logout')
  @UseGuards(JwtAuthGuard)
  @HttpCode(HttpStatus.OK)
  logout(@Req() req: Request & { user: JwtPayload }) {
    return this.svc.logout(req.user.session_id, req.user.sub, getIp(req));
  }
}
