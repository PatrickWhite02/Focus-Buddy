package com.example.FocusBuddy;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Checks for the accessibility service the app needs before it can block anything, and tracks first-run setup. */
public final class Permissions {
    private Permissions() {}

    public static boolean accessibilityEnabled(Context c) {
        String enabled = Settings.Secure.getString(
                c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(c, BlockerAccessibilityService.class);
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(cn.flattenToString())
                    || s.equalsIgnoreCase(cn.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean adminActive(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(new ComponentName(c, FocusAdminReceiver.class));
    }

    /** Device admin is optional now: it's only requested when the user turns on hardcore mode. */
    public static boolean allGranted(Context c) {
        return accessibilityEnabled(c);
    }

    // ---------- First-run setup: the settings screen is shown once, after accessibility is on ----------

    private static final String KEY_SETUP_DONE = "setup_done";

    public static boolean setupDone(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false);
    }

    public static void markSetupDone(Context c) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SETUP_DONE, true).apply();
    }

    // ---------- Notifications (Android 13+), asked once when the app is first opened ----------

    private static final String PREFS = "focus_permissions";
    private static final String KEY_NOTIFICATIONS_ASKED = "notifications_asked";

    /** True if the notification permission exists on this Android version, isn't granted, and we haven't asked yet. */
    public static boolean needsNotificationPrompt(Context c) {
        return noticesEnabled(c)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                && !c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_NOTIFICATIONS_ASKED, false);
    }

    /** Shows the system notification prompt and remembers that we did, so it only happens once. */
    public static void requestNotifications(Activity a, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        a.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply();
        a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, requestCode);
    }

    // ---------- Settings switch: warnings before scheduled sessions ----------

    private static final String KEY_NOTICES_ENABLED = "notices_enabled";

    /** The user's choice in Settings (on unless they switched it off). Says nothing about permission. */
    public static boolean noticesEnabled(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTICES_ENABLED, true);
    }

    /** Saves the choice and sets or removes the warning alarm to match. */
    public static void setNoticesEnabled(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NOTICES_ENABLED, enabled).apply();
        ScheduleNotifier.arm(c);
    }

    /** True if Android will actually show our notifications (permission granted on 13+, and not switched off for the app). */
    public static boolean notificationsAllowed(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.areNotificationsEnabled();
    }

    // ---------- Settings switch: precise (exact-alarm) timing for those warnings ----------

    private static final String KEY_EXACT_ENABLED = "exact_alarms_enabled";

    /** The user's choice in Settings (on unless they switched it off). Says nothing about permission. */
    public static boolean exactAlarmsEnabled(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXACT_ENABLED, true);
    }

    /** Saves the choice and re-sets the warning alarm to match. */
    public static void setExactAlarmsEnabled(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EXACT_ENABLED, enabled).apply();
        ScheduleNotifier.arm(c);
    }

    /** True if Android lets us set exact alarms: always before Android 12, a user-controlled permission after. */
    public static boolean exactAlarmsAllowed(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am.canScheduleExactAlarms();
    }
}
