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
    private static final int DB_VERSION = 4;

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
        // Manually-tagged Wi-Fi place directory (distinct from waypoints
        // above, which tag the dead-reckoned x/y): each row is one BSSID
        // from a live scan captured at the moment the user tapped "여기
        // 표시", labeled with the place name they typed. Not session-scoped
        // -- places are meant to accumulate across sessions/days, unlike
        // per-session dead-reckoning data. See MappingDb.recognizePlace().
        db.execSQL("CREATE TABLE place_fingerprints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, " +
                "floor TEXT, label TEXT, bssid TEXT, rssi INTEGER, freq INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS sessions");
        db.execSQL("DROP TABLE IF EXISTS radio_scans");
        db.execSQL("DROP TABLE IF EXISTS motion_samples");
        db.execSQL("DROP TABLE IF EXISTS waypoints");
        db.execSQL("DROP TABLE IF EXISTS place_fingerprints");
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

    public void insertPlaceFingerprint(long ts, String floor, String label, String bssid, int rssi, int freq) {
        ContentValues cv = new ContentValues();
        cv.put("ts", ts);
        cv.put("floor", floor);
        cv.put("label", label);
        cv.put("bssid", bssid);
        cv.put("rssi", rssi);
        cv.put("freq", freq);
        getWritableDatabase().insert("place_fingerprints", null, cv);
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

    // Shared by estimateLocationFromFingerprint() and recognizePlace(): RMS
    // RSSI difference between a live scan and a stored fingerprint over
    // their shared BSSIDs, with a fixed ~20dB penalty per BSSID present in
    // one but not the other. Returns -1 (not NaN) when there's no shared
    // BSSID at all -- not comparable.
    private static double fingerprintDistance(java.util.Map<String, Integer> liveRssi,
                                               java.util.Map<String, Integer> otherRssi) {
        double sumSq = 0;
        int shared = 0;
        for (java.util.Map.Entry<String, Integer> e : liveRssi.entrySet()) {
            Integer other = otherRssi.get(e.getKey());
            if (other != null) {
                double diff = e.getValue() - other;
                sumSq += diff * diff;
                shared++;
            } else {
                sumSq += 400; // ~20dB mismatch penalty for a BSSID missing here
            }
        }
        return shared == 0 ? -1 : Math.sqrt(sumSq / liveRssi.size());
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
            double dist = fingerprintDistance(liveRssi, fp.rssiByBssid);
            if (dist < 0) continue; // no shared BSSID at all -- not comparable
            scored.add(new double[]{dist, fp.x, fp.y});
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

    // One manual "여기 표시" tagging event's Wi-Fi snapshot, labeled with
    // the place name -- the place-recognition counterpart of Fingerprint
    // above. Grouped by ts alone (not session_id+ts): a manual tap is
    // already unique in time, and places are meant to persist across
    // sessions rather than being scoped to one.
    private static class PlaceFingerprint {
        String floor, label;
        java.util.Map<String, Integer> rssiByBssid = new java.util.HashMap<>();
    }

    private List<PlaceFingerprint> allPlaceFingerprints() {
        java.util.LinkedHashMap<Long, PlaceFingerprint> byTs = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT ts, floor, label, bssid, rssi FROM place_fingerprints ORDER BY ts", null)) {
            while (cur.moveToNext()) {
                long ts = cur.getLong(0);
                String floor = cur.getString(1);
                String label = cur.getString(2);
                PlaceFingerprint pf = byTs.computeIfAbsent(ts, k -> {
                    PlaceFingerprint p = new PlaceFingerprint();
                    p.floor = floor;
                    p.label = label;
                    return p;
                });
                pf.rssiByBssid.put(cur.getString(3), cur.getInt(4));
            }
        }
        return new ArrayList<>(byTs.values());
    }

    public static class PlaceMatch {
        public String floor, label;
        public double avgMatchDistance;
    }

    // kNN place *classification* (unlike estimateLocationFromFingerprint's
    // regression over x/y -- a place name can't be interpolated the way a
    // position can): matches a live Wi-Fi scan against every manually-
    // tagged place event using the same RSSI-distance metric, then
    // majority-votes the place label among the k nearest tagging events,
    // tie-broken by whichever place had the single closest neighbor.
    // Returns null when nothing is tagged yet, or the live scan shares no
    // BSSID with any tagged event.
    public PlaceMatch recognizePlace(java.util.Map<String, Integer> liveRssi, int k) {
        if (liveRssi == null || liveRssi.isEmpty()) return null;
        List<PlaceFingerprint> all = allPlaceFingerprints();
        if (all.isEmpty()) return null;

        List<Object[]> scored = new ArrayList<>(); // {Double distance, PlaceFingerprint}
        for (PlaceFingerprint pf : all) {
            double dist = fingerprintDistance(liveRssi, pf.rssiByBssid);
            if (dist < 0) continue;
            scored.add(new Object[]{dist, pf});
        }
        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        int n = Math.min(k, scored.size());
        java.util.Map<String, Integer> votes = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> bestDistByPlace = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            PlaceFingerprint pf = (PlaceFingerprint) scored.get(i)[1];
            double dist = (Double) scored.get(i)[0];
            String key = pf.floor + "\u001F" + pf.label;
            votes.merge(key, 1, Integer::sum);
            bestDistByPlace.merge(key, dist, Math::min);
        }
        String bestKey = null;
        int bestVotes = -1;
        double bestDist = Double.MAX_VALUE;
        for (java.util.Map.Entry<String, Integer> e : votes.entrySet()) {
            double d = bestDistByPlace.get(e.getKey());
            if (e.getValue() > bestVotes || (e.getValue() == bestVotes && d < bestDist)) {
                bestVotes = e.getValue();
                bestDist = d;
                bestKey = e.getKey();
            }
        }
        int sep = bestKey.indexOf('\u001F');
        PlaceMatch match = new PlaceMatch();
        match.floor = bestKey.substring(0, sep);
        match.label = bestKey.substring(sep + 1);
        match.avgMatchDistance = bestDist;
        return match;
    }

    // Count of distinct manually-tagged places (floor+label pairs), for
    // the Settings counts display -- "how much of the school is covered".
    public int placeCount() {
        SQLiteDatabase db = getReadableDatabase();
        return countRows(db, "SELECT COUNT(*) FROM (SELECT DISTINCT floor, label FROM place_fingerprints)");
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
