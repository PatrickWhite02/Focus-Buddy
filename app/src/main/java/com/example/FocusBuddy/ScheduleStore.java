package com.example.FocusBuddy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Weekly blocking windows ("Mondays 9:00-12:00"). Nothing is scheduled with the system: a session
 * counts as active whenever the clock is inside a window, which SessionStore checks on demand. That
 * means windows keep working after a reboot and need no alarm permissions.
 */
public final class ScheduleStore {
    private static final String PREFS = "focus_schedule";
    private static final String KEY_WINDOWS = "windows";
    private static final String KEY_SKIP_UNTIL = "skip_until";

    private ScheduleStore() {}

    /** One window on one day of the week. */
    public static final class Window {
        public final int day;       // Calendar.SUNDAY .. Calendar.SATURDAY
        public final int startMin;  // minutes after midnight
        public final int endMin;    // an end at or before the start means it runs past midnight

        public Window(int day, int startMin, int endMin) {
            this.day = day;
            this.startMin = startMin;
            this.endMin = endMin;
        }

        public boolean overnight() {
            return endMin <= startMin;
        }

        String encode() {
            return day + "," + startMin + "," + endMin;
        }

        static Window decode(String s) {
            try {
                String[] p = s.split(",");
                return new Window(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> raw(Context c) {
        return prefs(c).getStringSet(KEY_WINDOWS, Collections.<String>emptySet());
    }

    /** The windows on one day, earliest first. */
    public static List<Window> forDay(Context c, int day) {
        List<Window> out = new ArrayList<>();
        for (String s : raw(c)) {
            Window w = Window.decode(s);
            if (w != null && w.day == day) out.add(w);
        }
        Collections.sort(out, new Comparator<Window>() {
            @Override
            public int compare(Window a, Window b) {
                return a.startMin != b.startMin ? a.startMin - b.startMin : a.endMin - b.endMin;
            }
        });
        return out;
    }

    public static void add(Context c, Window w) {
        Set<String> next = new HashSet<>(raw(c));
        next.add(w.encode());
        prefs(c).edit().putStringSet(KEY_WINDOWS, next).apply();
    }

    public static void remove(Context c, Window w) {
        Set<String> next = new HashSet<>(raw(c));
        next.remove(w.encode());
        prefs(c).edit().putStringSet(KEY_WINDOWS, next).apply();
    }

    /**
     * If a window is open at {@code now}, the time (ms) when the last-ending open window finishes;
     * otherwise 0. Windows the user already ended with the QR code are ignored.
     */
    public static long activeUntil(Context c, long now) {
        Set<String> all = raw(c);
        if (all.isEmpty()) return 0L; // the common case, and this runs on every accessibility event
        long skipUntil = prefs(c).getLong(KEY_SKIP_UNTIL, 0L);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int today = cal.get(Calendar.DAY_OF_WEEK);
        int yesterday = today == Calendar.SUNDAY ? Calendar.SATURDAY : today - 1;

        long best = 0L;
        for (String s : all) {
            Window w = Window.decode(s);
            if (w == null) continue;
            if (w.day == today) {
                best = Math.max(best, endIfOpen(cal, w, 0, now, skipUntil));
            }
            if (w.day == yesterday && w.overnight()) {
                best = Math.max(best, endIfOpen(cal, w, 1, now, skipUntil));
            }
        }
        return best;
    }

    private static long endIfOpen(Calendar nowCal, Window w, int daysBack, long now, long skipUntil) {
        Calendar start = (Calendar) nowCal.clone();
        start.add(Calendar.DAY_OF_YEAR, -daysBack);
        start.set(Calendar.HOUR_OF_DAY, w.startMin / 60);
        start.set(Calendar.MINUTE, w.startMin % 60);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.set(Calendar.HOUR_OF_DAY, w.endMin / 60);
        end.set(Calendar.MINUTE, w.endMin % 60);
        if (w.overnight()) end.add(Calendar.DAY_OF_YEAR, 1);

        long s = start.getTimeInMillis();
        long e = end.getTimeInMillis();
        return (now >= s && now < e && e > skipUntil) ? e : 0L;
    }

    /** When the next window opens after {@code now} (ms), or 0 if nothing is scheduled. */
    public static long nextStartAfter(Context c, long now) {
        Set<String> all = raw(c);
        if (all.isEmpty()) return 0L;

        long best = 0L;
        for (String s : all) {
            Window w = Window.decode(s);
            if (w == null) continue;
            for (int ahead = 0; ahead <= 7; ahead++) { // 7 = the same weekday next week
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(now);
                cal.add(Calendar.DAY_OF_YEAR, ahead);
                if (cal.get(Calendar.DAY_OF_WEEK) != w.day) continue;
                cal.set(Calendar.HOUR_OF_DAY, w.startMin / 60);
                cal.set(Calendar.MINUTE, w.startMin % 60);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long t = cal.getTimeInMillis();
                if (t > now) {
                    if (best == 0L || t < best) best = t;
                    break;
                }
            }
        }
        return best;
    }

    /** Lets the service re-arm its timer when the schedule changes. Keep a strong reference to the listener. */
    public static void listen(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).registerOnSharedPreferenceChangeListener(l);
    }

    public static void stopListening(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(c).unregisterOnSharedPreferenceChangeListener(l);
    }

    /** Ends whatever scheduled window is open right now (used by the unlock QR code). Later windows still run. */
    public static void skipCurrent(Context c) {
        long until = activeUntil(c, System.currentTimeMillis());
        if (until > 0L) {
            prefs(c).edit().putLong(KEY_SKIP_UNTIL, until).commit();
        }
    }
}
