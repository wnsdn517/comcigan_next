package dev.rocky.comcitime;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class LiveNotifyService extends Service {

    // How long before the school day's first period starts Live Notify
    // switches from the generic "등교 전이거나 하교 후" message to already
    // showing the first class, same as it does for a between-classes break.
    private static final int PRE_SCHOOL_NOTICE_MINUTES = 30;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Timetable cached;
    private String cachedForDate = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 60_000); // refresh every minute
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler.post(tick);
    }

    // Every Context.startForegroundService() call -- not just the one that
    // creates the service -- comes with its own promise to call
    // startForeground() before the system's timeout, even if the service
    // instance is already running and onCreate() already handled it once.
    // Skipping this in onStartCommand() is what caused
    // ForegroundServiceDidNotStartInTimeException on a second start
    // (e.g. re-toggling a notification setting while Live Notify was
    // already on). Calling it again here is safe/idempotent.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NotificationHelper.ID_LIVE, NotificationHelper.buildLive(this, "컴시간알리미", "불러오는 중..."));
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void refresh() {
        Prefs prefs = new Prefs(this);
        if (prefs.schoolCode().isEmpty()) {
            stopSelf();
            return;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new java.util.Date());
        if (cached == null || !cachedForDate.equals(today)) {
            TimetableRepository.fetch(this, "1", (tt, offline, err) -> {
                if (tt != null) {
                    cached = tt;
                    cachedForDate = today;
                    updateNotification(prefs, offline);
                }
            });
        } else {
            updateNotification(prefs, false);
        }
    }

    // Mirrors MainActivity's now-panel logic: distinguishes "in a class",
    // "on a break between classes" (with the next class + time remaining),
    // and "before/after school" instead of lumping breaks and off-hours
    // into one generic "쉬는시간이거나 수업 시간이 아닙니다" message.
    private void updateNotification(Prefs prefs, boolean offline) {
        if (cached == null) return;
        int dow = mondayBased(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
        if (dow == 0) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.notify(NotificationHelper.ID_LIVE, NotificationHelper.buildLive(this, "컴시간알리미", "오늘은 주말입니다."));
            return;
        }
        List<Timetable.PeriodEntry> today = cached.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        Timetable.PeriodEntry current = null;
        Timetable.PeriodEntry next = null;
        int currentEnd = -1;
        int nextStart = Integer.MAX_VALUE;
        Integer dayStart = null, dayEnd = null;
        for (Timetable.PeriodEntry e : today) {
            String[] parts = prefs.periodTime(e.period).split("-");
            if (parts.length != 2) continue;
            Integer start = toMinutes(parts[0]);
            Integer end = toMinutes(parts[1]);
            if (start == null || end == null) continue;
            dayStart = dayStart == null ? start : Math.min(dayStart, start);
            dayEnd = dayEnd == null ? end : Math.max(dayEnd, end);
            if (nowMinutes >= start && nowMinutes < end) {
                current = e;
                currentEnd = end;
            } else if (start > nowMinutes && start < nextStart) {
                nextStart = start;
                next = e;
            }
        }

        String title, text;
        if (current != null) {
            title = current.period + "교시 진행중 · " + formatRemaining(currentEnd - nowMinutes) + " 남음";
            text = current.subject + (current.teacher.isEmpty() ? "" : " (" + current.teacher + ")");
        } else if (dayStart != null && nowMinutes >= dayStart - PRE_SCHOOL_NOTICE_MINUTES && nowMinutes < dayStart && next != null) {
            title = "등교 전 · " + formatRemaining(nextStart - nowMinutes) + " 후 " + next.period + "교시";
            text = next.subject + (next.teacher.isEmpty() ? "" : " (" + next.teacher + ")");
        } else if (dayStart != null && nowMinutes >= dayStart && nowMinutes < dayEnd) {
            if (next != null) {
                title = "쉬는시간 · " + formatRemaining(nextStart - nowMinutes) + " 후 다음 수업";
                text = next.period + "교시 " + next.subject + (next.teacher.isEmpty() ? "" : " (" + next.teacher + ")");
            } else {
                title = "쉬는시간";
                text = "오늘 남은 수업이 없습니다.";
            }
        } else {
            title = "컴시간알리미";
            text = "지금은 등교 전이거나 하교 후입니다.";
        }
        if (offline) title = title + " (오프라인)";
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        nm.notify(NotificationHelper.ID_LIVE, NotificationHelper.buildLive(this, title, text));
    }

    private String formatRemaining(int minutes) {
        if (minutes < 0) minutes = 0;
        if (minutes < 60) return minutes + "분";
        return (minutes / 60) + "시간 " + (minutes % 60) + "분";
    }

    private Integer toMinutes(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return null; }
    }

    private int mondayBased(int calendarDow) {
        switch (calendarDow) {
            case Calendar.MONDAY: return 1;
            case Calendar.TUESDAY: return 2;
            case Calendar.WEDNESDAY: return 3;
            case Calendar.THURSDAY: return 4;
            case Calendar.FRIDAY: return 5;
            default: return 0;
        }
    }
}
