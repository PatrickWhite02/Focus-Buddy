package com.example.FocusBuddy;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * While this app is an active device admin it can't be uninstalled, and Android greys out
 * "Force stop" on its App info page. The accessibility service also bounces you out of the
 * Settings screens that could deactivate the admin during a session.
 */
public class FocusAdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        if (SessionStore.isActive(context)) {
            return "A focus session is running. Disabling admin now defeats the point!";
        }
        return null;
    }
}
