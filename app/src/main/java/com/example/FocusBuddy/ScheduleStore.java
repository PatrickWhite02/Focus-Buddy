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
 * Weekly blocking windows ("Mondays 9:00-12:00"). Blocking itself isn't scheduled with the system: a
 * session counts as active whenever the clock is inside a window, which SessionStore checks on
 * demand. That means windows keep working after a reboot and need no alarm permissions. (Only the
 * heads-up notifications before a window, see ScheduleNotifier, use an alarm.)
 */
public final class ScheduleStore {
    private static final String PREFS = "focus_schedule";
    private static final String KEY_WINDOWS = "windows";
    private static final String KEY_SKIP_UNTIL = "skip_until";
    private static final String KEY_DISABLED = "disabled_starts"; // start times (ms) of occurrences switched off

    /** Day value for the "Everyday" card: a window that repeats on every day of the week. */
    public static final int EVERYDAY = 0;

    private ScheduleStore() {}

    /** One window on one day of the week (or on every day, see EVERYDAY). */
    public static final class Window {
        public final int day;       // Calendar.SUNDAY .. Calendar.SATURDAY, or EVERYDAY
        public final int startMin;  // minutes after midnight
        public final int endMin;    // an end at or before the start means it runs past midnight
        /**
         * Occurrences that began before this moment (ms) don't count. 0 for a window that was
         * added with no session running; a window added mid-session gets the moment it was added,
         * so it first applies the next time it comes around and can't touch the session running now.
         */
        final long since;

        public Window(int day, int startMin, int endMin) {
            this(day, startMin, endMin, 0L);
        }

        Window(int day, int startMin, int endMin, long since) {
            this.day = day;
            this.startMin = startMin;
            this.endMin = endMin;
            this.since = since;
        }

        public boolean overnight() {
            return endMin <= startMin;
        }

        String encode() {
            return since == 0L
                    ? day + "," + startMin + "," + endMin
                    : day + "," + startMin + "," + endMin + "," + since;
        }

        static Window decode(String s) {
            try {
                String[] p = s.split(",");
                return new Window(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        p.length > 3 ? Long.parseLong(p[3]) : 0L);
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

    /**
     * Adds a window. Done while a session is running, it doesn't affect that session: the window
     * first applies the next time it comes around (next week for a day, tomorrow for Everyday).
     */
    public static void add(Context c, Window w) {
        Set<String> next = new HashSet<>(raw(c));
        for (String s : next) {
            Window e = Window.decode(s);
            if (e != null && e.day == w.day && e.startMin == w.startMin && e.endMin == w.endMin) {
                return; // already there
            }
        }
        long since = SessionStore.isActive(c) ? System.currentTimeMillis() : 0L;
        next.add(new Window(w.day, w.startMin, w.endMin, since).encode());
        prefs(c).edit().putStringSet(KEY_WINDOWS, next).apply();
    }

    /** Removes a window. A session running right now carries on to its end even if this was its window. */
    public static void remove(Context c, Window w) {
        if (SessionStore.isActive(c)) {
            SessionStore.pinCurrent(c);
        }
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
            if (w.day == today || w.day == EVERYDAY) {
                best = Math.max(best, endIfOpen(c, cal, w, 0, now, skipUntil));
            }
            if ((w.day == yesterday || w.day == EVERYDAY) && w.overnight()) {
                best = Math.max(best, endIfOpen(c, cal, w, 1, now, skipUntil));
            }
        }
        return best;
    }

    private static long endIfOpen(Context c, Calendar nowCal, Window w, int daysBack, long now, long skipUntil) {
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
        if (s < w.since) return 0L; // an occurrence that began before the window was added
        if (!(now >= s && now < e && e > skipUntil)) return 0L;
        return isOccurrenceDisabled(c, s) ? 0L : e; // only looked up for a window that's open right now
    }

    /** When the next window opens after {@code now} (ms), or 0 if nothing is scheduled. */
    public static long nextStartAfter(Context c, long now) {
        long best = 0L;
        for (String s : raw(c)) {
            Window w = Window.decode(s);
            if (w == null) continue;
            long t = nextEnabledStart(c, w, now);
            if (t > 0L && (best == 0L || t < best)) best = t;
        }
        return best;
    }

    /** The first time after {@code now} that this window opens, skipping occurrences switched off. */
    private static long nextEnabledStart(Context c, Window w, long now) {
        Set<Long> off = disabledStarts(c);
        long t = startAfter(w, now);
        for (int i = 0; i < 60 && t > 0L && off.contains(t); i++) {
            t = startAfter(w, t); // that one is switched off: the one after it
        }
        return t;
    }

    // ---------- Switching a single upcoming occurrence off, without touching the window itself ----------

    private static Set<Long> disabledStarts(Context c) {
        Set<Long> out = new HashSet<>();
        for (String s : prefs(c).getStringSet(KEY_DISABLED, Collections.<String>emptySet())) {
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // skip a damaged entry
            }
        }
        return out;
    }

    /** True if the occurrence of a window that starts at {@code startMs} has been switched off. */
    public static boolean isOccurrenceDisabled(Context c, long startMs) {
        return disabledStarts(c).contains(startMs);
    }

    /**
     * Switches one occurrence (identified by its start time) off or back on. The window itself is
     * untouched, so it still starts at its next occurrence. Two windows that open at exactly the
     * same moment are switched off together.
     */
    public static void setOccurrenceDisabled(Context c, long startMs, boolean disabled) {
        Set<String> next = new HashSet<>();
        long now = System.currentTimeMillis();
        for (long t : disabledStarts(c)) {
            if (t > now && t != startMs) next.add(String.valueOf(t)); // drops ones already in the past
        }
        if (disabled && startMs > now) next.add(String.valueOf(startMs));
        prefs(c).edit().putStringSet(KEY_DISABLED, next).apply();
    }

    /**
     * When the next window opens after {@code now} (ms), whether or not that occurrence has been
     * switched off, or 0 if nothing is scheduled. For showing it on the main screen.
     */
    public static long nextOccurrenceAfter(Context c, long now) {
        long best = 0L;
        for (String s : raw(c)) {
            Window w = Window.decode(s);
            if (w == null) continue;
            long t = startAfter(w, now);
            if (t > 0L && (best == 0L || t < best)) best = t;
        }
        return best;
    }

    /** The first time after {@code now} (ms) that this window opens, or 0. */
    private static long startAfter(Window w, long now) {
        for (int ahead = 0; ahead <= 7; ahead++) { // 7 = the same weekday next week
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            cal.add(Calendar.DAY_OF_YEAR, ahead);
            if (w.day != EVERYDAY && cal.get(Calendar.DAY_OF_WEEK) != w.day) continue;
            cal.set(Calendar.HOUR_OF_DAY, w.startMin / 60);
            cal.set(Calendar.MINUTE, w.startMin % 60);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long t = cal.getTimeInMillis();
            if (t > now) return t;
        }
        return 0L;
    }

    /**
     * The next heads-up to send after {@code now}: for every upcoming window start, one moment per
     * entry of {@code minutesBefore} (0 = the start itself). Returns {fireAt, windowStart,
     * minutesBefore} for the earliest one, or null if nothing is scheduled.
     */
    public static long[] nextNotice(Context c, long now, int[] minutesBefore) {
        long[] best = null;
        for (String s : raw(c)) {
            Window w = Window.decode(s);
            if (w == null) continue;
            long start = nextEnabledStart(c, w, now);
            if (start == 0L) continue;
            for (int m : minutesBefore) {
                long fire = start - m * 60_000L;
                if (fire > now && (best == null || fire < best[0])) {
                    best = new long[] {fire, start, m};
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
