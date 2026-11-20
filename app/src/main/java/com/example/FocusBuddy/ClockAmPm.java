package com.example.FocusBuddy;

import android.widget.TimePicker;

import java.util.function.BooleanSupplier;

/**
 * Makes winding the clock's hour hand past 12 flip AM/PM, like a real clock, so 9 AM can be wound
 * straight round to 4 PM. Android's own clock never does this: past 12 it just carries on in the same half.
 */
final class ClockAmPm {
    private ClockAmPm() {}

    /** For a clock nobody else listens to. */
    static void attach(TimePicker picker) {
        attach(picker, () -> false, null);
    }

    /**
     * Hooks a 12-hour TimePicker (does nothing for a 24-hour one, which has no AM/PM to flip).
     * This takes over the picker's time-changed listener, so whatever the caller wanted to hear is
     * passed as {@code next} and still gets every change, after the flip.
     *
     * @param programmatic true while the caller is setting the clock from code, so a jump such as
     *                     11:30 AM to 12:30 PM is left alone instead of being flipped
     * @param next         the caller's own listener, or null
     */
    static void attach(final TimePicker picker, final BooleanSupplier programmatic,
                       final TimePicker.OnTimeChangedListener next) {
        if (picker == null) return;
        if (picker.is24HourView()) {
            picker.setOnTimeChangedListener(next);
            return;
        }
        final int[] last = { picker.getHour() };
        final boolean[] adjusting = { false };

        picker.setOnTimeChangedListener((view, hourOfDay, minute) -> {
            if (adjusting[0]) return;
            int hour = hourOfDay;
            if (!programmatic.getAsBoolean()) {
                // 11 <-> 12 is where a real clock changes between AM and PM (11 AM -> 12 PM -> 1 PM,
                // 11 PM -> 12 AM -> 1 AM), in either direction
                final int before = to12(last[0]);
                final int now = to12(hour);
                if ((before == 11 && now == 12) || (before == 12 && now == 11)) {
                    hour = (hour + 12) % 24;
                    adjusting[0] = true;
                    try {
                        view.setHour(hour);
                    } finally {
                        adjusting[0] = false;
                    }
                }
            }
            last[0] = hour;
            if (next != null) next.onTimeChanged(view, hour, minute);
        });
    }

    private static int to12(int hourOfDay) {
        final int h = hourOfDay % 12;
        return h == 0 ? 12 : h;
    }
}
