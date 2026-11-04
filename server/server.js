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
  if (!r) { r = { cmds: [], replies: new Map(), asks: new Map(), events: [], study: null, studyTs: 0, state: null, input: [], inWait: [], usbSeen: 0, last: 0, n: 0 }; rooms.set(id, r); }
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
app.get('/api/status', (req, res) => { const r = room(req, res); if (!r) return; res.json({ usbOnline: Date.now() - r.usbSeen < 20e3, waiting: r.cmds.length, state: r.state }); });
// USB -> publishes its mode (full access on/off and when it ends)
app.post('/api/state', (req, res) => { const r = room(req, res); if (!r) return; const b = req.body || {}; r.state = { full: !!b.full, until: Math.max(0, Number(b.until) || 0), remote: !!b.remote }; res.json({ ok: true }); });
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
  id: String(e.id || '').slice(0, 60), title: String(e.title || '').slice(0, 80), body: String(e.body || '').slice(0, 200), time: String(e.time || '').slice(0, 5), date: e.date ? String(e.date).slice(0, 10) : '', every: e.every === 'day' ? 'day' : (e.every === 'week' ? 'week' : ''), weekday: Math.min(7, Math.max(0, parseInt(e.weekday) || 0)) })); res.json({ ok: true }); });
app.get('/api/events', (req, res) => { const r = room(req, res); if (!r) return; res.json({ events: r.events }); });
// USB -> publishes the homework list and timetable so the phone can show them (phone edits go through commands)
app.post('/api/study', (req, res) => { const r = room(req, res); if (!r) return; const b = req.body || {};
  const hw = (Array.isArray(b.hw) ? b.hw : []).slice(0, 200).map(h => ({ i: parseInt(h.i) || 0, subj: String(h.subj || '').slice(0, 40), text: String(h.text || '').slice(0, 200), due: String(h.due || '').slice(0, 10), done: !!h.done }));
  const lessons = (Array.isArray(b.lessons) ? b.lessons : []).slice(0, 200).map(l => ({ i: parseInt(l.i) || 0, day: String(l.day || '').slice(0, 3), time: String(l.time || '').slice(0, 5), subj: String(l.subj || '').slice(0, 40) }));
  r.study = { hw, lessons, alarm: !!b.alarm, lead: Math.min(60, Math.max(1, parseInt(b.lead) || 5)) }; r.studyTs = Date.now(); res.json({ ok: true }); });
app.get('/api/study', (req, res) => { const r = room(req, res); if (!r) return; res.json({ study: r.study, ts: r.studyTs, usbOnline: Date.now() - r.usbSeen < 20e3 }); });

// phone -> mouse/keyboard events (small batches, fire and forget). Only a fixed set of event shapes is accepted.
const num = (v, lim) => Math.max(-lim, Math.min(lim, Number(v) || 0));
function cleanInput(e) {
  if (!e || typeof e !== 'object') return null;
  if (e.t === 'm') return { t: 'm', dx: num(e.dx, 2500), dy: num(e.dy, 2500) };
  if (e.t === 'c') return { t: 'c', b: e.b === 'r' ? 'r' : e.b === 'm' ? 'm' : 'l', n: Math.max(1, Math.min(3, parseInt(e.n) || 1)) };
  if (e.t === 'd') return { t: 'd', s: e.s === 'down' ? 'down' : 'up' };
  if (e.t === 's') return { t: 's', dy: num(e.dy, 40) };
  if (e.t === 't') return { t: 't', text: String(e.text || '').slice(0, 400) };
  if (e.t === 'k' && /^[a-z0-9+]{1,30}$/i.test(String(e.k || ''))) return { t: 'k', k: String(e.k) };
  return null;
}
app.post('/api/input', (req, res) => {
  const r = room(req, res); if (!r) return; const now = Date.now();
  const ev = (Array.isArray(req.body.events) ? req.body.events : []).slice(0, 60).map(cleanInput).filter(Boolean);
  for (const e of ev) r.input.push({ e, t: now });
  if (r.input.length > 300) r.input.splice(0, r.input.length - 300);
  r.inWait.splice(0).forEach(f => f()); res.json({ ok: true });
});
// USB -> wait for mouse/keyboard events (wakes up the moment they arrive). Events older than 4 seconds are dropped, never replayed.
app.get('/api/input/poll', async (req, res) => {
  const r = room(req, res); if (!r) return;
  const take = () => { const now = Date.now(); const fresh = r.input.filter(x => now - x.t < 4000).map(x => x.e); r.input.length = 0; return fresh; };
  let ev = take(); if (ev.length) return res.json({ events: ev });
  await new Promise(ok => { let f; const to = setTimeout(() => { const i = r.inWait.indexOf(f); if (i >= 0) r.inWait.splice(i, 1); ok(); }, 20000); f = () => { clearTimeout(to); ok(); }; r.inWait.push(f); });
  ev = take(); res.json(ev.length ? { events: ev } : { none: true });
});

app.listen(process.env.PORT || 3000, () => console.log('up'));
