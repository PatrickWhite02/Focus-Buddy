package com.example.FocusBuddy;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Date;

/** Screen 2: the options (what to block, schedule, start a session). Only shown when ready and idle. */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!routeIfNeeded()) {
                ui.postDelayed(this, 1000);
            }
        }
    };

    private static final int REQ_NOTIFICATIONS = 1;
    /** The notification / exact-alarm questions are asked at most once per visit to this screen. */
    private boolean askedNoticePermissions = false;

    private TextView tvStatus, tvNextBlock, tvNoApps;
    private Button btnSchedule, btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvNextBlock = findViewById(R.id.tvNextBlock);
        tvNoApps = findViewById(R.id.tvNoApps);
        btnSchedule = findViewById(R.id.btnSchedule);
        btnStart = findViewById(R.id.btnStart);

        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnSchedule.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        btnStart.setOnClickListener(v -> onStartClicked());

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (routeIfNeeded()) return;
        refresh();
        ScheduleNotifier.arm(this); // also picks up an exact-alarm permission granted in Settings
        askNoticePermissionsIfNeeded();
        // Keep checking so a scheduled block that starts while this screen is open moves us along
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ---------- Permissions for the schedule warnings ----------

    /**
     * Asks for notifications the first time the app is opened (if that hasn't already happened on the
     * setup screen), and again whenever the user has a scheduled window, since that's what the
     * notifications are for. The exact-alarm question only matters once there is a schedule.
     */
    private void askNoticePermissionsIfNeeded() {
        if (!Permissions.noticesEnabled(this)) return; // switched off in Settings: don't nag
        boolean hasSchedule = ScheduleStore.nextStartAfter(this, System.currentTimeMillis()) != 0L;
        boolean firstOpen = Permissions.needsNotificationPrompt(this);
        if (askedNoticePermissions || (!hasSchedule && !firstOpen)) {
            return;
        }
        askedNoticePermissions = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Permissions.requestNotifications(this, REQ_NOTIFICATIONS);
            return; // the exact-alarm question follows the answer
        }
        if (hasSchedule) askExactAlarmIfNeeded();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && !isFinishing()
                && ScheduleStore.nextStartAfter(this, System.currentTimeMillis()) != 0L) {
            askExactAlarmIfNeeded();
        }
    }

    private void askExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am.canScheduleExactAlarms()) return;
        new AlertDialog.Builder(this)
                .setTitle("Allow exact alarms?")
                .setMessage("Without this, Android may deliver the warnings before a scheduled "
                        + "session a few minutes late.")
                .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Hands off to the right screen if this one isn't it. Returns true if we left. */
    private boolean routeIfNeeded() {
        if (SessionStore.isActive(this)) {
            startActivity(new Intent(this, ActiveBlockActivity.class));
            finish();
            return true;
        }
        if (!Permissions.allGranted(this)) {
            startActivity(new Intent(this, PermissionsActivity.class));
            finish();
            return true;
        }
        // First run: the settings screen comes once, after accessibility is on, before this screen
        if (!Permissions.setupDone(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return true;
        }
        return false;
    }

    private void refresh() {
        // Starting with nothing selected is allowed to be tapped: it explains what to do instead
        tvNoApps.setVisibility(BlocklistStore.count(this) == 0 ? View.VISIBLE : View.GONE);
        tvStatus.setText("No session running.");

        long next = ScheduleStore.nextStartAfter(this, System.currentTimeMillis());
        if (next > 0L) {
            tvNextBlock.setText("Next scheduled block: " + formatStart(next));
            tvNextBlock.setVisibility(View.VISIBLE);
        } else {
            tvNextBlock.setVisibility(View.GONE);
        }
    }

    /** "2:00 PM" if it's today, otherwise with the weekday ("Tue 2:00 PM"). */
    private String formatStart(long startMs) {
        Date start = new Date(startMs);
        String time = DateFormat.format("h:mm a", start).toString(); // always 12-hour with AM/PM
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(start);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? time : DateFormat.format("EEE", start) + " " + time;
    }

    // ---------- Starting a session ----------

    // ---------- Starting a session ----------

    private void onStartClicked() {
        if (BlocklistStore.count(this) == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Nothing to block yet")
                    .setMessage("You haven't chosen any apps or websites to block. Open Settings "
                            + "(the gear icon, top right) and choose what to block before "
                            + "starting a session.")
                    .setPositiveButton("Open settings",
                            (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        startActivity(new Intent(this, StartSessionActivity.class));
    }
}
