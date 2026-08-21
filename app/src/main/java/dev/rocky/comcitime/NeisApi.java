package dev.rocky.comcitime;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Client for NEIS (National Education Information System) Open API --
// Korea's official public education data API. Requires a free API key
// the user registers themselves at open.neis.go.kr; this app cannot
// provision one on their behalf.
public class NeisApi {
    private static final String BASE = "https://open.neis.go.kr/hub";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback<T> { void onResult(T result, Exception error); }

    public static class SchoolMatch {
        public String officeCode, schoolCode, schoolName;
        SchoolMatch(String o, String s, String n) { officeCode = o; schoolCode = s; schoolName = n; }
    }

    public static void searchSchool(String apiKey, String schoolName, Callback<List<SchoolMatch>> cb) {
        EXEC.submit(() -> {
            try {
                String url = BASE + "/schoolInfo?KEY=" + urlEnc(apiKey) + "&Type=json&pSize=20&SCHUL_NM="
                        + urlEnc(schoolName);
                String raw = httpGet(url);
                JSONObject root = new JSONObject(raw);
                List<SchoolMatch> out = new ArrayList<>();
                JSONArray schoolInfo = root.optJSONArray("schoolInfo");
                if (schoolInfo != null && schoolInfo.length() > 1) {
                    JSONArray rows = schoolInfo.getJSONObject(1).getJSONArray("row");
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.getJSONObject(i);
                        out.add(new SchoolMatch(r.optString("ATPT_OFCDC_SC_CODE"),
                                r.optString("SD_SCHUL_CODE"), r.optString("SCHUL_NM")));
                    }
                }
                post(cb, out, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    // dateYyyyMMdd e.g. "20260820"
    public static void fetchMeal(String apiKey, String officeCode, String schoolCode, String dateYyyyMMdd,
                                  Callback<List<String>> cb) {
        EXEC.submit(() -> {
            try {
                String url = BASE + "/mealServiceDietInfo?KEY=" + urlEnc(apiKey) + "&Type=json"
                        + "&ATPT_OFCDC_SC_CODE=" + urlEnc(officeCode)
                        + "&SD_SCHUL_CODE=" + urlEnc(schoolCode)
                        + "&MLSV_YMD=" + urlEnc(dateYyyyMMdd);
                String raw = httpGet(url);
                JSONObject root = new JSONObject(raw);
                List<String> meals = new ArrayList<>();
                JSONArray service = root.optJSONArray("mealServiceDietInfo");
                if (service != null && service.length() > 1) {
                    JSONArray rows = service.getJSONObject(1).getJSONArray("row");
                    for (int i = 0; i < rows.length(); i++) {
                        String dish = rows.getJSONObject(i).optString("DDISH_NM", "");
                        dish = dish.replace("<br/>", "\n").replaceAll("\\([0-9.]+\\)", "").trim();
                        meals.add(dish);
                    }
                } else if (root.has("RESULT")) {
                    JSONObject result = root.getJSONObject("RESULT");
                    throw new Exception(result.optString("MESSAGE", "급식 정보 없음"));
                }
                post(cb, meals, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    private static <T> void post(Callback<T> cb, T result, Exception e) {
        MAIN.post(() -> cb.onResult(result, e));
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
