require('dotenv').config();
const express = require('express');
const http = require('http');
const path = require('path');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { Server } = require('socket.io');

const { router: authRouter } = require('./routes/auth');
const usersRouter = require('./routes/users');
const roomsRouter = require('./routes/rooms');
const dmRouter = require('./routes/dm');
const messageActionsRouter = require('./routes/messageActions');
const uploadRouter = require('./routes/upload');
const { setupSockets, startDisappearingMessagesLoop } = require('./sockets');

const PORT = process.env.PORT || 3000;
const CORS_ORIGIN = process.env.CORS_ORIGIN || '*'; // prod'da kendi domain'inle sınırla

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: CORS_ORIGIN, methods: ['GET', 'POST'] },
});
app.set('io', io);

app.use(helmet({ crossOriginResourcePolicy: false }));
app.use(cors({ origin: CORS_ORIGIN }));
app.use(express.json({ limit: '2mb' }));

// Genel API rate limit (brute-force / spam'e karşı ek katman)
app.use('/api/', rateLimit({ windowMs: 60 * 1000, max: 120 }));

app.use('/uploads', express.static(path.join(__dirname, '..', 'uploads')));

app.get('/api/health', (req, res) => res.json({ ok: true, time: Date.now() }));

app.use('/api/auth', authRouter);
app.use('/api/users', usersRouter);
app.use('/api/rooms', roomsRouter);
app.use('/api/dm', dmRouter);
app.use('/api/messages', messageActionsRouter);
app.use('/api/upload', uploadRouter);

// Bilinmeyen route
app.use((req, res) => res.status(404).json({ error: 'Bulunamadı' }));

// Hata yakalayıcı (ör. multer dosya türü hatası)
app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: err.message || 'Sunucu hatası' });
});

setupSockets(io);
startDisappearingMessagesLoop(io);

server.listen(PORT, () => {
  console.log(`AnzakChat server ${PORT} portunda çalışıyor.`);
});
