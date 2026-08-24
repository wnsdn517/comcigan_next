package dev.rocky.comcitime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

// Local-only storage for the experimental indoor-mapping data collection
// feature (Settings -> 실내 지도 만들기). Nothing here leaves the device --
// there is no upload/server in this build. Rows are keyed by an
// auto-increment session id, never by user identity or account, so this
// data cannot be traced back to a specific person on its own.
public class MappingDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "comcitime_mapping.db";
    private static final int DB_VERSION = 3;

    public MappingDb(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    // x/y are relative meters from the session's starting point, estimated
    // automatically by MappingCollector's dead-reckoning (step count +
    // compass heading) -- not typed in by hand. radio_scans and waypoints
    // each get the position estimate at the moment they were recorded, so
    // a Wi-Fi fingerprint map can be built from movement data alone;
    // waypoints remain available only as an optional ground-truth label on
    // top of that, not as the primary mapping mechanism.
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "started_at INTEGER, ended_at INTEGER, device_model TEXT)");
        db.execSQL("CREATE TABLE radio_scans (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "bssid TEXT, rssi INTEGER, freq INTEGER, x REAL, y REAL)");
        db.execSQL("CREATE TABLE motion_samples (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "heading_deg REAL, pitch_deg REAL, roll_deg REAL, step_count INTEGER, x REAL, y REAL)");
        db.execSQL("CREATE TABLE waypoints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "floor TEXT, label TEXT, x REAL, y REAL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        resetTables(db);
    }

    // Debug builds all share one fixed signing key specifically so
    // `adb install -r` works across commits without uninstalling first
    // (see app/build.gradle) -- which means a device can easily end up
    // with an on-device DB_VERSION newer than whatever's checked out
    // right now (e.g. after testing a later commit, then an earlier one).
    // SQLiteOpenHelper's default onDowngrade() just throws
    // ("Can't downgrade database from version X to Y"), crashing the app
    // the moment this DB is touched. Since this table only holds
    // expendable, anonymous, on-device-only mapping data (see class doc),
    // resetting it exactly like onUpgrade() does is a fine trade for never
    // crashing here.
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        resetTables(db);
    }

    private void resetTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS sessions");
        db.execSQL("DROP TABLE IF EXISTS radio_scans");
        db.execSQL("DROP TABLE IF EXISTS motion_samples");
        db.execSQL("DROP TABLE IF EXISTS waypoints");
        onCreate(db);
    }

    public long startSession() {
        ContentValues cv = new ContentValues();
        cv.put("started_at", System.currentTimeMillis());
        cv.put("device_model", android.os.Build.MODEL);
        return getWritableDatabase().insert("sessions", null, cv);
    }

    public void endSession(long sessionId) {
        ContentValues cv = new ContentValues();
        cv.put("ended_at", System.currentTimeMillis());
        getWritableDatabase().update("sessions", cv, "id=?", new String[]{String.valueOf(sessionId)});
    }

    public void insertRadioScan(long sessionId, long ts, String bssid, int rssi, int freq, double x, double y) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("ts", ts);
        cv.put("bssid", bssid);
        cv.put("rssi", rssi);
        cv.put("freq", freq);
        cv.put("x", x);
        cv.put("y", y);
        getWritableDatabase().insert("radio_scans", null, cv);
    }

    public void insertMotionSample(long sessionId, long ts, float headingDeg, float pitchDeg, float rollDeg,
                                    int stepCount, double x, double y) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("ts", ts);
        cv.put("heading_deg", headingDeg);
        cv.put("pitch_deg", pitchDeg);
        cv.put("roll_deg", rollDeg);
        cv.put("step_count", stepCount);
        cv.put("x", x);
        cv.put("y", y);
        getWritableDatabase().insert("motion_samples", null, cv);
    }

    public void insertWaypoint(long sessionId, String floor, String label, double x, double y) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("ts", System.currentTimeMillis());
        cv.put("floor", floor);
        cv.put("label", label);
        cv.put("x", x);
        cv.put("y", y);
        getWritableDatabase().insert("waypoints", null, cv);
    }

    public static class Counts {
        public int sessions, scans, samples, waypoints;
    }

    public Counts counts() {
        Counts c = new Counts();
        SQLiteDatabase db = getReadableDatabase();
        c.sessions = countRows(db, "SELECT COUNT(*) FROM sessions");
        c.scans = countRows(db, "SELECT COUNT(*) FROM radio_scans");
        c.samples = countRows(db, "SELECT COUNT(*) FROM motion_samples");
        c.waypoints = countRows(db, "SELECT COUNT(*) FROM waypoints");
        return c;
    }

    private int countRows(SQLiteDatabase db, String sql) {
        try (Cursor cur = db.rawQuery(sql, null)) {
            return cur.moveToFirst() ? cur.getInt(0) : 0;
        }
    }

    public static class ApEstimate {
        public String bssid;
        public double x, y;
        public int observations;
        public double avgRssi;
    }

    // Estimates each observed Wi-Fi access point's position as the RSSI-
    // power-weighted centroid of every (x, y) position it was seen from --
    // a standard technique for RF source localization from crowd-sourced
    // signal-strength readings (weight = 10^(rssi/10), i.e. proportional to
    // received power, so strong/close readings pull the estimate toward
    // them far more than weak/far ones). Only computed on demand for a
    // status screen, not on any hot path, and the whole scan table is
    // small enough during this experimental phase to aggregate in memory
    // rather than needing SQL-level math functions SQLite doesn't ship.
    public List<ApEstimate> estimateApPositions(int minObservations, int limit) {
        java.util.Map<String, double[]> acc = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery("SELECT bssid, rssi, x, y FROM radio_scans", null)) {
            while (cur.moveToNext()) {
                String bssid = cur.getString(0);
                int rssi = cur.getInt(1);
                double x = cur.getDouble(2);
                double y = cur.getDouble(3);
                double weight = Math.pow(10.0, rssi / 10.0);
                double[] a = acc.computeIfAbsent(bssid, k -> new double[5]); // wx, wy, wSum, rssiSum, count
                a[0] += weight * x;
                a[1] += weight * y;
                a[2] += weight;
                a[3] += rssi;
                a[4] += 1;
            }
        }
        List<ApEstimate> out = new ArrayList<>();
        for (java.util.Map.Entry<String, double[]> entry : acc.entrySet()) {
            double[] a = entry.getValue();
            if (a[4] < minObservations || a[2] <= 0) continue;
            ApEstimate est = new ApEstimate();
            est.bssid = entry.getKey();
            est.x = a[0] / a[2];
            est.y = a[1] / a[2];
            est.observations = (int) a[4];
            est.avgRssi = a[3] / a[4];
            out.add(est);
        }
        out.sort((p, q) -> Integer.compare(q.observations, p.observations));
        if (out.size() > limit) out = out.subList(0, limit);
        return out;
    }

    // One historical Wi-Fi scan snapshot (all BSSIDs seen in the same
    // scan, sharing the same session_id+ts), tagged with the dead-reckoned
    // position at that moment -- the unit of a Wi-Fi fingerprint map.
    private static class Fingerprint {
        double x, y;
        java.util.Map<String, Integer> rssiByBssid = new java.util.HashMap<>();
    }

    private List<Fingerprint> allFingerprints() {
        java.util.LinkedHashMap<String, Fingerprint> byKey = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT session_id, ts, x, y, bssid, rssi FROM radio_scans ORDER BY session_id, ts", null)) {
            while (cur.moveToNext()) {
                String key = cur.getLong(0) + ":" + cur.getLong(1);
                double x = cur.getDouble(2);
                double y = cur.getDouble(3);
                Fingerprint fp = byKey.computeIfAbsent(key, k -> {
                    Fingerprint f = new Fingerprint();
                    f.x = x; f.y = y;
                    return f;
                });
                fp.rssiByBssid.put(cur.getString(4), cur.getInt(5));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    // Count of distinct Wi-Fi scan snapshots recorded so far (one per
    // fingerprint), so Settings can show how much fingerprint data exists.
    public int fingerprintCount() {
        SQLiteDatabase db = getReadableDatabase();
        return countRows(db, "SELECT COUNT(DISTINCT session_id || ':' || ts) FROM radio_scans");
    }

    // Estimates a position by k-nearest-neighbor matching a live Wi-Fi
    // scan's RSSI signature against every recorded fingerprint -- the
    // standard indoor Wi-Fi-fingerprinting technique (RADAR-style), and
    // more robust indoors than first estimating each AP's own physical
    // position (see estimateApPositions above) since it never needs that
    // intermediate step to be accurate. Distance between two fingerprints
    // is the RMS RSSI difference over shared BSSIDs, with a fixed penalty
    // per BSSID present in one scan but not the other; the result is the
    // inverse-distance-weighted average position of the closest k matches.
    // Returns null when there's nothing to compare against yet, or the
    // live scan shares no BSSID with any recorded fingerprint. Result is
    // {x, y, avgMatchDistance} -- the third value is how far (in RSSI-space)
    // the k nearest matches were, so a caller fusing this into a Kalman
    // filter can size the measurement's uncertainty by it (see
    // MappingCollector.applyFingerprintCorrection()).
    public double[] estimateLocationFromFingerprint(java.util.Map<String, Integer> liveRssi, int k) {
        if (liveRssi == null || liveRssi.isEmpty()) return null;
        List<Fingerprint> all = allFingerprints();
        if (all.isEmpty()) return null;

        List<double[]> scored = new ArrayList<>(); // {distance, x, y}
        for (Fingerprint fp : all) {
            double sumSq = 0;
            int shared = 0;
            for (java.util.Map.Entry<String, Integer> e : liveRssi.entrySet()) {
                Integer other = fp.rssiByBssid.get(e.getKey());
                if (other != null) {
                    double diff = e.getValue() - other;
                    sumSq += diff * diff;
                    shared++;
                } else {
                    sumSq += 400; // ~20dB mismatch penalty for a BSSID missing here
                }
            }
            if (shared == 0) continue; // no overlap at all -- not comparable
            scored.add(new double[]{Math.sqrt(sumSq / liveRssi.size()), fp.x, fp.y});
        }
        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Double.compare(a[0], b[0]));
        int n = Math.min(k, scored.size());
        double wSum = 0, wx = 0, wy = 0, distSum = 0;
        for (int i = 0; i < n; i++) {
            double[] s = scored.get(i);
            double w = 1.0 / (s[0] + 1.0); // +1 avoids div-by-zero on an exact match
            wSum += w;
            wx += w * s[1];
            wy += w * s[2];
            distSum += s[0];
        }
        return new double[]{wx / wSum, wy / wSum, distSum / n};
    }

    // Chronological (x, y) trail from the most recent motion samples, for
    // the Settings 3D path drawing. Small on-demand read, same reasoning
    // as estimateApPositions() above.
    public List<double[]> recentPath(int limit) {
        List<double[]> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT x, y FROM motion_samples ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cur.moveToNext()) {
                out.add(new double[]{cur.getDouble(0), cur.getDouble(1)});
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    // Writes every recorded motion sample and waypoint out as one CSV file
    // (a leading UTF-8 BOM so Excel renders the Hangul column headers/labels
    // correctly instead of mojibake), for the Settings "움직임 기록 내보내기"
    // export -- see MainActivity.exportMappingCsv(). Motion and waypoint
    // rows share a single table (a `type` column distinguishes them) rather
    // than two separate files, so the whole recording history for a session
    // opens as one sortable sheet.
    public void exportMotionCsv(java.io.Writer writer) throws java.io.IOException {
        writer.write('\uFEFF'); // Excel-friendly BOM so Hangul headers/labels don't show as mojibake
        writer.write("type,session_id,ts,heading_deg,pitch_deg,roll_deg,step_count,x,y,floor,label\n");
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT session_id, ts, heading_deg, pitch_deg, roll_deg, step_count, x, y " +
                        "FROM motion_samples ORDER BY session_id, ts", null)) {
            while (cur.moveToNext()) {
                writer.write(csvRow("motion", cur.getLong(0), cur.getLong(1),
                        String.valueOf(cur.getFloat(2)), String.valueOf(cur.getFloat(3)), String.valueOf(cur.getFloat(4)),
                        String.valueOf(cur.getInt(5)), cur.getDouble(6), cur.getDouble(7), "", ""));
            }
        }
        try (Cursor cur = db.rawQuery(
                "SELECT session_id, ts, floor, label, x, y FROM waypoints ORDER BY session_id, ts", null)) {
            while (cur.moveToNext()) {
                writer.write(csvRow("waypoint", cur.getLong(0), cur.getLong(1),
                        "", "", "", "", cur.getDouble(4), cur.getDouble(5),
                        cur.getString(2), cur.getString(3)));
            }
        }
    }

    private static String csvRow(String type, long sessionId, long ts, String heading, String pitch, String roll,
                                  String stepCount, double x, double y, String floor, String label) {
        return String.join(",", type, String.valueOf(sessionId), String.valueOf(ts), heading, pitch, roll,
                stepCount, String.valueOf(x), String.valueOf(y), csvEscape(floor), csvEscape(label)) + "\n";
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public List<String> recentWaypoints(long sessionId, int limit) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT floor, label FROM waypoints WHERE session_id=? ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(sessionId), String.valueOf(limit)})) {
            while (cur.moveToNext()) {
                out.add(cur.getString(0) + " · " + cur.getString(1));
            }
        }
        return out;
    }
}
