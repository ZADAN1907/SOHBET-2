package com.anzakchat.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * /api/.../messages ve socket "message:new"/"message:updated" olaylarındaki
 * mesaj JSON'unu temsil eder (bkz. anzak-server/src/messages.js serializeMessage).
 */
public class MessageModel {

    private String id;
    private String roomId;
    private String dmId;
    private String sender;      // username
    private String senderUid;
    private String senderPhoto;
    private String text;
    private long timestamp;
    private Long editedAt;
    private boolean deleted;
    private final Map<String, Boolean> readBy = new HashMap<>();

    // Medya mesajları
    private String type;        // "voice" | "image" | "video" | "file" | null(=text)
    private String mediaUrl;    // sunucudaki gerçek dosya URL'i (eskiden base64 idi)
    private String fileName;
    private long fileSize;
    private String mimeType;
    private long durationMs;    // sesli mesaj süresi

    // Yanıtlanan mesaj önizlemesi (varsa)
    private String replyToId;
    private String replyToSender;
    private String replyToPreview;

    private Long expiresAt;     // kaybolan mesajlar için (varsa)

    public MessageModel() { }

    public static MessageModel fromJson(JSONObject o) throws JSONException {
        MessageModel m = new MessageModel();
        m.id = o.optString("id", null);
        m.roomId = o.isNull("roomId") ? null : o.optString("roomId", null);
        m.dmId = o.isNull("dmId") ? null : o.optString("dmId", null);
        m.sender = o.isNull("sender") ? null : o.optString("sender", null);
        m.senderUid = o.optString("senderUid", null);
        m.senderPhoto = o.isNull("senderPhoto") ? null : o.optString("senderPhoto", null);
        m.text = o.isNull("text") ? null : o.optString("text", null);
        m.timestamp = o.optLong("timestamp", 0);
        m.editedAt = o.isNull("editedAt") ? null : o.optLong("editedAt");
        m.deleted = o.optBoolean("deleted", false);
        m.type = o.isNull("type") ? null : o.optString("type", null);
        m.mediaUrl = o.isNull("mediaUrl") ? null : o.optString("mediaUrl", null);
        m.fileName = o.isNull("fileName") ? null : o.optString("fileName", null);
        m.fileSize = o.optLong("fileSize", 0);
        m.mimeType = o.isNull("mimeType") ? null : o.optString("mimeType", null);
        m.durationMs = o.optLong("durationMs", 0);
        m.expiresAt = o.isNull("expiresAt") ? null : o.optLong("expiresAt");

        JSONObject replyTo = o.optJSONObject("replyTo");
        if (replyTo != null) {
            m.replyToId = replyTo.optString("id", null);
            m.replyToSender = replyTo.optString("sender", null);
            m.replyToPreview = replyTo.optString("preview", null);
        }

        JSONArray readByArr = o.optJSONArray("readBy");
        if (readByArr != null) {
            for (int i = 0; i < readByArr.length(); i++) {
                m.readBy.put(readByArr.getString(i), true);
            }
        }
        return m;
    }

    /**
     * Firebase'deki rooms/{roomId}/messages/{id} veya
     * privateMessages/{dmId}/messages/{id} kaydından bir MessageModel üretir.
     * Şema (script.js ile aynı): sender, senderUid, text, timestamp, readBy: {uid:true},
     * type (voice/image/video/file, text için yok), fileName, fileSize, fileType, fileData (url),
     * duration (SANİYE cinsinden, web'de öyle). replyToId/replyToSender/replyToPreview web'de
     * yok ama Firebase şemasız olduğu için Android'in reply özelliği için ek alan olarak
     * biz yazıyoruz — web sadece bu alanları görmezden gelir, bozmaz.
     */
    public static MessageModel fromFirebaseJson(String id, JSONObject o) {
        MessageModel m = new MessageModel();
        m.id = id;
        m.sender = o.isNull("sender") ? null : o.optString("sender", null);
        m.senderUid = o.optString("senderUid", null);
        m.senderPhoto = o.isNull("senderPhoto") ? null : o.optString("senderPhoto", null);
        m.text = o.isNull("text") ? null : o.optString("text", null);
        m.timestamp = o.optLong("timestamp", 0);
        m.deleted = false;
        m.type = o.isNull("type") ? null : o.optString("type", null);
        m.mediaUrl = o.isNull("fileData") ? null : o.optString("fileData", null);
        m.fileName = o.isNull("fileName") ? null : o.optString("fileName", null);
        m.fileSize = o.optLong("fileSize", 0);
        m.mimeType = o.isNull("fileType") ? null : o.optString("fileType", null);
        m.durationMs = o.optLong("duration", 0) * 1000L; // web saniye tutuyor, biz ms kullanıyoruz

        m.replyToId = o.isNull("replyToId") ? null : o.optString("replyToId", null);
        m.replyToSender = o.isNull("replyToSender") ? null : o.optString("replyToSender", null);
        m.replyToPreview = o.isNull("replyToPreview") ? null : o.optString("replyToPreview", null);

        JSONObject readBy = o.optJSONObject("readBy");
        if (readBy != null) {
            java.util.Iterator<String> it = readBy.keys();
            while (it.hasNext()) {
                String uid = it.next();
                if (readBy.optBoolean(uid, false)) m.readBy.put(uid, true);
            }
        }
        return m;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    // Eski çağrı yerleriyle uyum için (Firebase'de messageId deniyordu)
    public String getMessageId() { return id; }
    public void setMessageId(String id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public String getDmId() { return dmId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getSenderUid() { return senderUid; }
    public void setSenderUid(String senderUid) { this.senderUid = senderUid; }

    public String getSenderPhoto() { return senderPhoto; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public long getTimestampMillis() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Long getEditedAt() { return editedAt; }
    public boolean isDeleted() { return deleted; }

    public Map<String, Boolean> getReadBy() { return readBy; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }

    // Eski adapter/ChatActivity kodu getFileData() ile medya URL'i bekliyordu;
    // artık base64 değil gerçek URL döndürüyor ama isim uyumluluğu için tutuldu.
    public String getFileData() { return mediaUrl; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getReplyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }

    public String getReplyToSender() { return replyToSender; }
    public String getReplyToPreview() { return replyToPreview; }
    public boolean hasReply() { return replyToId != null; }

    public Long getExpiresAt() { return expiresAt; }
}
