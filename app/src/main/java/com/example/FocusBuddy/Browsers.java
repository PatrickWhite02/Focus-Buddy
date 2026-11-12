package com.example.FocusBuddy;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the web browsers installed on the phone. An app counts as a browser only if it takes web
 * links for any website. Apps that merely open links to particular sites (YouTube, Reddit, shopping
 * apps...) don't count, so they are never treated as a browser.
 */
public final class Browsers {
    private Browsers() {}

    private static Intent webIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    public static List<ApplicationInfo> apps(PackageManager pm) {
        Map<String, ApplicationInfo> found = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(webIntent(), PackageManager.MATCH_ALL)) {
            if (!handlesAnyWebsite(ri)) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            found.put(ai.packageName, ai);
        }
        return new ArrayList<>(found.values());
    }

    /** A browser's link filter names no particular website; a link handler's filter always does. */
    private static boolean handlesAnyWebsite(ResolveInfo ri) {
        IntentFilter f = ri.filter;
        return f == null || f.authoritiesIterator() == null; // no filter to inspect: keep it
    }

    public static Set<String> packages(PackageManager pm) {
        Set<String> out = new HashSet<>();
        for (ApplicationInfo ai : apps(pm)) out.add(ai.packageName);
        return out;
    }

    /** The icon of the user's default browser, or the first browser found; null if there is none. */
    public static Drawable defaultIcon(PackageManager pm) {
        ResolveInfo def = pm.resolveActivity(webIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        // "android" is the system chooser, which is what you get when no default browser is set
        if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
            return def.activityInfo.applicationInfo.loadIcon(pm);
        }
        List<ApplicationInfo> all = apps(pm);
        return all.isEmpty() ? null : all.get(0).loadIcon(pm);
    }
}
