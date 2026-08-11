const { v4: uuid } = require('uuid');
const db = require('./db');

function getChatKey(roomId, dmId) {
  return roomId || dmId;
}

function getDisappearSeconds(chatKey) {
  if (!chatKey) return 0;
  const row = db.prepare('SELECT disappear_seconds FROM chat_settings WHERE chat_key = ?').get(chatKey);
  return row && row.disappear_seconds ? row.disappear_seconds : 0;
}

function serializeMessage(row) {
  if (!row) return null;
  const sender = db.prepare('SELECT id, username, profile_photo FROM users WHERE id = ?').get(row.sender_id);
  const reads = db.prepare('SELECT user_id FROM message_reads WHERE message_id = ?').all(row.id).map(r => r.user_id);
  const reactions = db.prepare('SELECT user_id, emoji FROM message_reactions WHERE message_id = ?').all(row.id);

  let repliedMessage = null;
  if (row.reply_to_id) {
    const replied = db.prepare('SELECT * FROM messages WHERE id = ?').get(row.reply_to_id);
    if (replied && !replied.deleted) {
      const repliedSender = db.prepare('SELECT username FROM users WHERE id = ?').get(replied.sender_id);
      repliedMessage = {
        id: replied.id,
        sender: repliedSender ? repliedSender.username : null,
        preview: replied.text || (replied.type ? `[${replied.type}]` : ''),
      };
    }
  }

  return {
    id: row.id,
    roomId: row.room_id,
    dmId: row.dm_id,
    sender: sender ? sender.username : null,
    senderUid: row.sender_id,
    senderPhoto: sender ? sender.profile_photo : null,
    text: row.deleted ? null : row.text,
    type: row.type,
    mediaUrl: row.deleted ? null : row.media_url,
    fileName: row.file_name,
    fileSize: row.file_size,
    mimeType: row.mime_type,
    durationMs: row.duration_ms,
    replyTo: repliedMessage,
    timestamp: row.created_at,
    editedAt: row.edited_at,
    expiresAt: row.expires_at,
    deleted: !!row.deleted,
    readBy: reads,
    reactions,
  };
}

// text|image|voice|video|file mesajı DB'ye yazar. roomId XOR dmId dolu olmalı.
function createMessage({ roomId, dmId, senderId, text, type, mediaUrl, fileName, fileSize, mimeType, durationMs, replyToId }) {
  const id = uuid();
  const now = Date.now();

  const chatKey = getChatKey(roomId, dmId);
  const disappearSeconds = getDisappearSeconds(chatKey);
  const expiresAt = disappearSeconds > 0 ? now + disappearSeconds * 1000 : null;

  db.prepare(`
    INSERT INTO messages (id, room_id, dm_id, sender_id, text, type, media_url, file_name, file_size, mime_type, duration_ms, reply_to_id, created_at, expires_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(id, roomId || null, dmId || null, senderId, text || null, type || null, mediaUrl || null, fileName || null, fileSize || null, mimeType || null, durationMs || null, replyToId || null, now, expiresAt);

  return serializeMessage(db.prepare('SELECT * FROM messages WHERE id = ?').get(id));
}

function buildDmId(uidA, uidB) {
  return uidA.localeCompare(uidB) <= 0 ? `${uidA}_${uidB}` : `${uidB}_${uidA}`;
}

/** Süresi dolmuş (kaybolan) mesajları kalıcı olarak siler. Periyodik çağrılır. */
function purgeExpiredMessages() {
  const now = Date.now();
  const expired = db.prepare('SELECT id, room_id, dm_id FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?').all(now);
  if (expired.length === 0) return [];
  db.prepare('DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?').run(now);
  return expired;
}

module.exports = { serializeMessage, createMessage, buildDmId, getChatKey, getDisappearSeconds, purgeExpiredMessages };
