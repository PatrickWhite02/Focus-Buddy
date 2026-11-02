package com.example.FocusBuddy;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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

/**
 * Lists the apps the user has installed (plus web browsers) with a toggle each.
 * Toggling adds/removes the app from the blocklist. Not available during a session.
 */
public class AppPickerActivity extends Activity {

    private static final class AppEntry {
        final String pkg;
        final String label;
        final Drawable icon;

        AppEntry(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private final List<AppEntry> apps = new ArrayList<>();
    private final Set<String> blocked = new HashSet<>();
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
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e = apps.get(position);
            boolean nowBlocked = !blocked.contains(e.pkg);
            if (nowBlocked) blocked.add(e.pkg); else blocked.remove(e.pkg);
            BlocklistStore.setBlocked(this, e.pkg, nowBlocked);
            adapter.notifyDataSetChanged();
            updateCount();
        });

        findViewById(R.id.btnDone).setOnClickListener(v -> finish());

        blocked.addAll(BlocklistStore.get(this));
        updateCount();

        // Loading labels and icons for every app can take a moment, so keep it off the UI thread.
        new Thread(() -> {
            final List<AppEntry> loaded = loadApps();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                apps.clear();
                apps.addAll(loaded);
                if (apps.isEmpty()) {
                    tvEmpty.setText("No apps found");
                } else {
                    // Forget blocked apps that were uninstalled, so the count matches what's listed
                    Set<String> visible = new HashSet<>();
                    for (AppEntry e : apps) visible.add(e.pkg);
                    BlocklistStore.retainOnly(this, visible);
                    blocked.retainAll(visible);
                }
                adapter.notifyDataSetChanged();
                updateCount();
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

    private void updateCount() {
        int n = blocked.size();
        tvCount.setText(n == 0 ? "Nothing selected yet" : n + (n == 1 ? " app" : " apps") + " will be blocked");
    }

    // ---------- Finding the apps ----------

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ApplicationInfo> found = new LinkedHashMap<>();

        // Anything with a launcher icon that the user installed (or updated, e.g. YouTube on most phones)
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (isUserApp(ai)) found.put(ai.packageName, ai);
        }

        // Web browsers, even when they're preinstalled system apps (Chrome, Samsung Internet...)
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        for (ResolveInfo ri : pm.queryIntentActivities(web, PackageManager.MATCH_ALL)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            found.put(ai.packageName, ai);
        }

        // Never offer ourselves, or the home screen app: blocking it would trap the user in a
        // loop, because a blocked app is closed by sending them to the home screen.
        found.remove(getPackageName());
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            found.remove(ri.activityInfo.packageName);
        }

        List<AppEntry> out = new ArrayList<>();
        for (ApplicationInfo ai : found.values()) {
            out.add(new AppEntry(ai.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm)));
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
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
            return apps.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return apps.get(position);
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
            AppEntry e = apps.get(position);
            ((ImageView) row.findViewById(R.id.ivIcon)).setImageDrawable(e.icon);
            ((TextView) row.findViewById(R.id.tvAppName)).setText(e.label);
            ((TextView) row.findViewById(R.id.tvAppPackage)).setText(e.pkg);
            ((Switch) row.findViewById(R.id.swBlock)).setChecked(blocked.contains(e.pkg));
            return row;
        }
    }
}
