package com.example.FocusBuddy;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.List;

/**
 * One banner per day of the week. The + opens a start/end time prompt; each window then shows under
 * its day with a trash can to remove it (no confirmation). Not available during a session.
 */
public class ScheduleActivity extends Activity {

    private LinearLayout daysContainer;
    private TextView tvHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (leaveIfSessionActive()) return;
        setContentView(R.layout.activity_schedule);

        daysContainer = findViewById(R.id.daysContainer);
        tvHint = findViewById(R.id.tvHint);
        findViewById(R.id.btnDone).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (leaveIfSessionActive()) return;
        tvHint.setVisibility(BlocklistStore.count(this) == 0 ? View.VISIBLE : View.GONE);
        renderDays();
    }

    private boolean leaveIfSessionActive() {
        if (SessionStore.isActive(this)) {
            Toast.makeText(this, "You can't change the schedule during a session",
                    Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        return false;
    }

    // ---------- Days and their windows ----------

    private void renderDays() {
        daysContainer.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        String[] names = DateFormatSymbols.getInstance().getWeekdays(); // indexed by Calendar.SUNDAY..SATURDAY
        int first = Calendar.getInstance().getFirstDayOfWeek();

        for (int i = 0; i < 7; i++) {
            final int day = ((first - 1 + i) % 7) + 1;

            View card = inflater.inflate(R.layout.item_schedule_day, daysContainer, false);
            ((TextView) card.findViewById(R.id.tvDayName)).setText(names[day]);
            card.findViewById(R.id.btnAddWindow).setOnClickListener(v -> promptForWindow(day));

            LinearLayout windows = card.findViewById(R.id.windowsContainer);
            List<ScheduleStore.Window> forDay = ScheduleStore.forDay(this, day);
            for (final ScheduleStore.Window w : forDay) {
                View row = inflater.inflate(R.layout.item_schedule_window, windows, false);
                ((TextView) row.findViewById(R.id.tvWindow)).setText(describe(w));
                row.findViewById(R.id.btnRemoveWindow).setOnClickListener(v -> {
                    if (leaveIfSessionActive()) return;
                    ScheduleStore.remove(this, w);
                    renderDays();
                });
                windows.addView(row);
            }
            daysContainer.addView(card);
        }
    }

    private String describe(ScheduleStore.Window w) {
        return formatTime(w.startMin) + " \u2013 " + formatTime(w.endMin)
                + (w.overnight() ? " (next day)" : "");
    }

    private String formatTime(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        return DateFormat.getTimeFormat(this).format(c.getTime());
    }

    // ---------- Adding a window ----------

    /** Asks for a start time, then an end time. Cancelling either one adds nothing. */
    private void promptForWindow(final int day) {
        if (leaveIfSessionActive()) return;
        final boolean use24h = DateFormat.is24HourFormat(this);

        TimePickerDialog startDialog = new TimePickerDialog(this, (view, startHour, startMinute) -> {
            final int startMin = startHour * 60 + startMinute;
            int suggestedEnd = (startMin + 60) % (24 * 60);

            TimePickerDialog endDialog = new TimePickerDialog(this, (v, endHour, endMinute) -> {
                int endMin = endHour * 60 + endMinute;
                if (endMin == startMin) {
                    Toast.makeText(this, "The end time has to be different from the start time",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (leaveIfSessionActive()) return;
                ScheduleStore.add(this, new ScheduleStore.Window(day, startMin, endMin));
                renderDays();
            }, suggestedEnd / 60, suggestedEnd % 60, use24h);
            endDialog.setTitle("Blocking ends at");
            endDialog.show();
        }, 9, 0, use24h);
        startDialog.setTitle("Blocking starts at");
        startDialog.show();
    }
}
