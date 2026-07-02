require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const helmet    = require('helmet');
const morgan    = require('morgan');
const rateLimit = require('express-rate-limit');
const path      = require('path');

const { initDB }      = require('./config/database');
const authRoutes      = require('./routes/auth');
const adminRoutes     = require('./routes/admin');
const streamRoutes    = require('./routes/stream');
const popupRoutes     = require('./routes/popup');
const webhookRoutes   = require('./routes/webhook');

const app  = express();
const PORT = process.env.PORT || 3000;

app.use(helmet({ contentSecurityPolicy: false }));
app.use(cors());
app.use(express.json());
app.use(morgan('combined'));

app.use('/api/', rateLimit({ windowMs: 15*60*1000, max: 200 }));
app.use('/api/auth/activate', rateLimit({ windowMs: 60*60*1000, max: 20 }));

app.use('/api/auth',    authRoutes);
app.use('/api/admin',   adminRoutes);
app.use('/api/stream',  streamRoutes);
app.use('/api/popup',   popupRoutes);
app.use('/api/webhook', webhookRoutes);

app.use('/admin', express.static(path.join(__dirname, '../admin')));
app.use('/',      express.static(path.join(__dirname, '../frontend')));

app.use((err, req, res, _next) => {
  res.status(err.status || 500).json({ error: err.message || 'Erreur serveur' });
});

initDB();

// Auto-expire sessions idle for more than 15 minutes
const { db: _db } = require('./config/database');
setInterval(() => {
  const { changes } = _db.prepare(`UPDATE sessions SET is_active=0 WHERE is_active=1 AND last_seen < datetime('now','-10 minutes')`).run();
  if (changes > 0) console.log(`[session-gc] ${changes} session(s) expirée(s)`);
}, 5 * 60 * 1000);

app.listen(PORT, () => {
  console.log(`HotPlayer backend → http://localhost:${PORT}`);
  console.log(`Admin panel       → http://localhost:${PORT}/admin`);
});

module.exports = app;
