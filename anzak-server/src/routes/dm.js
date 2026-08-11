const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');
const { serializeMessage, createMessage, buildDmId, getDisappearSeconds } = require('../messages');

const router = express.Router();

// Kaybolan mesajlar (WhatsApp'taki disappearing messages) — DM'de her iki taraf da ayarlayabilir.
router.patch('/:otherId/disappearing', requireAuth, (req, res) => {
  const dmId = buildDmId(req.user.uid, req.params.otherId);
  const seconds = Math.max(0, Number(req.body?.seconds) || 0);
  db.prepare(`
    INSERT INTO chat_settings (chat_key, disappear_seconds) VALUES (?, ?)
    ON CONFLICT(chat_key) DO UPDATE SET disappear_seconds = excluded.disappear_seconds
  `).run(dmId, seconds || null);

  const io = req.app.get('io');
  io?.to(`user:${req.params.otherId}`).emit('dm:disappearing-changed', { dmId, disappearSeconds: seconds });
  io?.to(`user:${req.user.uid}`).emit('dm:disappearing-changed', { dmId, disappearSeconds: seconds });
  res.json({ ok: true, disappearSeconds: seconds });
});

router.get('/:otherId/disappearing', requireAuth, (req, res) => {
  const dmId = buildDmId(req.user.uid, req.params.otherId);
  res.json({ disappearSeconds: getDisappearSeconds(dmId) });
});

router.get('/:otherId/messages', requireAuth, (req, res) => {
  const dmId = buildDmId(req.user.uid, req.params.otherId);
  const before = req.query.before ? Number(req.query.before) : Date.now() + 1;
  const limit = Math.min(Number(req.query.limit) || 50, 100);
  const rows = db.prepare(`
    SELECT * FROM messages WHERE dm_id = ? AND created_at < ?
    ORDER BY created_at DESC LIMIT ?
  `).all(dmId, before, limit);
  res.json(rows.reverse().map(serializeMessage));
});

router.post('/:otherId/messages', requireAuth, (req, res) => {
  const blocked = db.prepare('SELECT 1 FROM blocks WHERE user_id = ? AND blocked_id = ?')
    .get(req.params.otherId, req.user.uid);
  if (blocked) return res.status(403).json({ error: 'Bu kullanıcı seni engellemiş' });

  const { text, replyToId } = req.body || {};
  if (!text || !text.trim()) return res.status(400).json({ error: 'Mesaj boş olamaz' });

  const dmId = buildDmId(req.user.uid, req.params.otherId);
  const msg = createMessage({ dmId, senderId: req.user.uid, text: text.trim(), replyToId });

  const io = req.app.get('io');
  io?.to(`user:${req.params.otherId}`).emit('message:new', msg);
  io?.to(`user:${req.user.uid}`).emit('message:new', msg);
  res.status(201).json(msg);
});

// İki taraftan biri DM geçmişini temizleyebilir (paylaşılan geçmiş kalıcı olarak silinir)
router.delete('/:otherId/messages', requireAuth, (req, res) => {
  const dmId = buildDmId(req.user.uid, req.params.otherId);
  db.prepare('DELETE FROM messages WHERE dm_id = ?').run(dmId);
  const io = req.app.get('io');
  io?.to(`user:${req.params.otherId}`).emit('dm:cleared', { dmId });
  io?.to(`user:${req.user.uid}`).emit('dm:cleared', { dmId });
  res.json({ ok: true });
});

// Kullanıcının tüm DM özetlerini getir (son mesaj + karşı taraf bilgisi)
router.get('/', requireAuth, (req, res) => {
  const rows = db.prepare(`
    SELECT * FROM messages
    WHERE dm_id LIKE '%' || ? || '%'
    ORDER BY created_at DESC
  `).all(req.user.uid);

  const seen = new Map();
  for (const row of rows) {
    const [a, b] = row.dm_id.split('_');
    const otherId = a === req.user.uid ? b : (b === req.user.uid ? a : null);
    if (!otherId || seen.has(otherId)) continue;
    seen.set(otherId, row);
  }

  const summaries = [...seen.entries()].map(([otherId, lastMsg]) => {
    const other = db.prepare('SELECT id, username, profile_photo, is_online FROM users WHERE id = ?').get(otherId);
    if (!other) return null;
    return {
      otherUid: other.id,
      otherUsername: other.username,
      otherPhoto: other.profile_photo,
      online: !!other.is_online,
      lastMessage: serializeMessage(lastMsg),
    };
  }).filter(Boolean);

  res.json(summaries);
});

module.exports = router;
