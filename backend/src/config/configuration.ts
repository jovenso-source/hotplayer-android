export default () => ({
  port: parseInt(process.env.PORT ?? '3000', 10),

  database: {
    host:     process.env.DB_HOST     ?? 'localhost',
    port:     parseInt(process.env.DB_PORT ?? '5432', 10),
    name:     process.env.DB_NAME     ?? 'hotplayer',
    user:     process.env.DB_USER     ?? 'postgres',
    password: process.env.DB_PASSWORD ?? '',
  },

  jwt: {
    secret:          process.env.JWT_SECRET          ?? 'CHANGE_THIS_IN_PRODUCTION',
    expiresIn:       process.env.JWT_EXPIRES_IN      ?? '24h',
    refreshExpiresIn: process.env.JWT_REFRESH_EXPIRES ?? '30d',
  },

  security: {
    // Fenêtre anti-replay : le timestamp du client doit être dans ±N secondes
    signatureWindowSeconds: parseInt(process.env.SIGNATURE_WINDOW_SECS ?? '300', 10),
    // Nombre de nonces conservés par session (sliding window)
    maxNoncesPerSession: parseInt(process.env.MAX_NONCES ?? '100', 10),
    // Score de risque au-delà duquel l'appareil est automatiquement suspendu
    autoSuspendRiskScore: parseInt(process.env.AUTO_SUSPEND_SCORE ?? '80', 10),
    // Nombre max d'appareils par utilisateur (valeur par défaut, surchargeable par user)
    defaultMaxDevices: parseInt(process.env.DEFAULT_MAX_DEVICES ?? '1', 10),
  },
});
