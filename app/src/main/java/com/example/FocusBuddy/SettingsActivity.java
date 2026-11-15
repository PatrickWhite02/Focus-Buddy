package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings: what to block, notifications, and hardcore mode. Also reachable during a session, where
 * things can only be tightened: more can be added to the blocklist and hardcore mode can be turned
 * on, but nothing can be removed or turned off until the session ends.
 * Also shown once, right after the accessibility service is enabled, before the main screen.
 */
public class SettingsActivity extends Activity {

    private Button btnChooseApps;
    private Switch swHardcore, swNotices, swExact;
    private TextView tvSessionNote;
    private static final int REQ_NOTIFICATIONS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        btnChooseApps = findViewById(R.id.btnChooseApps);
        tvSessionNote = findViewById(R.id.tvSessionNote);
        swHardcore = findViewById(R.id.swHardcore);
        swNotices = findViewById(R.id.swNotices);
        swExact = findViewById(R.id.swExact);
        // Before Android 12 exact alarms are always allowed, so there's nothing to choose
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            findViewById(R.id.cardExact).setVisibility(View.GONE);
        }

        btnChooseApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        findViewById(R.id.btnDone).setOnClickListener(v -> finishSettings());

        // Clicks, not checked-change listeners, so setting a switch from code (see refresh)
        // never triggers a dialog, a permission request or an admin removal.
        swExact.setOnClickListener(v -> {
            if (swExact.isChecked()) {
                Permissions.setExactAlarmsEnabled(this, true);
                if (!Permissions.exactAlarmsAllowed(this)) {
                    // Android's own "Alarms & reminders" screen: only the user can grant this
                    swExact.setChecked(false);
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                }
            } else {
                Permissions.setExactAlarmsEnabled(this, false);
            }
        });
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
                HardcoreMode.showWarning(this, SessionStore.isActive(this));
            } else if (SessionStore.isActive(this)) {
                // Greyed out, but still tappable so it can say why it won't switch off
                swHardcore.setChecked(true);
                new AlertDialog.Builder(this)
                        .setTitle("Hardcore mode is locked")
                        .setMessage("You can't turn hardcore mode off while a session is running. "
                                + "It can be switched off once the session ends.")
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                turnOffHardcore();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        // Mid-session: more can be added to the blocklist and hardcore mode can go on, but not off
        boolean running = SessionStore.isActive(this);
        tvSessionNote.setVisibility(running ? View.VISIBLE : View.GONE);
        swHardcore.setAlpha(running && Permissions.adminActive(this) ? 0.4f : 1f);
        // On means: chosen here AND Android will show them (permission given, not blocked for the app)
        swNotices.setChecked(Permissions.noticesEnabled(this) && Permissions.notificationsAllowed(this));
        // Same idea: chosen here AND allowed by Android
        swExact.setChecked(Permissions.exactAlarmsEnabled(this) && Permissions.exactAlarmsAllowed(this));
        ScheduleNotifier.arm(this); // picks up a permission just granted or taken away on Android's side
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
        if (Permissions.setupDone(this)) {
            finish();
            return;
        }
        // First time through: make sure they know how a session gets ended early before they can start one
        new AlertDialog.Builder(this)
                .setTitle("Keep your QR code handy")
                .setMessage("The only way to end a focus session early is to scan your unlock QR "
                        + "code. Make sure you can get to it (printed out, or saved somewhere you "
                        + "can reach) before you start a session, especially one with no timer.")
                .setCancelable(false)
                .setPositiveButton("I understand", (d, w) -> {
                    Permissions.markSetupDone(this);
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        finishSettings();
    }
}
