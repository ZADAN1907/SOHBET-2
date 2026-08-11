package com.anzakchat.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Oturum bilgisi artık Firebase Realtime Database'deki users/{uid} kaydına
 * karşılık gelen uid + username ile tutuluyor. Kendi backend'imiz kalktığı
 * için JWT token kavramı da kalktı — Firebase REST'e kimliksiz (rules'a
 * güvenerek) bağlanıyoruz.
 */
public class SessionManager {

    private static final String PREFS = "anzakchat_session";
    private static final String KEY_UID = "uid";
    private static final String KEY_USERNAME = "username";

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String uid, String username) {
        prefs.edit()
                .putString(KEY_UID, uid)
                .putString(KEY_USERNAME, username)
                .apply();
    }

    /** Sadece görünen kullanıcı adını günceller. */
    public void updateUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public boolean isLoggedIn() {
        return getUid() != null;
    }

    /**
     * Artık kullanılmıyor (JWT token kaldırıldı, Firebase REST'e doğrudan
     * bağlanıyoruz) — sadece ApiClient gibi henüz Firebase'e taşınmamış eski
     * kod parçaları derlensin diye null döndürüyor.
     */
    @Deprecated
    public String getToken() {
        return null;
    }

    public String getUid() {
        return prefs.getString(KEY_UID, null);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
