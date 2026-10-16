package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.util.Locale;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    private TextView tvStatus, tvSetup, tvCountdown;
    private View setupGroup, startGroup, activeGroup;
    private Button btnAccessibility, btnAdmin;
    private EditText etHours;
    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        adminComponent = new ComponentName(this, FocusAdminReceiver.class);

        tvStatus = findViewById(R.id.tvStatus);
        tvSetup = findViewById(R.id.tvSetup);
        tvCountdown = findViewById(R.id.tvCountdown);
        setupGroup = findViewById(R.id.setupGroup);
        startGroup = findViewById(R.id.startGroup);
        activeGroup = findViewById(R.id.activeGroup);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnAdmin = findViewById(R.id.btnAdmin);
        etHours = findViewById(R.id.etHours);

        btnAccessibility.setOnClickListener(v -> onEnableAccessibilityClicked());

        btnAdmin.setOnClickListener(v -> {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Stops Focus Buddy from being force-stopped or uninstalled during a focus session.");
            startActivity(i);
        });

        findViewById(R.id.btnStart).setOnClickListener(v -> onStartClicked());
        findViewById(R.id.btnScan).setOnClickListener(v -> scanToUnlock());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ---------- UI state ----------

    private void render() {
        boolean active = SessionStore.isActive(this);
        boolean a11y = accessibilityEnabled();
        boolean admin = adminActive();
        boolean ready = a11y && admin;

        setupGroup.setVisibility(!active && !ready ? View.VISIBLE : View.GONE);
        startGroup.setVisibility(!active && ready ? View.VISIBLE : View.GONE);
        activeGroup.setVisibility(active ? View.VISIBLE : View.GONE);

        btnAccessibility.setEnabled(!a11y);
        btnAdmin.setEnabled(!admin);
        tvSetup.setText("Accessibility service: " + (a11y ? "on ✓" : "off")
                + "\nDevice admin: " + (admin ? "active ✓" : "inactive"));

        if (active) {
            tvStatus.setText("Session active. Blocked: "
                    + TextUtils.join(", ", Config.BLOCKED_APPS.values()));
            long s = SessionStore.remainingMs(this) / 1000;
            tvCountdown.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60));
        } else {
            tvStatus.setText("No session running.");
        }
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(this, BlockerAccessibilityService.class);
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(cn.flattenToString())
                    || s.equalsIgnoreCase(cn.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    private boolean adminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(adminComponent);
    }

    // ---------- Enabling accessibility (with the restricted-settings prompt) ----------

    private void onEnableAccessibilityClicked() {
        // "Restricted settings" only exists on Android 13+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openAccessibilitySettings();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Allow restricted settings first")
                .setMessage("Android blocks sideloaded apps from using accessibility until you allow it:\n\n"
                        + "1. Open Accessibility, tap Focus Buddy, and dismiss the \"Restricted setting\" message.\n\n"
                        + "2. Open Settings > Apps > Focus Buddy, tap the \u22EE menu (top right), then \"Allow restricted settings\".\n\n"
                        + "3. Go back to Accessibility and turn Focus Buddy on.")
                .setPositiveButton("Open Accessibility", (d, w) -> openAccessibilitySettings())
                .setNegativeButton("Open Apps list", (d, w) -> openAppsList())
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void openAccessibilitySettings() {
        launchSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openAppsList() {
        try {
            launchSettings(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        } catch (ActivityNotFoundException e) {
            launchSettings(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /**
     * Start Settings in its own task. Without FLAG_ACTIVITY_NEW_TASK the Settings screen is pushed onto
     * this app's task, so Recents shows it under the Focus Buddy icon and Settings treats it as part of this app.
     */
    private void launchSettings(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ---------- Starting a session ----------

    private void onStartClicked() {
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
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Ending a session with the in-app scanner ----------
    // (Scanning the printed QR with any camera app also works; it fires the same focusbuddy:// intent.)

    private void scanToUnlock() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> {
                    boolean ok = SessionStore.unlockFromLink(this, barcode.getRawValue());
                    Toast.makeText(this, ok ? "Session ended" : "Wrong QR code",
                            Toast.LENGTH_LONG).show();
                    render();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Scan failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
    }
}
