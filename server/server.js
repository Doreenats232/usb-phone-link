// Relay between the phone app and USB Desktop. Both sides send the same pairing code; it is hashed to pick a private room.
const express = require('express'), crypto = require('crypto');
const app = express(); app.use(express.json({ limit: '12mb' }));
app.use((req, res, next) => { res.set({ 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'x-pair, content-type', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' }); if (req.method === 'OPTIONS') return res.sendStatus(204); next(); });
const rooms = new Map(); // roomId -> { cmds: [], replies: Map, waiters: [], usbSeen: 0, last: 0 }
const sha = s => crypto.createHash('sha256').update(String(s)).digest('hex').slice(0, 32);
const sleep = ms => new Promise(r => setTimeout(r, ms));

function room(req, res) {
  const code = String(req.get('x-pair') || '').trim();
  if (code.length < 8) { res.status(401).json({ error: 'Pairing code must be at least 8 characters.' }); return null; }
  const id = sha(code); let r = rooms.get(id);
  if (!r) { r = { cmds: [], replies: new Map(), asks: new Map(), events: [], usbSeen: 0, last: 0, n: 0 }; rooms.set(id, r); }
  r.last = Date.now(); return r;
}
// forget idle rooms and old items
setInterval(() => {
  const now = Date.now();
  for (const [id, r] of rooms) {
    r.cmds = r.cmds.filter(c => now - c.t < 10 * 60e3);
    for (const [k, v] of r.replies) if (now - v.t < 0 || now - v.t > 30 * 60e3) r.replies.delete(k);
    for (const [k, a] of r.asks) if (now - a.t > 5 * 60e3) r.asks.delete(k);
    if (now - r.last > 24 * 3600e3) rooms.delete(id);
  }
}, 60e3);

app.get('/', (_, res) => res.send('USB phone link is running.'));
app.get('/ui.html', (_, res) => res.set('Cache-Control', 'no-store').sendFile(require('path').join(__dirname, 'ui.html')));

// phone -> send a command (optionally with a file: {name, data(base64)})
app.post('/api/send', (req, res) => {
  const r = room(req, res); if (!r) return;
  const text = String(req.body.text || '').trim().slice(0, 2000);
  let file = null;
  if (req.body.file && req.body.file.data) {
    const data = String(req.body.file.data); if (data.length > 11.5e6) return res.status(413).json({ error: 'File too big (max 8 MB).' });
    file = { name: String(req.body.file.name || 'file').slice(0, 120), data };
  }
  if (!text && !file) return res.status(400).json({ error: 'Empty message.' });
  if (r.cmds.length > 20) return res.status(429).json({ error: 'Too many waiting commands. Is USB Desktop open?' });
  const id = Date.now().toString(36) + (++r.n).toString(36);
  r.cmds.push({ id, text, file, t: Date.now() }); res.json({ id });
});
// phone -> wait for the answer to one command (long poll). Also reports approval requests from the PC.
app.get('/api/result', async (req, res) => {
  const r = room(req, res); if (!r) return; const id = String(req.query.id || '');
  const seen = String(req.query.seen || '').split(',').filter(Boolean).sort().join(',');
  const open = () => [...r.asks.values()].filter(a => a.answer === null);
  const end = Date.now() + 25e3;
  while (Date.now() < end) {
    const v = r.replies.get(id); if (v) { r.replies.delete(id); return res.json({ done: true, text: v.text, file: v.file || null }); }
    const cur = open().map(a => a.id).sort().join(',');
    if (cur !== seen) return res.json({ done: false, usbOnline: Date.now() - r.usbSeen < 20e3, asks: open().map(a => ({ id: a.id, text: a.text })) });
    await sleep(500);
  }
  res.json({ done: false, usbOnline: Date.now() - r.usbSeen < 20e3, asks: open().map(a => ({ id: a.id, text: a.text })) });
});
// phone -> is the USB online?
app.get('/api/status', (req, res) => { const r = room(req, res); if (!r) return; res.json({ usbOnline: Date.now() - r.usbSeen < 20e3, waiting: r.cmds.length }); });
// USB -> next command (long poll)
app.get('/api/poll', async (req, res) => {
  const r = room(req, res); if (!r) return; const end = Date.now() + 25e3;
  while (Date.now() < end) { r.usbSeen = Date.now(); const c = r.cmds.shift(); if (c) return res.json({ id: c.id, text: c.text, file: c.file || null }); await sleep(700); }
  res.json({ none: true });
});
// USB -> answer
app.post('/api/reply', (req, res) => {
  const r = room(req, res); if (!r) return;
  let file = null;
  if (req.body.file && req.body.file.data && String(req.body.file.data).length <= 11.5e6) file = { name: String(req.body.file.name || 'file').slice(0, 120), data: String(req.body.file.data) };
  r.replies.set(String(req.body.id || ''), { text: String(req.body.text || '').slice(0, 6000), file, t: Date.now() }); res.json({ ok: true });
});


// USB -> ask the phone to approve something; phone or PC answers (first answer wins)
app.post('/api/ask', (req, res) => { const r = room(req, res); if (!r) return; const id = String(req.body.id || ''); if (!id) return res.status(400).json({ error: 'id' });
  r.asks.set(id, { id, text: String(req.body.text || '').slice(0, 500), t: Date.now(), answer: null }); res.json({ ok: true }); });
app.post('/api/answer', (req, res) => { const r = room(req, res); if (!r) return; const a = r.asks.get(String(req.body.id || ''));
  if (!a) return res.json({ ok: false, gone: true }); if (a.answer !== null) return res.json({ ok: false, answer: a.answer });
  a.answer = !!req.body.allow; a.t = Date.now(); res.json({ ok: true, answer: a.answer }); });
app.get('/api/answer', (req, res) => { const r = room(req, res); if (!r) return; const a = r.asks.get(String(req.query.id || '')); res.json({ answer: a ? a.answer : null }); });
// USB -> publishes upcoming alarms/homework so the phone can schedule its own notifications
app.post('/api/events', (req, res) => { const r = room(req, res); if (!r) return; r.events = (Array.isArray(req.body.events) ? req.body.events : []).slice(0, 100).map(e => ({
  id: String(e.id || '').slice(0, 60), title: String(e.title || '').slice(0, 80), body: String(e.body || '').slice(0, 200), time: String(e.time || '').slice(0, 5), date: e.date ? String(e.date).slice(0, 10) : '', every: e.every === 'day' ? 'day' : '' })); res.json({ ok: true }); });
app.get('/api/events', (req, res) => { const r = room(req, res); if (!r) return; res.json({ events: r.events }); });

app.listen(process.env.PORT || 3000, () => console.log('up'));
