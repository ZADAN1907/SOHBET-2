const express = require('express');
const db = require('../db');
const { requireAuth } = require('../auth');
const { publicUser } = require('./auth');

const router = express.Router();

// Profil güncelle (bio, fotoğraf, bildirim ayarları)
router.patch('/me', requireAuth, (req, res) => {
  const { bio, profilePhoto, messageNotifications, soundNotifications } = req.body || {};
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.uid);
  if (!user) return res.status(404).json({ error: 'Kullanıcı bulunamadı' });

  db.prepare(`
    UPDATE users SET
      bio = COALESCE(?, bio),
      profile_photo = COALESCE(?, profile_photo),
      message_notifications = COALESCE(?, message_notifications),
      sound_notifications = COALESCE(?, sound_notifications)
    WHERE id = ?
  `).run(
    bio ?? null,
    profilePhoto ?? null,
    messageNotifications === undefined ? null : (messageNotifications ? 1 : 0),
    soundNotifications === undefined ? null : (soundNotifications ? 1 : 0),
    req.user.uid
  );

  const updated = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.uid);
  res.json(publicUser(updated));
});

// Tüm kullanıcıları listele (DM ekranında "Diğer Kullanıcılar" bölümü için).
// Kendimizi ve engellediğimiz kullanıcıları listeden çıkarıyoruz.
router.get('/', requireAuth, (req, res) => {
  const rows = db.prepare(`
    SELECT id, username, profile_photo, is_online FROM users
    WHERE id != ?
      AND id NOT IN (SELECT blocked_id FROM blocks WHERE user_id = ?)
    ORDER BY username COLLATE NOCASE ASC
  `).all(req.user.uid, req.user.uid);

  res.json(rows.map(u => ({
    otherUid: u.id,
    otherUsername: u.username,
    otherPhoto: u.profile_photo,
    online: !!u.is_online,
  })));
});

// Kullanıcı ara (kullanıcı adına göre, DM başlatmak için)
router.get('/search', requireAuth, (req, res) => {
  const q = (req.query.q || '').trim();
  if (q.length < 2) return res.json([]);
  const rows = db.prepare(
    `SELECT * FROM users WHERE username LIKE ? AND id != ? LIMIT 20`
  ).all(`%${q}%`, req.user.uid);
  res.json(rows.map(publicUser));
});

router.get('/:id', requireAuth, (req, res) => {
  const row = db.prepare('SELECT * FROM users WHERE id = ?').get(req.params.id);
  if (!row) return res.status(404).json({ error: 'Kullanıcı bulunamadı' });
  res.json(publicUser(row));
});

// Engelleme
router.post('/:id/block', requireAuth, (req, res) => {
  db.prepare('INSERT OR IGNORE INTO blocks (user_id, blocked_id) VALUES (?, ?)')
    .run(req.user.uid, req.params.id);
  res.json({ ok: true });
});
router.delete('/:id/block', requireAuth, (req, res) => {
  db.prepare('DELETE FROM blocks WHERE user_id = ? AND blocked_id = ?')
    .run(req.user.uid, req.params.id);
  res.json({ ok: true });
});
router.get('/me/blocked', requireAuth, (req, res) => {
  const rows = db.prepare('SELECT blocked_id FROM blocks WHERE user_id = ?').all(req.user.uid);
  res.json(rows.map(r => r.blocked_id));
});

// Sessize alma (oda veya DM)
router.post('/me/mute', requireAuth, (req, res) => {
  const { targetType, targetId } = req.body || {};
  if (!['room', 'dm'].includes(targetType) || !targetId) {
    return res.status(400).json({ error: 'targetType (room|dm) ve targetId zorunlu' });
  }
  db.prepare('INSERT OR IGNORE INTO mutes (user_id, target_type, target_id) VALUES (?, ?, ?)')
    .run(req.user.uid, targetType, targetId);
  res.json({ ok: true });
});
router.delete('/me/mute', requireAuth, (req, res) => {
  const { targetType, targetId } = req.body || {};
  db.prepare('DELETE FROM mutes WHERE user_id = ? AND target_type = ? AND target_id = ?')
    .run(req.user.uid, targetType, targetId);
  res.json({ ok: true });
});
router.get('/me/mutes', requireAuth, (req, res) => {
  const rows = db.prepare('SELECT target_type as targetType, target_id as targetId FROM mutes WHERE user_id = ?')
    .all(req.user.uid);
  res.json(rows);
});

module.exports = router;
