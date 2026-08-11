const express = require('express');
const bcrypt = require('bcryptjs');
const { v4: uuid } = require('uuid');
const rateLimit = require('express-rate-limit');
const db = require('../db');
const { signToken, requireAuth } = require('../auth');

const router = express.Router();

// Brute-force koruması: 10 dk'da en fazla 15 deneme (IP başına)
const authLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 15,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Çok fazla deneme yaptın, biraz sonra tekrar dene.' },
});

const USERNAME_RE = /^[a-zA-Z0-9_.]{3,20}$/;

function publicUser(row) {
  return {
    id: row.id,
    username: row.username,
    role: row.role,
    profilePhoto: row.profile_photo,
    bio: row.bio,
    isOnline: !!row.is_online,
    lastSeen: row.last_seen,
    messageNotifications: !!row.message_notifications,
    soundNotifications: !!row.sound_notifications,
  };
}

router.post('/register', authLimiter, (req, res) => {
  const { username, password } = req.body || {};
  if (!username || !password) {
    return res.status(400).json({ error: 'username ve password zorunlu' });
  }
  if (!USERNAME_RE.test(username)) {
    return res.status(400).json({ error: 'Kullanıcı adı 3-20 karakter, sadece harf/rakam/._ olabilir' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: 'Şifre en az 6 karakter olmalı' });
  }

  const existing = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
  if (existing) return res.status(409).json({ error: 'Bu kullanıcı adı alınmış' });

  const id = uuid();
  const passwordHash = bcrypt.hashSync(password, 12);
  const now = Date.now();

  db.prepare(`
    INSERT INTO users (id, username, password_hash, role, is_online, last_seen, created_at)
    VALUES (?, ?, ?, 'user', 0, ?, ?)
  `).run(id, username, passwordHash, now, now);

  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(id);
  const token = signToken(user);
  res.status(201).json({ token, user: publicUser(user) });
});

router.post('/login', authLimiter, (req, res) => {
  const { username, password } = req.body || {};
  if (!username || !password) {
    return res.status(400).json({ error: 'username ve password zorunlu' });
  }

  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username);
  if (!user || !bcrypt.compareSync(password, user.password_hash)) {
    return res.status(401).json({ error: 'Kullanıcı adı veya şifre hatalı' });
  }

  const token = signToken(user);
  res.json({ token, user: publicUser(user) });
});

router.get('/me', requireAuth, (req, res) => {
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.uid);
  if (!user) return res.status(404).json({ error: 'Kullanıcı bulunamadı' });
  res.json(publicUser(user));
});

router.post('/change-username', requireAuth, (req, res) => {
  const { username } = req.body || {};
  if (!username || !USERNAME_RE.test(username)) {
    return res.status(400).json({ error: 'Kullanıcı adı 3-20 karakter, sadece harf/rakam/._ olabilir' });
  }
  const existing = db.prepare('SELECT id FROM users WHERE username = ? AND id != ?').get(username, req.user.uid);
  if (existing) return res.status(409).json({ error: 'Bu kullanıcı adı alınmış' });

  db.prepare('UPDATE users SET username = ? WHERE id = ?').run(username, req.user.uid);
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.uid);
  const token = signToken(user);
  res.json({ token, user: publicUser(user) });
});

module.exports = { router, publicUser };
