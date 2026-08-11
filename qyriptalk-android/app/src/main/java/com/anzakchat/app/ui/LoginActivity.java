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
 * Firebase Realtime Database'e doğrudan REST üzerinden bağlanır (qyriptalk-cdf56
 * projesi — web sürümüyle aynı users/{uid} kaydı). Şifre kontrolü client
 * tarafında yapılıyor çünkü kendi backend'imiz artık yok; web sürümüyle
 * uyumlu kalmak için şifre şu an düz metin karşılaştırılıyor (web'deki gibi).
 */
public class LoginActivity extends AppCompatActivity {

    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionManager(this);

        if (session.isLoggedIn()) {
            goToMain();
            return;
        }

        EditText usernameInput = findViewById(R.id.login_username);
        EditText passwordInput = findViewById(R.id.login_password);
        MaterialButton loginButton = findViewById(R.id.login_button);
        TextView goToRegister = findViewById(R.id.go_to_register);
        ProgressBar progress = findViewById(R.id.login_progress);

        loginButton.setOnClickListener(v -> {
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString().trim();

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Lütfen kullanıcı adı ve şifre giriniz.", Toast.LENGTH_SHORT).show();
                return;
            }

            progress.setVisibility(View.VISIBLE);
            loginButton.setEnabled(false);

            FirebaseClient.get().get("users", new FirebaseClient.ObjectCallback() {
                @Override
                public void onSuccess(JSONObject body) {
                    progress.setVisibility(View.GONE);
                    loginButton.setEnabled(true);

                    String foundUid = null;
                    JSONObject foundUser = null;
                    if (body != null) {
                        java.util.Iterator<String> it = body.keys();
                        while (it.hasNext()) {
                            String uid = it.next();
                            JSONObject u = body.optJSONObject(uid);
                            if (u != null && username.equals(u.optString("username", null))) {
                                foundUid = uid;
                                foundUser = u;
                                break;
                            }
                        }
                    }

                    if (foundUser == null) {
                        Toast.makeText(LoginActivity.this, "Kullanıcı bulunamadı!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String storedPassword = foundUser.optString("password", "");

                    if (!password.equals(storedPassword)) {
                        Toast.makeText(LoginActivity.this, "Hatalı şifre!", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String uid = foundUid;
                    session.save(uid, username);
                    markOnline(uid);

                    Toast.makeText(LoginActivity.this, "Hoş geldin, " + username + "!", Toast.LENGTH_SHORT).show();
                    goToMain();
                }

                @Override
                public void onError(String message) {
                    progress.setVisibility(View.GONE);
                    loginButton.setEnabled(true);
                    Toast.makeText(LoginActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        });

        goToRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    private void markOnline(String uid) {
        try {
            JSONObject fields = new JSONObject();
            fields.put("isOnline", true);
            fields.put("lastSeen", FirebaseClient.serverTimestamp());
            FirebaseClient.get().patch("users/" + uid, fields, new FirebaseClient.SimpleCallback() {
                @Override public void onSuccess() { }
                @Override public void onError(String message) { }
            });
        } catch (JSONException ignored) { }
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
