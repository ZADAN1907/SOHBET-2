const { verifyToken } = require('../auth');
const db = require('../db');
const { createMessage, buildDmId, purgeExpiredMessages } = require('../messages');

// socket.userId -> flood koruması için basit sayaç
const floodMap = new Map();
const FLOOD_LIMIT = 8;      // 5 saniyede en fazla 8 mesaj
const FLOOD_WINDOW_MS = 5000;

function isFlooding(userId) {
  const now = Date.now();
  const entry = floodMap.get(userId) || { count: 0, windowStart: now };
  if (now - entry.windowStart > FLOOD_WINDOW_MS) {
    entry.count = 0;
    entry.windowStart = now;
  }
  entry.count += 1;
  floodMap.set(userId, entry);
  return entry.count > FLOOD_LIMIT;
}

function setupSockets(io) {
  io.use((socket, next) => {
    try {
      const token = socket.handshake.auth?.token;
      if (!token) return next(new Error('Token yok'));
      socket.user = verifyToken(token);
      next();
    } catch (e) {
      next(new Error('Token geçersiz'));
    }
  });

  io.on('connection', (socket) => {
    const userId = socket.user.uid;

    // Kişisel oda: DM ve profil güncellemeleri için
    socket.join(`user:${userId}`);

    db.prepare('UPDATE users SET is_online = 1 WHERE id = ?').run(userId);
    io.emit('presence:update', { userId, isOnline: true, lastSeen: null });

    // Kullanıcının üye olduğu tüm odalara otomatik katıl
    const rooms = db.prepare('SELECT room_id FROM room_members WHERE user_id = ?').all(userId);
    rooms.forEach(r => socket.join(`room:${r.room_id}`));

    socket.on('room:join', (roomId) => {
      const isMember = db.prepare('SELECT 1 FROM room_members WHERE room_id = ? AND user_id = ?').get(roomId, userId);
      if (isMember) socket.join(`room:${roomId}`);
    });

    socket.on('room:leave', (roomId) => {
      socket.leave(`room:${roomId}`);
    });

    socket.on('typing:start', ({ roomId, dmId }) => {
      if (roomId) socket.to(`room:${roomId}`).emit('typing:start', { roomId, userId });
      if (dmId) {
        const [a, b] = dmId.split('_');
        const other = a === userId ? b : a;
        io.to(`user:${other}`).emit('typing:start', { dmId, userId });
      }
    });

    socket.on('typing:stop', ({ roomId, dmId }) => {
      if (roomId) socket.to(`room:${roomId}`).emit('typing:stop', { roomId, userId });
      if (dmId) {
        const [a, b] = dmId.split('_');
        const other = a === userId ? b : a;
        io.to(`user:${other}`).emit('typing:stop', { dmId, userId });
      }
    });

    // {roomId?, otherUserId?, text, type?, mediaUrl?, fileName?, fileSize?, mimeType?}
    socket.on('message:send', (payload, ack) => {
      try {
        if (isFlooding(userId)) {
          return ack?.({ error: 'Çok hızlı mesaj gönderiyorsun, biraz yavaşla.' });
        }
        const { roomId, otherUserId, text, type, mediaUrl, fileName, fileSize, mimeType, durationMs, replyToId } = payload || {};
        if (!roomId && !otherUserId) return ack?.({ error: 'roomId veya otherUserId gerekli' });
        if (!text?.trim() && !mediaUrl) return ack?.({ error: 'Mesaj boş olamaz' });

        let msg;
        if (roomId) {
          const isMember = db.prepare('SELECT 1 FROM room_members WHERE room_id = ? AND user_id = ?').get(roomId, userId);
          if (!isMember) return ack?.({ error: 'Bu odanın üyesi değilsin' });
          msg = createMessage({ roomId, senderId: userId, text, type, mediaUrl, fileName, fileSize, mimeType, durationMs, replyToId });
          io.to(`room:${roomId}`).emit('message:new', msg);
        } else {
          const blocked = db.prepare('SELECT 1 FROM blocks WHERE user_id = ? AND blocked_id = ?').get(otherUserId, userId);
          if (blocked) return ack?.({ error: 'Bu kullanıcı seni engellemiş' });
          const dmId = buildDmId(userId, otherUserId);
          msg = createMessage({ dmId, senderId: userId, text, type, mediaUrl, fileName, fileSize, mimeType, durationMs, replyToId });
          io.to(`user:${otherUserId}`).emit('message:new', msg);
          io.to(`user:${userId}`).emit('message:new', msg);
        }
        ack?.({ ok: true, message: msg });
      } catch (e) {
        ack?.({ error: 'Sunucu hatası' });
      }
    });

    socket.on('disconnect', () => {
      // Aynı kullanıcının başka açık sekmesi/cihazı var mı kontrol et
      const stillConnected = [...io.sockets.sockets.values()]
        .some(s => s.user?.uid === userId && s.id !== socket.id);
      if (stillConnected) return;

      const now = Date.now();
      db.prepare('UPDATE users SET is_online = 0, last_seen = ? WHERE id = ?').run(now, userId);
      io.emit('presence:update', { userId, isOnline: false, lastSeen: now });
    });
  });
}

module.exports = { setupSockets, startDisappearingMessagesLoop };

/**
 * WhatsApp'taki "kaybolan mesajlar" özelliğinin arka planı: her 15 saniyede
 * bir süresi dolmuş mesajları kalıcı olarak siler ve ilgili oda/DM'e
 * "message:updated" (deleted=true) event'i yollayarak istemcide de anında
 * kaybolmasını sağlar.
 */
function startDisappearingMessagesLoop(io) {
  setInterval(() => {
    const expired = purgeExpiredMessages();
    for (const m of expired) {
      const payload = { id: m.id, roomId: m.room_id, dmId: m.dm_id, deleted: true };
      if (m.room_id) {
        io.to(`room:${m.room_id}`).emit('message:updated', payload);
      } else if (m.dm_id) {
        const [a, b] = m.dm_id.split('_');
        io.to(`user:${a}`).emit('message:updated', payload);
        io.to(`user:${b}`).emit('message:updated', payload);
      }
    }
  }, 15000);
}
