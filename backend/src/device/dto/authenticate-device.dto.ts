import { IsString, IsUUID, IsNumber, Length } from 'class-validator';

export class AuthenticateDeviceDto {
  @IsUUID('4')
  device_id!: string;

  @IsUUID('4')
  installation_id!: string;

  @IsString() @Length(32, 64)
  fingerprint!: string;

  @IsString() @Length(64, 64)
  signature!: string;

  @IsNumber()
  timestamp!: number;

  @IsUUID('4')
  nonce!: string;
}
