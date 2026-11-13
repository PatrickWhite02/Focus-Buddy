package com.example.FocusBuddy;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Checks for the two permissions the app needs before it can block anything. */
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

    public static boolean allGranted(Context c) {
        return accessibilityEnabled(c) && adminActive(c);
    }

    // ---------- Notifications (Android 13+), asked once when the app is first opened ----------

    private static final String PREFS = "focus_permissions";
    private static final String KEY_NOTIFICATIONS_ASKED = "notifications_asked";

    /** True if the notification permission exists on this Android version, isn't granted, and we haven't asked yet. */
    public static boolean needsNotificationPrompt(Context c) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
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
}
