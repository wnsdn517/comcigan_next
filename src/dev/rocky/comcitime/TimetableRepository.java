package dev.rocky.comcitime;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class TimetableRepository {

    public interface Callback {
        void onResult(Timetable tt, boolean offline, Exception err);
    }

    public static void fetch(Context ctx, String weekCode, Callback cb) {
        Prefs prefs = new Prefs(ctx);
        if (prefs.schoolCode().isEmpty()) {
            cb.onResult(null, false, new Exception("학교가 설정되지 않았습니다"));
            return;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new java.util.Date());
        ComciganApi.fetchTimetable(prefs.schoolCode(), weekCode, today, "1", false, "1.0", (tt, err) -> {
            if (tt != null) {
                logChangesIfNeeded(prefs, weekCode, tt);
                prefs.setCachedJson(weekCode, tt.rawJson);
                prefs.setCacheTimestamp(weekCode, System.currentTimeMillis());
                if ("1".equals(weekCode) && !tt.startDate.isEmpty()) {
                    prefs.archiveWeek(tt.startDate, tt.rawJson);
                }
                tt.isOffline = false;
                cb.onResult(tt, false, null);
                return;
            }
            String cached = prefs.cachedJson(weekCode);
            if (cached != null && !cached.isEmpty()) {
                try {
                    Timetable cachedTt = Timetable.parse(new JSONObject(cached));
                    cachedTt.isOffline = true;
                    cb.onResult(cachedTt, true, null);
                } catch (Exception parseErr) {
                    cb.onResult(null, false, err);
                }
            } else {
                cb.onResult(null, false, err);
            }
        });
    }

    private static void logChangesIfNeeded(Prefs prefs, String weekCode, Timetable fresh) {
        String prevJson = prefs.cachedJson(weekCode);
        if (prevJson == null || prevJson.isEmpty()) return;
        try {
            Timetable prev = Timetable.parse(new JSONObject(prevJson));
            String[] dowNames = {"", "월", "화", "수", "목", "금"};
            String nowStr = new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(new java.util.Date());
            for (int day = 1; day <= 5; day++) {
                List<Timetable.PeriodEntry> prevDay = prev.getDaySchedule(prefs.grade(), prefs.classNum(), day);
                List<Timetable.PeriodEntry> freshDay = fresh.getDaySchedule(prefs.grade(), prefs.classNum(), day);
                for (Timetable.PeriodEntry fe : freshDay) {
                    Timetable.PeriodEntry match = null;
                    for (Timetable.PeriodEntry pe : prevDay) if (pe.period == fe.period) { match = pe; break; }
                    String oldSubject = match == null ? "(없음)" : match.subject;
                    if (!oldSubject.equals(fe.subject)) {
                        prefs.addChangeHistoryEntry(nowStr, dowNames[day], fe.period, oldSubject, fe.subject);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
