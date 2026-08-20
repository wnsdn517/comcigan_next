package dev.rocky.comcitime;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class Prefs {
    private static final String FILE = "comcitime_prefs";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String schoolCode() { return sp.getString("schoolCode", ""); }
    public String schoolName() { return sp.getString("schoolName", ""); }
    public int grade() { return sp.getInt("grade", 1); }
    public int classNum() { return sp.getInt("classNum", 1); }

    public void setSchool(String code, String name) {
        sp.edit().putString("schoolCode", code).putString("schoolName", name).apply();
    }
    public void setClass(int grade, int classNum) {
        sp.edit().putInt("grade", grade).putInt("classNum", classNum).apply();
    }

    public String lastWeekCode() { return sp.getString("lastWeekCode", "1"); }
    public void setLastWeekCode(String code) { sp.edit().putString("lastWeekCode", code).apply(); }

    // Per-subject colors. Falls back to a deterministic pick from a
    // preset palette so untouched subjects still look distinct and
    // consistent across app restarts, not just gray.
    private static final int[] DEFAULT_PALETTE = {
            0xFF5B8CFF, 0xFFFF7A7A, 0xFF57C785, 0xFFF2B94C, 0xFFB57BFF,
            0xFF4CD3C2, 0xFFFF8FB1, 0xFFC9D14C, 0xFF7A93FF, 0xFFFF9F5B
    };

    public int subjectColor(String subject) {
        int custom = sp.getInt("subjcolor_" + subject, 0);
        if (custom != 0) return custom;
        int idx = Math.abs(subject.hashCode()) % DEFAULT_PALETTE.length;
        return DEFAULT_PALETTE[idx];
    }
    public void setSubjectColor(String subject, int color) {
        sp.edit().putInt("subjcolor_" + subject, color).apply();
    }
    public boolean hasCustomColor(String subject) {
        return sp.getInt("subjcolor_" + subject, 0) != 0;
    }

    // When on, timetable cells are filled with the full, solid subject
    // color instead of a muted blend with the background, and a changed
    // period is shown as a darker shade of that same color.
    public boolean solidTimetableColor() { return sp.getBoolean("solidTimetableColor", false); }
    public void setSolidTimetableColor(boolean v) { sp.edit().putBoolean("solidTimetableColor", v).apply(); }

    // Every subject name ever seen, so the color-settings screen has
    // something to list even before today's fetch completes again.
    public java.util.Set<String> knownSubjects() {
        return new java.util.HashSet<>(sp.getStringSet("knownSubjects", new java.util.HashSet<>()));
    }
    public void addKnownSubjects(java.util.Collection<String> subjects) {
        java.util.Set<String> cur = knownSubjects();
        cur.addAll(subjects);
        sp.edit().putStringSet("knownSubjects", cur).apply();
    }

    // ---------- offline cache ----------
    public String cachedJson(String weekCode) { return sp.getString("cache_" + weekCode, null); }
    public void setCachedJson(String weekCode, String json) { sp.edit().putString("cache_" + weekCode, json).apply(); }
    public long cacheTimestamp(String weekCode) { return sp.getLong("cache_ts_" + weekCode, 0); }
    public void setCacheTimestamp(String weekCode, long ts) { sp.edit().putLong("cache_ts_" + weekCode, ts).apply(); }

    // ---------- change history (newest first, capped) ----------
    private static final int MAX_HISTORY = 60;
    public static class HistoryEntry {
        public String timestamp, dayLabel, oldSubject, newSubject;
        public int period;
    }
    public List<HistoryEntry> changeHistory() {
        try {
            String raw = sp.getString("changeHistoryV2", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            List<HistoryEntry> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                HistoryEntry e = new HistoryEntry();
                e.timestamp = o.optString("ts"); e.dayLabel = o.optString("day");
                e.period = o.optInt("period"); e.oldSubject = o.optString("old"); e.newSubject = o.optString("new");
                out.add(e);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    public void addChangeHistoryEntry(String timestamp, String dayLabel, int period, String oldSubject, String newSubject) {
        List<HistoryEntry> cur = changeHistory();
        HistoryEntry e = new HistoryEntry();
        e.timestamp = timestamp; e.dayLabel = dayLabel; e.period = period; e.oldSubject = oldSubject; e.newSubject = newSubject;
        cur.add(0, e);
        while (cur.size() > MAX_HISTORY) cur.remove(cur.size() - 1);
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (HistoryEntry h : cur) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ts", h.timestamp); o.put("day", h.dayLabel); o.put("period", h.period);
                o.put("old", h.oldSubject); o.put("new", h.newSubject);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString("changeHistoryV2", arr.toString()).apply();
    }

    // ---------- onboarding ----------
    public boolean onboardingDone() { return sp.getBoolean("onboardingDone", false); }
    public void setOnboardingDone(boolean v) { sp.edit().putBoolean("onboardingDone", v).apply(); }

    // ---------- NEIS (optional meal info) ----------
    public String neisApiKey() { return sp.getString("neisKey", ""); }
    public void setNeisApiKey(String key) { sp.edit().putString("neisKey", key).apply(); }
    public String neisOfficeCode() { return sp.getString("neisOfficeCode", ""); }
    public String neisSchoolCode() { return sp.getString("neisSchoolCode", ""); }
    public void setNeisSchool(String officeCode, String schoolCode) {
        sp.edit().putString("neisOfficeCode", officeCode).putString("neisSchoolCode", schoolCode).apply();
    }

    // ---------- saved class list (multiple school/grade/class combos) ----------
    public List<SavedClass> savedClasses() {
        try {
            String raw = sp.getString("savedClasses", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            List<SavedClass> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                out.add(new SavedClass(o.getString("label"), o.getString("schoolCode"),
                        o.getString("schoolName"), o.getInt("grade"), o.getInt("classNum")));
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    public void saveSavedClasses(List<SavedClass> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (SavedClass c : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("label", c.label); o.put("schoolCode", c.schoolCode);
                o.put("schoolName", c.schoolName); o.put("grade", c.grade); o.put("classNum", c.classNum);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString("savedClasses", arr.toString()).apply();
    }
    public static class SavedClass {
        public String label, schoolCode, schoolName;
        public int grade, classNum;
        public SavedClass(String label, String schoolCode, String schoolName, int grade, int classNum) {
            this.label = label; this.schoolCode = schoolCode; this.schoolName = schoolName;
            this.grade = grade; this.classNum = classNum;
        }
    }

    // ---------- personal events (with abuse-prevention) ----------
    public static class PersonalEvent {
        public String date; // yyyy-MM-dd
        public int period;
        public String text;
    }

    public List<PersonalEvent> personalEvents() {
        try {
            String raw = sp.getString("personalEvents", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            List<PersonalEvent> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                PersonalEvent e = new PersonalEvent();
                e.date = o.optString("date"); e.period = o.optInt("period"); e.text = o.optString("text");
                out.add(e);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void savePersonalEvents(List<PersonalEvent> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (PersonalEvent e : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("date", e.date); o.put("period", e.period); o.put("text", e.text);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString("personalEvents", arr.toString()).apply();
    }

    public PersonalEvent findPersonalEvent(String date, int period) {
        for (PersonalEvent e : personalEvents()) if (e.date.equals(date) && e.period == period) return e;
        return null;
    }

    public enum AddEventResult { OK, RATE_LIMITED, SLOT_TAKEN }

    // Max 5 additions per rolling 30s window; refuses a second event in
    // an already-occupied slot (editing the existing one is always fine).
    public AddEventResult addPersonalEvent(String date, int period, String text) {
        if (findPersonalEvent(date, period) != null) return AddEventResult.SLOT_TAKEN;
        long now = System.currentTimeMillis();
        List<Long> stamps = recentAddTimestamps();
        stamps.removeIf(t -> now - t > 30_000);
        if (stamps.size() >= 5) return AddEventResult.RATE_LIMITED;
        stamps.add(now);
        setRecentAddTimestamps(stamps);

        List<PersonalEvent> list = personalEvents();
        PersonalEvent e = new PersonalEvent();
        e.date = date; e.period = period; e.text = text;
        list.add(e);
        savePersonalEvents(list);
        return AddEventResult.OK;
    }

    public void editPersonalEvent(String date, int period, String newText) {
        List<PersonalEvent> list = personalEvents();
        for (PersonalEvent e : list) {
            if (e.date.equals(date) && e.period == period) { e.text = newText; break; }
        }
        savePersonalEvents(list);
    }

    public void deletePersonalEvent(String date, int period) {
        List<PersonalEvent> list = personalEvents();
        list.removeIf(e -> e.date.equals(date) && e.period == period);
        savePersonalEvents(list);
    }

    private List<Long> recentAddTimestamps() {
        try {
            String raw = sp.getString("eventAddStamps", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            List<Long> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(arr.getLong(i));
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    private void setRecentAddTimestamps(List<Long> stamps) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (long s : stamps) arr.put(s);
        sp.edit().putString("eventAddStamps", arr.toString()).apply();
    }

    // ---------- weekly archive (for the simplified "view previous week") ----------
    public String archivedWeekJson(String mondayDate) { return sp.getString("archive_" + mondayDate, null); }
    public void archiveWeek(String mondayDate, String json) {
        if (sp.getString("archive_" + mondayDate, null) == null) {
            sp.edit().putString("archive_" + mondayDate, json).apply();
            List<String> dates = archivedWeekDates();
            if (!dates.contains(mondayDate)) {
                dates.add(mondayDate);
                java.util.Collections.sort(dates);
                sp.edit().putString("archiveIndex", String.join(",", dates)).apply();
            }
        }
    }
    public List<String> archivedWeekDates() {
        String raw = sp.getString("archiveIndex", "");
        List<String> out = new ArrayList<>();
        if (!raw.isEmpty()) for (String s : raw.split(",")) out.add(s);
        return out;
    }

    // Period times: stored as "HH:mm-HH:mm" per period, 1..8. Generic
    // Korean secondary-school default; fully editable in Settings.
    private static final String[] DEFAULT_PERIODS = {
            "09:00-09:50", "10:00-10:50", "11:00-11:50", "12:00-12:50",
            "13:50-14:40", "14:50-15:40", "15:50-16:40", "16:50-17:40"
    };

    public String periodTime(int period1to8) {
        return sp.getString("period_" + period1to8, DEFAULT_PERIODS[Math.max(0, Math.min(7, period1to8 - 1))]);
    }
    public void setPeriodTime(int period1to8, String hhmmRange) {
        sp.edit().putString("period_" + period1to8, hhmmRange).apply();
    }

    // Feature toggles
    public boolean notifyChange() { return sp.getBoolean("notifyChange", true); }
    public boolean notifyPeriod() { return sp.getBoolean("notifyPeriod", true); }
    public boolean notifyMorning() { return sp.getBoolean("notifyMorning", true); }
    public boolean liveNotify() { return sp.getBoolean("liveNotify", false); }
    public void setNotifyChange(boolean v) { sp.edit().putBoolean("notifyChange", v).apply(); }
    public void setNotifyPeriod(boolean v) { sp.edit().putBoolean("notifyPeriod", v).apply(); }
    public void setNotifyMorning(boolean v) { sp.edit().putBoolean("notifyMorning", v).apply(); }
    public void setLiveNotify(boolean v) { sp.edit().putBoolean("liveNotify", v).apply(); }

    public String morningTime() { return sp.getString("morningTime", "07:30"); }
    public void setMorningTime(String hhmm) { sp.edit().putString("morningTime", hhmm).apply(); }

    // Cache of last-seen timetable JSON, used for change detection across app restarts.
    public String lastTimetableJson() { return sp.getString("lastTimetableJson", ""); }
    public void setLastTimetableJson(String json) { sp.edit().putString("lastTimetableJson", json).apply(); }
}
