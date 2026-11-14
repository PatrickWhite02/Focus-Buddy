package com.example.FocusBuddy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;


import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Screen 4: shown while a block is running (countdown / release time + the QR scan button). */
public class ActiveBlockActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!routeIfNeeded()) {
                render();
                ui.postDelayed(this, 1000);
            }
        }
    };

    private TextView tvStatus, tvCountdown, tvHardcore;
    private String blockedSummary = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_block);

        tvStatus = findViewById(R.id.tvStatus);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvHardcore = findViewById(R.id.tvHardcore);
        tvHardcore.setOnClickListener(v -> HardcoreMode.showWarning(this, true));
        findViewById(R.id.btnScan).setOnClickListener(v -> scanToUnlock());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The blocklist can't change during a session, so this only needs refreshing here
        blockedSummary = TextUtils.join(", ", BlocklistStore.labels(this));
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    /** Once the block ends, hand back to the options screen. Returns true if we left. */
    private boolean routeIfNeeded() {
        if (!SessionStore.isActive(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return true;
        }
        return false;
    }

    /** "Hardcore mode: Off" (red, tap to turn on) or "Hardcore mode: On" (green, no way to turn it off mid-block). */
    private void renderHardcore() {
        boolean on = Permissions.adminActive(this);
        String label = "Hardcore mode: ";
        String state = on ? "On" : "Off";
        SpannableString text = new SpannableString(label + state);
        text.setSpan(new ForegroundColorSpan(on ? Color.rgb(0x2E, 0x7D, 0x32) : Color.rgb(0xD3, 0x2F, 0x2F)),
                label.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvHardcore.setText(text);
        tvHardcore.setClickable(!on); // once on, a tap does nothing
    }

    private void render() {
        renderHardcore(); // also picks up the admin being activated on Android's own screen
        tvStatus.setText("Session active. Blocked: " + blockedSummary);
        long release = SessionStore.scheduledReleaseMs(this);
        if (SessionStore.isUntilScan(this)) {
            // No timer: say how it ends instead of counting down
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
            tvCountdown.setText("Blocked until you scan the QR code");
        } else if (release > 0L) {
            // Scheduled block: say when it lifts rather than counting down
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
            tvCountdown.setText("Blocked until\n" + formatRelease(release));
        } else {
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
            long s = SessionStore.remainingMs(this) / 1000;
            tvCountdown.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60));
        }
    }

    /** "12:00 PM" if it's today, otherwise with the weekday ("Tue 2:00 AM"). */
    private String formatRelease(long releaseMs) {
        Date release = new Date(releaseMs);
        String time = DateFormat.getTimeFormat(this).format(release);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(release);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? time : DateFormat.format("EEE", release) + " " + time;
    }

    // ---------- Ending a session with the in-app scanner ----------
    // (Scanning the printed QR with any camera app also works; it fires the same focusbuddy:// intent.)

    private void scanToUnlock() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan your unlock QR code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IntentIntegrator.REQUEST_CODE) return;
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) return; // cancelled, or camera denied
        boolean ok = SessionStore.unlockFromLink(this, result.getContents());
        Toast.makeText(this, ok ? "Session ended" : "Wrong QR code", Toast.LENGTH_LONG).show();
        routeIfNeeded();
    }
}