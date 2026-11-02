package com.example.FocusBuddy;

import android.accessibilityservice.AccessibilityService;
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
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
            String site = blockedSiteOpenIn(pkg);
            if (site != null) {
                block(site + " is blocked",
                        "Focus session in progress. Time to get back to what matters.");
            }
        }, 300);
    }

    /** The blocked website the foreground browser is showing, or null. Reads its address bar. */
    private String blockedSiteOpenIn(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !pkg.equals(root.getPackageName().toString())) {
            return null;
        }

        boolean foundAddressBar = false;
        for (String name : Config.URL_BAR_ID_NAMES) {
            List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + name);
            for (AccessibilityNodeInfo bar : bars) {
                foundAddressBar = true;
                String hit = BlocklistStore.blockedSiteIn(this, bar.getText());
                if (hit != null) return hit;
            }
        }
        if (foundAddressBar) return null;

        // Unknown browser: look through (a limited number of) the screen's text fields instead
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 200) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            if ("android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName())) {
                String hit = BlocklistStore.blockedSiteIn(this, node.getText());
                if (hit != null) return hit;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
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
        dismissOverlay();
        super.onDestroy();
    }
}
