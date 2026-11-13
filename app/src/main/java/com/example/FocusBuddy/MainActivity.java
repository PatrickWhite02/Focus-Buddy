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
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TimePicker;
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
    private Button btnChooseApps, btnSchedule, btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvNextBlock = findViewById(R.id.tvNextBlock);
        btnChooseApps = findViewById(R.id.btnChooseApps);
        btnSchedule = findViewById(R.id.btnSchedule);
        btnStart = findViewById(R.id.btnStart);

        btnChooseApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        btnSchedule.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        btnStart.setOnClickListener(v -> showStartDialog());

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
        return false;
    }

    private void refresh() {
        int blockedCount = BlocklistStore.count(this);
        btnChooseApps.setText(blockedCount == 0
                ? "Choose what to block"
                : "Choose what to block (" + blockedCount + " selected)");
        // The session can't start until at least one app is chosen
        btnStart.setEnabled(blockedCount > 0);
        btnStart.setAlpha(blockedCount > 0 ? 1f : 0.5f);
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

    /**
     * One dialog for choosing how long the block lasts: a clock for "until this time" and
     * hours/minutes boxes for "for this long". They are the same thing said two ways, so
     * changing one updates the other.
     */
    private void showStartDialog() {
        if (BlocklistStore.count(this) == 0) {
            Toast.makeText(this, "Choose at least one app or website to block first", Toast.LENGTH_SHORT).show();
            return;
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_start_session, null);
        final TimePicker clock = content.findViewById(R.id.timePicker);
        final EditText hours = content.findViewById(R.id.etHours);
        final EditText minutes = content.findViewById(R.id.etMinutes);
        final CheckBox indefinite = content.findViewById(R.id.cbIndefinite);
        final View timeControls = content.findViewById(R.id.timeControls);
        final View timeBlocker = content.findViewById(R.id.timeBlocker);
        final TextView note = content.findViewById(R.id.tvNote);
        clock.setIs24HourView(false); // 12-hour clock with an AM/PM selector

        // Default: 3 hours from now, rounded up to the next quarter hour
        Calendar def = Calendar.getInstance();
        def.add(Calendar.HOUR_OF_DAY, 3);
        def.add(Calendar.MINUTE, 15 - def.get(Calendar.MINUTE) % 15);
        clock.setHour(def.get(Calendar.HOUR_OF_DAY));
        clock.setMinute(def.get(Calendar.MINUTE));

        // Which control was touched last. The clock means "end at exactly this time"; the boxes
        // mean "run for this long from the moment Start is pressed".
        final boolean[] byDuration = {false};
        // Set while one control is being updated from the other, so they don't trigger each other
        final boolean[] syncing = {false};

        Runnable clockToBoxes = () -> {
            long total = minutesUntil(clock.getHour(), clock.getMinute());
            syncing[0] = true;
            hours.setText(String.valueOf(total / 60));
            minutes.setText(String.valueOf(total % 60));
            syncing[0] = false;
        };

        clock.setOnTimeChangedListener((view, hour, minute) -> {
            if (syncing[0]) return;
            byDuration[0] = false;
            clockToBoxes.run();
        });

        TextWatcher boxesToClock = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (syncing[0]) return;
                byDuration[0] = true;
                long total = totalMinutes(hours, minutes);
                if (total < 1 || total > MAX_MINUTES) return; // leave the clock alone until it's valid
                Calendar end = Calendar.getInstance();
                end.add(Calendar.MINUTE, (int) total);
                syncing[0] = true;
                clock.setHour(end.get(Calendar.HOUR_OF_DAY));
                clock.setMinute(end.get(Calendar.MINUTE));
                syncing[0] = false;
            }
        };
        hours.addTextChangedListener(boxesToClock);
        minutes.addTextChangedListener(boxesToClock);

        clockToBoxes.run(); // fill the boxes to match the default time

        // "Block indefinitely": no timer at all, so grey out the clock and boxes
        final String timedNote = "The only way to end a session early is to scan the unlock QR code.";
        final String indefiniteNote = "Your blocked apps and websites stay blocked until you scan the "
                + "unlock QR code, so make sure you can get to it first.";
        note.setText(timedNote);
        indefinite.setOnCheckedChangeListener((box, checked) -> {
            timeControls.setAlpha(checked ? 0.4f : 1f);
            timeBlocker.setVisibility(checked ? View.VISIBLE : View.GONE);
            hours.setEnabled(!checked);
            minutes.setEnabled(!checked);
            note.setText(checked ? indefiniteNote : timedNote);
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Start focus session")
                .setView(content)
                .setPositiveButton("Start", null) // set below so a bad value keeps the dialog open
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (indefinite.isChecked()) {
                dialog.dismiss();
                SessionStore.startUntilScan(this);
                routeIfNeeded();
                return;
            }
            long until;
            if (byDuration[0]) {
                long total = totalMinutes(hours, minutes);
                if (total < 1 || total > MAX_MINUTES) {
                    Toast.makeText(this, "Enter a time between 1 minute and 24 hours", Toast.LENGTH_SHORT).show();
                    return;
                }
                // The end time is set now, at the moment the session starts
                until = System.currentTimeMillis() + total * 60_000L;
            } else {
                until = nextOccurrenceMs(clock.getHour(), clock.getMinute());
            }
            dialog.dismiss();
            SessionStore.startUntil(this, until);
            routeIfNeeded();
        });
    }

    /** A block can last up to 24 hours. */
    private static final long MAX_MINUTES = 24 * 60;

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

    /** Hours and minutes boxes as one length in minutes, or -1 if either can't be read. */
    private static long totalMinutes(EditText hours, EditText minutes) {
        long h = wholeNumber(hours.getText().toString());
        long m = wholeNumber(minutes.getText().toString());
        return h < 0 || m < 0 ? -1 : h * 60 + m;
    }

    /** The next moment the clock reads the given time (tomorrow if it has already passed today). */
    private static long nextOccurrenceMs(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /** Whole minutes from now until the next time the clock reads the given time. */
    private static long minutesUntil(int hour, int minute) {
        long now = System.currentTimeMillis();
        long startOfThisMinute = now - now % 60_000L;
        return Math.max(1L, Math.round((nextOccurrenceMs(hour, minute) - startOfThisMinute) / 60_000.0));
    }
}
