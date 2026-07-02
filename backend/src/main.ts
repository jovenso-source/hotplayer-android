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
  httpAdapter.get('/', (_req: unknown, res: { setHeader: (k: string, v: string) => void; end: (s: string) => void }) => {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end(`<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>HotPlayer Backend</title>
  <style>
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:'Segoe UI',sans-serif;background:#0f172a;color:#e2e8f0;min-height:100vh;display:flex;align-items:center;justify-content:center}
    .card{background:#1e293b;border:1px solid #334155;border-radius:16px;padding:40px 48px;max-width:520px;width:100%;text-align:center;box-shadow:0 25px 50px rgba(0,0,0,.5)}
    .logo{font-size:48px;margin-bottom:16px}
    h1{font-size:28px;font-weight:700;color:#f8fafc;margin-bottom:8px}
    .badge{display:inline-flex;align-items:center;gap:8px;background:#14532d;color:#86efac;border:1px solid #16a34a;border-radius:99px;padding:6px 16px;font-size:14px;font-weight:600;margin-bottom:28px}
    .dot{width:8px;height:8px;background:#4ade80;border-radius:50%;animation:pulse 2s infinite}
    @keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
    .section{text-align:left;margin-top:24px}
    .section h2{font-size:13px;font-weight:600;color:#94a3b8;text-transform:uppercase;letter-spacing:.08em;margin-bottom:12px}
    .endpoint{display:flex;align-items:center;gap:10px;padding:10px 14px;background:#0f172a;border-radius:8px;margin-bottom:8px;font-size:13px;font-family:monospace}
    .method{font-weight:700;min-width:36px}
    .get{color:#34d399}.post{color:#60a5fa}
    .path{color:#cbd5e1}
    .version{margin-top:28px;font-size:12px;color:#475569}
  </style>
</head>
<body>
  <div class="card">
    <div class="logo">📺</div>
    <h1>HotPlayer Backend</h1>
    <div class="badge"><span class="dot"></span> En ligne</div>
    <div class="section">
      <h2>Endpoints disponibles</h2>
      <div class="endpoint"><span class="method get">GET</span><span class="path">/health</span></div>
      <div class="endpoint"><span class="method post">POST</span><span class="path">/api/auth/activate</span></div>
      <div class="endpoint"><span class="method get">GET</span><span class="path">/api/auth/status</span></div>
      <div class="endpoint"><span class="method post">POST</span><span class="path">/api/auth/heartbeat</span></div>
      <div class="endpoint"><span class="method post">POST</span><span class="path">/api/device/register</span></div>
      <div class="endpoint"><span class="method post">POST</span><span class="path">/api/device/authenticate</span></div>
    </div>
    <p class="version">v2.0.0 · Propulsé par NestJS + PostgreSQL</p>
  </div>
</body>
</html>`);
  });

  const port = process.env.PORT ?? 3000;
  await app.listen(port);
  console.log(`HotPlayer backend running on port ${port}`);
}

bootstrap();
