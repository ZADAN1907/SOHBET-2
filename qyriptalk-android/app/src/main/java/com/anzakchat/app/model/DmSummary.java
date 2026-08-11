package com.anzakchat.app.model;

/** Uygulama içi DM özet satırı — Firebase node'u değil, MainActivity içinde hesaplanır. */
public class DmSummary {

    public final String otherUid;
    public final String otherUsername;
    public boolean online;
    public String photoBase64; // karşı tarafın profil fotoğrafı (varsa)

    // Faz 5: DM kutucuğunda "kim en son ne yazdı" önizlemesi için.
    public String lastMessageText;
    public String lastMessageSender;
    public boolean lastMessageIsMine;
    public long lastMessageTime;

    public DmSummary(String otherUid, String otherUsername) {
        this.otherUid = otherUid;
        this.otherUsername = otherUsername;
    }

    /** Faz 1'de olduğu gibi: iki uid'yi sözlük sırasına göre birleştirip dmId üretir.
     *  Not: eski web'deki split('_') hatasının aksine, burada dmId'yi PARÇALAMIYORUZ,
     *  sadece oluşturuyoruz — bu yüzden uid içinde '_' olması sorun yaratmaz. */
    public static String buildDmId(String uidA, String uidB) {
        return uidA.compareTo(uidB) <= 0 ? uidA + "_" + uidB : uidB + "_" + uidA;
    }
}
