package com.anzakchat.app.util;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.anzakchat.app.model.MessageModel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Profil / oda / DM menülerinde tekrar eden küçük görüntüleme yardımcıları
 * (rol rozeti metni, "son görülme" biçimlendirmesi).
 */
public final class UiUtils {

    private UiUtils() { }

    /** script.js'teki userProfile.role ("admin" | "moderator" | "user") -> rozet metni. */
    public static String roleLabel(String role) {
        if (role == null) return null;
        switch (role) {
            case "admin": return "Yönetici";
            case "moderator": return "Moderatör";
            default: return null; // "user" ya da bilinmeyen rol için rozet gösterilmez
        }
    }

    public static String formatLastSeen(long lastSeenMillis) {
        if (lastSeenMillis <= 0) return "Son görülme bilinmiyor";
        long diffMs = System.currentTimeMillis() - lastSeenMillis;
        long minutes = diffMs / 60000;
        if (minutes < 1) return "Son görülme: az önce";
        if (minutes < 60) return "Son görülme: " + minutes + " dakika önce";
        long hours = minutes / 60;
        if (hours < 24) return "Son görülme: " + hours + " saat önce";
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM, HH:mm", new Locale("tr", "TR"));
        return "Son görülme: " + fmt.format(new Date(lastSeenMillis));
    }

    public static String initial(String text) {
        return text != null && !text.isEmpty() ? text.substring(0, 1).toUpperCase(Locale.ROOT) : "?";
    }

    /**
     * Oda/DM kutucuğunda ve sohbet listesinde gösterilecek "son mesaj" önizleme
     * metni. Medya mesajları için ikon+etiket, metin mesajları için düz metin döner.
     */
    public static String messagePreview(MessageModel m) {
        if (m == null) return null;
        String type = m.getType();
        if ("image".equals(type)) return "📷 Fotoğraf";
        if ("file".equals(type)) return "📎 " + (m.getFileName() != null ? m.getFileName() : "Dosya");
        if ("voice".equals(type)) return "🎤 Sesli mesaj";
        if ("video".equals(type)) return "🎬 Video";
        String text = m.getText();
        return text != null ? text : "";
    }

    /** Uzun son-mesaj önizlemelerini kutucuk genişliğine sığdırmak için kısaltır. */
    public static String truncate(String text, int maxChars) {
        if (text == null) return "";
        String flattened = text.replace("\n", " ").trim();
        if (flattened.length() <= maxChars) return flattened;
        return flattened.substring(0, maxChars).trim() + "…";
    }

    /** "data:image/jpeg;base64,...." ya da düz base64 metnini Bitmap'e çevirir. Hatalıysa null döner. */
    public static Bitmap decodeAvatarPhoto(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            String base64 = data.contains(",") ? data.substring(data.indexOf(',') + 1) : data;
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Profil/DM avatarlarında: fotoğraf varsa yuvarlak kırpılmış halde imageView'e basar
     * ve baş harf TextView'ini gizler; yoksa tam tersini yapar (harf görünsün, foto gizlensin).
     */
    public static void applyAvatarPhoto(Resources res, ImageView photoView, TextView initialView, String photoData) {
        Bitmap bmp = decodeAvatarPhoto(photoData);
        if (bmp != null) {
            RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(res, bmp);
            drawable.setCircular(true);
            photoView.setImageDrawable(drawable);
            photoView.setVisibility(View.VISIBLE);
            initialView.setVisibility(View.INVISIBLE);
        } else {
            photoView.setVisibility(View.GONE);
            initialView.setVisibility(View.VISIBLE);
        }
    }
}
