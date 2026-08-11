const Database = require('better-sqlite3');
const path = require('path');

const db = new Database(path.join(__dirname, '..', 'anzak.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user',           -- user | moderator | admin
  profile_photo TEXT,
  bio TEXT,
  is_online INTEGER NOT NULL DEFAULT 0,
  last_seen INTEGER NOT NULL DEFAULT 0,
  message_notifications INTEGER NOT NULL DEFAULT 1,
  sound_notifications INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rooms (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  password_hash TEXT,                          -- NULL => herkese açık oda
  logo TEXT,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS room_members (
  room_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',         -- member | moderator | owner
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (room_id, user_id),
  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS room_banned (
  room_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  banned_at INTEGER NOT NULL,
  PRIMARY KEY (room_id, user_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  room_id TEXT,                                -- oda mesajıysa dolu
  dm_id TEXT,                                   -- DM mesajıysa dolu (sorted uidA_uidB)
  sender_id TEXT NOT NULL,
  text TEXT,
  type TEXT,                                    -- NULL(text) | image | voice | video | file
  media_url TEXT,
  file_name TEXT,
  file_size INTEGER,
  mime_type TEXT,
  duration_ms INTEGER,                          -- sesli mesajların süresi
  reply_to_id TEXT,                             -- yanıtlanan mesajın id'si (varsa)
  created_at INTEGER NOT NULL,
  edited_at INTEGER,
  expires_at INTEGER,                           -- kaybolan mesajlar: bu zamandan sonra kalıcı silinir
  deleted INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (sender_id) REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_messages_room ON messages(room_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_dm ON messages(dm_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages(expires_at);

-- Oda/DM başına "kaybolan mesajlar" süresi (WhatsApp'taki disappearing messages).
-- chat_key = roomId (oda) ya da dmId (DM, iki uid sıralı birleşimi).
CREATE TABLE IF NOT EXISTS chat_settings (
  chat_key TEXT PRIMARY KEY,
  disappear_seconds INTEGER                     -- NULL/0 = kapalı
);

CREATE TABLE IF NOT EXISTS message_reads (
  message_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  read_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, user_id)
);

CREATE TABLE IF NOT EXISTS message_reactions (
  message_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  emoji TEXT NOT NULL,
  PRIMARY KEY (message_id, user_id, emoji)
);

CREATE TABLE IF NOT EXISTS blocks (
  user_id TEXT NOT NULL,
  blocked_id TEXT NOT NULL,
  PRIMARY KEY (user_id, blocked_id)
);

CREATE TABLE IF NOT EXISTS mutes (
  user_id TEXT NOT NULL,
  target_type TEXT NOT NULL,                   -- room | dm
  target_id TEXT NOT NULL,
  PRIMARY KEY (user_id, target_type, target_id)
);
`);

module.exports = db;
