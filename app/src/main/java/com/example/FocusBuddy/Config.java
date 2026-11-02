package com.example.FocusBuddy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

    /**
     * Resource names (the part after ":id/") of browser address bars, used to read the current
     * website. Tried as "<browser package>:id/<name>", so Chrome, Brave, Edge, Firefox, Samsung
     * Internet etc. are covered. Unknown browsers fall back to scanning their text fields.
     * If a browser isn't detected, find its address bar id (Android Studio's Layout Inspector
     * or `uiautomator dump`) and add it here.
     */
    public static final List<String> URL_BAR_ID_NAMES = Collections.unmodifiableList(Arrays.asList(
            "url_bar",                          // Chrome, Brave, Edge, Vivaldi, Kiwi...
            "url_field",                        // Opera
            "location_bar_edit_text",           // Samsung Internet
            "mozac_browser_toolbar_url_view",   // Firefox
            "omnibarTextInput",                 // DuckDuckGo
            "url_edit_text",
            "url_text"));

    static {
        Set<String> settings = new HashSet<>();
        settings.add("com.android.settings");
        settings.add("com.google.android.settings");
        SETTINGS_PACKAGES = Collections.unmodifiableSet(settings);
    }
}
