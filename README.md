# AnzakChat (QyripTalk)

Firebase kaldırıldı — artık kendi backend'imiz var.

## Klasörler
- `anzak-server/` — kendi Node.js backend'imiz (Express + Socket.IO + SQLite + JWT + bcrypt). Kurulum ve API dokümantasyonu için `anzak-server/README.md`'ye bak.
- `qyriptalk-android/` — Android istemci (Java). `net/ApiClient.java` ve `net/SocketManager.java` yeni backend'e bağlanıyor.
- `MIGRATION.md` — Firebase'den yeni backend'e geçişte tamamlanan ve kalan işlerin tam listesi.

## Hızlı başlangıç (server)
```bash
cd anzak-server
npm install
cp .env.example .env   # JWT_SECRET'i değiştirmeyi unutma
npm start
```

## Durum
Backend tamamen çalışır ve test edildi. Android tarafında login/register/oturum
yeni sisteme geçirildi; `ChatActivity`, `MainActivity`, `CreateRoomActivity`
hâlâ eski Firebase kodunu içeriyor — detay için `MIGRATION.md`.
