package com.example.FocusBuddy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by the alarm ScheduleNotifier sets: posts the notification that is due, then arms the next. */
public class ScheduleNoticeReceiver extends BroadcastReceiver {
    static final String EXTRA_FIRE_AT = "fire_at";
    static final String EXTRA_START_AT = "start_at";
    static final String EXTRA_MINUTES = "minutes_before";

    @Override
    public void onReceive(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        long fireAt = intent.getLongExtra(EXTRA_FIRE_AT, 0L);
        long startAt = intent.getLongExtra(EXTRA_START_AT, 0L);
        int minutes = intent.getIntExtra(EXTRA_MINUTES, -1);

        if (startAt > 0L && minutes >= 0) {
            // A warning is skipped if it arrived too late to be a warning, or if a block is already
            // running (e.g. this window follows straight on from the previous one).
            boolean pointless = minutes > 0 && (now >= startAt || SessionStore.isActive(context));
            if (!pointless) ScheduleNotifier.show(context, startAt, minutes);
        }
        // Moving past fireAt guarantees the same notice can't be picked again if the alarm ran early
        ScheduleNotifier.arm(context, Math.max(now, fireAt));
    }
}
