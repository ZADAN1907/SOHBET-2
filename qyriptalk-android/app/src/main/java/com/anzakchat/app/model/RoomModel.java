package com.anzakchat.app.model;

/** /api/rooms yanıtındaki tek bir oda kaydını temsil eder. */
public class RoomModel {

    private String name;
    private boolean isPrivate;
    private String logo;
    private String createdBy;
    private long createdAt;
    private int memberCount;
    private boolean isMember;

    private String roomId; // sunucudan gelmez, response'tan biz set ederiz

    // Katılım şifresi (varsa) — sadece katılma ekranında karşılaştırma için tutulur.
    private String password;

    // Oda kutucuğunda "kim en son ne yazdı" önizlemesi için (yerel, sunucuya yazılmaz)
    private String lastMessageText;
    private String lastMessageSender;
    private String lastMessageSenderUid;
    private long lastMessageTime;

    public RoomModel() { }

    public boolean isPrivate() { return isPrivate; }
    public void setPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }

    public boolean isMember() { return isMember; }
    public void setMember(boolean member) { isMember = member; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getLastMessageText() { return lastMessageText; }
    public void setLastMessageText(String lastMessageText) { this.lastMessageText = lastMessageText; }

    public String getLastMessageSender() { return lastMessageSender; }
    public void setLastMessageSender(String lastMessageSender) { this.lastMessageSender = lastMessageSender; }

    public String getLastMessageSenderUid() { return lastMessageSenderUid; }
    public void setLastMessageSenderUid(String lastMessageSenderUid) { this.lastMessageSenderUid = lastMessageSenderUid; }

    public long getLastMessageTime() { return lastMessageTime; }
    public void setLastMessageTime(long lastMessageTime) { this.lastMessageTime = lastMessageTime; }

    /** GET /api/rooms JSON nesnesinden bir RoomModel üretir. (Eski özel backend biçimi.) */
    public static RoomModel fromJson(org.json.JSONObject o) throws org.json.JSONException {
        RoomModel r = new RoomModel();
        r.roomId = o.getString("id");
        r.name = o.optString("name", "");
        r.isPrivate = o.optBoolean("isPrivate", false);
        r.logo = o.isNull("logo") ? null : o.optString("logo", null);
        r.createdBy = o.optString("createdBy", null);
        r.createdAt = o.optLong("createdAt", 0);
        r.memberCount = o.optInt("memberCount", 0);
        r.isMember = o.optBoolean("isMember", false);
        return r;
    }

    /**
     * Firebase'deki rooms/{roomId} kaydından bir RoomModel üretir.
     * Şema (script.js ile birebir aynı): name, password, isPrivate, createdBy,
     * createdAt, logo, users: { uid: true, ... }, bannedUsers: { uid: true, ... }.
     */
    public static RoomModel fromFirebaseJson(String roomId, org.json.JSONObject o, String myUid) {
        RoomModel r = new RoomModel();
        r.roomId = roomId;
        r.name = o.optString("name", "");
        r.isPrivate = o.optBoolean("isPrivate", false);
        r.logo = o.isNull("logo") ? null : o.optString("logo", null);
        r.createdBy = o.optString("createdBy", null);
        r.createdAt = o.optLong("createdAt", 0);
        r.password = o.isNull("password") ? null : o.optString("password", null);

        org.json.JSONObject users = o.optJSONObject("users");
        r.memberCount = users != null ? users.length() : 0;
        r.isMember = users != null && myUid != null && users.has(myUid);
        return r;
    }
}
