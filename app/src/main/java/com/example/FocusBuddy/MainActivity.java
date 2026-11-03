package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Screen 2: the options (what to block, schedule, start a session). Only shown when ready and idle. */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!routeIfNeeded()) {
                ui.postDelayed(this, 1000);
            }
        }
    };

    private TextView tvStatus;
    private Button btnChooseApps, btnSchedule, btnStart, btnUntilScan;
    private EditText etHours;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        etHours = findViewById(R.id.etHours);
        btnChooseApps = findViewById(R.id.btnChooseApps);
        btnSchedule = findViewById(R.id.btnSchedule);
        btnStart = findViewById(R.id.btnStart);
        btnUntilScan = findViewById(R.id.btnUntilScan);

        btnChooseApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        btnSchedule.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        btnStart.setOnClickListener(v -> onStartClicked());
        btnUntilScan.setOnClickListener(v -> onUntilScanClicked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (routeIfNeeded()) return;
        refresh();
        // Keep checking so a scheduled block that starts while this screen is open moves us along
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    /** Hands off to the right screen if this one isn't it. Returns true if we left. */
    private boolean routeIfNeeded() {
        if (SessionStore.isActive(this)) {
            startActivity(new Intent(this, ActiveBlockActivity.class));
            finish();
            return true;
        }
        if (!Permissions.allGranted(this)) {
            startActivity(new Intent(this, PermissionsActivity.class));
            finish();
            return true;
        }
        return false;
    }

    private void refresh() {
        int blockedCount = BlocklistStore.count(this);
        btnChooseApps.setText(blockedCount == 0
                ? "Choose what to block"
                : "Choose what to block (" + blockedCount + " selected)");
        // The session can't start until at least one app is chosen
        btnStart.setEnabled(blockedCount > 0);
        btnStart.setAlpha(blockedCount > 0 ? 1f : 0.5f);
        btnUntilScan.setEnabled(blockedCount > 0);
        btnUntilScan.setAlpha(blockedCount > 0 ? 1f : 0.5f);
        tvStatus.setText(blockedCount == 0
                ? "Choose what to block before you start a session."
                : "No session running.");
    }

    // ---------- Starting a session ----------

    private void onStartClicked() {
        if (BlocklistStore.count(this) == 0) {
            Toast.makeText(this, "Choose at least one app or website to block first", Toast.LENGTH_SHORT).show();
            return;
        }
        float parsed;
        try {
            parsed = Float.parseFloat(etHours.getText().toString().trim());
        } catch (NumberFormatException e) {
            parsed = 0f;
        }
        if (parsed <= 0f) {
            Toast.makeText(this, "Enter a session length in hours", Toast.LENGTH_SHORT).show();
            return;
        }
        final float hours = parsed;

        new AlertDialog.Builder(this)
                .setTitle("Start focus session?")
                .setMessage(String.format(Locale.US,
                        "Blocked apps will be locked for %.2f hours. The only way to end it early "
                                + "is to scan the unlock QR code.", hours))
                .setPositiveButton("Start", (d, w) -> {
                    SessionStore.start(this, hours);
                    routeIfNeeded();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onUntilScanClicked() {
        if (BlocklistStore.count(this) == 0) {
            Toast.makeText(this, "Choose at least one app or website to block first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Block until you scan the QR code?")
                .setMessage("This starts a block with no timer. Your blocked apps and websites stay "
                        + "blocked until you scan the unlock QR code, so make sure you can get to "
                        + "it first.")
                .setPositiveButton("Start block", (d, w) -> {
                    SessionStore.startUntilScan(this);
                    routeIfNeeded();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
