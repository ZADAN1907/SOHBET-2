package com.anzakchat.app.net;

import android.os.Handler;
import android.os.Looper;

import com.anzakchat.app.util.SessionManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Firebase'in yerini alan kendi backend'imizle konuşan HTTP istemcisi.
 * Kullanım: ApiClient.get(this).post("/api/rooms", body, callback);
 *
 * BASE_URL'i kendi sunucunun adresine göre değiştir:
 *  - Yerelde emülatörden test: http://10.0.2.2:3000
 *  - Gerçek cihaz / prod: https://kendi-domainin.com
 */
public class ApiClient {

    // TODO: Kendi sunucunun adresini buraya yaz (HTTPS önerilir, prod'da mutlaka HTTPS kullan).
    public static final String BASE_URL = "http://10.0.2.2:3000";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static ApiClient instance;

    private final OkHttpClient client;
    private final SessionManager session;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ApiCallback {
        void onSuccess(JSONObject body);
        void onError(String message);
    }

    public interface ApiArrayCallback {
        void onSuccess(org.json.JSONArray body);
        void onError(String message);
    }

    private ApiClient(SessionManager session) {
        this.session = session;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized ApiClient get(android.content.Context context) {
        if (instance == null) {
            instance = new ApiClient(new SessionManager(context));
        }
        return instance;
    }

    private Request.Builder authedBuilder(String path) {
        Request.Builder b = new Request.Builder().url(BASE_URL + path);
        String token = session.getToken();
        if (token != null) {
            b.addHeader("Authorization", "Bearer " + token);
        }
        return b;
    }

    public void get(String path, ApiCallback cb) {
        Request req = authedBuilder(path).get().build();
        enqueue(req, cb);
    }

    public void getArray(String path, ApiArrayCallback cb) {
        Request req = authedBuilder(path).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                postError(cb, "Bağlantı hatası: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "[]";
                response.close();
                mainHandler.post(() -> {
                    try {
                        if (!response.isSuccessful()) {
                            cb.onError(extractError(bodyStr, response.code()));
                            return;
                        }
                        cb.onSuccess(new org.json.JSONArray(bodyStr));
                    } catch (JSONException e) {
                        cb.onError("Sunucudan geçersiz yanıt geldi");
                    }
                });
            }
        });
    }

    public void post(String path, JSONObject body, ApiCallback cb) {
        RequestBody rb = RequestBody.create(body == null ? "{}" : body.toString(), JSON);
        Request req = authedBuilder(path).post(rb).build();
        enqueue(req, cb);
    }

    public void patch(String path, JSONObject body, ApiCallback cb) {
        RequestBody rb = RequestBody.create(body == null ? "{}" : body.toString(), JSON);
        Request req = authedBuilder(path).patch(rb).build();
        enqueue(req, cb);
    }

    public void delete(String path, ApiCallback cb) {
        Request req = authedBuilder(path).delete().build();
        enqueue(req, cb);
    }

    public void delete(String path, JSONObject body, ApiCallback cb) {
        RequestBody rb = RequestBody.create(body == null ? "{}" : body.toString(), JSON);
        Request req = authedBuilder(path).delete(rb).build();
        enqueue(req, cb);
    }

    public interface UploadCallback {
        void onSuccess(String url, String fileName, long fileSize, String mimeType);
        void onError(String message);
    }

    /** Dosyayı /api/upload'a multipart olarak gönderir (resim/ses/video/dosya). */
    public void uploadFile(byte[] bytes, String fileName, String mimeType, UploadCallback cb) {
        MediaType mt = MediaType.parse(mimeType != null ? mimeType : "application/octet-stream");
        RequestBody fileBody = RequestBody.create(bytes, mt);
        okhttp3.MultipartBody body = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .build();
        Request req = authedBuilder("/api/upload").post(body).build();

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
                            cb.onError(extractError(bodyStr, response.code()));
                            return;
                        }
                        JSONObject obj = new JSONObject(bodyStr);
                        cb.onSuccess(
                                BASE_URL + obj.getString("url"),
                                obj.optString("fileName", fileName),
                                obj.optLong("fileSize", bytes.length),
                                obj.optString("mimeType", mimeType)
                        );
                    } catch (JSONException e) {
                        cb.onError("Sunucudan geçersiz yanıt geldi");
                    }
                });
            }
        });
    }

    private void enqueue(Request req, ApiCallback cb) {
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                postError(cb, "Bağlantı hatası: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "{}";
                response.close();
                mainHandler.post(() -> {
                    try {
                        if (!response.isSuccessful()) {
                            cb.onError(extractError(bodyStr, response.code()));
                            return;
                        }
                        cb.onSuccess(new JSONObject(bodyStr));
                    } catch (JSONException e) {
                        cb.onError("Sunucudan geçersiz yanıt geldi");
                    }
                });
            }
        });
    }

    private String extractError(String bodyStr, int code) {
        try {
            JSONObject obj = new JSONObject(bodyStr);
            return obj.optString("error", "Sunucu hatası (" + code + ")");
        } catch (JSONException e) {
            return "Sunucu hatası (" + code + ")";
        }
    }

    private void postError(ApiCallback cb, String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }

    private void postError(ApiArrayCallback cb, String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }
}
