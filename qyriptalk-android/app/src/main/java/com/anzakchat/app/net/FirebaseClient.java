package com.anzakchat.app.net;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Firebase Realtime Database'e SDK olmadan, doğrudan REST API üzerinden
 * konuşan istemci. google-services.json / SHA-1 kaydı gerekmez — sadece
 * database URL yeter. Canlı güncellemeler için Firebase'in REST tarafında
 * sunduğu Server-Sent Events (SSE) akışı kullanılıyor.
 *
 * Önemli: Bu, gerçek Firebase Android SDK'sının sağladığı bazı şeyleri
 * (ör. onDisconnect() ile bağlantı koptuğunda otomatik "çevrimdışı" yazma)
 * SAĞLAMAZ — REST üzerinden kalıcı bir bağlantı kavramı yok. Presence bu
 * yüzden uygulama yaşam döngüsüne (onResume/onPause) bağlı olarak yönetiliyor.
 */
public class FirebaseClient {

    // qyriptalk-cdf56 Firebase projesinin Realtime Database adresi.
    // Web sürümündeki script.js'teki databaseURL ile birebir aynı — Android
    // ve web aynı veriyi paylaşıyor.
    public static final String BASE_URL = "https://qyriptalk-cdf56-default-rtdb.europe-west1.firebasedatabase.app";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static FirebaseClient instance;

    private final OkHttpClient client;
    private final OkHttpClient streamClient; // timeout'suz, uzun ömürlü SSE bağlantıları için
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ObjectCallback {
        void onSuccess(JSONObject body); // Firebase boşsa null olabilir -> onSuccess(null)
        void onError(String message);
    }

    public interface KeyCallback {
        void onSuccess(String newKey);
        void onError(String message);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(String message);
    }

    /** Canlı akıştan gelen her "put"/"patch" olayında çağrılır. path "/" ise kökten itibaren tüm veri değişmiştir. */
    public interface RealtimeListener {
        void onEvent(String path, org.json.JSONObject dataWrapper); // dataWrapper: {"data": ...}
        void onError(String message);
    }

    private FirebaseClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.streamClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // SSE: sürekli açık kalmalı
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static synchronized FirebaseClient get() {
        if (instance == null) instance = new FirebaseClient();
        return instance;
    }

    private String urlFor(String path) {
        String cleaned = path.startsWith("/") ? path.substring(1) : path;
        return BASE_URL + "/" + cleaned + ".json";
    }

    // ------------------------------------------------------------------
    // Temel CRUD
    // ------------------------------------------------------------------

    /** GET — belirtilen path'teki veriyi getirir. Veri yoksa onSuccess(null) döner. */
    public void get(String path, ObjectCallback cb) {
        Request req = new Request.Builder().url(urlFor(path)).get().build();
        enqueueObject(req, cb);
    }

    /**
     * Basit sorgu: orderByChild alanı verilen değere eşit olan çocukları getirir.
     * Örn: query("users", "username", "ahmet", cb) -> kullanıcı adı "ahmet" olan kayıt(lar).
     * Not: Firebase kurallarında ".indexOn" tanımlı değilse küçük veri setlerinde yine çalışır,
     * sadece konsolda uyarı basar.
     */
    public void query(String path, String orderByChild, String equalToValue, ObjectCallback cb) {
        HttpUrl url = HttpUrl.parse(urlFor(path))
                .newBuilder()
                .addQueryParameter("orderBy", "\"" + orderByChild + "\"")
                .addQueryParameter("equalTo", "\"" + equalToValue + "\"")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        enqueueObject(req, cb);
    }

    /** Belirtilen path'in altındaki son N kaydı getirir (ör. son mesaj önizlemesi). */
    public void getLastN(String path, int n, ObjectCallback cb) {
        HttpUrl url = HttpUrl.parse(urlFor(path))
                .newBuilder()
                .addQueryParameter("orderBy", "\"$key\"")
                .addQueryParameter("limitToLast", String.valueOf(n))
                .build();
        Request req = new Request.Builder().url(url).get().build();
        enqueueObject(req, cb);
    }

    /** PUT — path'teki veriyi tamamen değeriyle DEĞİŞTİRİR (üzerine yazar). */
    public void put(String path, Object value, SimpleCallback cb) {
        RequestBody rb = RequestBody.create(toJsonString(value), JSON);
        Request req = new Request.Builder().url(urlFor(path)).put(rb).build();
        enqueueSimple(req, cb);
    }

    /** PATCH — sadece verilen alanları günceller, geri kalanına dokunmaz. */
    public void patch(String path, JSONObject fields, SimpleCallback cb) {
        RequestBody rb = RequestBody.create(fields == null ? "{}" : fields.toString(), JSON);
        Request req = new Request.Builder().url(urlFor(path)).patch(rb).build();
        enqueueSimple(req, cb);
    }

    /** POST — path altına otomatik anahtarlı (push key) yeni bir kayıt ekler, üretilen anahtarı döner. */
    public void push(String path, Object value, KeyCallback cb) {
        RequestBody rb = RequestBody.create(toJsonString(value), JSON);
        Request req = new Request.Builder().url(urlFor(path)).post(rb).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> cb.onError("Bağlantı hatası: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "{}";
                response.close();
                mainHandler.post(() -> {
                    try {
                        if (!response.isSuccessful()) {
                            cb.onError("Firebase hatası (" + response.code() + ")");
                            return;
                        }
                        JSONObject obj = new JSONObject(bodyStr);
                        cb.onSuccess(obj.optString("name", null));
                    } catch (JSONException e) {
                        cb.onError("Firebase'den geçersiz yanıt geldi");
                    }
                });
            }
        });
    }

    /** DELETE — path'teki veriyi siler. */
    public void delete(String path, SimpleCallback cb) {
        Request req = new Request.Builder().url(urlFor(path)).delete().build();
        enqueueSimple(req, cb);
    }

    /**
     * Sunucu saatiyle timestamp yazmak için kullanılacak özel değer.
     * Firebase REST'te tam olarak bu şekle sahip bir obje, sunucu tarafında
     * gerçek zamana çevrilir (web SDK'daki ServerValue.TIMESTAMP ile aynı iş).
     */
    public static JSONObject serverTimestamp() {
        try {
            JSONObject o = new JSONObject();
            o.put(".sv", "timestamp");
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------------
    // Canlı güncellemeler (SSE)
    // ------------------------------------------------------------------

    /**
     * Belirtilen path'i canlı dinler (Firebase'in "EventSource" akışı).
     * Dönen Call üzerinde cancel() çağırarak dinlemeyi durdurabilirsin
     * (ör. Activity onDestroy'da).
     */
    public Call listen(String path, RealtimeListener listener) {
        Request req = new Request.Builder()
                .url(urlFor(path))
                .header("Accept", "text/event-stream")
                .get()
                .build();
        Call call = streamClient.newCall(req);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                mainHandler.post(() -> listener.onError("Canlı bağlantı hatası: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> listener.onError("Canlı bağlantı kurulamadı (" + response.code() + ")"));
                    response.close();
                    return;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                    String eventType = null;
                    String line;
                    while (!call.isCanceled() && (line = reader.readLine()) != null) {
                        if (line.startsWith("event: ")) {
                            eventType = line.substring(7).trim();
                        } else if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("put".equals(eventType) || "patch".equals(eventType)) {
                                try {
                                    JSONObject payload = new JSONObject(data);
                                    String changedPath = payload.optString("path", "/");
                                    mainHandler.post(() -> listener.onEvent(changedPath, payload));
                                } catch (JSONException ignored) { }
                            }
                            eventType = null;
                        }
                        // "event: keep-alive" ve boş satırlar sessizce geçilir.
                    }
                } catch (IOException e) {
                    if (!call.isCanceled()) {
                        mainHandler.post(() -> listener.onError("Canlı bağlantı koptu: " + e.getMessage()));
                    }
                } finally {
                    response.close();
                }
            }
        });
        return call;
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    private String toJsonString(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject) return value.toString();
        if (value instanceof String) return jsonQuote((String) value);
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        return value.toString();
    }

    /** org.json'un kendi kaçışlamasını kullanarak (tırnak, ters bölü, satır sonu, vb.) güvenli bir JSON string üretir. */
    private String jsonQuote(String value) {
        return JSONObject.quote(value);
    }

    private void enqueueObject(Request req, ObjectCallback cb) {
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> cb.onError("Bağlantı hatası: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "null";
                response.close();
                mainHandler.post(() -> {
                    try {
                        if (!response.isSuccessful()) {
                            cb.onError("Firebase hatası (" + response.code() + ")");
                            return;
                        }
                        if (bodyStr == null || bodyStr.equals("null") || bodyStr.trim().isEmpty()) {
                            cb.onSuccess(null);
                            return;
                        }
                        cb.onSuccess(new JSONObject(bodyStr));
                    } catch (JSONException e) {
                        // Firebase, tek bir skaler değer (string/sayı/bool) döndüğünde
                        // geçerli bir JSON objesi olmayabilir; bu durumda ham veriyi
                        // "value" alanına sararak geri veriyoruz.
                        try {
                            JSONObject wrap = new JSONObject();
                            wrap.put("value", bodyStr);
                            cb.onSuccess(wrap);
                        } catch (JSONException e2) {
                            cb.onError("Firebase'den geçersiz yanıt geldi");
                        }
                    }
                });
            }
        });
    }

    private void enqueueSimple(Request req, SimpleCallback cb) {
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> cb.onError("Bağlantı hatası: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                boolean ok = response.isSuccessful();
                int code = response.code();
                response.close();
                mainHandler.post(() -> {
                    if (ok) cb.onSuccess();
                    else cb.onError("Firebase hatası (" + code + ")");
                });
            }
        });
    }
}
