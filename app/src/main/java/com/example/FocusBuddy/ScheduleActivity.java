package com.example.FocusBuddy;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.List;

/**
 * An "Everyday" banner followed by one banner per day of the week. The + opens a start/end time prompt; each window then shows under
 * its day with a trash can to remove it (no confirmation). Also editable during a session, where
 * changes never touch the session running now: they apply from the next time a window comes around.
 */
public class ScheduleActivity extends Activity {

    private LinearLayout daysContainer;
    private TextView tvHint, tvSessionNote;
    private PermissionPrompts prompts;
    /** The notification question is asked at most once per visit. */
    private boolean permissionsChecked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);
        prompts = new PermissionPrompts(this);

        daysContainer = findViewById(R.id.daysContainer);
        tvHint = findViewById(R.id.tvHint);
        tvSessionNote = findViewById(R.id.tvSessionNote);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvHint.setVisibility(BlocklistStore.count(this) == 0 ? View.VISIBLE : View.GONE);
        renderDays();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        prompts.onResult(requestCode);
    }

    // ---------- Days and their windows ----------

    private void renderDays() {
        tvSessionNote.setVisibility(SessionStore.isActive(this) ? View.VISIBLE : View.GONE);
        daysContainer.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        String[] names = DateFormatSymbols.getInstance().getWeekdays(); // indexed by Calendar.SUNDAY..SATURDAY
        int first = Calendar.getInstance().getFirstDayOfWeek();

        // Windows on this card repeat every day; they're kept apart from the individual days' windows
        addDayCard(inflater, "Everyday", ScheduleStore.EVERYDAY);

        for (int i = 0; i < 7; i++) {
            int day = ((first - 1 + i) % 7) + 1;
            addDayCard(inflater, names[day], day);
        }
    }

    private void addDayCard(LayoutInflater inflater, String name, final int day) {
        View card = inflater.inflate(R.layout.item_schedule_day, daysContainer, false);
        TextView tvDayName = card.findViewById(R.id.tvDayName);
        tvDayName.setText(name);
        if (day == ScheduleStore.EVERYDAY) {
            // Bigger title and roomier padding so the Everyday card reads as different from the days
            tvDayName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            card.setPadding(dp(28), dp(12), dp(12), dp(16));
            // A clearly bigger gap before the first day card (the others use the layout's 12dp)
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.bottomMargin = dp(36);
            card.setLayoutParams(lp);
        }
        card.findViewById(R.id.btnAddWindow).setOnClickListener(v -> onAddWindowClicked(day));

        LinearLayout windows = card.findViewById(R.id.windowsContainer);
        List<ScheduleStore.Window> forDay = ScheduleStore.forDay(this, day);
        for (final ScheduleStore.Window w : forDay) {
            View row = inflater.inflate(R.layout.item_schedule_window, windows, false);
            ((TextView) row.findViewById(R.id.tvWindow)).setText(describe(w));
            row.findViewById(R.id.btnRemoveWindow).setOnClickListener(v -> {
                ScheduleStore.remove(this, w);
                renderDays();
            });
            windows.addView(row);
        }
        daysContainer.addView(card);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
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

    /** Before a window is added: notifications (the warnings). */
    private void onAddWindowClicked(final int day) {
        if (permissionsChecked) {
            promptForWindow(day);
            return;
        }
        prompts.run(true, () -> {
            permissionsChecked = true;
            promptForWindow(day);
        });
    }

    /** Asks for a start time, then an end time. Cancelling either one adds nothing. */
    private void promptForWindow(final int day) {
        final boolean use24h = DateFormat.is24HourFormat(this);

        TimePickerDialog startDialog = new TimePickerDialog(this, R.style.FocusTimePickerDialog, (view, startHour, startMinute) -> {
            final int startMin = startHour * 60 + startMinute;
            int suggestedEnd = (startMin + 60) % (24 * 60);

            TimePickerDialog endDialog = new TimePickerDialog(this, R.style.FocusTimePickerDialog, (v, endHour, endMinute) -> {
                int endMin = endHour * 60 + endMinute;
                if (endMin == startMin) {
                    Toast.makeText(this, "The end time has to be different from the start time",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
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
