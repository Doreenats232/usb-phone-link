// Relay between the phone app and USB Desktop. Both sides send the same pairing code; it is hashed to pick a private room.
const express = require('express'), crypto = require('crypto');
const app = express(); app.use(express.json({ limit: '20kb' }));
app.use((req, res, next) => { res.set({ 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'x-pair, content-type', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' }); if (req.method === 'OPTIONS') return res.sendStatus(204); next(); });
const rooms = new Map(); // roomId -> { cmds: [], replies: Map, waiters: [], usbSeen: 0, last: 0 }
const sha = s => crypto.createHash('sha256').update(String(s)).digest('hex').slice(0, 32);
const sleep = ms => new Promise(r => setTimeout(r, ms));

function room(req, res) {
  const code = String(req.get('x-pair') || '').trim();
  if (code.length < 8) { res.status(401).json({ error: 'Pairing code must be at least 8 characters.' }); return null; }
  const id = sha(code); let r = rooms.get(id);
  if (!r) { r = { cmds: [], replies: new Map(), usbSeen: 0, last: 0, n: 0 }; rooms.set(id, r); }
  r.last = Date.now(); return r;
}
// forget idle rooms and old items
setInterval(() => {
  const now = Date.now();
  for (const [id, r] of rooms) {
    r.cmds = r.cmds.filter(c => now - c.t < 10 * 60e3);
    for (const [k, v] of r.replies) if (now - v.t < 0 || now - v.t > 30 * 60e3) r.replies.delete(k);
    if (now - r.last > 24 * 3600e3) rooms.delete(id);
  }
}, 60e3);

app.get('/', (_, res) => res.send('USB phone link is running.'));

// phone -> send a command
app.post('/api/send', (req, res) => {
  const r = room(req, res); if (!r) return;
  const text = String(req.body.text || '').trim().slice(0, 2000); if (!text) return res.status(400).json({ error: 'Empty message.' });
  if (r.cmds.length > 20) return res.status(429).json({ error: 'Too many waiting commands. Is USB Desktop open?' });
  const id = Date.now().toString(36) + (++r.n).toString(36);
  r.cmds.push({ id, text, t: Date.now() }); res.json({ id });
});
// phone -> wait for the answer to one command (long poll)
app.get('/api/result', async (req, res) => {
  const r = room(req, res); if (!r) return; const id = String(req.query.id || '');
  const end = Date.now() + 25e3;
  while (Date.now() < end) { const v = r.replies.get(id); if (v) { r.replies.delete(id); return res.json({ done: true, text: v.text }); } await sleep(500); }
  res.json({ done: false, usbOnline: Date.now() - r.usbSeen < 20e3 });
});
// phone -> is the USB online?
app.get('/api/status', (req, res) => { const r = room(req, res); if (!r) return; res.json({ usbOnline: Date.now() - r.usbSeen < 20e3, waiting: r.cmds.length }); });
// USB -> next command (long poll)
app.get('/api/poll', async (req, res) => {
  const r = room(req, res); if (!r) return; const end = Date.now() + 25e3;
  while (Date.now() < end) { r.usbSeen = Date.now(); const c = r.cmds.shift(); if (c) return res.json({ id: c.id, text: c.text }); await sleep(700); }
  res.json({ none: true });
});
// USB -> answer
app.post('/api/reply', (req, res) => {
  const r = room(req, res); if (!r) return;
  r.replies.set(String(req.body.id || ''), { text: String(req.body.text || '').slice(0, 6000), t: Date.now() }); res.json({ ok: true });
});

app.listen(process.env.PORT || 3000, () => console.log('up'));
