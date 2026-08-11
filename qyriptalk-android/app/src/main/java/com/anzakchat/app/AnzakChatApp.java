package com.anzakchat.app;

import android.app.Application;

import com.anzakchat.app.net.SocketManager;
import com.anzakchat.app.util.SessionManager;

/**
 * Firebase tamamen kaldırıldı. Artık veri kendi backend'imizde
 * (anzak-server/) tutuluyor; canlı bağlantı Socket.IO ile kuruluyor.
 * Uygulama açıldığında oturum varsa socket bağlantısı otomatik başlatılır
 * — presence (online/offline) artık sunucu tarafında socket connect/
 * disconnect event'lerine göre otomatik yönetiliyor (bkz. anzak-server/src/sockets).
 */
public class AnzakChatApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        SessionManager session = new SessionManager(this);
        if (session.isLoggedIn()) {
            SocketManager.get(this).connect();
        }
    }
}
