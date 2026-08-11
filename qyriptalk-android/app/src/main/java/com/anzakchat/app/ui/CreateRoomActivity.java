package com.anzakchat.app.ui;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.anzakchat.app.R;
import com.anzakchat.app.net.FirebaseClient;
import com.anzakchat.app.util.SessionManager;

import org.json.JSONException;
import org.json.JSONObject;

public class CreateRoomActivity extends AppCompatActivity {

    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_room);

        session = new SessionManager(this);

        Toolbar toolbar = findViewById(R.id.create_room_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        EditText nameInput = findViewById(R.id.room_name_input);
        EditText passwordInput = findViewById(R.id.room_password_input);
        MaterialButton confirmBtn = findViewById(R.id.create_room_confirm_btn);

        confirmBtn.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String password = passwordInput.getText().toString().trim();

            if (name.isEmpty()) {
                Toast.makeText(this, "Lütfen oda adı girin.", Toast.LENGTH_SHORT).show();
                return;
            }

            confirmBtn.setEnabled(false);

            // Aynı isimde oda var mı diye önce kontrol et (web sürümüyle aynı davranış).
            FirebaseClient.get().get("rooms", new FirebaseClient.ObjectCallback() {
                @Override
                public void onSuccess(JSONObject rooms) {
                    if (rooms != null) {
                        java.util.Iterator<String> it = rooms.keys();
                        while (it.hasNext()) {
                            JSONObject r = rooms.optJSONObject(it.next());
                            if (r != null && name.equals(r.optString("name"))) {
                                confirmBtn.setEnabled(true);
                                Toast.makeText(CreateRoomActivity.this, "Bu isimde bir oda zaten mevcut!", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                    }
                    createRoom(name, password, confirmBtn);
                }

                @Override
                public void onError(String message) {
                    confirmBtn.setEnabled(true);
                    Toast.makeText(CreateRoomActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void createRoom(String name, String password, MaterialButton confirmBtn) {
        try {
            JSONObject body = new JSONObject();
            body.put("name", name);
            body.put("password", password.isEmpty() ? JSONObject.NULL : password);
            body.put("isPrivate", !password.isEmpty());
            body.put("createdBy", session.getUid());
            body.put("createdAt", FirebaseClient.serverTimestamp());

            FirebaseClient.get().push("rooms", body, new FirebaseClient.KeyCallback() {
                @Override
                public void onSuccess(String roomId) {
                    // Odayı oluşturan kişi otomatik üye olur.
                    FirebaseClient.get().put("rooms/" + roomId + "/users/" + session.getUid(), true,
                            new FirebaseClient.SimpleCallback() {
                                @Override public void onSuccess() { }
                                @Override public void onError(String message) { }
                            });
                    Toast.makeText(CreateRoomActivity.this, "Oda oluşturuldu.", Toast.LENGTH_SHORT).show();
                    finish();
                }

                @Override
                public void onError(String message) {
                    confirmBtn.setEnabled(true);
                    Toast.makeText(CreateRoomActivity.this, "Oda oluşturulamadı: " + message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (JSONException e) {
            confirmBtn.setEnabled(true);
        }
    }
}
