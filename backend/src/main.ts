import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { logger: ['log', 'warn', 'error'] });

  // Préfixe global
  app.setGlobalPrefix('api');

  // Validation automatique des DTOs avec class-validator
  app.useGlobalPipes(new ValidationPipe({
    whitelist:        true,   // supprime les champs non déclarés dans le DTO
    forbidNonWhitelisted: true,
    transform:        true,
    transformOptions: { enableImplicitConversion: true },
  }));

  // CORS (ajuster en production)
  app.enableCors({
    origin: process.env.ALLOWED_ORIGINS?.split(',') ?? '*',
  });

  // Routes sans préfixe /api (utilisées par Railway et navigateur)
  const httpAdapter = app.getHttpAdapter();
  httpAdapter.get('/health', (_req: unknown, res: { json: (o: object) => void }) => {
    res.json({ status: 'ok', version: process.env.npm_package_version ?? '2.0.0' });
  });
  httpAdapter.get('/', (_req: unknown, res: { json: (o: object) => void }) => {
    res.json({
      name:    'HotPlayer Backend',
      version: '2.0.0',
      status:  'online',
      endpoints: {
        health:   'GET /health',
        activate: 'POST /api/auth/activate',
        status:   'GET  /api/auth/status',
        register: 'POST /api/device/register',
        auth:     'POST /api/device/authenticate',
      },
    });
  });

  const port = process.env.PORT ?? 3000;
  await app.listen(port);
  console.log(`HotPlayer backend running on port ${port}`);
}

bootstrap();
