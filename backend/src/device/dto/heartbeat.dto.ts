import { IsString, IsUUID } from 'class-validator';

export class HeartbeatDto {
  @IsUUID('4')
  device_id!: string;

  @IsString()
  session_id!: string;
}
