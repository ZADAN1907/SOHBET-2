package com.anzakchat.app.util;

import android.content.Context;

import com.anzakchat.app.net.FirebaseClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Presence (online/offline/lastSeen) artık doğrudan Firebase Realtime
 * Database'deki users/{uid} kaydına yazılıyor (web sürümüyle aynı alanlar:
 * isOnline, lastSeen). Kendi backend'imiz kalktığı için gerçek
 * onDisconnect() garantisi yok — uygulama kapanırken/arkaya atılırken
 * MainActivity onPause/onDestroy'da goOffline() çağırmalı.
 */
public final class PresenceManager {

    private PresenceManager() { }

    public static void goOnline(Context context) {
        setOnline(context, true);
    }

    public static void goOffline(Context context) {
        setOnline(context, false);
    }

    private static void setOnline(Context context, boolean online) {
        String uid = new SessionManager(context).getUid();
        if (uid == null) return;
        try {
            JSONObject fields = new JSONObject();
            fields.put("isOnline", online);
            fields.put("lastSeen", FirebaseClient.serverTimestamp());
            FirebaseClient.get().patch("users/" + uid, fields, new FirebaseClient.SimpleCallback() {
                @Override public void onSuccess() { }
                @Override public void onError(String message) { }
            });
        } catch (JSONException ignored) { }
    }
}
