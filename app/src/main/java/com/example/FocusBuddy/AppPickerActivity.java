package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (leaveIfSessionActive()) return;
        setContentView(R.layout.activity_app_picker);

        tvCount = findViewById(R.id.tvCount);
        tvEmpty = findViewById(R.id.tvEmpty);
        ListView list = findViewById(R.id.lvApps);
        list.setEmptyView(tvEmpty);

        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> toggle(rows.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
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

        // Loading labels and icons for every app can take a moment, so keep it off the UI thread.
        new Thread(() -> {
            final List<Entry> loaded = loadApps();
            final Drawable icon = Browsers.defaultIcon(getPackageManager());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                appEntries.clear();
                appEntries.addAll(loaded);
                browserIcon = icon;
                if (appEntries.isEmpty()) {
                    tvEmpty.setText("No apps found");
                } else {
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

    @Override
    protected void onResume() {
        super.onResume();
        leaveIfSessionActive();
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
    }

    private void toggle(Entry e) {
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
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("example.com");
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Block a website")
                .setMessage("Browsers will be closed when they open this site (and its subdomains).")
                .setView(input)
                .setPositiveButton("Add", (d, which) -> {
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
        new AlertDialog.Builder(this)
                .setTitle("Remove " + host + "?")
                .setPositiveButton("Remove", (d, which) -> {
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
            out.add(new Entry(ai.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm), false));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
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
            View row = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_app, parent, false);
            Entry e = rows.get(position);
            ImageView icon = row.findViewById(R.id.ivIcon);
            if (e.icon != null) {
                icon.setImageDrawable(e.icon);
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_search);
            }
            ((TextView) row.findViewById(R.id.tvAppName)).setText(e.label);
            ((TextView) row.findViewById(R.id.tvAppPackage))
                    .setText(e.website ? "Website \u00b7 hold to remove" : e.key);
            ((Switch) row.findViewById(R.id.swBlock)).setChecked(
                    e.website ? blockedSites.contains(e.key) : blockedApps.contains(e.key));
            return row;
        }
    }
}
