const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');
const { serializeMessage } = require('../messages');

const router = express.Router();

function canModerateRoom(roomId, userId) {
  if (!roomId) return false;
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(roomId);
  if (room && room.created_by === userId) return true;
  const m = db.prepare('SELECT role FROM room_members WHERE room_id = ? AND user_id = ?').get(roomId, userId);
  return !!m && (m.role === 'owner' || m.role === 'moderator');
}

function broadcast(req, msg) {
  const io = req.app.get('io');
  if (!io) return;
  if (msg.roomId) io.to(`room:${msg.roomId}`).emit('message:updated', msg);
  if (msg.dmId) {
    const [a, b] = msg.dmId.split('_');
    io.to(`user:${a}`).emit('message:updated', msg);
    io.to(`user:${b}`).emit('message:updated', msg);
  }
}

router.patch('/:id', requireAuth, (req, res) => {
  const row = db.prepare('SELECT * FROM messages WHERE id = ?').get(req.params.id);
  if (!row || row.deleted) return res.status(404).json({ error: 'Mesaj bulunamadı' });
  if (row.sender_id !== req.user.uid) return res.status(403).json({ error: 'Sadece kendi mesajını düzenleyebilirsin' });

  const { text } = req.body || {};
  if (!text || !text.trim()) return res.status(400).json({ error: 'Mesaj boş olamaz' });

  db.prepare('UPDATE messages SET text = ?, edited_at = ? WHERE id = ?')
    .run(text.trim(), Date.now(), row.id);

  const msg = serializeMessage(db.prepare('SELECT * FROM messages WHERE id = ?').get(row.id));
  broadcast(req, msg);
  res.json(msg);
});

router.delete('/:id', requireAuth, (req, res) => {
  const row = db.prepare('SELECT * FROM messages WHERE id = ?').get(req.params.id);
  if (!row || row.deleted) return res.status(404).json({ error: 'Mesaj bulunamadı' });

  const isOwner = row.sender_id === req.user.uid;
  const isMod = canModerateRoom(row.room_id, req.user.uid);
  if (!isOwner && !isMod) return res.status(403).json({ error: 'Bu mesajı silme yetkin yok' });

  db.prepare('UPDATE messages SET deleted = 1, text = NULL, media_url = NULL WHERE id = ?').run(row.id);

  const msg = serializeMessage(db.prepare('SELECT * FROM messages WHERE id = ?').get(row.id));
  broadcast(req, msg);
  res.json({ ok: true });
});

router.post('/:id/read', requireAuth, (req, res) => {
  db.prepare('INSERT OR IGNORE INTO message_reads (message_id, user_id, read_at) VALUES (?, ?, ?)')
    .run(req.params.id, req.user.uid, Date.now());
  const row = db.prepare('SELECT * FROM messages WHERE id = ?').get(req.params.id);
  if (row) broadcast(req, serializeMessage(row));
  res.json({ ok: true });
});

router.post('/:id/react', requireAuth, (req, res) => {
  const { emoji } = req.body || {};
  if (!emoji) return res.status(400).json({ error: 'emoji zorunlu' });
  db.prepare('INSERT OR IGNORE INTO message_reactions (message_id, user_id, emoji) VALUES (?, ?, ?)')
    .run(req.params.id, req.user.uid, emoji);
  const row = db.prepare('SELECT * FROM messages WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: 'Mesaj bulunamadı' });
  const msg = serializeMessage(row);
  broadcast(req, msg);
  res.json(msg);
});

router.delete('/:id/react', requireAuth, (req, res) => {
  const { emoji } = req.body || {};
  db.prepare('DELETE FROM message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?')
    .run(req.params.id, req.user.uid, emoji);
  const row = db.prepare('SELECT * FROM messages WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: 'Mesaj bulunamadı' });
  const msg = serializeMessage(row);
  broadcast(req, msg);
  res.json(msg);
});

module.exports = router;
