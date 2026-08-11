package com.anzakchat.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.anzakchat.app.R;
import com.anzakchat.app.adapter.MemberAdapter;
import com.anzakchat.app.adapter.MessageAdapter;
import com.anzakchat.app.model.MessageModel;
import com.anzakchat.app.net.FirebaseClient;
import com.anzakchat.app.net.SocketManager;
import com.anzakchat.app.util.SessionManager;
import com.anzakchat.app.util.UiUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;

/**
 * Firebase Realtime Database'e doğrudan bağlanan sürüm (qyriptalk-cdf56, web
 * sürümüyle aynı şema). Mesajlar rooms/{id}/messages veya
 * privateMessages/{dmId}/messages altında; canlı güncellemeler Firebase'in
 * SSE akışıyla (FirebaseClient.listen) geliyor, artık Socket.IO değil.
 *
 * NOT: Oda yönetimi (kick/ban/mute/disappearing/rename/parola değiştir/
 * üye listesi) henüz eski özel backend'e bağlı — bu ekranlar şimdilik
 * çalışmaz, bir sonraki adımda Firebase'e taşınacak.
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_CHAT_ID = "chat_id";   // oda: roomId, DM: otherUid
    public static final String EXTRA_IS_DM = "is_dm";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_OTHER_UID = "other_uid";

    private static final long TYPING_TIMEOUT_MS = 3000;
    private static final int MAX_IMAGE_DIMENSION = 1280;
    private static final int JPEG_QUALITY = 70;

    private MessageAdapter adapter;
    private RecyclerView recyclerView;
    private SessionManager session;

    private String chatId;      // oda: roomId, DM: otherUid
    private boolean isDm;
    private String myUid;
    private String myUsername;
    private String chatTitle;
    private String otherUid;
    private boolean isRoomOwner = false;
    private boolean amIBlockingOther = false;

    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private boolean isTyping = false;
    private final Runnable clearTypingRunnable = this::clearTypingState;

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> pickFileLauncher;
    private ActivityResultLauncher<String> recordAudioPermissionLauncher;

    // ---- Mesaja yanıt verme (reply) ----
    private MessageModel pendingReply;

    // ---- Sesli mesaj kaydı ----
    private MediaRecorder mediaRecorder;
    private File currentRecordingFile;
    private long recordingStartMs;
    private boolean isRecording = false;
    private final Handler recordingHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordingTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            long elapsed = System.currentTimeMillis() - recordingStartMs;
            TextView timer = findViewById(R.id.recording_timer);
            if (timer != null) timer.setText(formatDuration(elapsed));
            recordingHandler.postDelayed(this, 500);
        }
    };

    // ---- Firebase canlı bağlantılar ----
    private final Map<String, MessageModel> messageMap = new LinkedHashMap<>();
    private Call messagesListenCall;
    private Call typingListenCall;

    private String messagesPath() {
        return isDm ? "privateMessages/" + com.anzakchat.app.model.DmSummary.buildDmId(myUid, otherUid) + "/messages"
                : "rooms/" + chatId + "/messages";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        session = new SessionManager(this);
        myUid = session.getUid();
        myUsername = session.getUsername();

        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        isDm = getIntent().getBooleanExtra(EXTRA_IS_DM, false);
        chatTitle = getIntent().getStringExtra(EXTRA_TITLE);
        otherUid = getIntent().getStringExtra(EXTRA_OTHER_UID);

        Toolbar toolbar = findViewById(R.id.chat_toolbar);
        toolbar.setTitle(chatTitle != null ? chatTitle : "Sohbet");
        toolbar.setNavigationOnClickListener(v -> finish());
        setupChatMenu(toolbar);

        if (!isDm) {
            checkIsRoomOwner();
        } else if (otherUid != null) {
            refreshBlockState();
        }

        recyclerView = findViewById(R.id.messages_recycler);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(lm);
        adapter = new MessageAdapter(myUid, this::showMessageActions, this::openFile);
        recyclerView.setAdapter(adapter);
        if (recyclerView.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            androidx.recyclerview.widget.SimpleItemAnimator animator =
                    (androidx.recyclerview.widget.SimpleItemAnimator) recyclerView.getItemAnimator();
            animator.setSupportsChangeAnimations(false);
            animator.setAddDuration(160);
            animator.setChangeDuration(120);
        }

        listenForMessages();
        if (!isDm) listenForTyping();

        EditText messageInput = findViewById(R.id.message_input);
        ImageButton sendButton = findViewById(R.id.send_button);
        ImageButton attachButton = findViewById(R.id.attach_button);
        ImageButton emojiButton = findViewById(R.id.emoji_button);
        setupEmojiPanel(emojiButton, messageInput);

        Runnable sendAction = () -> {
            if (isDm && amIBlockingOther) {
                Toast.makeText(this, "Bu kullanıcıyı engellediniz. Mesaj göndermek için önce engeli kaldırın.", Toast.LENGTH_SHORT).show();
                return;
            }
            String text = messageInput.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "Lütfen bir mesaj yazın.", Toast.LENGTH_SHORT).show();
                return;
            }
            sendTextMessage(text);
            messageInput.setText("");
        };

        sendButton.setOnClickListener(v -> sendAction.run());
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendAction.run();
                return true;
            }
            return false;
        });

        setupTypingIndicator(messageInput);
        setupImageAttachment(attachButton);
        setupReplyBar();
        setupVoiceRecording();
    }

    // ============================================================
    // Mesaja yanıt verme (reply)
    // ============================================================
    private void setupReplyBar() {
        findViewById(R.id.reply_bar_close).setOnClickListener(v -> clearPendingReply());
    }

    private void showMessageActions(MessageModel message) {
        boolean canDelete = myUid.equals(message.getSenderUid());
        List<String> actions = new ArrayList<>();
        actions.add("Yanıtla");
        if (canDelete) actions.add("Sil");

        new AlertDialog.Builder(this)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String chosen = actions.get(which);
                    if ("Yanıtla".equals(chosen)) {
                        setPendingReply(message);
                    } else if ("Sil".equals(chosen)) {
                        confirmDeleteMessage(message);
                    }
                })
                .show();
    }

    private void setPendingReply(MessageModel message) {
        pendingReply = message;
        View replyBar = findViewById(R.id.reply_bar);
        TextView senderView = findViewById(R.id.reply_bar_sender);
        TextView textView = findViewById(R.id.reply_bar_text);

        senderView.setText(myUid.equals(message.getSenderUid()) ? "Sen" : message.getSender());
        textView.setText(replyPreviewFor(message));
        replyBar.setVisibility(View.VISIBLE);

        EditText messageInput = findViewById(R.id.message_input);
        messageInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(messageInput, 0);
    }

    private String replyPreviewFor(MessageModel message) {
        if (message.getText() != null && !message.getText().isEmpty()) return message.getText();
        if ("image".equals(message.getType())) return "\uD83D\uDCF7 Görsel";
        if ("voice".equals(message.getType())) return "\uD83C\uDFA4 Sesli mesaj";
        if ("file".equals(message.getType())) return "\uD83D\uDCCE " + (message.getFileName() != null ? message.getFileName() : "Dosya");
        return "";
    }

    private void clearPendingReply() {
        pendingReply = null;
        findViewById(R.id.reply_bar).setVisibility(View.GONE);
    }

    // ============================================================
    // Sesli mesaj kaydı (basılı tut → bırakınca gönder)
    // ============================================================
    private void setupVoiceRecording() {
        ImageButton micButton = findViewById(R.id.mic_button);
        ImageButton recordingCancel = findViewById(R.id.recording_cancel);

        recordAudioPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) {
                Toast.makeText(this, "Sesli mesaj göndermek için mikrofon izni gerekli.", Toast.LENGTH_SHORT).show();
            }
        });

        micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (isDm && amIBlockingOther) {
                        Toast.makeText(this, "Bu kullanıcıyı engellediniz.", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
                        return true;
                    }
                    startVoiceRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (isRecording) stopVoiceRecording(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (isRecording) stopVoiceRecording(false);
                    return true;
            }
            return false;
        });

        recordingCancel.setOnClickListener(v -> stopVoiceRecording(false));
    }

    private void startVoiceRecording() {
        try {
            currentRecordingFile = File.createTempFile("voice_", ".m4a", getCacheDir());
            MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(currentRecordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            mediaRecorder = recorder;
            isRecording = true;
            recordingStartMs = System.currentTimeMillis();

            findViewById(R.id.recording_bar).setVisibility(View.VISIBLE);
            TextView timer = findViewById(R.id.recording_timer);
            timer.setText("0:00");
            recordingHandler.post(recordingTickRunnable);
        } catch (IOException e) {
            Toast.makeText(this, "Ses kaydı başlatılamadı.", Toast.LENGTH_SHORT).show();
            isRecording = false;
        }
    }

    private void stopVoiceRecording(boolean send) {
        if (!isRecording) return;
        isRecording = false;
        recordingHandler.removeCallbacks(recordingTickRunnable);
        findViewById(R.id.recording_bar).setVisibility(View.GONE);

        long durationMs = System.currentTimeMillis() - recordingStartMs;

        try {
            mediaRecorder.stop();
        } catch (Exception ignored) { }
        mediaRecorder.release();
        mediaRecorder = null;

        if (!send || durationMs < 800) {
            if (currentRecordingFile != null) currentRecordingFile.delete();
            currentRecordingFile = null;
            if (send) Toast.makeText(this, "Kayıt çok kısa, tekrar dene.", Toast.LENGTH_SHORT).show();
            return;
        }

        uploadVoiceMessage(currentRecordingFile, durationMs);
        currentRecordingFile = null;
    }

    private void uploadVoiceMessage(File file, long durationMs) {
        try {
            if (file.length() > MAX_BASE64_FILE_BYTES) {
                Toast.makeText(this, "Ses kaydı çok uzun (4 MB üstü).", Toast.LENGTH_SHORT).show();
                file.delete();
                return;
            }
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int read = fis.read(bytes);
                if (read != bytes.length) {
                    Toast.makeText(this, "Ses dosyası okunamadı.", Toast.LENGTH_SHORT).show();
                    file.delete();
                    return;
                }
            }
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            String dataUrl = "data:audio/mp4;base64," + base64;
            sendVoiceMessage(dataUrl, "sesli_mesaj.m4a", bytes.length, "audio/mp4", durationMs);
            file.delete();
        } catch (Exception e) {
            Toast.makeText(this, "Sesli mesaj gönderilirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    // ============================================================
    // Firebase canlı mesaj dinleme
    // ============================================================
    private void listenForMessages() {
        messagesListenCall = FirebaseClient.get().listen(messagesPath(), new FirebaseClient.RealtimeListener() {
            @Override
            public void onEvent(String path, JSONObject wrapper) {
                onMessagesFirebaseEvent(path, wrapper);
            }

            @Override
            public void onError(String message) {
                // Sessiz geç — bağlantı geçiciyse OkHttp otomatik tekrar dener (retryOnConnectionFailure).
            }
        });
    }

    private void onMessagesFirebaseEvent(String path, JSONObject wrapper) {
        boolean dataIsNull = wrapper.isNull("data");
        Object rawData = dataIsNull ? null : wrapper.opt("data");

        if ("/".equals(path)) {
            messageMap.clear();
            adapter.clear();
            if (rawData instanceof JSONObject) {
                JSONObject all = (JSONObject) rawData;
                List<String> ids = new ArrayList<>();
                Iterator<String> it = all.keys();
                while (it.hasNext()) ids.add(it.next());
                java.util.Collections.sort(ids); // Firebase push key'leri kronolojik sıralanabilir
                for (String id : ids) {
                    JSONObject o = all.optJSONObject(id);
                    if (o == null) continue;
                    MessageModel m = MessageModel.fromFirebaseJson(id, o);
                    messageMap.put(id, m);
                    adapter.add(m);
                    markAsReadIfNeeded(m);
                }
            }
            if (adapter.getMessageCount() > 0) recyclerView.scrollToPosition(adapter.getMessageCount() - 1);
            return;
        }

        String rest = path.startsWith("/") ? path.substring(1) : path;
        int slash = rest.indexOf('/');
        String msgId = slash >= 0 ? rest.substring(0, slash) : rest;
        if (msgId.isEmpty()) return;

        // Alt bir alan değişmiş olsa bile (ör. readBy/{uid}) o mesajı tam olarak
        // yeniden çekip adapter'ı güncelliyoruz — kısmi patch'i elle birleştirmekten
        // daha basit ve hataya kapalı.
        FirebaseClient.get().get(messagesPath() + "/" + msgId, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject o) {
                if (o == null) {
                    if (messageMap.remove(msgId) != null) adapter.removeById(msgId);
                    return;
                }
                MessageModel m = MessageModel.fromFirebaseJson(msgId, o);
                boolean isNew = !messageMap.containsKey(msgId);
                messageMap.put(msgId, m);
                if (isNew) {
                    adapter.add(m);
                    recyclerView.scrollToPosition(adapter.getMessageCount() - 1);
                    markAsReadIfNeeded(m);
                } else {
                    adapter.update(m);
                }
            }

            @Override
            public void onError(String message) { }
        });
    }

    // ============================================================
    // Firebase canlı "yazıyor..." dinleme (yalnızca odalar için — web şemasıyla aynı)
    // ============================================================
    private void listenForTyping() {
        typingListenCall = FirebaseClient.get().listen("rooms/" + chatId + "/typing", new FirebaseClient.RealtimeListener() {
            @Override
            public void onEvent(String path, JSONObject wrapper) {
                boolean anyoneElseTyping = false;
                boolean dataIsNull = wrapper.isNull("data");
                Object rawData = dataIsNull ? null : wrapper.opt("data");
                if ("/".equals(path) && rawData instanceof JSONObject) {
                    JSONObject typingMap = (JSONObject) rawData;
                    Iterator<String> it = typingMap.keys();
                    while (it.hasNext()) {
                        String uid = it.next();
                        if (!uid.equals(myUid) && typingMap.optBoolean(uid, false)) {
                            anyoneElseTyping = true;
                            break;
                        }
                    }
                } else if (!"/".equals(path)) {
                    String rest = path.startsWith("/") ? path.substring(1) : path;
                    String uid = rest.split("/")[0];
                    if (!uid.equals(myUid) && rawData instanceof Boolean && (Boolean) rawData) {
                        anyoneElseTyping = true;
                    } else if (!(rawData instanceof Boolean) || !((Boolean) rawData)) {
                        // O kullanıcı yazmayı bıraktı — başka biri hâlâ yazıyor mu bilemiyoruz,
                        // basitlik için indicator'ı temizliyoruz (nadiren yanlış negatif olur).
                        anyoneElseTyping = false;
                    }
                }
                TextView typingIndicatorView = findViewById(R.id.typing_indicator);
                if (typingIndicatorView != null) {
                    typingIndicatorView.setText(anyoneElseTyping ? "yazıyor..." : "");
                }
            }

            @Override
            public void onError(String message) { }
        });
    }

    private void checkIsRoomOwner() {
        FirebaseClient.get().get("rooms/" + chatId, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject room) {
                isRoomOwner = room != null && myUid.equals(room.optString("createdBy", null));
            }

            @Override
            public void onError(String message) { }
        });
    }

    private void refreshBlockState() {
        // TODO: engelleme listesi henüz Firebase'e taşınmadı.
    }

    private void updateBlockedInputState() {
        EditText messageInput = findViewById(R.id.message_input);
        if (messageInput == null) return;
        if (amIBlockingOther) {
            messageInput.setEnabled(false);
            messageInput.setHint("Bu kullanıcıyı engellediniz");
        } else {
            messageInput.setEnabled(true);
            messageInput.setHint("Mesaj yaz...");
        }
    }

    private void markAsReadIfNeeded(MessageModel m) {
        if (m.getId() == null || myUid.equals(m.getSenderUid())) return;
        if (Boolean.TRUE.equals(m.getReadBy().get(myUid))) return;
        FirebaseClient.get().put(messagesPath() + "/" + m.getId() + "/readBy/" + myUid, true,
                new FirebaseClient.SimpleCallback() {
                    @Override public void onSuccess() { }
                    @Override public void onError(String message) { }
                });
    }

    // ============================================================
    // Emoji seçici
    // ============================================================
    private void setupEmojiPanel(ImageButton emojiButton, EditText messageInput) {
        RecyclerView emojiPanel = findViewById(R.id.emoji_panel);
        emojiPanel.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 8));

        List<String> emojis = java.util.Arrays.asList(
                "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜",
                "🤔", "😎", "😢", "😭", "😡", "😱", "🥳", "🙄",
                "👍", "👎", "👏", "🙏", "💪", "🤝", "✌️", "🤞",
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔",
                "🔥", "✨", "🎉", "🎂", "☕", "🍕", "⚽", "🎮",
                "😴", "🤗", "😇", "🥺", "😅", "😆", "🙃", "😉");

        com.anzakchat.app.adapter.EmojiAdapter emojiAdapter =
                new com.anzakchat.app.adapter.EmojiAdapter(emojis, emoji -> {
                    int start = Math.max(messageInput.getSelectionStart(), 0);
                    int end = Math.max(messageInput.getSelectionEnd(), 0);
                    messageInput.getText().replace(Math.min(start, end), Math.max(start, end), emoji);
                });
        emojiPanel.setAdapter(emojiAdapter);

        emojiButton.setOnClickListener(v -> {
            if (emojiPanel.getVisibility() == View.VISIBLE) {
                emojiPanel.setVisibility(View.GONE);
            } else {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(messageInput.getWindowToken(), 0);
                emojiPanel.setVisibility(View.VISIBLE);
            }
        });

        messageInput.setOnClickListener(v -> emojiPanel.setVisibility(View.GONE));
    }

    // ============================================================
    // Mesaj gönderme — REST yerine doğrudan socket üzerinden (daha hızlı, canlı)
    // ============================================================
    private void sendTextMessage(String text) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sender", myUsername);
            payload.put("senderUid", myUid);
            payload.put("text", text);
            payload.put("timestamp", FirebaseClient.serverTimestamp());
            payload.put("readBy", new JSONObject());
            if (pendingReply != null && pendingReply.getId() != null) {
                payload.put("replyToId", pendingReply.getId());
                payload.put("replyToSender", myUid.equals(pendingReply.getSenderUid()) ? "Sen" : pendingReply.getSender());
                payload.put("replyToPreview", replyPreviewFor(pendingReply));
            }
            FirebaseClient.get().push(messagesPath(), payload, new FirebaseClient.KeyCallback() {
                @Override public void onSuccess(String newKey) { }
                @Override public void onError(String message) {
                    Toast.makeText(ChatActivity.this, "Mesaj gönderilirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
                }
            });
            clearPendingReply();
        } catch (JSONException e) {
            Toast.makeText(this, "Mesaj gönderilirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendMediaMessage(String type, String mediaUrl, String fileName, long fileSize, String mimeType) {
        sendMediaMessage(type, mediaUrl, fileName, fileSize, mimeType, 0);
    }

    private void sendMediaMessage(String type, String mediaUrl, String fileName, long fileSize, String mimeType, long durationMs) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sender", myUsername);
            payload.put("senderUid", myUid);
            payload.put("type", type);
            payload.put("fileData", mediaUrl);
            payload.put("fileName", fileName);
            payload.put("fileSize", fileSize);
            payload.put("fileType", mimeType);
            payload.put("timestamp", FirebaseClient.serverTimestamp());
            payload.put("readBy", new JSONObject());
            if (durationMs > 0) payload.put("duration", durationMs / 1000);
            if (pendingReply != null && pendingReply.getId() != null) {
                payload.put("replyToId", pendingReply.getId());
                payload.put("replyToSender", myUid.equals(pendingReply.getSenderUid()) ? "Sen" : pendingReply.getSender());
                payload.put("replyToPreview", replyPreviewFor(pendingReply));
            }
            FirebaseClient.get().push(messagesPath(), payload, new FirebaseClient.KeyCallback() {
                @Override public void onSuccess(String newKey) { }
                @Override public void onError(String message) {
                    Toast.makeText(ChatActivity.this, "Gönderilemedi: " + message, Toast.LENGTH_SHORT).show();
                }
            });
            clearPendingReply();
        } catch (JSONException ignored) { }
    }

    private void sendVoiceMessage(String url, String fileName, long fileSize, String mimeType, long durationMs) {
        sendMediaMessage("voice", url, fileName, fileSize, mimeType, durationMs);
    }

    // ============================================================
    // Yazıyor... göstergesi (socket: typing:start / typing:stop)
    // ============================================================
    private void setupTypingIndicator(EditText messageInput) {
        if (isDm) return;

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0 && !isTyping) {
                    isTyping = true;
                    FirebaseClient.get().put("rooms/" + chatId + "/typing/" + myUid, true,
                            new FirebaseClient.SimpleCallback() {
                                @Override public void onSuccess() { }
                                @Override public void onError(String message) { }
                            });
                }
                typingHandler.removeCallbacks(clearTypingRunnable);
                typingHandler.postDelayed(clearTypingRunnable, TYPING_TIMEOUT_MS);
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });
    }

    private void clearTypingState() {
        if (isTyping) {
            isTyping = false;
            FirebaseClient.get().delete("rooms/" + chatId + "/typing/" + myUid,
                    new FirebaseClient.SimpleCallback() {
                        @Override public void onSuccess() { }
                        @Override public void onError(String message) { }
                    });
        }
    }

    // ============================================================
    // Mesaj silme
    // ============================================================
    private void confirmDeleteMessage(MessageModel message) {
        new AlertDialog.Builder(this)
                .setTitle("Mesajı sil")
                .setMessage("Bu mesajı silmek istiyor musun?")
                .setPositiveButton("Sil", (dialog, which) -> deleteMessage(message))
                .setNegativeButton("İptal", null)
                .show();
    }

    private void deleteMessage(MessageModel message) {
        if (message.getId() == null) return;
        if (!myUid.equals(message.getSenderUid())) {
            Toast.makeText(this, "Sadece kendi mesajlarınızı silebilirsiniz.", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseClient.get().delete(messagesPath() + "/" + message.getId(), new FirebaseClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                adapter.removeById(message.getId());
                Toast.makeText(ChatActivity.this, "Mesaj silindi.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message1) {
                Toast.makeText(ChatActivity.this, "Mesaj silinirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ============================================================
    // Görsel / Dosya mesajı gönderme — ayrı bir upload sunucusu yok, dosya
    // base64'e çevrilip doğrudan Firebase mesaj kaydına yazılıyor (web'in
    // backend rewrite'ından ÖNCEki orijinal Firebase yaklaşımı).
    // ============================================================
    private static final long MAX_BASE64_FILE_BYTES = 4L * 1024 * 1024; // Firebase RTDB'ye taşınabilir makul üst sınır

    private void setupImageAttachment(ImageButton attachButton) {
        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) sendImageMessage(uri);
        });
        pickFileLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) sendFileMessage(uri);
        });

        attachButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Görsel");
            popup.getMenu().add(0, 2, 1, "Dosya");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    pickImageLauncher.launch("image/*");
                } else {
                    pickFileLauncher.launch("*/*");
                }
                return true;
            });
            popup.show();
        });
    }

    private void sendImageMessage(Uri uri) {
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            Bitmap original = BitmapFactory.decodeStream(input);
            if (input != null) input.close();
            if (original == null) {
                Toast.makeText(this, "Görsel okunamadı.", Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap resized = resizeIfNeeded(original);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            byte[] bytes = baos.toByteArray();
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + base64;
            sendMediaMessage("image", dataUrl, "gorsel.jpg", bytes.length, "image/jpeg");
        } catch (Exception e) {
            Toast.makeText(this, "Görsel gönderilirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap resizeIfNeeded(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();
        if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) return original;

        float ratio = Math.min((float) MAX_IMAGE_DIMENSION / width, (float) MAX_IMAGE_DIMENSION / height);
        int newWidth = Math.round(width * ratio);
        int newHeight = Math.round(height * ratio);
        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
    }

    private void sendFileMessage(Uri uri) {
        try {
            String fileName = queryFileName(uri);
            String mimeType = getContentResolver().getType(uri);
            long fileSize = queryFileSize(uri);

            if (fileSize > MAX_BASE64_FILE_BYTES) {
                Toast.makeText(this, "Dosya çok büyük (maksimum 4 MB).", Toast.LENGTH_LONG).show();
                return;
            }

            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) {
                Toast.makeText(this, "Dosya okunamadı.", Toast.LENGTH_SHORT).show();
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            input.close();

            byte[] bytes = buffer.toByteArray();
            if (bytes.length > MAX_BASE64_FILE_BYTES) {
                Toast.makeText(this, "Dosya çok büyük (maksimum 4 MB).", Toast.LENGTH_LONG).show();
                return;
            }

            String effectiveMime = mimeType != null ? mimeType : "application/octet-stream";
            String finalName = fileName != null ? fileName : "dosya";
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            String dataUrl = "data:" + effectiveMime + ";base64," + base64;

            sendMediaMessage("file", dataUrl, finalName, bytes.length, effectiveMime);
        } catch (Exception e) {
            Toast.makeText(this, "Dosya gönderilirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
        }
    }

    private String queryFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private long queryFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) { }
        return 0L;
    }

    /** Dosya mesajına dokununca: base64 veriyi cache'e yazıp FileProvider ile açar. */
    private void openFile(MessageModel message) {
        String url = message.getMediaUrl();
        if (url == null) return;
        try {
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0) return;
                byte[] bytes = android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT);
                File dir = new File(getCacheDir(), "received_files");
                if (!dir.exists()) dir.mkdirs();
                String name = message.getFileName() != null ? message.getFileName() : "dosya";
                File out = new File(dir, System.currentTimeMillis() + "_" + name);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(bytes);
                }
                Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", out);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, message.getMimeType() != null ? message.getMimeType() : "*/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Bu dosyayı açacak bir uygulama bulunamadı.", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            // Geriye dönük uyumluluk: eski mesajlarda gerçek bir URL olabilir.
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "Bu dosyayı açacak bir uygulama bulunamadı.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Dosya açılırken bir hata oluştu.", Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // Oda / DM menüsü
    // ============================================================
    private void setupChatMenu(Toolbar toolbar) {
        ImageButton menuButton = findViewById(R.id.chat_menu_button);
        menuButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, menuButton);
            if (isDm) {
                popup.inflate(R.menu.dm_chat_menu);
                popup.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.menu_user_info) {
                        showUserInfo();
                        return true;
                    } else if (id == R.id.menu_mute_dm) {
                        toggleMuteDm();
                        return true;
                    } else if (id == R.id.menu_clear_dm_chat) {
                        confirmClearChat(true);
                        return true;
                    } else if (id == R.id.menu_block_user) {
                        toggleBlockUser();
                        return true;
                    }
                    return false;
                });
            } else {
                popup.inflate(R.menu.room_chat_menu);
                popup.getMenu().findItem(R.id.menu_manage_members).setVisible(isRoomOwner);
                popup.getMenu().findItem(R.id.menu_clear_room_chat).setVisible(isRoomOwner);
                popup.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.menu_room_info) {
                        showRoomInfo();
                        return true;
                    } else if (id == R.id.menu_manage_members) {
                        showManageMembers();
                        return true;
                    } else if (id == R.id.menu_mute_room) {
                        toggleMuteRoom();
                        return true;
                    } else if (id == R.id.menu_clear_room_chat) {
                        confirmClearChat(false);
                        return true;
                    } else if (id == R.id.menu_leave_room) {
                        confirmLeaveRoom();
                        return true;
                    }
                    return false;
                });
            }
            popup.show();
        });
    }

    private void showRoomInfo() {
        FirebaseClient.get().get("rooms/" + chatId, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject room) {
                int count = 0;
                if (room != null) {
                    JSONObject users = room.optJSONObject("users");
                    count = users != null ? users.length() : 0;
                }
                bindRoomInfoDialog(count);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ChatActivity.this, "Oda bilgisi alınamadı.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bindRoomInfoDialog(int memberCount) {
        String cleanRoomName = chatTitle != null ? chatTitle.replaceAll("\\s+", "") : "Oda";
        String inviteLink = "AnzakChat/" + cleanRoomName + "/" + chatId;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_room_info, null);
        TextView avatarView = view.findViewById(R.id.room_info_avatar);
        TextView nameView = view.findViewById(R.id.room_info_name);
        TextView metaView = view.findViewById(R.id.room_info_meta);
        EditText linkView = view.findViewById(R.id.room_info_invite_link);
        SwitchMaterial muteSwitch = view.findViewById(R.id.room_info_mute_switch);
        View ownerSection = view.findViewById(R.id.room_info_owner_section);

        avatarView.setText(UiUtils.initial(chatTitle));
        nameView.setText(chatTitle);
        metaView.setText(memberCount + " üye");
        linkView.setText(inviteLink);
        ownerSection.setVisibility(isRoomOwner ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("Kapat", null)
                .create();
        dialog.show();

        view.findViewById(R.id.room_info_copy_button).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Davet linki", inviteLink));
            Toast.makeText(this, "Davet linki kopyalandı.", Toast.LENGTH_SHORT).show();
        });

        view.findViewById(R.id.room_info_share_button).setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "AnzakChat'te \"" + chatTitle + "\" odasına katıl: " + inviteLink);
            startActivity(Intent.createChooser(shareIntent, "Davet linkini paylaş"));
        });

        loadMuteState("room", chatId, muted -> {
            muteSwitch.setChecked(muted);
            muteSwitch.setOnCheckedChangeListener((btn, checked) -> setMuted("room", chatId, checked, null));
        });

        View disappearingRow = view.findViewById(R.id.room_info_disappearing_row);
        TextView disappearingValue = view.findViewById(R.id.room_info_disappearing_value);
        loadRoomDisappearingSeconds(seconds -> disappearingValue.setText(disappearingLabel(seconds)));
        disappearingRow.setOnClickListener(v -> {
            if (!isRoomOwner) {
                Toast.makeText(this, "Sadece oda kurucusu değiştirebilir.", Toast.LENGTH_SHORT).show();
                return;
            }
            showDisappearingPicker(seconds ->
                    FirebaseClient.get().put("rooms/" + chatId + "/disappearSeconds", seconds, new FirebaseClient.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            disappearingValue.setText(disappearingLabel(seconds));
                            Toast.makeText(ChatActivity.this, "Kaybolan mesajlar güncellendi.", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    }));
        });

        view.findViewById(R.id.room_info_rename_button).setOnClickListener(v -> {
            dialog.dismiss();
            showRenameRoomDialog(chatTitle);
        });
        view.findViewById(R.id.room_info_password_button).setOnClickListener(v -> {
            dialog.dismiss();
            showChangeRoomPasswordDialog();
        });
        view.findViewById(R.id.room_info_delete_button).setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteRoom(chatTitle);
        });
    }

    private interface MuteStateCallback { void onResult(boolean muted); }

    // ============================================================
    // Kaybolan mesajlar (WhatsApp'taki disappearing messages) — gizlilik özelliği
    // ============================================================
    private interface DisappearingPickListener { void onPicked(int seconds); }

    private void loadRoomDisappearingSeconds(java.util.function.IntConsumer cb) {
        FirebaseClient.get().get("rooms/" + chatId + "/disappearSeconds", new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject wrap) {
                int seconds = 0;
                if (wrap != null && wrap.has("value")) {
                    try { seconds = Integer.parseInt(wrap.getString("value")); } catch (Exception ignored) { }
                }
                cb.accept(seconds);
            }

            @Override
            public void onError(String message) { cb.accept(0); }
        });
    }

    private String disappearingLabel(int seconds) {
        if (seconds <= 0) return "Kapalı";
        if (seconds < 3600) return (seconds / 60) + " dakika";
        if (seconds < 86400) return (seconds / 3600) + " saat";
        return (seconds / 86400) + " gün";
    }

    private void showDisappearingPicker(DisappearingPickListener listener) {
        String[] labels = {"Kapalı", "5 dakika", "1 saat", "24 saat", "7 gün"};
        int[] values = {0, 5 * 60, 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60};

        new AlertDialog.Builder(this)
                .setTitle("Kaybolan mesajlar")
                .setItems(labels, (dialog, which) -> listener.onPicked(values[which]))
                .show();
    }

    private void loadMuteState(String targetType, String targetId, MuteStateCallback cb) {
        FirebaseClient.get().get("users/" + myUid + "/mutes/" + targetType + "_" + targetId, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject wrap) {
                cb.onResult(wrap != null);
            }

            @Override
            public void onError(String message) { cb.onResult(false); }
        });
    }

    private void setMuted(String targetType, String targetId, boolean muted, Runnable onDone) {
        String path = "users/" + myUid + "/mutes/" + targetType + "_" + targetId;
        FirebaseClient.SimpleCallback cb = new FirebaseClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(ChatActivity.this,
                        muted ? "Bu sohbetin bildirimleri sessize alındı." : "Bildirimler tekrar açıldı.",
                        Toast.LENGTH_SHORT).show();
                if (onDone != null) onDone.run();
            }

            @Override
            public void onError(String message) { }
        };
        if (muted) {
            FirebaseClient.get().put(path, true, cb);
        } else {
            FirebaseClient.get().delete(path, cb);
        }
    }

    private void toggleMuteRoom() {
        loadMuteState("room", chatId, muted -> setMuted("room", chatId, !muted, null));
    }

    private void showRenameRoomDialog(String currentName) {
        EditText input = new EditText(this);
        input.setText(currentName);
        input.setSelection(input.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("Oda adını değiştir")
                .setView(input)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Oda adı boş olamaz.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        JSONObject body = new JSONObject();
                        body.put("name", newName);
                    FirebaseClient.get().patch("rooms/" + chatId, body, new FirebaseClient.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            chatTitle = newName;
                            Toolbar toolbar = findViewById(R.id.chat_toolbar);
                            toolbar.setTitle(newName);
                            Toast.makeText(ChatActivity.this, "Oda adı güncellendi.", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    });
                    } catch (JSONException ignored) { }
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void showChangeRoomPasswordDialog() {
        EditText input = new EditText(this);
        input.setHint("Yeni şifre (boş bırak = şifresiz)");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("Şifreyi değiştir")
                .setMessage("Odayı herkese açık yapmak için şifreyi boş bırak.")
                .setView(input)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String newPassword = input.getText().toString().trim();
                    try {
                        JSONObject body = new JSONObject();
                        body.put("password", newPassword.isEmpty() ? JSONObject.NULL : newPassword);
                        FirebaseClient.get().patch("rooms/" + chatId, body, new FirebaseClient.SimpleCallback() {
                            @Override
                            public void onSuccess() {
                                Toast.makeText(ChatActivity.this,
                                        newPassword.isEmpty() ? "Oda artık herkese açık." : "Şifre güncellendi.",
                                        Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (JSONException ignored) { }
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void confirmDeleteRoom(String roomName) {
        new AlertDialog.Builder(this)
                .setTitle("Odayı sil")
                .setMessage("\"" + roomName + "\" odası ve tüm mesajları kalıcı olarak silinecek. Bu işlem geri alınamaz.")
                .setPositiveButton("Sil", (d, w) ->
                        FirebaseClient.get().delete("rooms/" + chatId, new FirebaseClient.SimpleCallback() {
                            @Override
                            public void onSuccess() {
                                Toast.makeText(ChatActivity.this, "Oda silindi.", Toast.LENGTH_SHORT).show();
                                finish();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                            }
                        }))
                .setNegativeButton("İptal", null)
                .show();
    }

    private void showManageMembers() {
        if (!isRoomOwner) {
            Toast.makeText(this, "Üyeleri yönetme yetkisi sadece oda kurucusunda.", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseClient.get().get("rooms/" + chatId, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject room) {
                JSONObject memberIds = room != null ? room.optJSONObject("users") : null;
                if (memberIds == null || memberIds.length() == 0) {
                    showMembersDialog(new ArrayList<>());
                    return;
                }
                String createdBy = room.optString("createdBy", null);
                FirebaseClient.get().get("users", new FirebaseClient.ObjectCallback() {
                    @Override
                    public void onSuccess(JSONObject allUsers) {
                        List<MemberAdapter.MemberRow> rows = new ArrayList<>();
                        Iterator<String> it = memberIds.keys();
                        while (it.hasNext()) {
                            String uid = it.next();
                            if (myUid.equals(uid)) continue;
                            JSONObject u = allUsers != null ? allUsers.optJSONObject(uid) : null;
                            rows.add(new MemberAdapter.MemberRow(
                                    uid,
                                    u != null ? u.optString("username", uid) : uid,
                                    u != null && u.optBoolean("isOnline", false),
                                    uid.equals(createdBy)
                            ));
                        }
                        showMembersDialog(rows);
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showMembersDialog(List<MemberAdapter.MemberRow> rows) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_manage_members, null);
        RecyclerView recycler = view.findViewById(R.id.manage_members_recycler);
        TextView emptyView = view.findViewById(R.id.manage_members_empty);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        if (rows.isEmpty()) {
            recycler.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            new AlertDialog.Builder(this).setView(view)
                    .setNegativeButton("Kapat", null).show();
            return;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton("Kapat", null)
                .create();
        recycler.setAdapter(new MemberAdapter(rows, member -> {
            dialog.dismiss();
            showMemberAction(member.uid, member.username);
        }));
        dialog.show();
    }

    private void showMemberAction(String targetUid, String targetName) {
        new AlertDialog.Builder(this)
                .setTitle(targetName)
                .setItems(new String[]{"Odadan Çıkar", "Yasakla", "İptal"}, (dialog, which) -> {
                    if (which == 0) {
                        kickMember(targetUid, targetName);
                    } else if (which == 1) {
                        banMember(targetUid, targetName);
                    }
                })
                .show();
    }

    private void kickMember(String targetUid, String targetName) {
        FirebaseClient.get().delete("rooms/" + chatId + "/users/" + targetUid, new FirebaseClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                FirebaseClient.get().delete("users/" + targetUid + "/joinedRooms/" + chatId,
                        new FirebaseClient.SimpleCallback() {
                            @Override public void onSuccess() { }
                            @Override public void onError(String message) { }
                        });
                Toast.makeText(ChatActivity.this, targetName + " odadan çıkarıldı.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void banMember(String targetUid, String targetName) {
        FirebaseClient.get().delete("rooms/" + chatId + "/users/" + targetUid, new FirebaseClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                FirebaseClient.get().put("rooms/" + chatId + "/bannedUsers/" + targetUid, true,
                        new FirebaseClient.SimpleCallback() {
                            @Override public void onSuccess() { }
                            @Override public void onError(String message) { }
                        });
                Toast.makeText(ChatActivity.this, targetName + " yasaklandı.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmLeaveRoom() {
        new AlertDialog.Builder(this)
                .setTitle("Odadan Ayrıl")
                .setMessage((chatTitle != null ? "\"" + chatTitle + "\"" : "Bu oda") + " odasından ayrılmak istediğinize emin misiniz?")
                .setPositiveButton("Ayrıl", (dialog, which) ->
                        FirebaseClient.get().delete("rooms/" + chatId + "/users/" + myUid, new FirebaseClient.SimpleCallback() {
                            @Override
                            public void onSuccess() {
                                FirebaseClient.get().delete("users/" + myUid + "/joinedRooms/" + chatId,
                                        new FirebaseClient.SimpleCallback() {
                                            @Override public void onSuccess() { }
                                            @Override public void onError(String message) { }
                                        });
                                Toast.makeText(ChatActivity.this, "Odadan ayrıldınız.", Toast.LENGTH_SHORT).show();
                                finish();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                            }
                        }))
                .setNegativeButton("İptal", null)
                .show();
    }

    private void showUserInfo() {
        if (otherUid == null) {
            Toast.makeText(this, chatTitle != null ? chatTitle : "Kullanıcı bilgisi yok.", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseClient.get().get("users/" + otherUid, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject u) {
                bindUserInfoDialog(u != null ? u : new JSONObject());
            }

            @Override
            public void onError(String message) {
                bindUserInfoDialog(new JSONObject());
            }
        });
    }

    private void bindUserInfoDialog(JSONObject u) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_user_info, null);
        TextView avatar = view.findViewById(R.id.user_info_avatar);
        View onlineDot = view.findViewById(R.id.user_info_online_dot);
        TextView roleBadge = view.findViewById(R.id.user_info_role_badge);
        TextView nameView = view.findViewById(R.id.user_info_name);
        TextView statusView = view.findViewById(R.id.user_info_status);
        TextView bioView = view.findViewById(R.id.user_info_bio);
        SwitchMaterial muteSwitch = view.findViewById(R.id.user_info_mute_switch);
        com.google.android.material.button.MaterialButton clearBtn = view.findViewById(R.id.user_info_clear_chat_button);
        com.google.android.material.button.MaterialButton blockBtn = view.findViewById(R.id.user_info_block_button);

        String displayName = chatTitle != null ? chatTitle : u.optString("username", "Kullanıcı");
        avatar.setText(UiUtils.initial(displayName));
        nameView.setText(displayName);
        boolean online = u.optBoolean("isOnline", false);
        onlineDot.setVisibility(online ? View.VISIBLE : View.GONE);
        statusView.setText(online ? "Çevrimiçi" : UiUtils.formatLastSeen(u.optLong("lastSeen", 0)));
        String roleText = UiUtils.roleLabel(u.isNull("role") ? null : u.optString("role", null));
        if (roleText != null) {
            roleBadge.setText(roleText);
            roleBadge.setVisibility(View.VISIBLE);
        }
        String bio = u.isNull("bio") ? null : u.optString("bio", null);
        if (bio != null && !bio.trim().isEmpty()) {
            bioView.setText(bio);
            bioView.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();

        loadMuteState("dm", otherUid, muted -> {
            muteSwitch.setChecked(muted);
            muteSwitch.setOnCheckedChangeListener((btn, checked) -> setMuted("dm", otherUid, checked, null));
        });
        applyBlockButtonState(blockBtn, amIBlockingOther);

        View disappearingRow = view.findViewById(R.id.user_info_disappearing_row);
        TextView disappearingValue = view.findViewById(R.id.user_info_disappearing_value);
        String dmId = com.anzakchat.app.model.DmSummary.buildDmId(myUid, otherUid);
        FirebaseClient.get().get("privateMessages/" + dmId + "/disappearSeconds", new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject wrap) {
                int seconds = 0;
                if (wrap != null && wrap.has("value")) {
                    try { seconds = Integer.parseInt(wrap.getString("value")); } catch (Exception ignored) { }
                }
                disappearingValue.setText(disappearingLabel(seconds));
            }

            @Override
            public void onError(String message) { }
        });
        disappearingRow.setOnClickListener(v ->
                showDisappearingPicker(seconds ->
                        FirebaseClient.get().put("privateMessages/" + dmId + "/disappearSeconds", seconds, new FirebaseClient.SimpleCallback() {
                            @Override
                            public void onSuccess() {
                                disappearingValue.setText(disappearingLabel(seconds));
                                Toast.makeText(ChatActivity.this, "Kaybolan mesajlar güncellendi.", Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String message) {
                                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                            }
                        })));

        clearBtn.setOnClickListener(v -> {
            dialog.dismiss();
            confirmClearChat(true);
        });
        blockBtn.setOnClickListener(v -> {
            dialog.dismiss();
            toggleBlockUser();
        });

        dialog.show();
    }

    private void applyBlockButtonState(com.google.android.material.button.MaterialButton blockBtn, boolean blocked) {
        blockBtn.setText(blocked ? "Engeli kaldır" : "Kullanıcıyı engelle");
        int colorRes = blocked ? R.color.text_muted : R.color.qyrip_danger;
        blockBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this, colorRes)));
    }

    private void toggleMuteDm() {
        loadMuteState("dm", otherUid, muted -> setMuted("dm", otherUid, !muted, null));
    }

    private void toggleBlockUser() {
        if (otherUid == null) return;
        if (amIBlockingOther) {
            FirebaseClient.get().delete("users/" + myUid + "/blockedUsers/" + otherUid, new FirebaseClient.SimpleCallback() {
                @Override
                public void onSuccess() {
                    amIBlockingOther = false;
                    updateBlockedInputState();
                    Toast.makeText(ChatActivity.this, "Engel kaldırıldı.", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) { }
            });
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Kullanıcıyı engelle")
                    .setMessage((chatTitle != null ? "\"" + chatTitle + "\"" : "Bu kullanıcı") + " artık sana mesaj gönderemeyecek.")
                    .setPositiveButton("Engelle", (d, w) ->
                            FirebaseClient.get().put("users/" + myUid + "/blockedUsers/" + otherUid, true, new FirebaseClient.SimpleCallback() {
                                @Override
                                public void onSuccess() {
                                    amIBlockingOther = true;
                                    updateBlockedInputState();
                                    Toast.makeText(ChatActivity.this, "Kullanıcı engellendi.", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String message) { }
                            }))
                    .setNegativeButton("İptal", null)
                    .show();
        }
    }

    private void confirmClearChat(boolean dm) {
        new AlertDialog.Builder(this)
                .setTitle("Sohbeti temizle")
                .setMessage("Bu sohbetteki tüm mesajlar kalıcı olarak silinecek. Bu işlem geri alınamaz.")
                .setPositiveButton("Temizle", (d, w) -> {
                    String path = dm ? "privateMessages/" + com.anzakchat.app.model.DmSummary.buildDmId(myUid, otherUid) + "/messages"
                            : "rooms/" + chatId + "/messages";
                    FirebaseClient.get().delete(path, new FirebaseClient.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            adapter.clear();
                            Toast.makeText(ChatActivity.this, "Sohbet temizlendi.", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (messagesListenCall != null) messagesListenCall.cancel();
        if (typingListenCall != null) typingListenCall.cancel();

        typingHandler.removeCallbacks(clearTypingRunnable);
        clearTypingState();

        recordingHandler.removeCallbacks(recordingTickRunnable);
        if (isRecording) {
            stopVoiceRecording(false);
        }
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) { }
            mediaRecorder = null;
        }
        if (currentRecordingFile != null) {
            currentRecordingFile.delete();
            currentRecordingFile = null;
        }
        if (adapter != null) adapter.release();
    }
}
