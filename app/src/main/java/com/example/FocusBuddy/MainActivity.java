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
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.graphics.Paint;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Screen 2: the options (what to block, schedule, start a session). Also the screen shown while a
 * session is running, where it says when the block ends and swaps "Start focus session" for
 * "Extend session" and adds the QR scan button.
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!routeIfNeeded()) {
                refresh(); // keeps the countdown moving, and notices a session starting or ending
                ui.postDelayed(this, 1000);
            }
        }
    };

    private static final int REQ_NOTIFICATIONS = 1;
    private PermissionPrompts prompts;
    /** The notification / exact-alarm questions are asked at most once per visit to this screen. */
    private boolean askedNoticePermissions = false;

    private TextView tvStatus, tvNextBlock, tvNoApps, tvCountdown, tvSessionEnds, tvHardcoreNote;
    private Button btnSchedule, btnStart, btnScan;
    private CheckBox cbNextEnabled;
    /** Start time (ms) of the upcoming scheduled block the checkbox is for. */
    private long shownNextStart = 0L;
    /** What's blocked, as a sentence fragment. Looked up when the screen opens, not every second. */
    private String blockedSummary = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prompts = new PermissionPrompts(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvNextBlock = findViewById(R.id.tvNextBlock);
        tvNoApps = findViewById(R.id.tvNoApps);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvSessionEnds = findViewById(R.id.tvSessionEnds);
        tvHardcoreNote = findViewById(R.id.tvHardcoreNote);
        btnSchedule = findViewById(R.id.btnSchedule);
        btnStart = findViewById(R.id.btnStart);
        btnScan = findViewById(R.id.btnScan);
        cbNextEnabled = findViewById(R.id.cbNextEnabled);

        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnSchedule.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        btnStart.setOnClickListener(v -> onStartClicked());
        btnScan.setOnClickListener(v -> scanToUnlock());
        // A click listener, not a checked-change one, so refresh() setting the box never triggers it
        cbNextEnabled.setOnClickListener(v -> {
            if (shownNextStart > 0L) {
                ScheduleStore.setOccurrenceDisabled(this, shownNextStart, !cbNextEnabled.isChecked());
            }
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (routeIfNeeded()) return;
        // The blocklist can grow during a session (from Settings), so look it up each time we're shown
        blockedSummary = TextUtils.join(", ", BlocklistStore.labels(this));
        refresh();
        ScheduleNotifier.arm(this); // also picks up an exact-alarm permission granted in Settings
        askNoticePermissionsIfNeeded();
        // Keep updating so a scheduled block that starts or ends while this screen is open shows up
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
        if (prompts.onResult(requestCode)) return;
        if (requestCode == REQ_NOTIFICATIONS && !isFinishing()
                && ScheduleStore.nextStartAfter(this, System.currentTimeMillis()) != 0L) {
            askExactAlarmIfNeeded();
        }
    }

    private void askExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (!Permissions.exactAlarmsEnabled(this)) return; // switched off in Settings
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
        // Also covers a running session whose accessibility service was turned off (a force stop
        // does that): nothing is being blocked, so the setup screen turns it back on
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
        renderSession();

        // The next occurrence whether or not it's switched off, so a switched-off one stays here to be switched back on
        long next = ScheduleStore.nextOccurrenceAfter(this, System.currentTimeMillis());
        shownNextStart = next;
        if (next > 0L) {
            boolean enabled = !ScheduleStore.isOccurrenceDisabled(this, next);
            tvNextBlock.setText("Next scheduled block: " + formatStart(next));
            tvNextBlock.setVisibility(View.VISIBLE);
            // Switched off: greyed out and crossed out
            tvNextBlock.setAlpha(enabled ? 1f : 0.4f);
            if (enabled) {
                tvNextBlock.setPaintFlags(tvNextBlock.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                tvNextBlock.setPaintFlags(tvNextBlock.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
            cbNextEnabled.setChecked(enabled);
            cbNextEnabled.setVisibility(View.VISIBLE);
        } else {
            tvNextBlock.setVisibility(View.GONE);
            cbNextEnabled.setVisibility(View.GONE);
        }
    }

    /** The running session's details and buttons, or the idle state. */
    private void renderSession() {
        boolean active = SessionStore.isActive(this);
        btnScan.setVisibility(active ? View.VISIBLE : View.GONE);
        tvCountdown.setVisibility(active ? View.VISIBLE : View.GONE);
        // Hardcore mode is "device admin is active"; during a session it's what blocks uninstalling
        tvHardcoreNote.setVisibility(active && Permissions.adminActive(this) ? View.VISIBLE : View.GONE);
        if (!active) {
            tvStatus.setText("No session running.");
            tvSessionEnds.setVisibility(View.GONE);
            btnStart.setText("Start focus session");
            btnStart.setEnabled(true);
            btnStart.setAlpha(1f);
            return;
        }

        tvStatus.setText("Session active. Blocked: " + blockedSummary);
        boolean untilScan = SessionStore.isUntilScan(this);
        // A session with no end time can't be made any longer
        btnStart.setText("Extend session");
        btnStart.setEnabled(!untilScan);
        btnStart.setAlpha(untilScan ? 0.4f : 1f);

        long release = SessionStore.scheduledReleaseMs(this);
        if (untilScan) {
            // No timer: say how it ends instead of counting down
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
            tvCountdown.setText("Blocked until you scan the QR code");
            tvSessionEnds.setVisibility(View.GONE);
        } else if (release > 0L) {
            // Scheduled block: say when it lifts rather than counting down
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
            tvCountdown.setText("Blocked until\n" + formatEnd(release));
            tvSessionEnds.setVisibility(View.GONE);
        } else {
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
            long s = SessionStore.remainingMs(this) / 1000;
            tvCountdown.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60));
            tvSessionEnds.setText("Ends at " + formatEnd(SessionStore.endMs(this)));
            tvSessionEnds.setVisibility(View.VISIBLE);
        }
    }

    /** "12:00 PM" if it's today, otherwise with the weekday ("Tue 2:00 AM"). */
    private String formatEnd(long endMs) {
        Date end = new Date(endMs);
        String time = DateFormat.getTimeFormat(this).format(end);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(end);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? time : DateFormat.format("EEE", end) + " " + time;
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

    // ---------- Starting or extending a session ----------

    private void onStartClicked() {
        if (SessionStore.isActive(this)) {
            // Same screen as starting one, but the end time can only be moved later
            startActivity(new Intent(this, StartSessionActivity.class)
                    .putExtra(StartSessionActivity.EXTRA_EXTEND, true));
            return;
        }
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
        // With hardcore mode on, the camera can't be turned on once the session is running (Settings
        // is locked), so this is the last chance to ask for it. Without hardcore mode nothing is asked.
        prompts.run(false, () -> startActivity(new Intent(this, StartSessionActivity.class)));
    }

    // ---------- Ending a session with the in-app scanner ----------
    // (Scanning the printed QR with any camera app also works; it fires the same focusbuddy:// intent.)

    private void scanToUnlock() {
        // The scanner needs the camera: ask for it (and offer Android's settings) if it isn't allowed
        prompts.scan(() -> new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan your unlock QR code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IntentIntegrator.REQUEST_CODE) return;
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) return; // cancelled, or camera denied
        boolean ok = SessionStore.unlockFromLink(this, result.getContents());
        Toast.makeText(this, ok ? "Session ended" : "Wrong QR code", Toast.LENGTH_LONG).show();
        refresh();
    }
}
