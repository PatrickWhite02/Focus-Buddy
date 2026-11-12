package com.example.FocusBuddy;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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

    private TextView tvStatus, tvNextBlock;
    private Button btnChooseApps, btnSchedule, btnStart, btnUntilScan, btnBlockUntil, btnBlockHours;

    // The "block until" time of day. The block ends at the next time the clock reads this.
    private int untilHour, untilMinute;
    // The other way to choose: a length in minutes, counted from the moment the session starts.
    // 0 means the "block until" time above is in use. Picking either one replaces the other.
    private long durationMinutes = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvNextBlock = findViewById(R.id.tvNextBlock);
        btnBlockUntil = findViewById(R.id.btnBlockUntil);
        btnBlockHours = findViewById(R.id.btnBlockHours);
        btnChooseApps = findViewById(R.id.btnChooseApps);
        btnSchedule = findViewById(R.id.btnSchedule);
        btnStart = findViewById(R.id.btnStart);
        btnUntilScan = findViewById(R.id.btnUntilScan);

        btnChooseApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        btnSchedule.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        btnBlockUntil.setOnClickListener(v -> pickUntilTime());
        btnBlockHours.setOnClickListener(v -> pickHours());
        btnStart.setOnClickListener(v -> onStartClicked());

        // Default: about 3 hours from now, rounded up to the next quarter hour
        Calendar def = Calendar.getInstance();
        def.add(Calendar.HOUR_OF_DAY, 3);
        def.add(Calendar.MINUTE, 15 - def.get(Calendar.MINUTE) % 15);
        untilHour = def.get(Calendar.HOUR_OF_DAY);
        untilMinute = def.get(Calendar.MINUTE);
        btnUntilScan.setOnClickListener(v -> onUntilScanClicked());
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

    /** Only asks once the user has a scheduled window, since that's what the notifications are for. */
    private void askNoticePermissionsIfNeeded() {
        if (askedNoticePermissions
                || ScheduleStore.nextStartAfter(this, System.currentTimeMillis()) == 0L) {
            return;
        }
        askedNoticePermissions = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return; // the exact-alarm question follows the answer
        }
        askExactAlarmIfNeeded();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && !isFinishing()) askExactAlarmIfNeeded();
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
        return false;
    }

    private void refresh() {
        updateUntilLabel();
        int blockedCount = BlocklistStore.count(this);
        btnChooseApps.setText(blockedCount == 0
                ? "Choose what to block"
                : "Choose what to block (" + blockedCount + " selected)");
        // The session can't start until at least one app is chosen
        btnStart.setEnabled(blockedCount > 0);
        btnStart.setAlpha(blockedCount > 0 ? 1f : 0.5f);
        btnUntilScan.setEnabled(blockedCount > 0);
        btnUntilScan.setAlpha(blockedCount > 0 ? 1f : 0.5f);
        tvStatus.setText(blockedCount == 0
                ? "Choose what to block before you start a session."
                : "No session running.");

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

    private void pickUntilTime() {
        TimePickerDialog dialog = new TimePickerDialog(this, (view, hour, minute) -> {
            untilHour = hour;
            untilMinute = minute;
            durationMinutes = 0;
            updateUntilLabel();
        }, untilHour, untilMinute, false); // false = 12-hour clock with an AM/PM selector
        dialog.setTitle("Block until");
        dialog.show();
    }

    /**
     * The other way of choosing the end: a length in hours and minutes. Only the length is
     * remembered here; the end time is worked out when the session actually starts, so the full
     * length is always blocked.
     */
    private void pickHours() {
        final EditText hours = numberField("hours");
        final EditText minutes = numberField("minutes");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad / 3, pad, 0);
        row.addView(hours, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(minutes, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        new AlertDialog.Builder(this)
                .setTitle("Block for how long?")
                .setView(row)
                .setPositiveButton("Set", (d, w) -> setDuration(
                        wholeNumber(hours.getText().toString()),
                        wholeNumber(minutes.getText().toString())))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText numberField(String hint) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER); // whole numbers only
        field.setHint(hint);
        field.setGravity(Gravity.CENTER);
        return field;
    }

    /** A blank box counts as 0; anything unreadable (like a number too big to hold) is -1. */
    private static long wholeNumber(String text) {
        String t = text.trim();
        if (t.isEmpty()) return 0;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setDuration(long hours, long minutes) {
        long total = hours < 0 || minutes < 0 ? -1 : hours * 60 + minutes;
        // "Block until" is a time within the next 24 hours, so that's the limit here too
        if (total < 1 || total > 24 * 60) {
            Toast.makeText(this, "Enter a time between 1 minute and 24 hours", Toast.LENGTH_SHORT).show();
            return;
        }
        durationMinutes = total;
        updateUntilLabel();
    }

    private static String formatDuration(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        String hours = h + (h == 1 ? " hour" : " hours");
        String mins = m + (m == 1 ? " minute" : " minutes");
        return h == 0 ? mins : (m == 0 ? hours : hours + " " + mins);
    }

    /** The next moment the clock reads the chosen time (tomorrow if it has already passed today). */
    private long nextUntilMs() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, untilHour);
        c.set(Calendar.MINUTE, untilMinute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /** Shows the time, with the weekday when it falls on tomorrow (e.g. "Wed 6:30 AM"). */
    private void updateUntilLabel() {
        boolean byHours = durationMinutes > 0;
        btnBlockUntil.setText(formatStart(nextUntilMs()));
        btnBlockUntil.setAlpha(byHours ? 0.5f : 1f); // dimmed while the hours option is the one in use
        btnBlockHours.setText(byHours
                ? "Block for " + formatDuration(durationMinutes)
                : "Block for a number of hours");
    }

    private void onStartClicked() {
        if (BlocklistStore.count(this) == 0) {
            Toast.makeText(this, "Choose at least one app or website to block first", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean byHours = durationMinutes > 0;
        final long chosenUntil = nextUntilMs();

        new AlertDialog.Builder(this)
                .setTitle("Start focus session?")
                .setMessage("Blocked apps will be locked "
                        + (byHours ? "for " + formatDuration(durationMinutes)
                                : "until " + formatStart(chosenUntil))
                        + ". The only way to end it early is to scan the unlock QR code.")
                .setPositiveButton("Start", (d, w) -> {
                    // The end time is set now, at the moment the session starts
                    long until = byHours
                            ? System.currentTimeMillis() + durationMinutes * 60_000L
                            : chosenUntil;
                    SessionStore.startUntil(this, until);
                    routeIfNeeded();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onUntilScanClicked() {
        if (BlocklistStore.count(this) == 0) {
            Toast.makeText(this, "Choose at least one app or website to block first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Block until you scan the QR code?")
                .setMessage("This starts a block with no timer. Your blocked apps and websites stay "
                        + "blocked until you scan the unlock QR code, so make sure you can get to "
                        + "it first.")
                .setPositiveButton("Start block", (d, w) -> {
                    SessionStore.startUntilScan(this);
                    routeIfNeeded();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
