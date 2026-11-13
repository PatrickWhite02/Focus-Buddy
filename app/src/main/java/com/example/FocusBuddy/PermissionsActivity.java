package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

/** Screen 1: asks for the accessibility service + device admin. Moves on once both are granted. */
public class PermissionsActivity extends Activity {

    private TextView tvSetup;
    private Button btnAccessibility, btnAdmin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        tvSetup = findViewById(R.id.tvSetup);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnAdmin = findViewById(R.id.btnAdmin);

        btnAccessibility.setOnClickListener(v -> onEnableAccessibilityClicked());

        btnAdmin.setOnClickListener(v -> {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(this, FocusAdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Stops Focus Buddy from being force-stopped or uninstalled during a focus session.");
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from Settings lands here, so this is where we notice the permissions were granted
        if (SessionStore.isActive(this)) {
            startActivity(new Intent(this, ActiveBlockActivity.class));
            finish();
            return;
        }
        boolean a11y = Permissions.accessibilityEnabled(this);
        boolean admin = Permissions.adminActive(this);
        if (a11y && admin) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        btnAccessibility.setEnabled(!a11y);
        btnAdmin.setEnabled(!admin);
        // A fresh install lands here first, so this is where the first-open notification prompt appears
        if (Permissions.needsNotificationPrompt(this)) {
            Permissions.requestNotifications(this, 1);
        }
        tvSetup.setText("Accessibility service: " + (a11y ? "on ✓" : "off")
                + "\nDevice admin: " + (admin ? "active ✓" : "inactive"));
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
}
