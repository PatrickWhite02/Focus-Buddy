package com.example.FocusBuddy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Session state lives in SharedPreferences, so it survives reboots and process death. */
public final class SessionStore {
    private static final String PREFS = "focus_session";
    private static final String KEY_END = "end_time";
    private static final String KEY_UNTIL_SCAN = "until_scan"; // no timer: lasts until the QR code is scanned

    private SessionStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void start(Context c, float hours) {
        long end = System.currentTimeMillis() + (long) (hours * 3_600_000f);
        prefs(c).edit().putLong(KEY_END, end).commit();
    }

    /** Starts a block with no end time. Only scanning the unlock QR code ends it. */
    public static void startUntilScan(Context c) {
        prefs(c).edit().putBoolean(KEY_UNTIL_SCAN, true).commit();
    }

    public static boolean isUntilScan(Context c) {
        return prefs(c).getBoolean(KEY_UNTIL_SCAN, false);
    }

    /** Time left in the manual session or in the open scheduled window, whichever ends later. */
    public static long remainingMs(Context c) {
        long now = System.currentTimeMillis();
        long manual = Math.max(0L, prefs(c).getLong(KEY_END, 0L) - now);
        long scheduledEnd = ScheduleStore.activeUntil(c, now);
        long scheduled = scheduledEnd > 0L ? scheduledEnd - now : 0L;
        return Math.max(manual, scheduled);
    }

    /**
     * If the current session is a scheduled one (a scheduled window lasts at least as long as any
     * manual session), the time (ms) the block releases, counting windows that run back to back;
     * otherwise 0. Lets the UI say "Blocked until 12:00 PM" instead of showing a countdown.
     */
    public static long scheduledReleaseMs(Context c) {
        long now = System.currentTimeMillis();
        long release = ScheduleStore.activeUntil(c, now);
        if (release <= now) return 0L;
        // A window that starts exactly when this one ends keeps the block going
        for (int i = 0; i < 50; i++) {
            long next = ScheduleStore.activeUntil(c, release);
            if (next <= release) break;
            release = next;
        }
        return release >= prefs(c).getLong(KEY_END, 0L) ? release : 0L;
    }

    public static boolean isActive(Context c) {
        return isUntilScan(c) || remainingMs(c) > 0;
    }

    /** Accepts focusbuddy://unlock?token=... and ends the session if the token matches the hardcoded one. */
    public static boolean unlockFromUri(Context c, Uri uri) {
        if (uri == null
                || !"focusbuddy".equals(uri.getScheme())
                || !"unlock".equals(uri.getHost())) {
            return false;
        }
        String token = uri.getQueryParameter("token");
        if (token == null) return false;

        boolean match = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                Config.UNLOCK_TOKEN.getBytes(StandardCharsets.UTF_8));
        if (!match) return false;

        prefs(c).edit().remove(KEY_END).remove(KEY_UNTIL_SCAN).commit();
        ScheduleStore.skipCurrent(c); // also ends a scheduled window that's open right now
        return true;
    }

    public static boolean unlockFromLink(Context c, String raw) {
        return raw != null && unlockFromUri(c, Uri.parse(raw));
    }
}
