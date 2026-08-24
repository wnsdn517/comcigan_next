package dev.rocky.comcitime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String type = intent.getStringExtra(NotificationScheduler.EXTRA_TYPE);
        if (type == null) return;
        Prefs prefs = new Prefs(context);
        if (prefs.schoolCode().isEmpty()) return; // not set up yet

        switch (type) {
            case NotificationScheduler.TYPE_MORNING:
                handleMorning(context, prefs);
                rearm(context, type, NotificationScheduler.reqCodeMorning(), 0, prefs.morningTime());
                break;
            case NotificationScheduler.TYPE_PERIOD:
                int period = intent.getIntExtra("period", 0);
                handlePeriod(context, prefs, period);
                if (period > 0) {
                    String range = prefs.periodTime(period);
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        rearm(context, type, NotificationScheduler.reqCodeForPeriod(period), period, parts[1].trim());
                    }
                }
                break;
            case NotificationScheduler.TYPE_CHANGE_CHECK:
                handleChangeCheck(context, prefs);
                rearm(context, type, NotificationScheduler.reqCodeChangeCheck(), 0, "06:30");
                break;
        }
    }

    private void rearm(Context ctx, String type, int reqCode, int periodExtra, String hhmm) {
        try {
            String[] hm = hhmm.split(":");
            NotificationScheduler.rearmSingle(ctx, type, reqCode, periodExtra,
                    Integer.parseInt(hm[0].trim()), Integer.parseInt(hm[1].trim()));
        } catch (Exception ignored) {}
    }

    private int todayDow() {
        int cal = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // 1=Sun..7=Sat
        // Map to app's convention: 1=Mon..5=Fri
        switch (cal) {
            case Calendar.MONDAY: return 1;
            case Calendar.TUESDAY: return 2;
            case Calendar.WEDNESDAY: return 3;
            case Calendar.THURSDAY: return 4;
            case Calendar.FRIDAY: return 5;
            default: return 0; // weekend
        }
    }

    private void handleMorning(Context ctx, Prefs prefs) {
        int dow = todayDow();
        if (dow == 0) return; // no school on weekends
        if (prefs.isTeacherMode()) {
            handleTeacherMorning(ctx, prefs, dow);
            return;
        }
        fetchToday(ctx, prefs, (tt, offline) -> {
            List<Timetable.PeriodEntry> today = tt.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
            if (today.isEmpty()) {
                NotificationHelper.show(ctx, NotificationHelper.ID_MORNING, NotificationHelper.CHANNEL_ALERTS,
                        "오늘의 시간표", "오늘은 등록된 수업이 없습니다.", true);
                return;
            }
            String todayDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(new java.util.Date());
            StringBuilder sb = new StringBuilder();
            for (Timetable.PeriodEntry e : today) {
                sb.append(e.period).append("교시 ").append(e.subject);
                if (!e.teacher.isEmpty()) sb.append(" (").append(e.teacher).append(")");
                if (e.changed) sb.append(" [변경]");
                Prefs.PersonalEvent ev = prefs.findPersonalEvent(todayDate, e.period);
                if (ev != null) sb.append(" \ud83d\udcdd ").append(ev.text);
                sb.append("\n");
            }
            if (offline) sb.append("\n(오프라인 데이터 -- 실제와 다를 수 있어요)");
            NotificationHelper.show(ctx, NotificationHelper.ID_MORNING, NotificationHelper.CHANNEL_ALERTS,
                    offline ? "오늘의 시간표 (오프라인)" : "오늘의 시간표", sb.toString().trim(), true);
        });
    }

    private void handleTeacherMorning(Context ctx, Prefs prefs, int dow) {
        fetchToday(ctx, prefs, (tt, offline) -> {
            List<Timetable.TeacherPeriodEntry> week = tt.getTeacherWeek(prefs.teacherName());
            List<Timetable.TeacherPeriodEntry> today = new ArrayList<>();
            for (Timetable.TeacherPeriodEntry e : week) if (e.dayOfWeek == dow) today.add(e);
            if (today.isEmpty()) {
                NotificationHelper.show(ctx, NotificationHelper.ID_MORNING, NotificationHelper.CHANNEL_ALERTS,
                        "오늘의 시간표 (교사)", "오늘은 수업이 없습니다.", true);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= 8; p++) {
                Timetable.TeacherPeriodEntry found = null;
                for (Timetable.TeacherPeriodEntry e : today) if (e.period == p) { found = e; break; }
                if (found != null) {
                    sb.append(p).append("교시 ").append(found.grade).append("학년 ").append(found.classNum).append("반 ").append(found.subject);
                    if (found.changed) sb.append(" [변경]");
                    sb.append("\n");
                }
            }
            NotificationHelper.show(ctx, NotificationHelper.ID_MORNING, NotificationHelper.CHANNEL_ALERTS,
                    offline ? "오늘의 시간표 (교사, 오프라인)" : "오늘의 시간표 (교사)", sb.toString().trim(), true);
        });
    }

    private void handlePeriod(Context ctx, Prefs prefs, int justEndedPeriod) {
        int dow = todayDow();
        if (dow == 0) return;
        if (prefs.isTeacherMode()) {
            handleTeacherPeriod(ctx, prefs, justEndedPeriod, dow);
            return;
        }
        fetchToday(ctx, prefs, (tt, offline) -> {
            List<Timetable.PeriodEntry> today = tt.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
            Timetable.PeriodEntry next = null;
            for (Timetable.PeriodEntry e : today) {
                if (e.period == justEndedPeriod + 1) { next = e; break; }
            }
            String title, text;
            if (next == null) {
                title = "오늘 수업 종료";
                text = justEndedPeriod + "교시가 마지막 수업이었습니다. 수고하셨습니다.";
            } else {
                title = (justEndedPeriod + 1) + "교시 알림";
                text = next.subject + (next.teacher.isEmpty() ? "" : " (" + next.teacher + ")");
                if (next.changed) text += " -- 시간표 변경됨";
            }
            String todayDate2 = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(new java.util.Date());
            Prefs.PersonalEvent ev = prefs.findPersonalEvent(todayDate2, justEndedPeriod + 1);
            if (ev != null) text += "\n\ud83d\udcdd 일정: " + ev.text;
            if (offline) { title += " (오프라인)"; text += "\n(오프라인 데이터 -- 실제와 다를 수 있어요)"; }
            NotificationHelper.show(ctx, NotificationHelper.ID_PERIOD, NotificationHelper.CHANNEL_ALERTS, title, text, true);
        });
    }

    private void handleTeacherPeriod(Context ctx, Prefs prefs, int justEndedPeriod, int dow) {
        fetchToday(ctx, prefs, (tt, offline) -> {
            List<Timetable.TeacherPeriodEntry> week = tt.getTeacherWeek(prefs.teacherName());
            Timetable.TeacherPeriodEntry next = null;
            for (Timetable.TeacherPeriodEntry e : week) {
                if (e.dayOfWeek == dow && e.period == justEndedPeriod + 1) { next = e; break; }
            }
            String title, text;
            if (next == null) {
                title = "수업 종료 (교사)";
                text = justEndedPeriod + "교시가 마지막 수업이었습니다. 수고하셨습니다.";
            } else {
                title = (justEndedPeriod + 1) + "교시 수업 안내";
                text = next.grade + "학년 " + next.classNum + "반 " + next.subject;
                if (next.changed) text += " -- 시간표 변경됨";
            }
            NotificationHelper.show(ctx, NotificationHelper.ID_PERIOD, NotificationHelper.CHANNEL_ALERTS, title, text, true);
        });
    }

    private void handleChangeCheck(Context ctx, Prefs prefs) {
        int dow = todayDow();
        if (dow == 0) return;
        fetchToday(ctx, prefs, (tt, offline) -> {
            if (offline) return; // can't detect changes from stale data
            List<Timetable.PeriodEntry> today = tt.getDaySchedule(prefs.grade(), prefs.classNum(), dow);
            StringBuilder changed = new StringBuilder();
            for (Timetable.PeriodEntry e : today) {
                if (e.changed) {
                    if (changed.length() > 0) changed.append(", ");
                    changed.append(e.period).append("교시(").append(e.subject).append(")");
                }
            }
            if (changed.length() > 0) {
                NotificationHelper.show(ctx, NotificationHelper.ID_CHANGE, NotificationHelper.CHANNEL_ALERTS,
                        "시간표 변동 알림", "변경된 시간: " + changed, true);
            }
        });
    }

    private interface TtCallback { void onLoaded(Timetable tt, boolean offline); }

    private void fetchToday(Context ctx, Prefs prefs, TtCallback cb) {
        TimetableRepository.fetch(ctx, "1", (tt, offline, err) -> { // "1" = current week, always -- background notifications must not follow whatever week the user last browsed in the UI
            if (tt != null) cb.onLoaded(tt, offline);
        });
    }
}
