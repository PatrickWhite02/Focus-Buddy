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

    private SessionStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void start(Context c, float hours) {
        long end = System.currentTimeMillis() + (long) (hours * 3_600_000f);
        prefs(c).edit().putLong(KEY_END, end).commit();
    }

    public static long remainingMs(Context c) {
        return Math.max(0L, prefs(c).getLong(KEY_END, 0L) - System.currentTimeMillis());
    }

    public static boolean isActive(Context c) {
        return remainingMs(c) > 0;
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

        prefs(c).edit().remove(KEY_END).commit();
        return true;
    }

    public static boolean unlockFromLink(Context c, String raw) {
        return raw != null && unlockFromUri(c, Uri.parse(raw));
    }
}
