package com.example.FocusBuddy;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class BlockerAccessibilityService extends AccessibilityService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private View overlay;
    private long lastOverlayAt = 0L;

    // Browsers installed on the phone (refreshed once a minute), and a debounce for URL checks
    private Set<String> browsers = Collections.emptySet();
    private long browsersAt = -1L;
    private boolean siteCheckPending = false;

    // Visiting a blocked website doesn't close the browser straight away: the user gets a grace
    // period to close the tab themselves. While it runs, a toast counts down.
    private static final long GRACE_MS = 10_000L;
    private static final long TOAST_EVERY_MS = 3_000L;
    private static final Set<String> TRANSIENT_WINDOWS =
            new java.util.HashSet<>(java.util.Arrays.asList("com.android.systemui", "android"));
    private boolean graceActive = false;
    private boolean leftBrowserCheckPending = false;
    private String gracePkg;    // the browser the warning is about
    private String graceSite;   // the blocked website that triggered it
    private long graceEndsAt;   // elapsedRealtime
    private TextView graceBanner;   // the countdown banner (an accessibility overlay, not a Toast)

    private final Runnable graceTick = new Runnable() {
        @Override
        public void run() {
            if (!graceActive) return;
            if (!SessionStore.isActive(BlockerAccessibilityService.this)) {
                cancelGrace();
                return;
            }
            long left = graceEndsAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                endGrace();
                return;
            }
            if (left >= 2000) {
                showGraceToast(graceSite + " is blocked. Close the tab within "
                        + ((left + 999) / 1000) + " seconds.");
            }
            main.postDelayed(this, Math.min(left, TOAST_EVERY_MS));
        }
    };

    // No polling. A scheduled window can open while the user is sitting still in a blocked app (so
    // no accessibility events), so one timer is set for the moment the next window opens. When it
    // fires, it looks once at what's on screen, then sets itself for the window after that.
    private final Runnable windowOpened = new Runnable() {
        @Override
        public void run() {
            if (SessionStore.isActive(BlockerAccessibilityService.this)) {
                String pkg = foregroundPackage();
                if (pkg != null) handlePackage(pkg);
            }
            armNextWindowTimer();
        }
    };

    // Handler timers don't count time the phone spends asleep, so re-arm from the real clock
    // whenever the screen turns on, and whenever the schedule is edited.
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            armNextWindowTimer();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener scheduleChanged =
            (prefs, key) -> armNextWindowTimer();

    private void armNextWindowTimer() {
        main.removeCallbacks(windowOpened);
        long now = System.currentTimeMillis();
        long next = ScheduleStore.nextStartAfter(this, now);
        if (next > 0L) {
            main.postDelayed(windowOpened, next - now + 1000); // +1s so the window is definitely open
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        ScheduleStore.listen(this, scheduleChanged);
        armNextWindowTimer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (graceActive && !pkg.equals(gracePkg)
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleLeftBrowserCheck();
        }
        handlePackage(pkg);
    }

    private void handlePackage(String pkg) {
        if (pkg.equals(getPackageName()) || !SessionStore.isActive(this)) return;

        if (BlocklistStore.isBlocked(this, pkg)) {
            // Only act if it's really the foreground window (ignores ghost events while animating out)
            if (pkg.equals(foregroundPackage())) {
                block(BlocklistStore.labelFor(this, pkg) + " is blocked",
                        "Focus session in progress. Time to get back to what matters.");
            }
        } else if (Config.SETTINGS_PACKAGES.contains(pkg) && settingsShowsUs()) {
            block("Settings locked", "You can't change Focus Buddy's settings during a session.");
        } else if (BlocklistStore.hasBlockedSites(this) && isBrowser(pkg)) {
            scheduleSiteCheck(pkg);
        }
    }

    private boolean isBrowser(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (browsersAt < 0 || now - browsersAt > 60_000) {
            browsers = Browsers.packages(getPackageManager());
            browsersAt = now;
        }
        return browsers.contains(pkg);
    }

    /**
     * Browsers fire a burst of events while a page loads, so wait a moment and then look once at
     * where the browser ended up. A burst's last event always gets a check after it.
     */
    private void scheduleSiteCheck(final String pkg) {
        if (siteCheckPending) return;
        siteCheckPending = true;
        main.postDelayed(() -> {
            siteCheckPending = false;
            if (!SessionStore.isActive(this)) return;
            SiteReading reading = readSite(pkg);
            if (!reading.inBrowser) return;
            if (reading.blockedSite != null) {
                // Already counting down for this browser? Let that timer run, don't restart it.
                if (!graceActive || !pkg.equals(gracePkg)) startGrace(pkg, reading.blockedSite);
            } else if (graceActive && pkg.equals(gracePkg) && reading.otherUrl) {
                // The address bar changed to a different website: they've moved on, so the
                // countdown is dropped. Coming back to the blocked site later starts a new one.
                cancelGrace();
            }
        }, 300);
    }

    // ---------- Grace period for blocked websites ----------

    private void startGrace(String pkg, String site) {
        graceActive = true;
        gracePkg = pkg;
        graceSite = site;
        graceEndsAt = SystemClock.elapsedRealtime() + GRACE_MS;
        main.removeCallbacks(graceTick);
        graceTick.run();
    }

    private void cancelGrace() {
        graceActive = false;
        main.removeCallbacks(graceTick);
        hideGraceBanner();
    }

    /**
     * Shows (or updates) the countdown banner at the bottom of the screen. This is deliberately
     * not a Toast: Android silently drops toasts from an app that isn't in the foreground when
     * that app's notifications are disabled, and on Android 13+ they are off until the user grants
     * POST_NOTIFICATIONS. An accessibility overlay needs no permission, same as the block dialog.
     */
    private void showGraceToast(String text) {
        if (graceBanner != null) {
            graceBanner.setText(text);
            return;
        }
        TextView banner = new TextView(this);
        banner.setText(text);
        banner.setTextSize(14f);
        banner.setTextColor(Color.WHITE);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6323232);
        bg.setCornerRadius(dp(24));
        banner.setBackground(bg);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(96);
        lp.horizontalMargin = 0.05f;

        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(banner, lp);
            graceBanner = banner;
        } catch (Exception ignored) {
            // No banner; the tab still gets closed when the grace period ends
        }
    }

    private void hideGraceBanner() {
        if (graceBanner != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(graceBanner);
            } catch (Exception ignored) {
            }
            graceBanner = null;
        }
    }

    /**
     * The grace period is over without the user having moved on, so the tab is closed (they are
     * sent home). Nothing is re-read here: being unable to see the address bar is not a reason to
     * assume they left the site. Leaving the site or the browser cancels the grace period when it
     * happens (see scheduleSiteCheck and scheduleLeftBrowserCheck).
     */
    private void endGrace() {
        String site = graceSite;
        cancelGrace();
        if (!SessionStore.isActive(this)) return;
        block(site + " is blocked",
                "Focus session in progress. Time to get back to what matters.");
    }

    /** Another app's window came to the front: if the browser really is gone, drop the grace period. */
    private void scheduleLeftBrowserCheck() {
        if (leftBrowserCheckPending) return;
        leftBrowserCheckPending = true;
        main.postDelayed(() -> {
            leftBrowserCheckPending = false;
            if (!graceActive) return;
            String front = foregroundPackage();
            // Brief system windows (notification shade, system dialogs) aren't leaving the browser
            if (front != null && !front.equals(gracePkg) && !TRANSIENT_WINDOWS.contains(front)) {
                cancelGrace();
            }
        }, 300);
    }

    // ---------- Reading the browser ----------

    /** What a look at the screen showed about the browser. */
    private static final class SiteReading {
        boolean inBrowser;     // the browser is the foreground window
        String blockedSite;    // a blocked website shown in the address bar, or null
        boolean otherUrl;      // the address bar shows a website that is NOT blocked
    }

    private SiteReading readSite(String pkg) {
        SiteReading r = new SiteReading();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return r;
        if (!pkg.equals(root.getPackageName().toString())) return r;
        r.inBrowser = true;

        boolean foundAddressBar = false;
        for (String name : Config.URL_BAR_ID_NAMES) {
            List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + name);
            for (AccessibilityNodeInfo bar : bars) {
                foundAddressBar = true;
                CharSequence text = bar.getText();
                String hit = BlocklistStore.blockedSiteIn(this, text);
                if (hit != null) {
                    r.blockedSite = hit;
                    return r;
                }
                // Only a website in a bar the user isn't typing in counts: text being typed (or a
                // hint like "Search or type web address") hasn't taken them anywhere.
                if (!bar.isFocused() && text != null
                        && BlocklistStore.normalizeHost(text.toString()) != null) {
                    r.otherUrl = true;
                }
            }
        }
        if (foundAddressBar) return r;

        // Unknown browser: look through (a limited number of) the screen's text fields instead.
        // These could be fields on the web page itself, so they can only ever confirm a blocked site.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 200) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            if ("android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName())) {
                String hit = BlocklistStore.blockedSiteIn(this, node.getText());
                if (hit != null) {
                    r.blockedSite = hit;
                    return r;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return r;
    }

    private String foregroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return root.getPackageName().toString();
    }

    /** True if the Settings screen currently showing mentions this app (App info, Accessibility, Device admin...). */
    private boolean settingsShowsUs() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        if (!Config.SETTINGS_PACKAGES.contains(root.getPackageName().toString())) return false;
        return !root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isEmpty();
    }

    private void block(String title, String message) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOverlayAt > 1500) {
            lastOverlayAt = now;
            showOverlay(title, message);
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void showOverlay(String title, String message) {
        dismissOverlay();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(20f);
        titleView.setTextColor(Color.BLACK);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        card.addView(titleView);

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(15f);
        messageView.setTextColor(Color.DKGRAY);
        messageView.setPadding(0, dp(8), 0, dp(16));
        card.addView(messageView);

        Button ok = new Button(this);
        ok.setText("OK");
        ok.setOnClickListener(v -> dismissOverlay());
        card.addView(ok);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3000000);
        root.setClickable(true);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        cardLp.setMargins(dp(32), 0, dp(32), 0);
        root.addView(card, cardLp);

        // TYPE_ACCESSIBILITY_OVERLAY needs no extra permission for an accessibility service
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.addView(root, lp);
            overlay = root;
            main.postDelayed(() -> {
                if (overlay == root) dismissOverlay();
            }, 4000);
        } catch (Exception ignored) {
            // If the overlay fails, the app still gets closed by GLOBAL_ACTION_HOME
        }
    }

    private void dismissOverlay() {
        if (overlay != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        main.removeCallbacks(windowOpened);
        cancelGrace();
        ScheduleStore.stopListening(this, scheduleChanged);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
            // wasn't registered
        }
        dismissOverlay();
        super.onDestroy();
    }
}
