package com.example.FocusBuddy;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
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
}
