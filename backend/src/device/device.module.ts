import { Module } from '@nestjs/common';
import { DeviceController } from './device.controller';
import { DeviceService } from './device.service';
import { AuthModule } from '../auth/auth.module';
import { FraudDetectionService } from '../fraud/fraud-detection.service';
import { AuditService } from '../audit/audit.service';

@Module({
  imports:     [AuthModule],
  controllers: [DeviceController],
  providers:   [DeviceService, FraudDetectionService, AuditService],
})
export class DeviceModule {}
