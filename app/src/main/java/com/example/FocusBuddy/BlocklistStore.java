package com.example.FocusBuddy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The user's chosen blocklist (package names), kept in SharedPreferences.
 * The activity and the accessibility service run in the same process, so changes made in the
 * picker are visible to the service immediately.
 */
public final class BlocklistStore {
    private static final String PREFS = "focus_blocklist";
    private static final String KEY_PACKAGES = "blocked_packages";

    private BlocklistStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A copy of the blocked package names (the set SharedPreferences returns must not be modified). */
    public static Set<String> get(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_PACKAGES, Collections.<String>emptySet()));
    }

    public static boolean isBlocked(Context c, String pkg) {
        return prefs(c).getStringSet(KEY_PACKAGES, Collections.<String>emptySet()).contains(pkg);
    }

    public static int count(Context c) {
        return prefs(c).getStringSet(KEY_PACKAGES, Collections.<String>emptySet()).size();
    }

    public static void setBlocked(Context c, String pkg, boolean blocked) {
        Set<String> next = get(c);
        if (blocked) next.add(pkg); else next.remove(pkg);
        prefs(c).edit().putStringSet(KEY_PACKAGES, next).apply();
    }

    /** Drops entries that are no longer in {@code keep} (e.g. apps that were uninstalled). */
    public static void retainOnly(Context c, Set<String> keep) {
        Set<String> next = get(c);
        if (next.retainAll(keep)) {
            prefs(c).edit().putStringSet(KEY_PACKAGES, next).apply();
        }
    }

    /** Human-readable app name, falling back to the package name. */
    public static String labelFor(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Sorted display names of everything currently blocked. */
    public static List<String> labels(Context c) {
        List<String> out = new ArrayList<>();
        for (String pkg : get(c)) out.add(labelFor(c, pkg));
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
