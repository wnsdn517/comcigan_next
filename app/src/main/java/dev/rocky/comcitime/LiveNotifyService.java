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

    private void updateNotification(Prefs prefs, boolean offline) {
        if (cached == null) return;
        int dow = mondayBased(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
        if (dow == 0) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.notify(NotificationHelper.ID_LIVE, NotificationHelper.buildLive(this, "컴시간알리미", "오늘은 주말입니다."));
            return;
        }
        List<Timetable.PeriodEntry> today = cached.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
        Timetable.PeriodEntry current = findCurrentPeriod(prefs, today);

        String title, text;
        if (current == null) {
            title = "컴시간알리미";
            text = "지금은 쉬는시간이거나 수업 시간이 아닙니다.";
        } else {
            title = current.period + "교시 진행중";
            text = current.subject + (current.teacher.isEmpty() ? "" : " (" + current.teacher + ")");
        }
        if (offline) title = title + " (오프라인)";
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        nm.notify(NotificationHelper.ID_LIVE, NotificationHelper.buildLive(this, title, text));
    }

    private Timetable.PeriodEntry findCurrentPeriod(Prefs prefs, List<Timetable.PeriodEntry> today) {
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (Timetable.PeriodEntry e : today) {
            String[] parts = prefs.periodTime(e.period).split("-");
            if (parts.length != 2) continue;
            Integer start = toMinutes(parts[0]);
            Integer end = toMinutes(parts[1]);
            if (start == null || end == null) continue;
            if (nowMinutes >= start && nowMinutes < end) return e;
        }
        return null;
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
