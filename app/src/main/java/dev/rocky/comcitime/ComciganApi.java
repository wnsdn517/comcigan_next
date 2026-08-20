package dev.rocky.comcitime;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Client for the same backend the official 컴시간알리미 apps use
// (comci.kr:4081). Query encoding and response parsing are ported
// directly from the apps' own bundled hour.html / hour_T.html JS, which
// ships unobfuscated inside their assets.
public class ComciganApi {

    private static final String BASE_URL = "http://comci.kr:4081";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback<T> {
        void onResult(T result, Exception error);
    }

    public static class School {
        public String region, name, code;
        School(String region, String name, String code) {
            this.region = region; this.name = name; this.code = code;
        }
    }

    // ---------- school search ----------
    public static void searchSchools(String query, Callback<List<School>> cb) {
        EXEC.submit(() -> {
            try {
                String url = BASE_URL + "/sc_" + eucKrEncode(query);
                String raw = httpGet(url);
                String json = extractJson(raw);
                JSONObject obj = new JSONObject(json);
                JSONArray arr = obj.optJSONArray("학교검색");
                List<School> out = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONArray row = arr.getJSONArray(i);
                        // row: [?, region, name, code]
                        out.add(new School(row.optString(1), row.optString(2), row.optString(3)));
                    }
                }
                postResult(cb, out, null);
            } catch (Exception e) {
                postResult(cb, null, e);
            }
        });
    }

    // ---------- timetable fetch ----------
    // studentOrTeacherParam: student year (e.g. "1") in student mode, or
    // teacher name in teacher mode. dayCode: "1" for current week.
    public static void fetchTimetable(String schoolCode, String dayCode, String dateHint,
                                       String studentOrTeacherParam, boolean teacherMode,
                                       String appVersion, Callback<Timetable> cb) {
        EXEC.submit(() -> {
            try {
                String url = BASE_URL + queryString(schoolCode, dayCode, dateHint, studentOrTeacherParam, appVersion, teacherMode);
                String raw = httpGet(url);
                String json = extractJson(raw);
                Timetable tt = Timetable.parse(new JSONObject(json));
                postResult(cb, tt, null);
            } catch (Exception e) {
                postResult(cb, null, e);
            }
        });
    }

    private static <T> void postResult(Callback<T> cb, T result, Exception e) {
        MAIN.post(() -> cb.onResult(result, e));
    }

    // Exact port of QueryString() from hour.html / hour_T.html.
    // Student: prefix 36174, mode digit 4, 5th field = year/grade.
    // Teacher: prefix 36171, mode digit 3, 5th field = teacher name.
    static String queryString(String sc, String day, String hNal, String fifthField,
                               String verName, boolean teacherMode) {
        String prefix = teacherMode ? "36171" : "36174";
        String modeDigit = teacherMode ? "3" : "4";
        String s = prefix + "_" + sc + "_" + day + "_" + modeDigit + "_" + hNal + "_" + fifthField + "_" + verName;
        String swapped = s.substring(9) + s.substring(0, 9);
        String reversed = new StringBuilder(swapped).reverse().toString();
        return "/7813?" + eucKrEncode(reversed);
    }

    // Raw responses look like: "<ver>^<dateCode>{...json...}<trailing junk>"
    // -- same trimming the apps' own JS does before JSON.parse.
    static String extractJson(String raw) throws Exception {
        int caret = raw.indexOf('^');
        int braceStart = raw.indexOf('{', caret >= 0 ? caret : 0);
        int braceEnd = raw.lastIndexOf('}');
        if (braceStart < 0 || braceEnd < braceStart) throw new Exception("unexpected response shape: " + safeSnippet(raw));
        return raw.substring(braceStart, braceEnd + 1);
    }

    private static String safeSnippet(String s) {
        return s == null ? "null" : s.substring(0, Math.min(120, s.length()));
    }

    private static String eucKrEncode(String s) {
        try {
            return URLEncoder.encode(s, "euc-kr");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new BufferedInputStream(conn.getInputStream()), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
