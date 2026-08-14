process.env.DB_PATH = ':memory:';
process.env.JWT_SECRET = 'test_secret_for_channel_filters_tests_only_min_32_chars';

const test = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');

const { initDB } = require('../config/database');
initDB();

const adminAuthRoutes           = require('../routes/admin');
const channelFiltersRoutes       = require('../routes/channelFilters');
const channelFiltersPublicRoutes = require('../routes/channelFiltersPublic');

const app = express();
app.use(express.json());
app.use('/api/admin', adminAuthRoutes);
app.use('/api/admin/channel-filters', channelFiltersRoutes);
app.use('/api/channel-filters', channelFiltersPublicRoutes);

let server;
let baseUrl;

test.before(() => new Promise(resolve => {
  server = app.listen(0, () => { baseUrl = `http://localhost:${server.address().port}`; resolve(); });
}));

test.after(() => new Promise(resolve => server.close(resolve)));

async function req(path, { method = 'GET', token = null, body = null } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(`${baseUrl}${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  return { status: res.status, data };
}

async function loginAdmin() {
  const { data } = await req('/api/admin/login', { method: 'POST', body: { username: 'admin', password: 'Admin@1234!' } });
  return data.token;
}

async function createList(token, name, category) {
  const { data } = await req('/api/admin/channel-filters', { method: 'POST', token, body: { name, category } });
  return data;
}

test('création d\'une liste', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'France Test', 'France');
  assert.equal(list.name, 'France Test');
  assert.equal(list.category, 'France');
  assert.equal(list.enabled, true);
  assert.equal(list.version, 0);
  assert.equal(list.hidden_count, 0);
  assert.ok(list.id);
});

test('suppression d\'une liste (cascade des entrées)', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Suppression Test', 'Divers');
  await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: { entries: [{ channel_id: '1', name: 'Chan A', status: 'INACTIVE' }] },
  });
  const del = await req(`/api/admin/channel-filters/${list.id}`, { method: 'DELETE', token });
  assert.equal(del.status, 200);
  const entries = await req(`/api/admin/channel-filters/${list.id}/entries`, { token });
  assert.equal(entries.status, 404);
});

test('activation/désactivation d\'une liste', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Toggle Test', 'Toggle');
  await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: { entries: [{ channel_id: 't1', name: 'Chan Toggle', status: 'INACTIVE' }] },
  });

  let pub = await req('/api/channel-filters');
  assert.ok(pub.data.lists.some(l => l.id === list.id));

  const deact = await req(`/api/admin/channel-filters/${list.id}/deactivate`, { method: 'POST', token });
  assert.equal(deact.status, 200);
  pub = await req('/api/channel-filters');
  assert.ok(!pub.data.lists.some(l => l.id === list.id), 'liste désactivée absente de la route publique');

  const act = await req(`/api/admin/channel-filters/${list.id}/activate`, { method: 'POST', token });
  assert.equal(act.status, 200);
  pub = await req('/api/channel-filters');
  assert.ok(pub.data.lists.some(l => l.id === list.id), 'liste réactivée réapparaît');
});

test('import valide: mix HIDE/INACTIVE + champs superflus', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Import Valide Test', 'Sports');
  const { status, data } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: {
      entries: [
        { channel_id: '1234', name: 'ESPN HD', category: 'Sports', status: 'INACTIVE', action: 'HIDE', extra_field: 'ignored' },
        { tvg_id: 'bein.us', name: 'BeIN Sport', action: 'HIDE' },
      ],
    },
  });
  assert.equal(status, 200);
  assert.equal(data.created, 2);
  assert.equal(data.version, 1);
});

test('import tolère le format {channels:[...]} donné en exemple dans la spec (pas seulement {entries:[...]})', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Format Channels Test', 'X');
  const { status, data } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: { channels: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }] },
  });
  assert.equal(status, 200);
  assert.equal(data.created, 1);
});

test('import tolère le format HotPlayer Channel Scanner ({hidden_candidates:[...]}, id préfixé)', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Scanner Format Test', 'France');
  const { status, data } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: {
      generated_at: '2026-08-14T04:26:10Z',
      hidden_candidates: [
        { id: 'hash:b42819f70603f465b03d2c6b', name: '#### GÉNÉRALE ####', status: 'INACTIVE' },
        { id: 'tvg:MaisonEtTravauxTV.fr', name: '|FR| MAISON &amp; TRAVAUX FHD', status: 'INACTIVE' },
        { id: 'tvg:GONGMAX', name: '|FR| GONG MAX FHD', status: 'INACTIVE' },
      ],
    },
  });
  assert.equal(status, 200);
  assert.equal(data.created, 3);

  const entries = await req(`/api/admin/channel-filters/${list.id}/entries`, { token });
  const maison = entries.data.entries.find(e => e.tvg_id === 'MaisonEtTravauxTV.fr');
  assert.ok(maison, 'entrée tvg:MaisonEtTravauxTV.fr doit être stockée avec tvg_id (pas channel_id)');
  assert.equal(maison.channel_id, null);
  assert.equal(maison.name, '|FR| MAISON & TRAVAUX FHD', 'entité HTML &amp; doit être décodée en &');

  const hashEntry = entries.data.entries.find(e => e.name.includes('GÉNÉRALE'));
  assert.ok(hashEntry, 'entrée hash: doit quand même être stockée (fallback nom)');
  assert.equal(hashEntry.channel_id, null, 'id "hash:..." ne doit jamais être utilisé comme channel_id');
  assert.equal(hashEntry.tvg_id, null);

  const pub = await req('/api/channel-filters');
  const l = pub.data.lists.find(x => x.id === list.id);
  assert.ok(l.hidden_channels.includes('tvg:maisonettravauxtv.fr'));
  assert.ok(l.hidden_channels.includes('tvg:gongmax'));
  assert.ok(!l.hidden_channels.some(k => k.startsWith('id:hash:')), 'aucune clé "id:hash:..." ne doit jamais être émise');
});

test('import tolère aussi un tableau JSON brut sans wrapper', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Format Array Test', 'X');
  const res = await fetch(`${baseUrl}/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify([{ channel_id: '1', name: 'A', status: 'INACTIVE' }]),
  });
  const data = await res.json();
  assert.equal(res.status, 200);
  assert.equal(data.created, 1);
});

test('JSON invalide: pas de tableau', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'JSON Invalide Test', 'X');
  const { status, data } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token, body: { entries: 'not-an-array' },
  });
  assert.equal(status, 400);
  const check = await req(`/api/admin/channel-filters`, { token });
  const l = check.data.lists.find(x => x.id === list.id);
  assert.equal(l.hidden_count, 0);
  assert.equal(l.version, 0);
});

test('fichier vide: entries=[]', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Fichier Vide Test', 'X');
  const { status } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token, body: { entries: [] },
  });
  assert.equal(status, 400);
});

test('doublons dans le batch', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Doublons Test', 'X');
  const { data } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: {
      entries: [
        { channel_id: '99', name: 'Chan A', status: 'INACTIVE' },
        { channel_id: '99', name: 'Chan A bis', status: 'INACTIVE' },
      ],
    },
  });
  assert.equal(data.created, 1);
  assert.equal(data.skipped_duplicate, 1);
});

test('entrées ACTIVE/SHOW/REVIEW/UNSTABLE jamais masquées', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Statuts Test', 'X');
  const { data } = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: {
      entries: [
        { channel_id: '1', name: 'A', status: 'ACTIVE' },
        { channel_id: '2', name: 'B', status: 'SHOW' },
        { channel_id: '3', name: 'C', status: 'REVIEW' },
        { channel_id: '4', name: 'D', status: 'UNSTABLE' },
        { channel_id: '5', name: 'E' },
        { channel_id: '6', name: 'F', status: 'INACTIVE' },
      ],
    },
  });
  assert.equal(data.created, 1);
  assert.equal(data.ignored, 5);
});

test('version + global_version incrémentés à chaque import réussi', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Version Test', 'X');
  const before = await req('/api/admin/channel-filters', { token });
  const gvBefore = before.data.global.global_version;

  const r1 = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token, body: { entries: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }] },
  });
  assert.equal(r1.data.version, 1);

  const r2 = await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token, body: { entries: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }, { channel_id: '2', name: 'B', status: 'INACTIVE' }], confirm: true },
  });
  assert.equal(r2.data.version, 2);

  const after = await req('/api/admin/channel-filters', { token });
  assert.ok(after.data.global.global_version > gvBefore);
});

test('ancienne liste conservée après import invalide', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Conservation Test', 'X');
  await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token, body: { entries: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }] },
  });
  const before = await req(`/api/admin/channel-filters/${list.id}/entries`, { token });
  assert.equal(before.data.total, 1);

  await req(`/api/admin/channel-filters/${list.id}/import`, { method: 'POST', token, body: { entries: [] } });
  await req(`/api/admin/channel-filters/${list.id}/import`, { method: 'POST', token, body: { entries: [{ channel_id: null, name: null, status: 'INACTIVE' }] } });

  const after = await req(`/api/admin/channel-filters/${list.id}/entries`, { token });
  assert.equal(after.data.total, 1, 'la liste précédente doit rester intacte après deux imports invalides');
});

test('accès admin uniquement (routes admin protégées, route publique libre)', async () => {
  const noToken = await req('/api/admin/channel-filters');
  assert.equal(noToken.status, 401);

  const badToken = await req('/api/admin/channel-filters', { token: 'garbage.token.value' });
  assert.equal(badToken.status, 401);

  const pub = await req('/api/channel-filters');
  assert.equal(pub.status, 200);
});

test('plusieurs listes actives simultanément', async () => {
  const token = await loginAdmin();
  const listA = await createList(token, 'Multi A', 'CatA');
  const listB = await createList(token, 'Multi B', 'CatB');
  await req(`/api/admin/channel-filters/${listA.id}/import`, { method: 'POST', token, body: { entries: [{ channel_id: 'a1', name: 'A1', status: 'INACTIVE' }] } });
  await req(`/api/admin/channel-filters/${listB.id}/import`, { method: 'POST', token, body: { entries: [{ channel_id: 'b1', name: 'B1', status: 'INACTIVE' }] } });

  const pub = await req('/api/channel-filters');
  const a = pub.data.lists.find(l => l.id === listA.id);
  const b = pub.data.lists.find(l => l.id === listB.id);
  assert.ok(a && b, 'les deux listes doivent apparaître simultanément');
  assert.ok(a.hidden_channels.includes('id:a1'));
  assert.ok(b.hidden_channels.includes('id:b1'));
  assert.ok(!a.hidden_channels.includes('id:b1'), 'pas de fuite entre catégories');
});

test('avertissement anomalie (409) puis confirmation applique l\'import', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Anomalie Test', 'X');
  await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: { entries: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }, { channel_id: '2', name: 'B', status: 'INACTIVE' }] },
  });

  const bigEntries = Array.from({ length: 10 }, (_, i) => ({ channel_id: `big${i}`, name: `Chan ${i}`, status: 'INACTIVE' }));
  const warn = await req(`/api/admin/channel-filters/${list.id}/import`, { method: 'POST', token, body: { entries: bigEntries } });
  assert.equal(warn.status, 409);
  assert.equal(warn.data.warning, true);

  const unchanged = await req('/api/admin/channel-filters', { token });
  assert.equal(unchanged.data.lists.find(l => l.id === list.id).hidden_count, 2, 'liste inchangée après un warning non confirmé');

  const confirmed = await req(`/api/admin/channel-filters/${list.id}/import`, { method: 'POST', token, body: { entries: bigEntries, confirm: true } });
  assert.equal(confirmed.status, 200);
  assert.equal(confirmed.data.created, 10);
});

test('premier import ne déclenche jamais l\'avertissement d\'anomalie', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Premier Import Test', 'X');
  const bigEntries = Array.from({ length: 50 }, (_, i) => ({ channel_id: `first${i}`, name: `Chan ${i}`, status: 'INACTIVE' }));
  const { status } = await req(`/api/admin/channel-filters/${list.id}/import`, { method: 'POST', token, body: { entries: bigEntries } });
  assert.equal(status, 200);
});

test('suppression d\'une entrée et vidage recalculent hidden_count', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Entry Ops Test', 'X');
  await req(`/api/admin/channel-filters/${list.id}/import`, {
    method: 'POST', token,
    body: { entries: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }, { channel_id: '2', name: 'B', status: 'INACTIVE' }] },
  });
  const entries = await req(`/api/admin/channel-filters/${list.id}/entries`, { token });
  const entryId = entries.data.entries[0].id;

  const del = await req(`/api/admin/channel-filters/${list.id}/entries/${entryId}`, { method: 'DELETE', token });
  assert.equal(del.data.hidden_count, 1);

  const clear = await req(`/api/admin/channel-filters/${list.id}/clear`, { method: 'POST', token });
  assert.equal(clear.status, 200);
  const after = await req('/api/admin/channel-filters', { token });
  assert.equal(after.data.lists.find(l => l.id === list.id).hidden_count, 0);
});

test('killswitch global masque toutes les listes de la route publique', async () => {
  const token = await loginAdmin();
  const list = await createList(token, 'Killswitch Test', 'X');
  await req(`/api/admin/channel-filters/${list.id}/import`, { method: 'POST', token, body: { entries: [{ channel_id: '1', name: 'A', status: 'INACTIVE' }] } });

  await req('/api/admin/channel-filters/settings', { method: 'PATCH', token, body: { enabled: false } });
  const pub = await req('/api/channel-filters');
  assert.equal(pub.data.enabled, false);
  assert.deepEqual(pub.data.lists, []);

  await req('/api/admin/channel-filters/settings', { method: 'PATCH', token, body: { enabled: true } });
  const pub2 = await req('/api/channel-filters');
  assert.equal(pub2.data.enabled, true);
});
