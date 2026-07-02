import { IsString, IsUUID, IsNotEmpty, IsNumber, IsObject, ValidateNested, Length } from 'class-validator';
import { Type } from 'class-transformer';

export class DeviceInfoDto {
  @IsString() @IsNotEmpty() model!: string;
  @IsString() @IsNotEmpty() os_version!: string;
  @IsString() @IsNotEmpty() app_version!: string;
}

export class RegisterDeviceDto {
  @IsUUID('4')
  device_id!: string;

  @IsUUID('4')
  installation_id!: string;

  /** SHA-256 hex (32 chars) des propriétés hardware */
  @IsString() @Length(32, 64)
  fingerprint!: string;

  /** HMAC-SHA256 hex (64 chars) */
  @IsString() @Length(64, 64)
  signature!: string;

  /** Unix timestamp en millisecondes */
  @IsNumber()
  timestamp!: number;

  /** UUID v4 aléatoire — anti-replay */
  @IsUUID('4')
  nonce!: string;

  @IsObject()
  @ValidateNested()
  @Type(() => DeviceInfoDto)
  device_info!: DeviceInfoDto;
}
