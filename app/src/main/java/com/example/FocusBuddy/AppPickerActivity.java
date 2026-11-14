package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the websites the user added (top) and the apps they have installed, including web
 * browsers, with a toggle each. Toggling adds/removes the item from the blocklist.
 * Not available during a session.
 */
public class AppPickerActivity extends Activity {

    /** One row: either an installed app (key = package name) or a website (key = host). */
    private static final class Entry {
        final String key;
        final String label;
        final Drawable icon;
        final boolean website;

        Entry(String key, String label, Drawable icon, boolean website) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.website = website;
        }
    }

    private final List<Entry> rows = new ArrayList<>();
    private final List<Entry> appEntries = new ArrayList<>();
    private final Set<String> blockedApps = new HashSet<>();
    private final Set<String> blockedSites = new HashSet<>();
    private Drawable browserIcon;
    private AppAdapter adapter;
    private TextView tvCount, tvEmpty;
    private ListView list;
    /** "Loading apps" block at the end of the list: under any websites, where the apps will appear. */
    private View loadingFooter;
    private boolean footerAttached = false;
    /** True until the background scan for installed apps has finished. */
    private boolean loading = true;
    /** Bumped on every reload so a slow, older scan can never overwrite a newer one. */
    private int loadGeneration = 0;
    /** Website icons fetched so far, by host; sites missing from here show the browser icon. */
    private final Map<String, Drawable> favicons = new HashMap<>();
    private final Set<String> faviconRequested = new HashSet<>();
    private final ExecutorService faviconWorker = Executors.newFixedThreadPool(3);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (leaveIfSessionActive()) return;
        setContentView(R.layout.activity_app_picker);

        tvCount = findViewById(R.id.tvCount);
        tvEmpty = findViewById(R.id.tvEmpty);
        list = findViewById(R.id.lvApps);
        // Not setEmptyView: it hides the whole list when there are no rows, and the loading footer lives in it
        loadingFooter = getLayoutInflater().inflate(R.layout.footer_loading, list, false);
        // Re-fit the footer whenever the list is laid out (first draw, rotation...)
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> list.post(this::sizeLoadingFooter));

        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < rows.size()) toggle(rows.get(position));
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= rows.size()) return false;
            Entry e = rows.get(position);
            if (!e.website) return false;
            confirmRemoveSite(e.key);
            return true;
        });

        findViewById(R.id.btnAddSite).setOnClickListener(v -> showAddSiteDialog());
        findViewById(R.id.btnDone).setOnClickListener(v -> finish());

        blockedApps.addAll(BlocklistStore.get(this));
        blockedSites.addAll(BlocklistStore.blockedSites(this));
        rebuildRows();
    }

    @Override
    protected void onDestroy() {
        faviconWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (leaveIfSessionActive()) return;
        // Runs on first open and every time the user comes back to this screen (e.g. after
        // installing or uninstalling an app), so the list is always a fresh scan, never reused.
        reloadApps();
    }

    /** Throws away the current app list and scans the phone again. */
    private void reloadApps() {
        final int generation = ++loadGeneration;
        loading = true;
        appEntries.clear();
        blockedApps.clear();
        blockedApps.addAll(BlocklistStore.get(this));
        rebuildRows();

        // Loading labels and icons for every app can take a moment, so keep it off the UI thread.
        new Thread(() -> {
            final List<Entry> loaded = loadApps();
            final Drawable icon = toRowIcon(Browsers.defaultIcon(getPackageManager()));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != loadGeneration) return;
                loading = false;
                appEntries.clear();
                appEntries.addAll(loaded);
                browserIcon = icon;
                if (!appEntries.isEmpty()) {
                    // Forget blocked apps that were uninstalled, so the count matches what's listed
                    Set<String> visible = new HashSet<>();
                    for (Entry e : appEntries) visible.add(e.key);
                    BlocklistStore.retainOnly(this, visible);
                    blockedApps.retainAll(visible);
                }
                rebuildRows();
            });
        }).start();
    }

    private boolean leaveIfSessionActive() {
        if (SessionStore.isActive(this)) {
            Toast.makeText(this, "You can't change the blocklist during a session",
                    Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        return false;
    }

    // ---------- List state ----------

    /** Websites first (alphabetical), then the installed apps (already alphabetical). */
    private void rebuildRows() {
        rows.clear();
        for (String host : new TreeSet<>(BlocklistStore.sites(this))) {
            rows.add(new Entry(host, host, browserIcon, true));
        }
        rows.addAll(appEntries);
        adapter.notifyDataSetChanged();
        updateCount();
        updateLoadingFooter();
        loadMissingFavicons();
    }

    /** Fetches each website's own icon once, in the background, and refreshes the list as they arrive. */
    private void loadMissingFavicons() {
        for (Entry e : rows) {
            if (!e.website || !faviconRequested.add(e.key)) continue;
            final String host = e.key;
            try {
                faviconWorker.execute(() -> {
                    final Drawable icon = Favicons.load(getApplicationContext(), host);
                    if (icon == null) return;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        favicons.put(host, icon);
                        adapter.notifyDataSetChanged();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Activity is shutting down
            }
        }
    }

    /**
     * While the app scan runs, a big "Loading apps" block sits at the end of the list, so it
     * appears underneath any websites, exactly where the apps will show up. When the scan is
     * done it is removed, and "No apps found" appears if there is nothing to list at all.
     */
    private void updateLoadingFooter() {
        if (loading && !footerAttached) {
            list.addFooterView(loadingFooter, null, false); // not selectable: it isn't a row
            footerAttached = true;
        } else if (!loading && footerAttached) {
            list.removeFooterView(loadingFooter);
            footerAttached = false;
        }
        tvEmpty.setVisibility(!loading && rows.isEmpty() ? View.VISIBLE : View.GONE);
        sizeLoadingFooter();
    }

    /** Makes the loading block as tall as the room left under the websites (never shorter than 220dp). */
    private void sizeLoadingFooter() {
        if (!footerAttached || list.getHeight() == 0) return;
        int rowHeight = 0;
        if (!rows.isEmpty()) {
            View sample = adapter.getView(0, null, list);
            int width = list.getWidth() > 0 ? list.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            sample.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            rowHeight = sample.getMeasuredHeight() + list.getDividerHeight();
        }
        int free = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom()
                - rows.size() * rowHeight;
        int minimum = Math.round(220 * getResources().getDisplayMetrics().density);
        int height = Math.max(free, minimum);
        ViewGroup.LayoutParams lp = loadingFooter.getLayoutParams();
        if (lp.height != height) { // only on a change, so this can't loop with the layout listener
            lp.height = height;
            loadingFooter.setLayoutParams(lp);
        }
    }

    private void toggle(Entry e) {
        if (leaveIfSessionActive()) return;
        if (e.website) {
            boolean now = !blockedSites.contains(e.key);
            if (now) blockedSites.add(e.key); else blockedSites.remove(e.key);
            BlocklistStore.setSiteBlocked(this, e.key, now);
        } else {
            boolean now = !blockedApps.contains(e.key);
            if (now) blockedApps.add(e.key); else blockedApps.remove(e.key);
            BlocklistStore.setBlocked(this, e.key, now);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        int a = blockedApps.size();
        int s = blockedSites.size();
        if (a + s == 0) {
            tvCount.setText("Nothing selected yet");
            return;
        }
        List<String> parts = new ArrayList<>();
        if (a > 0) parts.add(a + (a == 1 ? " app" : " apps"));
        if (s > 0) parts.add(s + (s == 1 ? " website" : " websites"));
        tvCount.setText(android.text.TextUtils.join(" and ", parts) + " will be blocked");
    }

    // ---------- Websites ----------

    private void showAddSiteDialog() {
        if (leaveIfSessionActive()) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("example.com");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Block a website")
                .setMessage("Browsers will be closed when they open this site (and its subdomains).")
                .setView(input)
                .setPositiveButton("Add", (d, which) -> {
                    if (leaveIfSessionActive()) return;
                    String host = BlocklistStore.normalizeHost(input.getText().toString());
                    if (host == null) {
                        Toast.makeText(this, "That doesn't look like a website address",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BlocklistStore.addSite(this, host);
                    blockedSites.add(host);
                    rebuildRows();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemoveSite(final String host) {
        if (leaveIfSessionActive()) return;
        new AlertDialog.Builder(this)
                .setTitle("Remove " + host + "?")
                .setPositiveButton("Remove", (d, which) -> {
                    if (leaveIfSessionActive()) return;
                    BlocklistStore.removeSite(this, host);
                    blockedSites.remove(host);
                    rebuildRows();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Finding the apps ----------

    private List<Entry> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ApplicationInfo> found = new LinkedHashMap<>();

        // Anything with a launcher icon that the user installed (or updated, e.g. YouTube on most phones)
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (isUserApp(ai)) found.put(ai.packageName, ai);
        }

        // Web browsers, even when they're preinstalled system apps (Chrome, Samsung Internet...)
        for (ApplicationInfo ai : Browsers.apps(pm)) {
            found.put(ai.packageName, ai);
        }

        // Never offer ourselves, or the home screen app: blocking it would trap the user in a
        // loop, because a blocked app is closed by sending them to the home screen.
        found.remove(getPackageName());
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            found.remove(ri.activityInfo.packageName);
        }

        List<Entry> out = new ArrayList<>();
        for (ApplicationInfo ai : found.values()) {
            out.add(new Entry(ai.packageName, ai.loadLabel(pm).toString(),
                    toRowIcon(ai.loadIcon(pm)), false));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /**
     * App icons are usually large adaptive icons that Android re-masks and re-scales every time
     * a row is drawn, which is what makes scrolling stutter. Drawing each one once into a small
     * bitmap (the size it is shown at) makes drawing a row nearly free. Runs on the background
     * thread, so the cost is only paid while "Loading apps" is showing.
     */
    private Drawable toRowIcon(Drawable src) {
        if (src == null) return null;
        int size = Math.round(40 * getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        src.setBounds(0, 0, size, size);
        src.draw(canvas);
        return new BitmapDrawable(getResources(), bmp);
    }

    private static boolean isUserApp(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    // ---------- List rows ----------

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Entry getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            Holder h;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_app, parent, false);
                h = new Holder(row);
                row.setTag(h);
            } else {
                h = (Holder) row.getTag();
            }
            Entry e = rows.get(position);
            Drawable shown = e.website && favicons.containsKey(e.key)
                    ? favicons.get(e.key)
                    : e.icon;
            if (shown != null) {
                h.icon.setImageDrawable(shown);
            } else {
                h.icon.setImageResource(android.R.drawable.ic_menu_search);
            }
            h.name.setText(e.label);
            h.sub.setText(e.website ? "Website \u00b7 hold to remove" : e.key);
            h.toggle.setChecked(
                    e.website ? blockedSites.contains(e.key) : blockedApps.contains(e.key));
            // setChecked() animates the thumb on a row that's already on screen; a recycled row
            // would slide its switch while scrolling. Snap it to the right state instead.
            h.toggle.jumpDrawablesToCurrentState();
            return row;
        }
    }

    private static final class Holder {
        final ImageView icon;
        final TextView name, sub;
        final Switch toggle;

        Holder(View row) {
            icon = row.findViewById(R.id.ivIcon);
            name = row.findViewById(R.id.tvAppName);
            sub = row.findViewById(R.id.tvAppPackage);
            toggle = row.findViewById(R.id.swBlock);
        }
    }
}