package com.anzakchat.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.anzakchat.app.R;
import com.anzakchat.app.net.FirebaseClient;
import com.anzakchat.app.util.SessionManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Firebase Realtime Database'e doğrudan REST üzerinden kayıt olur (qyriptalk-cdf56
 * projesi, users/{uid} şeması web sürümüyle birebir aynı).
 */
public class RegisterActivity extends AppCompatActivity {

    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        session = new SessionManager(this);

        EditText usernameInput = findViewById(R.id.register_username);
        EditText passwordInput = findViewById(R.id.register_password);
        MaterialButton registerButton = findViewById(R.id.register_button);
        TextView goToLogin = findViewById(R.id.go_to_login);
        ProgressBar progress = findViewById(R.id.register_progress);

        registerButton.setOnClickListener(v -> {
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString().trim();

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Lütfen kullanıcı adı ve şifre giriniz.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (username.length() < 3) {
                Toast.makeText(this, "Kullanıcı adı en az 3 karakter olmalıdır.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (password.length() < 6) {
                Toast.makeText(this, "Şifre en az 6 karakter olmalıdır.", Toast.LENGTH_SHORT).show();
                return;
            }

            progress.setVisibility(View.VISIBLE);
            registerButton.setEnabled(false);

            FirebaseClient.get().get("users", new FirebaseClient.ObjectCallback() {
                @Override
                public void onSuccess(JSONObject allUsers) {
                    boolean taken = false;
                    if (allUsers != null) {
                        java.util.Iterator<String> it = allUsers.keys();
                        while (it.hasNext()) {
                            JSONObject u = allUsers.optJSONObject(it.next());
                            if (u != null && username.equals(u.optString("username", null))) {
                                taken = true;
                                break;
                            }
                        }
                    }
                    if (taken) {
                        progress.setVisibility(View.GONE);
                        registerButton.setEnabled(true);
                        Toast.makeText(RegisterActivity.this, "Bu kullanıcı adı zaten alınmış!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createUser(username, password, progress, registerButton);
                }

                @Override
                public void onError(String message) {
                    progress.setVisibility(View.GONE);
                    registerButton.setEnabled(true);
                    Toast.makeText(RegisterActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        });

        goToLogin.setOnClickListener(v -> finish());
    }

    private void createUser(String username, String password, ProgressBar progress, MaterialButton registerButton) {
        try {
            JSONObject body = new JSONObject();
            body.put("username", username);
            body.put("password", password);
            body.put("isOnline", true);
            body.put("createdAt", FirebaseClient.serverTimestamp());
            body.put("lastSeen", FirebaseClient.serverTimestamp());
            body.put("role", "user");

            FirebaseClient.get().push("users", body, new FirebaseClient.KeyCallback() {
                @Override
                public void onSuccess(String uid) {
                    progress.setVisibility(View.GONE);
                    registerButton.setEnabled(true);
                    session.save(uid, username);
                    Toast.makeText(RegisterActivity.this, "Kayıt başarılı, hoş geldin " + username + "!", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(RegisterActivity.this, MainActivity.class));
                    finish();
                }

                @Override
                public void onError(String message) {
                    progress.setVisibility(View.GONE);
                    registerButton.setEnabled(true);
                    Toast.makeText(RegisterActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (JSONException e) {
            progress.setVisibility(View.GONE);
            registerButton.setEnabled(true);
        }
    }
}
