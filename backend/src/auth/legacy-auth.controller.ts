import {
  Controller, Post, Get, Body, Req, Headers, UseGuards, HttpCode, HttpStatus,
} from '@nestjs/common';
import { Request } from 'express';
import { IsString, IsNotEmpty } from 'class-validator';
import { LegacyAuthService } from './legacy-auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';
import type { JwtPayload } from './auth.service';

class LegacyActivateDto {
  @IsString() @IsNotEmpty() mac!: string;
}

function getIp(req: Request): string {
  const fwd = req.headers['x-forwarded-for'];
  if (fwd) return (Array.isArray(fwd) ? fwd[0] : fwd).split(',')[0].trim();
  return req.socket.remoteAddress ?? '0.0.0.0';
}

/**
 * Contrôleur de compatibilité ascendante — anciens endpoints MAC.
 *
 * Ces routes restent fonctionnelles pendant toute la période de transition.
 * L'ancienne app les utilise telles quelles.
 * La nouvelle app les utilise uniquement lors du premier lancement post-mise-à-jour
 * (migration MAC → Device ID), puis bascule sur /device/*.
 *
 * Ordre de lecture des en-têtes identifiants :
 *   1. X-Device-ID   (nouvelle app — contient l'UUID du device)
 *   2. X-MAC-Address (ancienne app — contient la MAC address)
 */
@Controller('auth')
export class LegacyAuthController {
  constructor(private readonly svc: LegacyAuthService) {}

  /**
   * POST /auth/activate
   *
   * Ancienne app  → body: { "mac": "AA:BB:CC:DD:EE:FF" }
   * Nouvelle app  → body: { "mac": "AA:BB:CC:DD:EE:FF" }
   *               + header X-Device-ID: <uuid>
   *               + header X-Device-Fingerprint: <sha256>
   *               + header X-Device-Model: <model>
   *
   * Réponse (rétrocompatible) :
   * {
   *   "success": true,
   *   "token": "eyJ...",
   *   "expires_at": "...",
   *   "session_id": "<uuid>",
   *   "device_id": "<uuid>",     ← NOUVEAU (anciens clients ignorent ce champ)
   *   "device": { "label", "plan", "status", "expiration_date", "max_connections" },
   *   "playlist": { "type", "url" | "server"+"user"+"pass" }
   * }
   */
  @Post('activate')
  @HttpCode(HttpStatus.OK)
  activate(
    @Body() body: LegacyActivateDto,
    @Req()  req:  Request,
    @Headers('X-Device-ID')          proposedDeviceId: string | undefined,
    @Headers('X-MAC-Address')        macHeader:        string | undefined,
    @Headers('X-Device-Fingerprint') fingerprint:      string | undefined,
    @Headers('X-Device-Model')       model:            string | undefined,
  ) {
    const mac = body.mac || macHeader || '';
    return this.svc.activate(
      mac,
      proposedDeviceId?.trim() || null,
      getIp(req),
      model?.trim()       || null,
      fingerprint?.trim() || null,
    );
  }

  /**
   * GET /auth/status
   *
   * Accepte X-Device-ID (UUID) ou X-MAC-Address (MAC) comme identifiant.
   */
  @Get('status')
  status(
    @Headers('X-Device-ID')   deviceId: string | undefined,
    @Headers('X-MAC-Address') mac:      string | undefined,
  ) {
    const identifier = deviceId?.trim() || mac?.trim() || '';
    return this.svc.status(identifier);
  }

  /**
   * POST /auth/heartbeat  [JWT requis]
   */
  @Post('heartbeat')
  @UseGuards(JwtAuthGuard)
  @HttpCode(HttpStatus.OK)
  heartbeat(@Req() req: Request & { user: JwtPayload }) {
    return this.svc.heartbeat(req.user.sub, req.user.session_id, getIp(req));
  }

  /**
   * POST /auth/logout  [JWT requis]
   */
  @Post('logout')
  @UseGuards(JwtAuthGuard)
  @HttpCode(HttpStatus.OK)
  logout(@Req() req: Request & { user: JwtPayload }) {
    return this.svc.logout(req.user.sub, req.user.session_id, getIp(req));
  }

  /**
   * GET /stream/playlist  [JWT requis]
   *
   * Retourne les identifiants de la playlist assignée à l'appareil.
   * Utilisé par les deux versions de l'app.
   */
  @Get('stream/playlist')
  @UseGuards(JwtAuthGuard)
  getPlaylist(@Req() req: Request & { user: JwtPayload }) {
    return this.svc.getPlaylist(req.user.sub);
  }
}
