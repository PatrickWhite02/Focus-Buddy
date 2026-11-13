package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;

/**
 * Settings: what to block, and hardcore mode. Not reachable during a session (there is no gear
 * on the running screen, and this screen hands off to it if a session starts while it's open).
 * Also shown once, right after the accessibility service is enabled, before the main screen.
 */
public class SettingsActivity extends Activity {

    private Button btnChooseApps;
    private Switch swHardcore, swNotices;
    private static final int REQ_NOTIFICATIONS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        btnChooseApps = findViewById(R.id.btnChooseApps);
        swHardcore = findViewById(R.id.swHardcore);
        swNotices = findViewById(R.id.swNotices);

        btnChooseApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        findViewById(R.id.btnDone).setOnClickListener(v -> finishSettings());

        // Clicks, not checked-change listeners, so setting a switch from code (see refresh)
        // never triggers a dialog, a permission request or an admin removal.
        swNotices.setOnClickListener(v -> {
            if (swNotices.isChecked()) {
                // Stays off until Android will really show our notifications
                swNotices.setChecked(false);
                enableNotices();
            } else {
                Permissions.setNoticesEnabled(this, false);
            }
        });

        swHardcore.setOnClickListener(v -> {
            if (swHardcore.isChecked()) {
                // Stays off until the user confirms and Android has really activated the admin
                swHardcore.setChecked(false);
                showHardcoreWarning();
            } else {
                turnOffHardcore();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SessionStore.isActive(this)) {
            startActivity(new Intent(this, ActiveBlockActivity.class));
            finish();
            return;
        }
        if (!Permissions.accessibilityEnabled(this)) {
            startActivity(new Intent(this, PermissionsActivity.class));
            finish();
            return;
        }
        refresh();
    }

    private void refresh() {
        int count = BlocklistStore.count(this);
        btnChooseApps.setText(count == 0
                ? "Choose what to block"
                : "Choose what to block (" + count + " selected)");
        // Hardcore mode simply is "device admin is active", so this also covers the admin being
        // activated or cancelled on Android's own screen, or removed from Android's settings.
        swHardcore.setChecked(Permissions.adminActive(this));
        // On means: chosen here AND Android will show them (permission given, not blocked for the app)
        swNotices.setChecked(Permissions.noticesEnabled(this) && Permissions.notificationsAllowed(this));
    }

    // ---------- Scheduled session notifications ----------

    private void enableNotices() {
        Permissions.setNoticesEnabled(this, true);
        if (Permissions.notificationsAllowed(this)) {
            refresh();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Permissions.requestNotifications(this, REQ_NOTIFICATIONS);
        } else {
            showNotificationsBlockedDialog(); // permission is fine; they're switched off for the app
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        if (Permissions.notificationsAllowed(this)) {
            refresh();
        } else {
            // Declined (after a second decline Android stops asking, so this is the only route left)
            showNotificationsBlockedDialog();
        }
    }

    private void showNotificationsBlockedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Notifications are turned off")
                .setMessage("Android isn't allowing Focus Buddy to show notifications. Open its "
                        + "notification settings and allow them, then switch this on again.")
                .setPositiveButton("Open settings", (d, w) -> startActivity(
                        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Hardcore mode ----------

    private void showHardcoreWarning() {
        new AlertDialog.Builder(this)
                .setTitle("Turn on hardcore mode?")
                .setMessage("Hardcore mode stops you from deleting Focus Buddy, and during a focus "
                        + "session it stops you opening the app's pages in Android Settings, so "
                        + "you can't force-stop it.\n\n"
                        + "You can still delete the app, but only when no session is running and "
                        + "hardcore mode is turned off here.")
                .setPositiveButton("Turn on", (d, w) -> requestAdmin())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestAdmin() {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(this, FocusAdminReceiver.class));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Hardcore mode: stops Focus Buddy from being uninstalled or force-stopped during a focus session.");
        startActivity(i);
    }

    private void turnOffHardcore() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, FocusAdminReceiver.class);
        if (dpm.isAdminActive(admin)) {
            dpm.removeActiveAdmin(admin);
        }
        swHardcore.setChecked(false);
    }

    // ---------- Leaving ----------

    /** The first visit (right after setup) is followed by the main screen, which isn't on the stack yet. */
    private void finishSettings() {
        boolean firstRun = !Permissions.setupDone(this);
        Permissions.markSetupDone(this);
        if (firstRun) {
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        finishSettings();
    }
}
