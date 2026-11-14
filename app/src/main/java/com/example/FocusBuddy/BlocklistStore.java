package com.example.FocusBuddy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The user's chosen blocklist, kept in SharedPreferences: app package names, plus websites
 * (stored as bare hosts like "youtube.com"). A website stays in the list when switched off.
 * The activity and the accessibility service run in the same process, so changes made in the
 * picker are visible to the service immediately.
 */
public final class BlocklistStore {
    private static final String PREFS = "focus_blocklist";
    private static final String KEY_PACKAGES = "blocked_packages";
    private static final String KEY_SITES = "sites";                 // every website the user added
    private static final String KEY_SITES_BLOCKED = "blocked_sites"; // the ones switched on

    private static final Pattern HOST =
            Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    private BlocklistStore() {}

    /** For the accessibility service, to notice the blocklist changing. The listener must be kept referenced. */
    public static void listen(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).registerOnSharedPreferenceChangeListener(l);
    }

    public static void stopListening(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Read-only view of a stored set (the set SharedPreferences returns must not be modified). */
    private static Set<String> read(Context c, String key) {
        return prefs(c).getStringSet(key, Collections.<String>emptySet());
    }

    private static Set<String> copy(Context c, String key) {
        return new HashSet<>(read(c, key));
    }

    // ---------- Apps ----------

    public static Set<String> get(Context c) {
        return copy(c, KEY_PACKAGES);
    }

    public static boolean isBlocked(Context c, String pkg) {
        return read(c, KEY_PACKAGES).contains(pkg);
    }

    public static void setBlocked(Context c, String pkg, boolean blocked) {
        Set<String> next = copy(c, KEY_PACKAGES);
        if (blocked) next.add(pkg); else next.remove(pkg);
        prefs(c).edit().putStringSet(KEY_PACKAGES, next).apply();
    }

    /** Drops blocked apps that are no longer in {@code keep} (e.g. apps that were uninstalled). */
    public static void retainOnly(Context c, Set<String> keep) {
        Set<String> next = copy(c, KEY_PACKAGES);
        if (next.retainAll(keep)) {
            prefs(c).edit().putStringSet(KEY_PACKAGES, next).apply();
        }
    }

    // ---------- Websites ----------

    /**
     * Turns what a user typed, or what a browser's address bar shows, into a bare host:
     * "https://www.YouTube.com/watch?v=1" -> "youtube.com". Returns null if it isn't a web address.
     */
    public static String normalizeHost(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') {
                s = s.substring(0, i);
                break;
            }
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        if (s.startsWith("www.")) s = s.substring(4);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return HOST.matcher(s).matches() ? s : null;
    }

    /** Every website the user has added, switched on or not. */
    public static Set<String> sites(Context c) {
        return copy(c, KEY_SITES);
    }

    public static Set<String> blockedSites(Context c) {
        return copy(c, KEY_SITES_BLOCKED);
    }

    public static boolean hasBlockedSites(Context c) {
        return !read(c, KEY_SITES_BLOCKED).isEmpty();
    }

    /** Adds a website (already normalised) to the list, switched on. */
    public static void addSite(Context c, String host) {
        Set<String> all = copy(c, KEY_SITES);
        Set<String> on = copy(c, KEY_SITES_BLOCKED);
        all.add(host);
        on.add(host);
        prefs(c).edit().putStringSet(KEY_SITES, all).putStringSet(KEY_SITES_BLOCKED, on).apply();
    }

    public static void setSiteBlocked(Context c, String host, boolean blocked) {
        Set<String> on = copy(c, KEY_SITES_BLOCKED);
        if (blocked) on.add(host); else on.remove(host);
        prefs(c).edit().putStringSet(KEY_SITES_BLOCKED, on).apply();
    }

    public static void removeSite(Context c, String host) {
        Set<String> all = copy(c, KEY_SITES);
        Set<String> on = copy(c, KEY_SITES_BLOCKED);
        all.remove(host);
        on.remove(host);
        prefs(c).edit().putStringSet(KEY_SITES, all).putStringSet(KEY_SITES_BLOCKED, on).apply();
    }

    /**
     * If {@code addressBarText} is a blocked website (or a subdomain of one, like m.youtube.com),
     * returns that blocked website; otherwise null.
     */
    public static String blockedSiteIn(Context c, CharSequence addressBarText) {
        if (addressBarText == null) return null;
        String host = normalizeHost(addressBarText.toString());
        if (host == null) return null;
        for (String site : read(c, KEY_SITES_BLOCKED)) {
            if (host.equals(site) || host.endsWith("." + site)) return site;
        }
        return null;
    }

    // ---------- Both ----------

    /** How many things (apps + websites) are switched on. A session needs at least one. */
    public static int count(Context c) {
        return read(c, KEY_PACKAGES).size() + read(c, KEY_SITES_BLOCKED).size();
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

    /** Sorted display names of everything currently blocked (apps and websites). */
    public static List<String> labels(Context c) {
        List<String> out = new ArrayList<>();
        for (String pkg : get(c)) out.add(labelFor(c, pkg));
        out.addAll(blockedSites(c));
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
