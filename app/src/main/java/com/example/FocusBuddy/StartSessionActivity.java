package com.example.FocusBuddy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.Calendar;

/**
 * The screen for choosing how long a block lasts: a clock for "until this time" and hours/minutes
 * boxes for "for this long" (the same thing said two ways, so changing one updates the other), or
 * "Block indefinitely" for a block that only the unlock QR code ends.
 */
public class StartSessionActivity extends Activity {

    /** A block can last up to 24 hours. */
    private static final long MAX_MINUTES = 24 * 60;

    /** A one-off session lasts this long unless the person changes it. */
    private static final int DEFAULT_MINUTES = 60;

    private TimePicker clock;
    private EditText hours, minutes;

    // Which control was touched last. The clock means "end at exactly this time"; the boxes
    // mean "run for this long from the moment Start is pressed".
    private boolean byDuration = false;
    // Set while one control is being updated from the other, so they don't trigger each other
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_session);

        clock = findViewById(R.id.timePicker);
        hours = findViewById(R.id.etHours);
        minutes = findViewById(R.id.etMinutes);
        final CheckBox indefinite = findViewById(R.id.cbIndefinite);
        final View timeControls = findViewById(R.id.timeControls);
        final View timeBlocker = findViewById(R.id.timeBlocker);
        final TextView note = findViewById(R.id.tvNote);

        clock.setIs24HourView(false); // 12-hour clock with an AM/PM selector

        // Default: a one-hour session
        Calendar def = Calendar.getInstance();
        def.add(Calendar.MINUTE, DEFAULT_MINUTES);
        clock.setHour(def.get(Calendar.HOUR_OF_DAY));
        clock.setMinute(def.get(Calendar.MINUTE));

        clock.setOnTimeChangedListener((view, hour, minute) -> {
            if (syncing) return;
            byDuration = false;
            clockToBoxes();
        });

        TextWatcher boxesToClock = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (syncing) return;
                byDuration = true;
                long total = totalMinutes();
                if (total < 1 || total > MAX_MINUTES) return; // leave the clock alone until it's valid
                Calendar end = Calendar.getInstance();
                end.add(Calendar.MINUTE, (int) total);
                syncing = true;
                clock.setHour(end.get(Calendar.HOUR_OF_DAY));
                clock.setMinute(end.get(Calendar.MINUTE));
                syncing = false;
            }
        };
        hours.addTextChangedListener(boxesToClock);
        minutes.addTextChangedListener(boxesToClock);

        // Fill the boxes with the default length, and count from the moment Start is tapped
        syncing = true;
        hours.setText(String.valueOf(DEFAULT_MINUTES / 60));
        minutes.setText(String.valueOf(DEFAULT_MINUTES % 60));
        syncing = false;
        byDuration = true;

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

        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnStartSession).setOnClickListener(v -> onStartClicked(indefinite.isChecked()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A session may have begun (a scheduled one), or the blocklist been emptied: nothing to start here then
        if (SessionStore.isActive(this)) {
            startActivity(new Intent(this, ActiveBlockActivity.class));
            finish();
        } else if (BlocklistStore.count(this) == 0) {
            finish();
        }
    }

    private void onStartClicked(boolean indefinite) {
        if (indefinite) {
            SessionStore.startUntilScan(this);
        } else if (byDuration) {
            long total = totalMinutes();
            if (total < 1 || total > MAX_MINUTES) {
                Toast.makeText(this, "Enter a time between 1 minute and 24 hours", Toast.LENGTH_SHORT).show();
                return;
            }
            // The end time is set now, at the moment the session starts
            SessionStore.startUntil(this, System.currentTimeMillis() + total * 60_000L);
        } else {
            SessionStore.startUntil(this, nextOccurrenceMs(clock.getHour(), clock.getMinute()));
        }
        startActivity(new Intent(this, ActiveBlockActivity.class));
        finish();
    }

    private void clockToBoxes() {
        long total = minutesUntil(clock.getHour(), clock.getMinute());
        syncing = true;
        hours.setText(String.valueOf(total / 60));
        minutes.setText(String.valueOf(total % 60));
        syncing = false;
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

    /** Hours and minutes boxes as one length in minutes, or -1 if either can't be read. */
    private long totalMinutes() {
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
