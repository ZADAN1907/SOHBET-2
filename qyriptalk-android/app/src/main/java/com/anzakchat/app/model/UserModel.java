package com.anzakchat.app.model;

import java.util.HashMap;
import java.util.Map;

/**
 * users/{uid} node'unu temsil eder (script.js ile birebir aynı alanlar).
 * Firebase'in DataSnapshot.getValue(UserModel.class) ile otomatik map
 * edebilmesi için boş constructor + public getter/setter'lar gerekli.
 */
public class UserModel {

    private String username;
    private String password;
    private boolean isOnline;
    private long lastSeen;
    private String role; // "user" | "admin" | "moderator"
    private String profilePhoto;
    private String bio;
    private Map<String, Boolean> joinedRooms;

    // Faz 4: profil/oda/DM menü geliştirmeleri için eklenen alanlar.
    // Notlar: Boolean (kutulu) kullanılıyor ki Firebase'de hiç yazılmamışsa
    // null dönsün ve "varsayılan açık" mantığını Java tarafında biz karar verelim.
    private Map<String, Boolean> mutedRooms;
    private Map<String, Boolean> mutedDms;
    private Map<String, Boolean> blockedUsers;
    private Boolean messageNotifications; // null/true = açık, false = kapalı
    private Boolean soundNotifications;   // null/true = açık, false = kapalı

    public UserModel() {
        // Firebase için gerekli boş constructor
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getProfilePhoto() { return profilePhoto; }
    public void setProfilePhoto(String profilePhoto) { this.profilePhoto = profilePhoto; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public Map<String, Boolean> getJoinedRooms() {
        return joinedRooms == null ? new HashMap<>() : joinedRooms;
    }
    public void setJoinedRooms(Map<String, Boolean> joinedRooms) { this.joinedRooms = joinedRooms; }

    public Map<String, Boolean> getMutedRooms() {
        return mutedRooms == null ? new HashMap<>() : mutedRooms;
    }
    public void setMutedRooms(Map<String, Boolean> mutedRooms) { this.mutedRooms = mutedRooms; }

    public Map<String, Boolean> getMutedDms() {
        return mutedDms == null ? new HashMap<>() : mutedDms;
    }
    public void setMutedDms(Map<String, Boolean> mutedDms) { this.mutedDms = mutedDms; }

    public Map<String, Boolean> getBlockedUsers() {
        return blockedUsers == null ? new HashMap<>() : blockedUsers;
    }
    public void setBlockedUsers(Map<String, Boolean> blockedUsers) { this.blockedUsers = blockedUsers; }

    public Boolean getMessageNotifications() { return messageNotifications; }
    public void setMessageNotifications(Boolean messageNotifications) { this.messageNotifications = messageNotifications; }

    public Boolean getSoundNotifications() { return soundNotifications; }
    public void setSoundNotifications(Boolean soundNotifications) { this.soundNotifications = soundNotifications; }
}
