package com.anzakchat.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;
import com.anzakchat.app.R;
import com.anzakchat.app.adapter.DmAdapter;
import com.anzakchat.app.adapter.RoomAdapter;
import com.anzakchat.app.model.DmSummary;
import com.anzakchat.app.model.MessageModel;
import com.anzakchat.app.model.RoomModel;
import com.anzakchat.app.net.FirebaseClient;
import com.anzakchat.app.util.PresenceManager;
import com.anzakchat.app.util.SessionManager;
import com.anzakchat.app.util.UiUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Firebase Realtime Database'e doğrudan REST üzerinden bağlanan sürüm
 * (qyriptalk-cdf56 projesi, web sürümüyle aynı şema: rooms/, users/,
 * privateMessages/). Canlı güncelleme yok — liste onResume'da tazelenir.
 */
public class MainActivity extends AppCompatActivity {

    private SessionManager session;
    private String myUid;
    private String myUsername;

    private final List<RoomModel> allRooms = new ArrayList<>();
    private final List<DmSummary> allUsers = new ArrayList<>(); // daha önce konuştuğumuz kullanıcılar
    private final List<DmSummary> allOtherUsers = new ArrayList<>(); // henüz konuşmadığımız diğer kullanıcılar

    private RoomAdapter roomAdapter;
    private DmAdapter dmAdapter;

    private RecyclerView roomsRecycler;
    private RecyclerView dmsRecycler;
    private TextView emptyStateText;
    private boolean roomsTabActive = true;

    private ActivityResultLauncher<String> pickAvatarLauncher;
    private TextView pendingAvatarView;
    private ImageView pendingAvatarPhotoView;
    private static final int MAX_AVATAR_DIMENSION = 480;
    private static final int AVATAR_JPEG_QUALITY = 80;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        session = new SessionManager(this);
        if (!session.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        myUid = session.getUid();
        myUsername = session.getUsername();

        PresenceManager.goOnline(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("AnzakChat — " + myUsername);
        setupMainMenu(toolbar);

        roomsRecycler = findViewById(R.id.rooms_recycler);
        dmsRecycler = findViewById(R.id.dms_recycler);
        emptyStateText = findViewById(R.id.empty_state_text);
        roomsRecycler.setLayoutManager(new LinearLayoutManager(this));
        dmsRecycler.setLayoutManager(new LinearLayoutManager(this));

        roomAdapter = new RoomAdapter(myUid, this::onRoomClicked);
        dmAdapter = new DmAdapter(this::onDmClicked);
        roomsRecycler.setAdapter(roomAdapter);
        dmsRecycler.setAdapter(dmAdapter);

        pickAvatarLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) uploadAvatarPhoto(uri);
        });

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                boolean rooms = tab.getPosition() == 0;
                roomsTabActive = rooms;
                roomsRecycler.setVisibility(rooms ? View.VISIBLE : View.GONE);
                dmsRecycler.setVisibility(rooms ? View.GONE : View.VISIBLE);
                updateEmptyState();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        EditText searchInput = findViewById(R.id.search_input);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                tryJoinByInviteLink(searchInput.getText().toString().trim());
                return true;
            }
            return false;
        });

        FloatingActionButton fab = findViewById(R.id.fab_new_room);
        fab.setOnClickListener(v -> startActivity(new Intent(this, CreateRoomActivity.class)));

        loadRooms();
        loadDms();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Başka bir ekrandan (oda oluşturma, katılma, ayrılma) dönünce listeyi tazele.
        loadRooms();
        loadDms();
    }

    private void loadRooms() {
        FirebaseClient.get().get("rooms", new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject body) {
                allRooms.clear();
                if (body != null) {
                    Iterator<String> it = body.keys();
                    while (it.hasNext()) {
                        String roomId = it.next();
                        JSONObject o = body.optJSONObject(roomId);
                        if (o != null) allRooms.add(RoomModel.fromFirebaseJson(roomId, o, myUid));
                    }
                }
                applyFilter(currentQuery());
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "Odalar yüklenemedi: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Kullanıcıları getirir, sonra her biri için privateMessages/{dmId}
     * altında konuşma olup olmadığını sırayla kontrol eder. Konuşma varsa
     * "Sohbetlerim" (allUsers), yoksa "Diğer Kullanıcılar" (allOtherUsers)
     * listesine eklenir (web sürümündeki updateDMList ile aynı mantık).
     */
    private void loadDms() {
        FirebaseClient.get().get("users", new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject body) {
                allUsers.clear();
                allOtherUsers.clear();
                if (body == null) {
                    applyFilter(currentQuery());
                    return;
                }

                List<String> otherUids = new ArrayList<>();
                Iterator<String> it = body.keys();
                while (it.hasNext()) {
                    String uid = it.next();
                    if (!uid.equals(myUid)) otherUids.add(uid);
                }

                if (otherUids.isEmpty()) {
                    applyFilter(currentQuery());
                    return;
                }

                AtomicInteger remaining = new AtomicInteger(otherUids.size());
                java.util.Set<String> seenUsernames = new java.util.HashSet<>();
                for (String otherUid : otherUids) {
                    JSONObject u = body.optJSONObject(otherUid);
                    if (u == null) {
                        if (remaining.decrementAndGet() == 0) applyFilter(currentQuery());
                        continue;
                    }
                    String username = u.optString("username", "?");
                    boolean online = u.optBoolean("isOnline", false);
                    String photo = u.isNull("profilePhoto") ? null : u.optString("profilePhoto", null);
                    String dmId = DmSummary.buildDmId(myUid, otherUid);

                    FirebaseClient.get().getLastN("privateMessages/" + dmId + "/messages", 1, new FirebaseClient.ObjectCallback() {
                        @Override
                        public void onSuccess(JSONObject messages) {
                            // Firebase'de aynı kullanıcı adıyla birden fazla hesap kalmış olabilir
                            // (eski kayıt denemelerinden). Listede aynı ismi tekrar göstermiyoruz.
                            if (!seenUsernames.add(username.toLowerCase(Locale.ROOT))) {
                                if (remaining.decrementAndGet() == 0) applyFilter(currentQuery());
                                return;
                            }

                            DmSummary dm = new DmSummary(otherUid, username);
                            dm.online = online;
                            dm.photoBase64 = photo;

                            if (messages != null && messages.length() > 0) {
                                String lastKey = messages.keys().next();
                                JSONObject lastMsg = messages.optJSONObject(lastKey);
                                if (lastMsg != null) {
                                    dm.lastMessageText = lastMsg.isNull("text") ? mediaPreview(lastMsg) : lastMsg.optString("text");
                                    dm.lastMessageSender = lastMsg.optString("sender", username);
                                    dm.lastMessageIsMine = myUid.equals(lastMsg.optString("senderUid"));
                                    dm.lastMessageTime = lastMsg.optLong("timestamp", 0);
                                }
                                allUsers.add(dm);
                            } else {
                                allOtherUsers.add(dm);
                            }

                            if (remaining.decrementAndGet() == 0) applyFilter(currentQuery());
                        }

                        @Override
                        public void onError(String message) {
                            // Bu kullanıcı için DM kontrolü başarısız oldu; "Diğer Kullanıcılar"a düşsün.
                            DmSummary dm = new DmSummary(otherUid, username);
                            dm.online = online;
                            dm.photoBase64 = photo;
                            allOtherUsers.add(dm);
                            if (remaining.decrementAndGet() == 0) applyFilter(currentQuery());
                        }
                    });
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "Kullanıcılar yüklenemedi: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String mediaPreview(JSONObject msg) {
        String type = msg.isNull("type") ? null : msg.optString("type", null);
        if ("voice".equals(type)) return "🎤 Sesli mesaj";
        if ("image".equals(type)) return "📷 Fotoğraf";
        if ("video".equals(type)) return "🎬 Video";
        if (type != null) return "📎 Dosya";
        return "";
    }

    private String currentQuery() {
        EditText searchInput = findViewById(R.id.search_input);
        return searchInput.getText().toString();
    }

    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();

        List<RoomModel> filteredRooms = new ArrayList<>();
        for (RoomModel r : allRooms) {
            if (r.getName() != null && r.getName().toLowerCase().contains(q)) {
                filteredRooms.add(r);
            }
        }
        roomAdapter.submit(filteredRooms);

        List<DmSummary> filteredConversations = new ArrayList<>();
        for (DmSummary d : allUsers) {
            if (dmMatchesQuery(d, q)) filteredConversations.add(d);
        }

        List<DmSummary> filteredOthers = new ArrayList<>();
        for (DmSummary d : allOtherUsers) {
            if (dmMatchesQuery(d, q)) filteredOthers.add(d);
        }

        dmAdapter.submit(filteredConversations, filteredOthers);
        updateEmptyState();
    }

    private boolean dmMatchesQuery(DmSummary d, String q) {
        boolean usernameMatch = d.otherUsername != null && d.otherUsername.toLowerCase().contains(q);
        boolean uidMatch = !q.isEmpty() && d.otherUid != null && d.otherUid.toLowerCase().contains(q);
        return usernameMatch || uidMatch;
    }

    private void updateEmptyState() {
        boolean empty = roomsTabActive ? roomAdapter.getRoomCount() == 0 : dmAdapter.getDmCount() == 0;
        emptyStateText.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /**
     * Arama kutusuna davet linki ("AnzakChat/{odaAdı}/{roomId}") yapıştırılıp
     * Enter'a basılırsa, son parçadaki roomId ile odayı bulup katılım akışını tetikler.
     */
    private void tryJoinByInviteLink(String text) {
        if (text.isEmpty()) return;

        boolean looksLikeInviteLink = text.startsWith("AnzakChat/") || text.startsWith("Qyriptalk/") || text.contains("/");
        if (!looksLikeInviteLink) return;

        String[] parts = text.split("/");
        String roomId = parts[parts.length - 1].trim();
        if (roomId.isEmpty()) return;

        for (RoomModel r : allRooms) {
            if (roomId.equals(r.getRoomId())) {
                onRoomClicked(r);
                return;
            }
        }
        Toast.makeText(this, "Bu davet linkine ait oda bulunamadı.", Toast.LENGTH_SHORT).show();
    }

    private void onRoomClicked(RoomModel room) {
        if (room.isMember()) {
            openChat(room.getRoomId(), false, room.getName());
            return;
        }
        if (room.isPrivate()) {
            showJoinPasswordDialog(room);
        } else {
            joinRoom(room, null);
        }
    }

    private void showJoinPasswordDialog(RoomModel room) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_join_room, null);
        TextView label = view.findViewById(R.id.join_room_label);
        EditText passwordInput = view.findViewById(R.id.join_room_password_input);
        label.setText("\"" + room.getName() + "\" odasına katıl");

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("Katıl", (dialog, which) ->
                        joinRoom(room, passwordInput.getText().toString().trim()))
                .setNegativeButton("İptal", null)
                .show();
    }

    private void joinRoom(RoomModel room, String password) {
        if (room.isPrivate() && room.getPassword() != null && !room.getPassword().isEmpty()
                && !room.getPassword().equals(password)) {
            Toast.makeText(this, "Yanlış şifre!", Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseClient.get().put("rooms/" + room.getRoomId() + "/users/" + myUid, true, new FirebaseClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                room.setMember(true);
                FirebaseClient.get().put("users/" + myUid + "/joinedRooms/" + room.getRoomId(), true,
                        new FirebaseClient.SimpleCallback() {
                            @Override public void onSuccess() { }
                            @Override public void onError(String message) { }
                        });
                openChat(room.getRoomId(), false, room.getName());
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void onDmClicked(DmSummary dm) {
        openChat(dm.otherUid, true, dm.otherUsername, dm.otherUid);
    }

    private void openChat(String chatId, boolean isDm, String title) {
        openChat(chatId, isDm, title, null);
    }

    private void openChat(String chatId, boolean isDm, String title, String otherUid) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chatId);
        intent.putExtra(ChatActivity.EXTRA_IS_DM, isDm);
        intent.putExtra(ChatActivity.EXTRA_TITLE, title);
        if (otherUid != null) intent.putExtra(ChatActivity.EXTRA_OTHER_UID, otherUid);
        startActivity(intent);
    }

    // ============================================================
    // Ayarlar menüsü
    // ============================================================
    private void setupMainMenu(Toolbar toolbar) {
        ImageButton menuButton = findViewById(R.id.main_menu_button);
        menuButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, menuButton);
            popup.inflate(R.menu.main_menu);
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_online_users) {
                    showOnlineUsersDialog();
                    return true;
                } else if (id == R.id.menu_profile) {
                    showProfileDialog();
                    return true;
                } else if (id == R.id.menu_logout) {
                    confirmLogout();
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    private void showOnlineUsersDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_online_users, null);
        RecyclerView recycler = view.findViewById(R.id.online_users_recycler);
        TextView emptyView = view.findViewById(R.id.online_users_empty);

        List<DmSummary> online = new ArrayList<>();
        for (DmSummary d : allUsers) {
            if (d.online) online.add(d);
        }

        recycler.setLayoutManager(new LinearLayoutManager(this));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        com.anzakchat.app.adapter.OnlineUsersAdapter onlineAdapter =
                new com.anzakchat.app.adapter.OnlineUsersAdapter(online, user -> {
                    dialog.dismiss();
                    onDmClicked(user);
                });
        recycler.setAdapter(onlineAdapter);

        recycler.setVisibility(online.isEmpty() ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(online.isEmpty() ? View.VISIBLE : View.GONE);

        dialog.show();
    }

    private void showProfileDialog() {
        FirebaseClient.get().get("users/" + myUid, new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject me) {
                bindProfileDialog(me != null ? me : new JSONObject());
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "Profil yüklenemedi: " + message, Toast.LENGTH_SHORT).show();
                bindProfileDialog(new JSONObject());
            }
        });
    }

    private void bindProfileDialog(JSONObject me) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null);

        TextView avatar = view.findViewById(R.id.profile_avatar);
        ImageView avatarPhoto = view.findViewById(R.id.profile_avatar_photo);
        TextView roleBadge = view.findViewById(R.id.profile_role_badge);
        TextView usernameView = view.findViewById(R.id.profile_username);
        ImageButton editUsernameBtn = view.findViewById(R.id.profile_edit_username_button);
        TextView uidView = view.findViewById(R.id.profile_uid);
        TextView copyUidBtn = view.findViewById(R.id.profile_copy_uid);
        TextView bioView = view.findViewById(R.id.profile_bio);
        ImageButton editBioBtn = view.findViewById(R.id.profile_edit_bio_button);
        ImageButton editAvatarBtn = view.findViewById(R.id.profile_avatar_edit_button);
        SwitchMaterial messageSwitch = view.findViewById(R.id.profile_switch_message_notif);
        SwitchMaterial soundSwitch = view.findViewById(R.id.profile_switch_sound_notif);
        com.google.android.material.button.MaterialButton logoutBtn = view.findViewById(R.id.profile_logout_button);

        String photo = me.isNull("profilePhoto") ? null : me.optString("profilePhoto", null);
        String bio = me.isNull("bio") ? null : me.optString("bio", null);
        String role = me.isNull("role") ? null : me.optString("role", null);

        avatar.setText(UiUtils.initial(myUsername));
        UiUtils.applyAvatarPhoto(getResources(), avatarPhoto, avatar, photo);
        usernameView.setText(myUsername);
        uidView.setText("UID: " + myUid);
        bioView.setText(bio != null && !bio.trim().isEmpty() ? bio : "Henüz bir açıklama eklenmedi.");
        String roleText = UiUtils.roleLabel(role);
        if (roleText != null) {
            roleBadge.setText(roleText);
            roleBadge.setVisibility(View.VISIBLE);
        }
        messageSwitch.setChecked(me.optBoolean("messageNotifications", true));
        soundSwitch.setChecked(me.optBoolean("soundNotifications", true));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();

        editAvatarBtn.setOnClickListener(v -> {
            pendingAvatarView = avatar;
            pendingAvatarPhotoView = avatarPhoto;
            pickAvatarLauncher.launch("image/*");
        });

        copyUidBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("UID", myUid));
            Toast.makeText(this, "UID kopyalandı.", Toast.LENGTH_SHORT).show();
        });

        editUsernameBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showEditUsernameDialog();
        });

        editBioBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showEditBioDialog(bio);
        });

        messageSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (btn.isPressed()) patchProfile("messageNotifications", checked);
        });
        soundSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (btn.isPressed()) patchProfile("soundNotifications", checked);
        });

        logoutBtn.setOnClickListener(v -> {
            dialog.dismiss();
            confirmLogout();
        });

        dialog.show();
    }

    private void patchProfile(String key, Object value) {
        try {
            JSONObject body = new JSONObject();
            body.put(key, value);
            FirebaseClient.get().patch("users/" + myUid, body, new FirebaseClient.SimpleCallback() {
                @Override public void onSuccess() { }
                @Override public void onError(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (JSONException ignored) { }
    }

    private void uploadAvatarPhoto(Uri uri) {
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            Bitmap original = BitmapFactory.decodeStream(input);
            if (input != null) input.close();
            if (original == null) {
                Toast.makeText(this, "Fotoğraf okunamadı.", Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap resized = resizeAvatarIfNeeded(original);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resized.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, baos);
            String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + base64;

            patchProfileCallback("profilePhoto", dataUrl, () -> {
                Toast.makeText(this, "Profil fotoğrafı güncellendi.", Toast.LENGTH_SHORT).show();
                if (pendingAvatarPhotoView != null && pendingAvatarView != null) {
                    UiUtils.applyAvatarPhoto(getResources(), pendingAvatarPhotoView, pendingAvatarView, dataUrl);
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Fotoğraf yüklenirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
        }
    }

    private interface SimpleCallback { void run(); }

    private void patchProfileCallback(String key, String value, SimpleCallback onDone) {
        try {
            JSONObject body = new JSONObject();
            body.put(key, value);
            FirebaseClient.get().patch("users/" + myUid, body, new FirebaseClient.SimpleCallback() {
                @Override public void onSuccess() { onDone.run(); }
                @Override public void onError(String message) {
                    Toast.makeText(MainActivity.this, "Fotoğraf yüklenirken bir hata oluştu.", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (JSONException ignored) { }
    }

    private Bitmap resizeAvatarIfNeeded(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();
        if (width <= MAX_AVATAR_DIMENSION && height <= MAX_AVATAR_DIMENSION) return original;

        float ratio = Math.min((float) MAX_AVATAR_DIMENSION / width, (float) MAX_AVATAR_DIMENSION / height);
        int newWidth = Math.round(width * ratio);
        int newHeight = Math.round(height * ratio);
        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
    }

    private void showEditUsernameDialog() {
        EditText input = new EditText(this);
        input.setText(myUsername);
        input.setSelection(input.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("Kullanıcı adını değiştir")
                .setView(input)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Kullanıcı adı boş olamaz.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newName.equals(myUsername)) return;
                    applyUsernameChange(newName);
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void applyUsernameChange(String newName) {
        FirebaseClient.get().get("users", new FirebaseClient.ObjectCallback() {
            @Override
            public void onSuccess(JSONObject allUsers) {
                boolean taken = false;
                if (allUsers != null) {
                    java.util.Iterator<String> it = allUsers.keys();
                    while (it.hasNext()) {
                        JSONObject u = allUsers.optJSONObject(it.next());
                        if (u != null && newName.equals(u.optString("username", null))) {
                            taken = true;
                            break;
                        }
                    }
                }
                if (taken) {
                    Toast.makeText(MainActivity.this, "Bu kullanıcı adı zaten alınmış!", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    JSONObject body = new JSONObject();
                    body.put("username", newName);
                    FirebaseClient.get().patch("users/" + myUid, body, new FirebaseClient.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            session.updateUsername(newName);
                            myUsername = newName;
                            Toolbar toolbar = findViewById(R.id.toolbar);
                            toolbar.setTitle("AnzakChat — " + myUsername);
                            Toast.makeText(MainActivity.this, "Kullanıcı adı güncellendi.", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (JSONException ignored) { }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showEditBioDialog(String currentBio) {
        EditText input = new EditText(this);
        input.setText(currentBio != null ? currentBio : "");
        input.setSelection(input.getText().length());
        input.setHint("Kendinden bahset...");
        input.setMaxLines(3);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("Hakkımda")
                .setView(input)
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    String newBio = input.getText().toString().trim();
                    patchProfile("bio", newBio);
                    Toast.makeText(this, "Hakkımda bölümü güncellendi.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Çıkış Yap")
                .setMessage("Hesabınızdan çıkış yapmak istediğinize emin misiniz?")
                .setPositiveButton("Çıkış Yap", (dialog, which) -> logout())
                .setNegativeButton("İptal", null)
                .show();
    }

    private void logout() {
        PresenceManager.goOffline(this);
        session.clear();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) PresenceManager.goOffline(this);
    }
}
