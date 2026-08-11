# AnzakChat Server

Firebase yerine geçen, kendi barındırdığın (self-hosted) sohbet backend'i.
Express + Socket.IO + SQLite (better-sqlite3) + JWT + bcrypt.

## Neden Firebase yerine bu?
- Şifreler artık **bcrypt ile hashlenmiş** tutuluyor (Firebase'deki sistemde düz metin şifre vardı — büyük risk).
- Tüm veri kendi sunucunda, kimsenin Firebase konsolundan erişemeyeceği kendi veritabanında.
- Rate limiting + Helmet + flood koruması ile ekstra sertleştirilmiş.
- Dosyalar artık dev base64 string olarak DB'ye değil, diskte ayrı dosya olarak tutuluyor (çok daha performanslı).

## Kurulum
```bash
cd anzak-server
npm install
cp .env.example .env
# .env içindeki JWT_SECRET'i değiştir (öneri: openssl rand -hex 32)
npm start
```
Sunucu `http://localhost:3000` üzerinde ayağa kalkar. `anzak.db` dosyası
otomatik oluşur, elle bir şey kurmana gerek yok.

## Prod'a çıkarken
- `.env` içindeki `JWT_SECRET`'i kimseyle paylaşma, git'e commitleme.
- `CORS_ORIGIN`'i `*` yerine kendi app'inin origin'iyle sınırla.
- Sunucuyu HTTPS arkasında çalıştır (ör. Caddy/Nginx reverse proxy + Let's Encrypt).
- Basit bir VPS'te (DigitalOcean, Hetzner vb.) `pm2 start src/index.js --name anzak` ile arka planda tut.

## Android tarafında
`ApiClient.BASE_URL`'i sunucunun adresine göre ayarla:
- Emülatörden yerel teste: `http://10.0.2.2:3000`
- Prod: `https://kendi-domainin.com`

## API özeti

### Auth
- `POST /api/auth/register {username, password}` → `{token, user}`
- `POST /api/auth/login {username, password}` → `{token, user}`
- `GET /api/auth/me` (Bearer token) → kullanıcı bilgisi

### Kullanıcı
- `PATCH /api/users/me {bio?, profilePhoto?, messageNotifications?, soundNotifications?}`
- `GET /api/users/search?q=...`
- `GET /api/users/:id`
- `POST/DELETE /api/users/:id/block`
- `POST/DELETE /api/users/me/mute {targetType: "room"|"dm", targetId}`

### Odalar
- `GET /api/rooms` — genel odalar + üye olunan özel odalar
- `POST /api/rooms {name, password?, logo?}`
- `POST /api/rooms/:id/join {password?}`
- `POST /api/rooms/:id/leave`
- `GET /api/rooms/:id/members`
- `POST /api/rooms/:id/ban/:userId` (sadece oda sahibi/moderatör)
- `GET /api/rooms/:id/messages?before=&limit=`
- `POST /api/rooms/:id/messages {text}` (REST fallback — asıl gönderim socket ile)

### DM
- `GET /api/dm` — tüm DM özetleri (son mesajla birlikte)
- `GET /api/dm/:otherId/messages?before=&limit=`
- `POST /api/dm/:otherId/messages {text}`

### Mesaj işlemleri
- `PATCH /api/messages/:id {text}` — düzenle (sadece kendi mesajın)
- `DELETE /api/messages/:id` — sil (kendi mesajın veya moderatörsen)
- `POST /api/messages/:id/read` — okundu işaretle
- `POST/DELETE /api/messages/:id/react {emoji}` — tepki ekle/kaldır

### Dosya yükleme
- `POST /api/upload` (multipart/form-data, alan: `file`, max 25MB) → `{url, fileName, fileSize, mimeType}`

### Socket.IO (`auth: {token}` ile bağlan)
- `room:join` / `room:leave` — odaya katıl/ayrıl
- `message:send {roomId|otherUserId, text, type?, mediaUrl?}` → ack ile sonuç döner
- `message:new`, `message:updated` — gelen olaylar
- `typing:start` / `typing:stop {roomId|dmId}`
- `presence:update {userId, isOnline, lastSeen}` — herkese broadcast

## Yeni eklenen özellikler (Firebase sürümünde olmayan)
- Mesaj düzenleme ve silme
- Emoji tepkileri (reactions)
- Okundu bilgisi (read receipts)
- Yazıyor... göstergesi (typing indicators)
- Kullanıcı arama
- Flood/spam koruması (5 saniyede 8 mesaj limiti)
- Brute-force koruması (auth endpoint'lerinde rate limit)
- Diskte dosya depolama (base64 yerine gerçek dosya + statik servis)

## Sonraki adım
Android tarafındaki geri kalan dosyalar (`ChatActivity`, `MainActivity`,
`CreateRoomActivity`) hâlâ Firebase kullanıyor — bkz. `../MIGRATION.md`.
