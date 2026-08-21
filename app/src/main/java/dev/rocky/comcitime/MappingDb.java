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
