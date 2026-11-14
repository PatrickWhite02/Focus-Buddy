package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;

/**
 * Hardcore mode is "Focus Buddy is an active device admin". Turning it on is the same from
 * Settings and from the running-session screen, so the warning and the request live here.
 */
public final class HardcoreMode {
    private HardcoreMode() {}

    /**
     * Explains what hardcore mode does and, if the user agrees, asks Android to activate the admin.
     *
     * @param sessionRunning true when turned on in the middle of a block, where it can't be undone until the block ends
     */
    public static void showWarning(Activity a, boolean sessionRunning) {
        String message = "Hardcore mode stops you from deleting Focus Buddy, and during a focus "
                + "session it stops you opening the app's pages in Android Settings, so "
                + "you can't force-stop it.\n\n"
                + "You can still delete the app, but only when no session is running and "
                + "hardcore mode is turned off in Settings.";
        if (sessionRunning) {
            message += "\n\nYou won't be able to disable this until the block ends.";
        }
        new AlertDialog.Builder(a)
                .setTitle("Turn on hardcore mode?")
                .setMessage(message)
                .setPositiveButton("Turn on", (d, w) -> requestAdmin(a))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void requestAdmin(Activity a) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(a, FocusAdminReceiver.class));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Hardcore mode: stops Focus Buddy from being uninstalled or force-stopped during a focus session.");
        a.startActivity(i);
    }
}
