import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { PassportModule } from '@nestjs/passport';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { AuthService } from './auth.service';
import { JwtStrategy } from './jwt.strategy';
import { JwtAuthGuard } from './jwt-auth.guard';
import { LegacyAuthService } from './legacy-auth.service';
import { LegacyAuthController } from './legacy-auth.controller';
import { AuditService } from '../audit/audit.service';

@Module({
  imports: [
    PassportModule,
    JwtModule.registerAsync({
      imports:    [ConfigModule],
      inject:     [ConfigService],
      useFactory: (cfg: ConfigService) => ({
        secret:      cfg.get<string>('jwt.secret'),
        signOptions: { expiresIn: cfg.get<string>('jwt.expiresIn') ?? '24h' },
      }),
    }),
  ],
  controllers: [LegacyAuthController],
  providers:   [AuthService, JwtStrategy, JwtAuthGuard, LegacyAuthService, AuditService],
  exports:     [AuthService, JwtAuthGuard],
})
export class AuthModule {}
