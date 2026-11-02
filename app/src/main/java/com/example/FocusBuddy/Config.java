package com.example.FocusBuddy;

import java.util.Collections;
import java.util.HashSet;
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

    /**
     * Settings packages that get bounced during a session when the screen mentions this app
     * (App info, Accessibility, Device admin pages). Some OEMs use other packages; add them here.
     */
    public static final Set<String> SETTINGS_PACKAGES;

    static {
        Set<String> settings = new HashSet<>();
        settings.add("com.android.settings");
        settings.add("com.google.android.settings");
        SETTINGS_PACKAGES = Collections.unmodifiableSet(settings);
    }
}
