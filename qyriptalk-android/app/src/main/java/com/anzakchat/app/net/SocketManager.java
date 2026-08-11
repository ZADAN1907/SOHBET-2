package com.anzakchat.app.net;

import android.content.Context;

import com.anzakchat.app.util.SessionManager;

import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Firebase Realtime Database dinleyicilerinin (ValueEventListener) yerini
 * alan canlı bağlantı katmanı. Uygulama boyunca TEK bir socket bağlantısı
 * tutulur (singleton).
 *
 * Kullanım:
 *   SocketManager.get(context).connect();
 *   SocketManager.get(context).raw().on("message:new", args -> { ... });
 *   SocketManager.get(context).sendRoomMessage(roomId, text);
 */
public class SocketManager {

    private static SocketManager instance;

    private final SessionManager session;
    private Socket socket;

    private SocketManager(Context context) {
        this.session = new SessionManager(context.getApplicationContext());
    }

    public static synchronized SocketManager get(Context context) {
        if (instance == null) {
            instance = new SocketManager(context);
        }
        return instance;
    }

    public void connect() {
        if (socket != null && socket.connected()) return;
        try {
            IO.Options opts = new IO.Options();
            JSONObject auth = new JSONObject();
            auth.put("token", session.getToken());
            opts.auth = jsonToMap(auth);
            opts.reconnection = true;

            socket = IO.socket(ApiClient.BASE_URL, opts);
            socket.connect();
        } catch (Exception e) {
            // Sunucuya ulaşılamıyorsa burada log atılabilir; UI tarafı
            // ayrı REST çağrılarıyla çalışmaya devam edebilir.
        }
    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
        }
    }

    public Socket raw() {
        return socket;
    }

    public void joinRoom(String roomId) {
        if (socket != null) socket.emit("room:join", roomId);
    }

    public void leaveRoom(String roomId) {
        if (socket != null) socket.emit("room:leave", roomId);
    }

    public void sendRoomMessage(String roomId, String text) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("roomId", roomId);
            payload.put("text", text);
            if (socket != null) socket.emit("message:send", payload);
        } catch (Exception ignored) { }
    }

    public void sendDirectMessage(String otherUserId, String text) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("otherUserId", otherUserId);
            payload.put("text", text);
            if (socket != null) socket.emit("message:send", payload);
        } catch (Exception ignored) { }
    }

    public void typingStart(String roomId, String dmId) {
        emitTyping("typing:start", roomId, dmId);
    }

    public void typingStop(String roomId, String dmId) {
        emitTyping("typing:stop", roomId, dmId);
    }

    private void emitTyping(String event, String roomId, String dmId) {
        try {
            JSONObject payload = new JSONObject();
            if (roomId != null) payload.put("roomId", roomId);
            if (dmId != null) payload.put("dmId", dmId);
            if (socket != null) socket.emit(event, payload);
        } catch (Exception ignored) { }
    }

    // org.json -> socket.io-client'in beklediği java.util.Map dönüşümü
    private static java.util.Map<String, String> jsonToMap(JSONObject obj) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, obj.optString(k));
            }
        } catch (Exception ignored) { }
        return map;
    }
}
