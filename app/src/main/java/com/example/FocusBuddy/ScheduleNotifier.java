package com.example.FocusBuddy;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Date;

/**
 * Heads-up notifications for scheduled sessions: 10, 5 and 1 minute before a window opens, and once
 * when it opens. One alarm is set at a time, for the next notification due; when it fires,
 * ScheduleNoticeReceiver posts the notification and sets the alarm for the one after. Nothing polls.
 */
public final class ScheduleNotifier {
    private ScheduleNotifier() {}

    /** Minutes before a window opens; 0 is the moment it opens. */
    static final int[] MINUTES_BEFORE = {10, 5, 1, 0};

    private static final String CHANNEL_ID = "schedule_warnings";
    private static final int NOTIFICATION_ID = 2001; // one id: each notice replaces the previous one
    private static final int ALARM_REQUEST = 2002;

    /** Sets the alarm for the next notification due (or removes it if nothing is scheduled). */
    public static void arm(Context c) {
        arm(c, System.currentTimeMillis());
    }

    /** Same, ignoring notifications due at or before {@code after}. */
    static void arm(Context c, long after) {
        Context app = c.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        long[] next = Permissions.noticesEnabled(app)
                ? ScheduleStore.nextNotice(app, after, MINUTES_BEFORE)
                : null; // switched off in Settings: nothing to arm

        Intent intent = new Intent(app, ScheduleNoticeReceiver.class);
        if (next == null) {
            if (!Permissions.noticesEnabled(app)) {
                // Also take down a warning that is still showing
                ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE))
                        .cancel(NOTIFICATION_ID);
            }
            am.cancel(PendingIntent.getBroadcast(app, ALARM_REQUEST, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            return;
        }
        intent.putExtra(ScheduleNoticeReceiver.EXTRA_FIRE_AT, next[0]);
        intent.putExtra(ScheduleNoticeReceiver.EXTRA_START_AT, next[1]);
        intent.putExtra(ScheduleNoticeReceiver.EXTRA_MINUTES, (int) next[2]);
        PendingIntent pi = PendingIntent.getBroadcast(app, ALARM_REQUEST, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Exact alarms need the Settings switch on and a permission the user can revoke (Android 12+).
        // Without them the notice still arrives, but Android may delay it by a few minutes.
        try {
            if (Permissions.exactAlarmsEnabled(app)
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
                return;
            }
        } catch (SecurityException ignored) {
            // permission was just revoked: use the inexact alarm below
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
    }

    static void show(Context c, long startAt, int minutesBefore) {
        if (!Permissions.noticesEnabled(c)) return; // switched off while this alarm was pending
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Scheduled session warnings", NotificationManager.IMPORTANCE_HIGH));

        String title;
        String text;
        if (minutesBefore > 0) {
            title = "Focus session starts in " + minutesBefore + (minutesBefore == 1 ? " minute" : " minutes");
            text = "Blocking begins at " + clock(startAt) + ".";
        } else {
            title = "Focus session started";
            long release = SessionStore.scheduledReleaseMs(c);
            text = release > 0L ? "Blocked until " + clock(release) + "." : "Blocking is now on.";
        }

        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_frog)
                .setLargeIcon(appIcon(c))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true);
        if (minutesBefore > 0) {
            // A warning is pointless once the session has started
            b.setTimeoutAfter(Math.max(1000L, startAt - System.currentTimeMillis()));
        }
        nm.notify(NOTIFICATION_ID, b.build());
    }

    /** The app's launcher icon as a bitmap, for the full-colour icon shown in the notification. */
    private static Bitmap appIcon(Context c) {
        Drawable d = c.getDrawable(R.mipmap.ic_launcher);
        if (d == null) return null;
        int size = (int) (64 * c.getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bmp));
        return bmp;
    }

    private static String clock(long ms) {
        return DateFormat.format("h:mm a", new Date(ms)).toString();
    }
}
