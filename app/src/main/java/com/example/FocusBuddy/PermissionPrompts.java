package com.example.FocusBuddy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The camera and notification questions. Each permission is requested the usual way first; if it
 * still isn't on, a dialog explains what that costs and offers "Launch settings" or a way on. Its
 * "Don't show this again" box greys out "Launch settings", and once the user carries on with it
 * ticked that dialog is never shown again.
 *
 * - {@link #scan}: the "Scan QR" button. Needs the camera, so it asks for it. If the answer is no
 *   (or Android no longer asks), a dialog offers "Launch settings", or, during a hardcore session,
 *   when Settings is locked, explains that and points to the phone's regular camera app.
 * - {@link #run}: just before a session is started or scheduled. Only notifications are asked
 *   about, and only when scheduling. The camera is never asked about here.
 *
 * One instance per activity. The activity forwards onRequestPermissionsResult to {@link #onResult}.
 */
public final class PermissionPrompts {
    private static final int REQ_NOTICES = 42;
    private static final int REQ_CAMERA_SCAN = 43;

    private static final String PREFS = "focus_permission_prompts";
    private static final String KEY_HIDE_CAMERA_LOCKED = "hide_camera_locked_dialog";
    private static final String KEY_HIDE_CAMERA_SCAN = "hide_camera_scan_dialog";
    private static final String KEY_HIDE_NOTICES = "hide_notices_dialog";

    private final Activity a;
    private boolean wantNotices;
    private Runnable proceed;    // what to do once the start / schedule questions are answered
    private Runnable scanAction; // opens the scanner, once the camera is allowed

    public PermissionPrompts(Activity activity) {
        this.a = activity;
    }

    public static boolean cameraGranted(Context c) {
        return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- Entry points ----------

    /**
     * For the "Scan QR" button: runs {@code launchScanner} straight away if the camera is allowed,
     * otherwise asks for it first and runs it only if the answer is yes.
     */
    public void scan(Runnable launchScanner) {
        if (cameraGranted(a)) {
            launchScanner.run();
            return;
        }
        scanAction = launchScanner;
        a.requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_SCAN);
    }

    /**
     * Before a session is started or scheduled: notifications if {@code notices} is true, then
     * {@code proceed}. {@code proceed} does not run if the user picks "Launch settings": they come
     * back and try again.
     */
    public void run(boolean notices, Runnable proceed) {
        this.wantNotices = notices;
        this.proceed = proceed;
        askNotices();
    }

    /** Call from onRequestPermissionsResult. Returns true if the request was one of ours. */
    public boolean onResult(int requestCode) {
        if (requestCode != REQ_NOTICES && requestCode != REQ_CAMERA_SCAN) {
            return false;
        }
        if (a.isFinishing() || a.isDestroyed()) return true;
        if (requestCode == REQ_CAMERA_SCAN) {
            Runnable launch = scanAction;
            scanAction = null;
            if (launch == null) return true;
            if (cameraGranted(a)) launch.run(); else scanDeniedDialog();
        } else if (proceed != null) {
            // After a second decline Android no longer shows its prompt and answers "no" straight away
            if (Permissions.notificationsAllowed(a)) done(); else noticesDialog();
        }
        return true;
    }

    // ---------- Camera: the scan button ----------

    private void scanDeniedDialog() {
        if (settingsLocked()) {
            // Hardcore session: Android's settings for this app are closed, so there's nothing to launch
            if (hidden(KEY_HIDE_CAMERA_LOCKED)) {
                Toast.makeText(a, "Camera permission is off. Scan the QR code with your phone's "
                        + "regular camera app instead.", Toast.LENGTH_LONG).show();
                return;
            }
            final LinearLayout box = dontShowBox();
            final CheckBox dontShow = (CheckBox) box.getChildAt(0);
            new AlertDialog.Builder(a)
                    .setTitle("Camera permission is off")
                    .setMessage("The camera permission isn't enabled, so the built-in scanner can't "
                            + "open. Focus Buddy's settings are locked while hardcore mode is on "
                            + "during a session, so it can't be turned on right now.\n\nScan the QR "
                            + "code with your phone's regular camera app instead.\n\nIn the "
                            + "future, turn on the camera permission in Focus Buddy's settings "
                            + "while no hardcore session is running, and the built-in scanner "
                            + "will work.")
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> {
                        if (dontShow.isChecked()) {
                            prefs().edit().putBoolean(KEY_HIDE_CAMERA_LOCKED, true).apply();
                        }
                    })
                    .show();
            return;
        }
        if (hidden(KEY_HIDE_CAMERA_SCAN)) {
            Toast.makeText(a, "Camera permission is off. Scan the QR code with your phone's "
                    + "camera app instead.", Toast.LENGTH_LONG).show();
            return;
        }
        show("Camera permission is off",
                "The camera permission isn't enabled. In order to use the built-in barcode "
                        + "scanner, you must enable this permission in settings.\n\nYou can always "
                        + "use a standard camera app to scan the QR code instead, if you'd prefer.",
                KEY_HIDE_CAMERA_SCAN, appInfo(), "Cancel", () -> { });
    }

    // ---------- Notifications ----------

    private void askNotices() {
        // Switched off in Focus Buddy's Settings: the user has said they don't want them, so don't nag
        if (!wantNotices || !Permissions.noticesEnabled(a) || Permissions.notificationsAllowed(a)) {
            done();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Permissions.requestNotifications(a, REQ_NOTICES);
        } else {
            noticesDialog(); // no permission to ask for: they're switched off for the app in Android
        }
    }

    private void noticesDialog() {
        if (hidden(KEY_HIDE_NOTICES) || settingsLocked()) {
            done();
            return;
        }
        show("Notifications are off",
                "Notifications aren't enabled, so Focus Buddy can't warn you before a scheduled "
                        + "session starts. To get those warnings, you must enable notifications "
                        + "in settings.\n\nYou can still schedule sessions without them; they'll "
                        + "just begin with no heads-up.",
                KEY_HIDE_NOTICES,
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()),
                "Continue without", this::done);
    }

    // ---------- Shared ----------

    private void done() {
        Runnable next = proceed;
        proceed = null;
        if (next != null) next.run();
    }

    private Intent appInfo() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.getPackageName()));
    }

    /**
     * During a session with hardcore mode on, Android's settings pages for this app are closed
     * by the blocker, so "Launch settings" would only bounce the user.
     */
    private boolean settingsLocked() {
        return SessionStore.isActive(a) && Permissions.adminActive(a);
    }

    private SharedPreferences prefs() {
        return a.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean hidden(String key) {
        return prefs().getBoolean(key, false);
    }

    /** A padded row holding the "Don't show this again" checkbox, which is its only child. */
    private LinearLayout dontShowBox() {
        CheckBox dontShow = new CheckBox(a);
        dontShow.setText("Don't show this again");
        LinearLayout box = new LinearLayout(a);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(dontShow);
        return box;
    }

    private void show(String title, String message, final String hideKey, final Intent settings,
                      String continueLabel, final Runnable onContinue) {
        final LinearLayout box = dontShowBox();
        final CheckBox dontShow = (CheckBox) box.getChildAt(0);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setView(box)
                .setCancelable(false) // only the buttons close it
                .setPositiveButton("Launch settings", (d, w) -> {
                    proceed = null; // they're going to change it, then try again
                    a.startActivity(settings);
                })
                .setNegativeButton(continueLabel, (d, w) -> {
                    if (dontShow.isChecked()) prefs().edit().putBoolean(hideKey, true).apply();
                    onContinue.run();
                })
                .create();
        dialog.show();

        final Button launch = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        dontShow.setOnCheckedChangeListener((b, checked) -> {
            launch.setEnabled(!checked);
            launch.setAlpha(checked ? 0.4f : 1f);
        });
    }
}
