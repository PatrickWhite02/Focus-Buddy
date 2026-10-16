package com.example.focuslock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Receives the focuslock://unlock?token=... intent fired by scanning the printed QR code. */
public class UnlockActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean ok = SessionStore.unlockFromUri(this, getIntent() != null ? getIntent().getData() : null);
        Toast.makeText(this, ok ? "Focus session ended" : "Invalid unlock code", Toast.LENGTH_LONG).show();

        if (ok) {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(open);
        }
        finish();
    }
}
