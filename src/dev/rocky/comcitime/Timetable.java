package dev.rocky.comcitime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Timetable {

    public static class PeriodEntry {
        public int period;          // 1..8
        public int dayOfWeek;       // 1=Mon..5=Fri
        public String subject = ""; // 과목명
        public String teacher = ""; // 성명
        public boolean changed;     // 정규 시간표 대비 변경됨
    }

    public String startDate = "";
    public String[] periodTimeLabels = new String[8]; // 일과시간 (as returned by the API, often just labels not clock times)
    public JSONArray fullSchedule;   // 시간표 (regular)
    public JSONArray todaySchedule;  // 학급시간표 (actual/today, carries changes)
    public JSONArray teacherNames;   // 성명
    public JSONArray subjectNames;   // 과목명
    public int changeNotifyMode;     // 변경알림 (0 or 1 -- selects which encoding baSplit uses)
    public int grade;
    public int classNum;

    public static class WeekOption {
        public String code;  // value to send back as the day/week parameter
        public String label; // e.g. "1주(08/18~08/22)" -- shown to the user as-is
        public WeekOption(String code, String label) { this.code = code; this.label = label; }
    }
    public List<WeekOption> weekOptions = new ArrayList<>();
    public String rawJson = ""; // stashed for offline caching
    public boolean isOffline = false;

    public static Timetable parse(JSONObject root) throws Exception {
        Timetable t = new Timetable();
        t.rawJson = root.toString();
        t.startDate = root.optString("시작일", "");
        JSONArray times = root.optJSONArray("일과시간");
        if (times != null) {
            for (int i = 0; i < Math.min(8, times.length()); i++) t.periodTimeLabels[i] = times.optString(i, "");
        }
        t.fullSchedule = root.optJSONArray("시간표");
        t.todaySchedule = root.optJSONArray("학급시간표");
        t.teacherNames = root.optJSONArray("성명");
        t.subjectNames = root.optJSONArray("과목명");
        t.changeNotifyMode = root.optInt("변경알림", 0);

        JSONArray weeks = root.optJSONArray("일자자료");
        if (weeks != null) {
            for (int i = 0; i < weeks.length(); i++) {
                JSONArray pair = weeks.optJSONArray(i);
                if (pair != null && pair.length() >= 2) {
                    t.weekOptions.add(new WeekOption(String.valueOf(pair.get(0)), pair.optString(1, "")));
                }
            }
        }
        return t;
    }

    // dayOfWeek: 1=Mon .. 5=Fri (matches the apps' own 요일 convention)
    public List<PeriodEntry> getDaySchedule(int grade, int classNum, int dayOfWeek) {
        List<PeriodEntry> out = new ArrayList<>();
        for (int period = 1; period <= 8; period++) {
            PeriodEntry e = decodePeriod(grade, classNum, dayOfWeek, period);
            if (e != null) { e.dayOfWeek = dayOfWeek; out.add(e); }
        }
        return out;
    }

    private PeriodEntry decodePeriod(int grade, int classNum, int dayOfWeek, int period) {
        try {
            long rawRegular = nestedLong(fullSchedule, grade, classNum, dayOfWeek, period);
            long rawToday = nestedLong(todaySchedule, grade, classNum, dayOfWeek, period);

            int th, sb;
            boolean changed;
            if (changeNotifyMode == 1) {
                String todayStr = nestedString(todaySchedule, grade, classNum, dayOfWeek, period);
                if (todayStr != null && todayStr.startsWith(">")) {
                    long v = parseLongSafe(todayStr.substring(1));
                    th = decodeTh(v); sb = decodeSb(v);
                    changed = true;
                } else {
                    long v = parseLongSafe(todayStr);
                    th = decodeTh(v); sb = decodeSb(v);
                    changed = false;
                }
            } else {
                th = decodeTh(rawToday);
                sb = decodeSb(rawToday);
                changed = rawRegular != rawToday;
            }

            if (th <= 0 && sb <= 0) return null; // empty period, nothing scheduled

            PeriodEntry e = new PeriodEntry();
            e.period = period;
            e.teacher = arrayString(teacherNames, th);
            e.subject = arrayString(subjectNames, sb);
            e.changed = changed;
            return e;
        } catch (Exception ex) {
            return null; // degrade gracefully on any unexpected shape rather than crash the whole parse
        }
    }

    // Port of splitData()'s effective behavior for the 2-group case that
    // baSplit() actually uses: groups of up to 3 digits from the right.
    // th = last 3 digits, sb = the digits before that.
    private static int decodeTh(long value) {
        if (value <= 0) return 0;
        return (int) (value % 1000);
    }
    private static int decodeSb(long value) {
        if (value <= 0) return 0;
        return (int) (value / 1000);
    }

    public static class TeacherPeriodEntry extends PeriodEntry {
        public int grade;
        public int classNum;
    }

    // Derives a specific teacher's full week by scanning every
    // grade/class this response already covers -- no extra network call
    // needed, since a normal fetch already returns the whole school's
    // data, not just one class. Mirrors what the teacher app itself does
    // client-side (교사시간표_원자료생성 in its own JS).
    public List<TeacherPeriodEntry> getTeacherWeek(String teacherName) {
        List<TeacherPeriodEntry> out = new ArrayList<>();
        int th = indexOf(teacherNames, teacherName);
        if (th < 0 || fullSchedule == null) return out;

        for (int g = 0; g < fullSchedule.length(); g++) {
            JSONArray classesArr = fullSchedule.optJSONArray(g);
            if (classesArr == null) continue;
            for (int c = 0; c < classesArr.length(); c++) {
                for (int day = 1; day <= 5; day++) {
                    for (int period = 1; period <= 8; period++) {
                        PeriodEntry e = decodePeriod(g, c, day, period);
                        if (e == null) continue;
                        int entryTh = -1;
                        try { entryTh = indexOf(teacherNames, e.teacher); } catch (Exception ignored) {}
                        if (entryTh == th) {
                            TeacherPeriodEntry te = new TeacherPeriodEntry();
                            te.period = e.period; te.subject = e.subject; te.teacher = e.teacher; te.changed = e.changed;
                            te.grade = g; te.classNum = c;
                            te.dayOfWeek = day;
                            out.add(te);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static int indexOf(JSONArray arr, String value) {
        if (arr == null || value == null) return -1;
        for (int i = 0; i < arr.length(); i++) {
            if (value.equals(arr.optString(i))) return i;
        }
        return -1;
    }
    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long nestedLong(JSONArray root, int a, int b, int c, int d) {
        try {
            return Long.parseLong(nestedString(root, a, b, c, d));
        } catch (Exception e) { return 0; }
    }

    private static String nestedString(JSONArray root, int a, int b, int c, int d) {
        try {
            JSONArray l1 = root.getJSONArray(a);
            JSONArray l2 = l1.getJSONArray(b);
            JSONArray l3 = l2.getJSONArray(c);
            return l3.opt(d) == null ? "0" : String.valueOf(l3.get(d));
        } catch (Exception e) { return "0"; }
    }

    private static String arrayString(JSONArray arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length()) return "";
        String s = arr.optString(idx, "");
        return s == null ? "" : s;
    }
}
