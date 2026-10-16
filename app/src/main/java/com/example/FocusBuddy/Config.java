package com.example.FocusBuddy;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Config {
    private Config() {}

    /**
     * The one hardcoded unlock secret. The printed QR code encodes
     * focusbuddy://unlock?token=<this value>.
     *
     * To use your own: change it here, then run `python3 tools/make_qr.py`
     * to regenerate unlock-qr.png, and rebuild the app.
     */
    public static final String UNLOCK_TOKEN = "3b9624dc106b717b709534f3573717cf";

    /** package name -> display name. Edit to change what gets blocked. */
    public static final Map<String, String> BLOCKED_APPS;

    /**
     * Settings packages that get bounced during a session when the screen mentions this app
     * (App info, Accessibility, Device admin pages). Some OEMs use other packages; add them here.
     */
    public static final Set<String> SETTINGS_PACKAGES;

    static {
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("com.facebook.katana", "Facebook");
        apps.put("com.facebook.lite", "Facebook Lite");
        apps.put("com.google.android.youtube", "YouTube");
        apps.put("com.instagram.android", "Instagram");
        BLOCKED_APPS = Collections.unmodifiableMap(apps);

        Set<String> settings = new HashSet<>();
        settings.add("com.android.settings");
        settings.add("com.google.android.settings");
        SETTINGS_PACKAGES = Collections.unmodifiableSet(settings);
    }
}
