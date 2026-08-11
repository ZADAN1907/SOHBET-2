const express = require('express');
const bcrypt = require('bcryptjs');
const { v4: uuid } = require('uuid');
const db = require('../db');
const { requireAuth } = require('../auth');
const { serializeMessage, createMessage, getDisappearSeconds } = require('../messages');

const router = express.Router();

function serializeRoom(row, viewerId) {
  const memberCount = db.prepare('SELECT COUNT(*) c FROM room_members WHERE room_id = ?').get(row.id).c;
  const isMember = viewerId
    ? !!db.prepare('SELECT 1 FROM room_members WHERE room_id = ? AND user_id = ?').get(row.id, viewerId)
    : false;
  return {
    id: row.id,
    name: row.name,
    isPrivate: !!row.password_hash,
    logo: row.logo,
    createdBy: row.created_by,
    createdAt: row.created_at,
    memberCount,
    isMember,
    disappearSeconds: getDisappearSeconds(row.id),
  };
}

function getMembership(roomId, userId) {
  return db.prepare('SELECT * FROM room_members WHERE room_id = ? AND user_id = ?').get(roomId, userId);
}

function canModerate(roomId, userId) {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(roomId);
  if (room && room.created_by === userId) return true;
  const m = getMembership(roomId, userId);
  return !!m && (m.role === 'owner' || m.role === 'moderator');
}

// Herkese açık odalar + kullanıcının üye olduğu özel odalar
router.get('/', requireAuth, (req, res) => {
  const rows = db.prepare(`
    SELECT DISTINCT r.* FROM rooms r
    LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = ?
    WHERE r.password_hash IS NULL OR m.user_id IS NOT NULL
    ORDER BY r.created_at DESC
  `).all(req.user.uid);
  res.json(rows.map(r => serializeRoom(r, req.user.uid)));
});

router.get('/:id', requireAuth, (req, res) => {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(req.params.id);
  if (!room) return res.status(404).json({ error: 'Oda bulunamadı' });
  res.json(serializeRoom(room, req.user.uid));
});

router.post('/', requireAuth, (req, res) => {
  const { name, password, logo } = req.body || {};
  if (!name || !name.trim()) return res.status(400).json({ error: 'Oda adı zorunlu' });

  const id = uuid();
  const now = Date.now();
  const passwordHash = password ? bcrypt.hashSync(password, 10) : null;

  db.prepare(`INSERT INTO rooms (id, name, password_hash, logo, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)`)
    .run(id, name.trim(), passwordHash, logo || null, req.user.uid, now);
  db.prepare(`INSERT INTO room_members (room_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)`)
    .run(id, req.user.uid, now);

  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(id);
  res.status(201).json(serializeRoom(room, req.user.uid));
});

router.post('/:id/join', requireAuth, (req, res) => {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(req.params.id);
  if (!room) return res.status(404).json({ error: 'Oda bulunamadı' });

  const banned = db.prepare('SELECT 1 FROM room_banned WHERE room_id = ? AND user_id = ?').get(room.id, req.user.uid);
  if (banned) return res.status(403).json({ error: 'Bu odadan yasaklısın' });

  if (room.password_hash) {
    const { password } = req.body || {};
    if (!password || !bcrypt.compareSync(password, room.password_hash)) {
      return res.status(401).json({ error: 'Oda şifresi hatalı' });
    }
  }

  db.prepare(`INSERT OR IGNORE INTO room_members (room_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)`)
    .run(room.id, req.user.uid, Date.now());
  res.json(serializeRoom(room, req.user.uid));
});

router.post('/:id/leave', requireAuth, (req, res) => {
  db.prepare('DELETE FROM room_members WHERE room_id = ? AND user_id = ?').run(req.params.id, req.user.uid);
  res.json({ ok: true });
});

router.get('/:id/members', requireAuth, (req, res) => {
  const rows = db.prepare(`
    SELECT u.id, u.username, u.profile_photo, u.is_online, m.role
    FROM room_members m JOIN users u ON u.id = m.user_id
    WHERE m.room_id = ?
  `).all(req.params.id);
  res.json(rows);
});

router.post('/:id/ban/:userId', requireAuth, (req, res) => {
  if (!canModerate(req.params.id, req.user.uid)) {
    return res.status(403).json({ error: 'Bu işlem için yetkin yok' });
  }
  db.prepare('INSERT OR IGNORE INTO room_banned (room_id, user_id, banned_at) VALUES (?, ?, ?)')
    .run(req.params.id, req.params.userId, Date.now());
  db.prepare('DELETE FROM room_members WHERE room_id = ? AND user_id = ?').run(req.params.id, req.params.userId);
  res.json({ ok: true });
});

// Sadece odadan at (banlamadan) — kurucu/moderatör
router.post('/:id/kick/:userId', requireAuth, (req, res) => {
  if (!canModerate(req.params.id, req.user.uid)) {
    return res.status(403).json({ error: 'Bu işlem için yetkin yok' });
  }
  db.prepare('DELETE FROM room_members WHERE room_id = ? AND user_id = ?').run(req.params.id, req.params.userId);
  res.json({ ok: true });
});

// Odayı sadece kurucusu yeniden adlandırabilir / şifresini / avatarını değiştirebilir
router.patch('/:id', requireAuth, (req, res) => {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(req.params.id);
  if (!room) return res.status(404).json({ error: 'Oda bulunamadı' });
  if (room.created_by !== req.user.uid) return res.status(403).json({ error: 'Sadece oda kurucusu düzenleyebilir' });

  const { name, password, logo } = req.body || {};
  const newName = name !== undefined ? name.trim() : room.name;
  const newPasswordHash = password !== undefined
    ? (password ? bcrypt.hashSync(password, 10) : null)
    : room.password_hash;
  const newLogo = logo !== undefined ? logo : room.logo;

  db.prepare('UPDATE rooms SET name = ?, password_hash = ?, logo = ? WHERE id = ?')
    .run(newName, newPasswordHash, newLogo, room.id);

  const updated = db.prepare('SELECT * FROM rooms WHERE id = ?').get(room.id);
  res.json(serializeRoom(updated, req.user.uid));
});

// Kaybolan mesajlar (WhatsApp'taki disappearing messages) — sadece kurucu ayarlayabilir.
// seconds: 0 = kapalı, aksi halde bu odaya yeni gönderilen her mesaj bu süre sonunda kalıcı silinir.
router.patch('/:id/disappearing', requireAuth, (req, res) => {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(req.params.id);
  if (!room) return res.status(404).json({ error: 'Oda bulunamadı' });
  if (room.created_by !== req.user.uid) return res.status(403).json({ error: 'Sadece oda kurucusu ayarlayabilir' });

  const seconds = Math.max(0, Number(req.body?.seconds) || 0);
  db.prepare(`
    INSERT INTO chat_settings (chat_key, disappear_seconds) VALUES (?, ?)
    ON CONFLICT(chat_key) DO UPDATE SET disappear_seconds = excluded.disappear_seconds
  `).run(room.id, seconds || null);

  res.json({ ok: true, disappearSeconds: seconds });
});

// Odayı sil (sadece kurucu) — mesajlar/üyeler CASCADE ile birlikte silinir
router.delete('/:id', requireAuth, (req, res) => {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(req.params.id);
  if (!room) return res.status(404).json({ error: 'Oda bulunamadı' });
  if (room.created_by !== req.user.uid) return res.status(403).json({ error: 'Sadece oda kurucusu silebilir' });

  db.prepare('DELETE FROM messages WHERE room_id = ?').run(room.id);
  db.prepare('DELETE FROM rooms WHERE id = ?').run(room.id);
  res.json({ ok: true });
});

// Oda geçmişini temizle (sadece kurucu) — oda kalır, sadece mesajlar silinir
router.delete('/:id/messages', requireAuth, (req, res) => {
  const room = db.prepare('SELECT * FROM rooms WHERE id = ?').get(req.params.id);
  if (!room) return res.status(404).json({ error: 'Oda bulunamadı' });
  if (room.created_by !== req.user.uid) return res.status(403).json({ error: 'Sadece oda kurucusu temizleyebilir' });

  db.prepare('DELETE FROM messages WHERE room_id = ?').run(room.id);
  req.app.get('io')?.to(`room:${room.id}`).emit('room:cleared', { roomId: room.id });
  res.json({ ok: true });
});

router.get('/:id/messages', requireAuth, (req, res) => {
  const before = req.query.before ? Number(req.query.before) : Date.now() + 1;
  const limit = Math.min(Number(req.query.limit) || 50, 100);
  const rows = db.prepare(`
    SELECT * FROM messages WHERE room_id = ? AND created_at < ?
    ORDER BY created_at DESC LIMIT ?
  `).all(req.params.id, before, limit);
  res.json(rows.reverse().map(serializeMessage));
});

// REST fallback (asıl gönderim socket üzerinden 'message:send' ile yapılır)
router.post('/:id/messages', requireAuth, (req, res) => {
  if (!getMembership(req.params.id, req.user.uid)) {
    return res.status(403).json({ error: 'Bu odanın üyesi değilsin' });
  }
  const { text, replyToId } = req.body || {};
  if (!text || !text.trim()) return res.status(400).json({ error: 'Mesaj boş olamaz' });
  const msg = createMessage({ roomId: req.params.id, senderId: req.user.uid, text: text.trim(), replyToId });
  req.app.get('io')?.to(`room:${req.params.id}`).emit('message:new', msg);
  res.status(201).json(msg);
});

module.exports = router;
