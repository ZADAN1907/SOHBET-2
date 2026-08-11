# Firebase'den kendi sunucumuza geçiş — durum

## Tamamlandı ✅
- `anzak-server/`: Tam çalışan, test edilmiş Node.js backend (Express + Socket.IO
  + SQLite + JWT + bcrypt). Tüm uç noktalar test edildi.
- Android: `LoginActivity`, `RegisterActivity`, `CreateRoomActivity`,
  `MainActivity`, `ChatActivity`, `SessionManager`, `AnzakChatApp`,
  `PresenceManager`, `RoomModel`, `MessageModel`, `RoomAdapter`,
  `MessageAdapter` — hepsi Firebase'den `ApiClient`/`SocketManager`'a
  geçirildi. Projede artık **hiçbir gerçek Firebase kodu yok** (sadece
  eski davranışı açıklayan Türkçe yorum satırlarında "Firebase" kelimesi
  geçiyor, kod tarafında referans yok).
- `app/build.gradle`: Firebase kaldırıldı; OkHttp, Socket.IO client ve
  Glide (chat görselleri için) eklendi.
- Medya mesajları (resim/dosya) artık base64 olarak DB'ye değil,
  `/api/upload` ile sunucuda gerçek dosya olarak tutuluyor.
- Profil fotoğrafı küçük olduğu için (480px, JPEG) hâlâ base64 data-URL
  olarak `profilePhoto` alanında tutuluyor — bu bilinçli bir tercih,
  ekstra karmaşıklık gerektirmiyor.

## Bilinen sınırlamalar / ileride geliştirilebilir
- `UserModel.java` artık hiçbir yerde kullanılmıyor (JSON tabanlı okuma
  ile değiştirildi) — istenirse silinebilir, dursa da derlemeyi bozmaz.
- Ses (voice) ve video mesaj **gönderme UI'ı** orijinal projede zaten
  yoktu (sadece "bu sürümde görüntülenemiyor" placeholder'ı vardı),
  bu haliyle bırakıldı.
- `ApiClient.BASE_URL` şu an `http://10.0.2.2:3000` (emülatör-yerel).
  Gerçek cihaz/prod için değiştirilmesi gerekiyor (bkz. `anzak-server/README.md`).
- Derleme bu ortamda Android SDK olmadığı için gradle ile fiilen
  derlenip test edilemedi; syntax/parantez dengesi ve tüm metod/alan
  referansları elle ve script ile tek tek doğrulandı.

## Bu turda eklenen yeni özellikler (WhatsApp seviyesi + gizlilik)
- **Mesaja yanıt verme (reply)**: `replyToId` ile gönderilen her mesaj, yanıtlanan
  mesajın önizlemesini taşır (`replyTo: {id, sender, preview}`). Backend tamamen
  hazır ve test edildi; `MessageModel`'e alanlar eklendi.
- **Kaybolan mesajlar (disappearing messages)**: Her oda/DM için ayrı ayrı
  süre ayarlanabiliyor (`PATCH /api/rooms/:id/disappearing`,
  `PATCH /api/dm/:otherId/disappearing`, `{seconds}`). Sunucu her 15 saniyede
  bir süresi dolan mesajları **kalıcı olarak** siler ve bağlı istemcilere
  anında `message:updated (deleted:true)` yollar. Uçtan uca test edildi.
- **Sesli mesaj altyapısı**: `messages.duration_ms` kolonu ve `/api/upload`
  zaten ses dosyalarını kabul ediyor; `type:"voice"` ile gönderilebiliyor.
- **Oda avatarı**: `PATCH /api/rooms/:id {logo}` ile güncellenebiliyor.

### Android tarafında bu turda kalan iş
`MessageModel` yeni alanları (`replyToId/replyToSender/replyToPreview`,
`durationMs`, `expiresAt`) okuyacak şekilde güncellendi. Ancak şu ikisi için
**UI henüz yazılmadı** (backend tamamen hazır, sadece ekran/etkileşim eksik):
- Mesaja uzun basıp "Yanıtla" seçeneği + input üstünde yanıt önizleme çubuğu
- Mikrofon butonu ile ses kaydı (MediaRecorder) + oynatma UI'ı
- Oda bilgisi ekranında "Kaybolan mesajlar" süre seçici (backend endpoint'i hazır)

İstersen bir sonraki adımda bu üç UI parçasını da tamamlarım.

## Bu turda tamamlanan WhatsApp-seviyesi özellikler (UI dahil)
Önceki turda backend'i hazırlanan üç özelliğin **Android UI'ı da tamamlandı**:

- **Mesaja yanıt (reply)**: Mesaja uzun basınca "Yanıtla / Sil" menüsü açılıyor.
  Yanıtla seçilince input'un üstünde yanıt önizleme çubuğu çıkıyor, gönderilen
  mesaj `replyToId` ile işaretleniyor, karşı tarafta mesaj balonunun içinde
  küçük bir alıntı kutusu olarak görünüyor.
- **Sesli mesaj**: Mikrofon butonuna basılı tutunca kayıt başlıyor (MediaRecorder,
  AAC/M4A), üstte kayıt süresi ve iptal butonu beliriyor, parmağı çekince
  otomatik `/api/upload`'a yükleniyor ve gönderiliyor. Mesaj balonunda
  play/pause butonu ile MediaPlayer üzerinden çalınıyor.
- **Kaybolan mesajlar**: Oda bilgisi ve DM kullanıcı bilgisi ekranlarına
  "Kaybolan mesajlar" satırı eklendi (Kapalı / 5dk / 1sa / 24sa / 7gün seçici).
  Odalarda sadece kurucu değiştirebiliyor, DM'de her iki taraf da ayarlayabiliyor.

### Bu tur için eklenen altyapı
- `AndroidManifest.xml`: `RECORD_AUDIO` izni + `network_security_config.xml`
  (emülatörden `10.0.2.2`/`localhost`'a HTTP izni — prod'da HTTPS'e geçince
  bu istisna otomatik devre dışı, çünkü sadece o iki domain için tanımlı).
- `GET /api/rooms/:id` endpoint'i eklendi (oda bilgisi dialogunun disappearing
  değerini okuyabilmesi için).
- Tüm dosyalar parantez/süslü parantez dengesi + kullanılan tüm `R.id.*` /
  `R.menu.*` referanslarının karşılık geldiği layout/menu dosyalarında
  tanımlı olup olmadığı script ile tek tek doğrulandı.

## Genel durum
Backend tamamen test edilmiş ve çalışıyor. Android tarafında derleme
(Android SDK/Gradle bu ortamda yok) fiilen yapılamadı, ama syntax ve tüm
kaynak referansları elle + otomatik script ile doğrulandı. İlk Android
Studio açılışında ufak bir uyuşmazlık çıkarsa (örn. bir tema/stil detayı)
buraya yapıştırınca anında düzeltilir.
