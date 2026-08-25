package dev.rocky.comcitime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Local-only storage for the experimental indoor-mapping data collection
// feature (Settings -> 실내 지도 만들기). Nothing leaves the device on its
// own -- there is no upload/server in this build -- but exportAllData()
// lets the user pull everything out as a file (see MainActivity's
// "내보내기" button). Rows are keyed by an
// auto-increment session id, never by user identity or account, so this
// data cannot be traced back to a specific person on its own.
public class MappingDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "comcitime_mapping.db";
    private static final int DB_VERSION = 12;

    public MappingDb(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    // Write-Ahead Logging instead of the platform default rollback
    // journal -- Android's own SQLite performance guide recommends this
    // for exactly this app's write pattern (a background service doing
    // frequent small writes: one row per step/scan/second-heartbeat).
    // enableWriteAheadLogging() also sets synchronous=NORMAL for WAL as
    // part of enabling it, the officially recommended pairing (full
    // durability against an app crash or being killed; only an actual
    // device power-loss/kernel-panic could still lose the last commit,
    // same tradeoff every WAL-mode app on Android makes). onConfigure()
    // is the documented hook for this -- it runs before onCreate/
    // onUpgrade, unlike setting it lazily on first use elsewhere.
    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    // Runs `work` (typically a handful of insertX() calls) as one SQLite
    // transaction instead of each insert auto-committing (and fsync-ing)
    // on its own -- both a correctness improvement (a multi-row write,
    // like one Wi-Fi scan's whole set of results, either lands completely
    // or not at all instead of possibly being caught half-written if the
    // process dies mid-loop) and a real performance one (one fsync
    // instead of N).
    public void runInTransaction(Runnable work) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            work.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
        // IF NOT EXISTS: this also runs from onUpgrade() below (to create
        // any table that didn't exist yet at the device's old version),
        // where re-running a plain CREATE TABLE against a table that's
        // already there would throw.
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "started_at INTEGER, ended_at INTEGER, device_model TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS radio_scans (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "bssid TEXT, rssi INTEGER, freq INTEGER, x REAL, y REAL)");
        // accel/gyro/mag/pressure/top_rssi mirror sensor_snapshots below,
        // but captured automatically on every sample instead of only when
        // the user manually taps "센서값 기록" -- post-hoc analysis (tuning
        // drift correction, diagnosing a bad session) needs the full raw
        // context at every recorded point, not just hand-picked instants.
        db.execSQL("CREATE TABLE IF NOT EXISTS motion_samples (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "heading_deg REAL, pitch_deg REAL, roll_deg REAL, step_count INTEGER, x REAL, y REAL, " +
                "floor_delta INTEGER, " +
                "accel_x REAL, accel_y REAL, accel_z REAL, " +
                "gyro_x REAL, gyro_y REAL, gyro_z REAL, " +
                "mag_x REAL, mag_y REAL, mag_z REAL, " +
                "pressure_hpa REAL, top_rssi INTEGER, " +
                // Platform-fused gravity-only vector and gravity-compensated
                // linear acceleration (TYPE_GRAVITY/TYPE_LINEAR_ACCELERATION),
                // alongside the raw accel_x/y/z above -- lets a suspected
                // device-tilt-related step-detection bug (raw accelerometer
                // magnitude conflating real motion with an orientation-
                // dependent gravity component) actually be verified against
                // real recorded data instead of guessed at. See
                // MappingCollector's class doc.
                "grav_x REAL, grav_y REAL, grav_z REAL, " +
                "lin_accel_x REAL, lin_accel_y REAL, lin_accel_z REAL, " +
                // Floor-tracking internals (MappingCollector.
                // updateFloorEstimate()): world-frame vertical acceleration,
                // the weather-adapted pressure baseline for the current
                // floor, and the uncommitted offset from it. Recorded so a
                // floor reading that looks wrong can be traced to which
                // part misbehaved -- the baseline drifting, or a transition
                // being missed/falsely committed.
                "vert_accel REAL, floor_ref_pressure REAL, floor_offset_raw REAL, " +
                // Associated AP's live RSSI. Unlike top_rssi (which comes
                // from the last 30s-throttled scan and so repeats between
                // scans) this is un-throttled link state, genuinely fresh
                // on every sample -- see MappingCollector.pollConnectedRssi().
                "connected_rssi INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS waypoints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "floor TEXT, label TEXT, x REAL, y REAL)");
        // Wi-Fi RTT (Round-Trip-Time) ranging results.
        db.execSQL("CREATE TABLE IF NOT EXISTS radio_rtt (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, " +
                "bssid TEXT, distance_mm INTEGER, stddev_mm INTEGER, rssi INTEGER)");
        // Manually-tagged Wi-Fi place directory (distinct from waypoints
        // above, which tag the dead-reckoned x/y): each row is one BSSID
        // from a live scan captured at the moment the user tapped "여기
        // 표시", labeled with the place name they typed. Not session-scoped
        // -- places are meant to accumulate across sessions/days, unlike
        // per-session dead-reckoning data. See MappingDb.recognizePlace().
        // x/y is the dead-reckoned position at the same moment -- an
        // explicit, user-asserted anchor point (see MappingCollector.
        // addPlaceTag()/applyAnchorCorrection()), unlike radio_scans' x/y
        // which is just wherever dead reckoning happened to be during
        // ordinary passive collection.
        db.execSQL("CREATE TABLE IF NOT EXISTS place_fingerprints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, " +
                "floor TEXT, label TEXT, bssid TEXT, rssi INTEGER, freq INTEGER, " +
                "x REAL, y REAL)");
        // Manually-triggered full raw-sensor snapshots -- see
        // MappingCollector.snapshotSensors() / MainActivity's "센서값 기록"
        // button. Separate from motion_samples (which only ever records
        // heading/pitch/roll/steps automatically on each step): this is
        // for capturing every raw signal at one specific instant the user
        // flags, e.g. right as something looks wrong on the live graphs.
        db.execSQL("CREATE TABLE IF NOT EXISTS sensor_snapshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, ts INTEGER, label TEXT, " +
                "accel_x REAL, accel_y REAL, accel_z REAL, " +
                "gyro_x REAL, gyro_y REAL, gyro_z REAL, " +
                "mag_x REAL, mag_y REAL, mag_z REAL, " +
                "pressure_hpa REAL, heading_deg REAL, pitch_deg REAL, roll_deg REAL, " +
                "top_rssi INTEGER, floor_delta INTEGER, x REAL, y REAL)");
    }

    // Additive-only, never resets existing data: creates whatever tables
    // didn't exist yet at the device's old version (onCreate() above is
    // now idempotent via CREATE TABLE IF NOT EXISTS, so re-running it here
    // is safe), then adds whatever columns have been introduced to
    // already-existing tables since. An ordinary app update runs this
    // path -- previously it called resetTables() here too and silently
    // wiped every real recording on every single schema bump, which is
    // exactly the "왜 움직임 기록이 사라짐" bug this replaces.
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
        addColumnIfMissing(db, "motion_samples", "floor_delta", "INTEGER");
        addColumnIfMissing(db, "motion_samples", "accel_x", "REAL");
        addColumnIfMissing(db, "motion_samples", "accel_y", "REAL");
        addColumnIfMissing(db, "motion_samples", "accel_z", "REAL");
        addColumnIfMissing(db, "motion_samples", "gyro_x", "REAL");
        addColumnIfMissing(db, "motion_samples", "gyro_y", "REAL");
        addColumnIfMissing(db, "motion_samples", "gyro_z", "REAL");
        addColumnIfMissing(db, "motion_samples", "mag_x", "REAL");
        addColumnIfMissing(db, "motion_samples", "mag_y", "REAL");
        addColumnIfMissing(db, "motion_samples", "mag_z", "REAL");
        addColumnIfMissing(db, "motion_samples", "pressure_hpa", "REAL");
        addColumnIfMissing(db, "motion_samples", "top_rssi", "INTEGER");
        addColumnIfMissing(db, "place_fingerprints", "x", "REAL");
        addColumnIfMissing(db, "place_fingerprints", "y", "REAL");
        addColumnIfMissing(db, "motion_samples", "grav_x", "REAL");
        addColumnIfMissing(db, "motion_samples", "grav_y", "REAL");
        addColumnIfMissing(db, "motion_samples", "grav_z", "REAL");
        addColumnIfMissing(db, "motion_samples", "lin_accel_x", "REAL");
        addColumnIfMissing(db, "motion_samples", "lin_accel_y", "REAL");
        addColumnIfMissing(db, "motion_samples", "lin_accel_z", "REAL");
        addColumnIfMissing(db, "motion_samples", "vert_accel", "REAL");
        addColumnIfMissing(db, "motion_samples", "floor_ref_pressure", "REAL");
        addColumnIfMissing(db, "motion_samples", "floor_offset_raw", "REAL");
        addColumnIfMissing(db, "motion_samples", "connected_rssi", "INTEGER");
    }

    // ALTER TABLE ADD COLUMN has no IF NOT EXISTS in the SQLite versions
    // Android ships, so this just tries it and swallows the "duplicate
    // column name" failure -- the state that error means (the column is
    // already there) is exactly the state this method exists to ensure.
    // table/column/type are always this file's own hardcoded literals,
    // never external input, so string-building the statement is safe.
    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (android.database.sqlite.SQLiteException alreadyExists) {
            // Column already there -- nothing to do.
        }
    }

    // Debug builds all share one fixed signing key specifically so
    // `adb install -r` works across commits without uninstalling first
    // (see app/build.gradle) -- which means a device can easily end up
    // with an on-device DB_VERSION newer than whatever's checked out
    // right now (e.g. after testing a later commit, then an earlier one).
    // SQLiteOpenHelper's default onDowngrade() just throws
    // ("Can't downgrade database from version X to Y"), crashing the app
    // the moment this DB is touched. Unlike onUpgrade() above, there's no
    // safe additive path backward -- code that expects an older schema
    // can't be handed columns/tables from a newer one it doesn't know
    // about -- so this is the one remaining case that still resets. Since
    // this table only holds expendable, anonymous, on-device-only mapping
    // data (see class doc), that's a fine trade for never crashing here.
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        resetTables(db);
    }

    private void resetTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS sessions");
        db.execSQL("DROP TABLE IF EXISTS radio_scans");
        db.execSQL("DROP TABLE IF EXISTS motion_samples");
        db.execSQL("DROP TABLE IF EXISTS waypoints");
        db.execSQL("DROP TABLE IF EXISTS place_fingerprints");
        db.execSQL("DROP TABLE IF EXISTS radio_rtt");
        db.execSQL("DROP TABLE IF EXISTS sensor_snapshots");
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
                                    int stepCount, double x, double y, int floorDelta,
                                    float accelX, float accelY, float accelZ,
                                    float gyroX, float gyroY, float gyroZ,
                                    float magX, float magY, float magZ,
                                    float pressureHpa, int topRssi,
                                    float gravX, float gravY, float gravZ,
                                    float linAccelX, float linAccelY, float linAccelZ,
                                    float vertAccel, float floorRefPressure, float floorOffsetRaw,
                                    int connectedRssi) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("ts", ts);
        cv.put("heading_deg", headingDeg);
        cv.put("pitch_deg", pitchDeg);
        cv.put("roll_deg", rollDeg);
        cv.put("step_count", stepCount);
        cv.put("x", x);
        cv.put("y", y);
        cv.put("floor_delta", floorDelta);
        cv.put("accel_x", accelX); cv.put("accel_y", accelY); cv.put("accel_z", accelZ);
        cv.put("gyro_x", gyroX); cv.put("gyro_y", gyroY); cv.put("gyro_z", gyroZ);
        cv.put("mag_x", magX); cv.put("mag_y", magY); cv.put("mag_z", magZ);
        cv.put("pressure_hpa", pressureHpa);
        cv.put("top_rssi", topRssi);
        cv.put("grav_x", gravX); cv.put("grav_y", gravY); cv.put("grav_z", gravZ);
        cv.put("lin_accel_x", linAccelX); cv.put("lin_accel_y", linAccelY); cv.put("lin_accel_z", linAccelZ);
        cv.put("vert_accel", vertAccel);
        cv.put("floor_ref_pressure", floorRefPressure);
        cv.put("floor_offset_raw", floorOffsetRaw);
        cv.put("connected_rssi", connectedRssi);
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

    public void insertPlaceFingerprint(long ts, String floor, String label, String bssid, int rssi, int freq, double x, double y) {
        ContentValues cv = new ContentValues();
        cv.put("ts", ts);
        cv.put("floor", floor);
        cv.put("label", label);
        cv.put("bssid", bssid);
        cv.put("rssi", rssi);
        cv.put("freq", freq);
        cv.put("x", x);
        cv.put("y", y);
        getWritableDatabase().insert("place_fingerprints", null, cv);
    }

    public void insertRadioRtt(long sessionId, long ts, String bssid, int distMm, int stdDevMm, int rssi) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("ts", ts);
        cv.put("bssid", bssid);
        cv.put("distance_mm", distMm);
        cv.put("stddev_mm", stdDevMm);
        cv.put("rssi", rssi);
        getWritableDatabase().insert("radio_rtt", null, cv);
    }

    // See MappingCollector.snapshotSensors() -- one full raw-sensor
    // snapshot at a manually-flagged instant, distinct from the automatic
    // per-step motion_samples rows above.
    public void insertSensorSnapshot(long sessionId, long ts, String label,
                                      float accelX, float accelY, float accelZ,
                                      float gyroX, float gyroY, float gyroZ,
                                      float magX, float magY, float magZ,
                                      float pressureHpa, float headingDeg, float pitchDeg, float rollDeg,
                                      int topRssi, int floorDelta, double x, double y) {
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("ts", ts);
        cv.put("label", label);
        cv.put("accel_x", accelX); cv.put("accel_y", accelY); cv.put("accel_z", accelZ);
        cv.put("gyro_x", gyroX); cv.put("gyro_y", gyroY); cv.put("gyro_z", gyroZ);
        cv.put("mag_x", magX); cv.put("mag_y", magY); cv.put("mag_z", magZ);
        cv.put("pressure_hpa", pressureHpa);
        cv.put("heading_deg", headingDeg);
        cv.put("pitch_deg", pitchDeg);
        cv.put("roll_deg", rollDeg);
        cv.put("top_rssi", topRssi);
        cv.put("floor_delta", floorDelta);
        cv.put("x", x);
        cv.put("y", y);
        getWritableDatabase().insert("sensor_snapshots", null, cv);
    }

    public static class Counts {
        public int sessions, scans, samples, waypoints, sensorSnapshots;
    }

    public Counts counts() {
        Counts c = new Counts();
        SQLiteDatabase db = getReadableDatabase();
        c.sessions = countRows(db, "SELECT COUNT(*) FROM sessions");
        c.scans = countRows(db, "SELECT COUNT(*) FROM radio_scans");
        c.samples = countRows(db, "SELECT COUNT(*) FROM motion_samples");
        c.waypoints = countRows(db, "SELECT COUNT(*) FROM waypoints");
        c.sensorSnapshots = countRows(db, "SELECT COUNT(*) FROM sensor_snapshots");
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
    // Differential RSSI difference between a live scan and a stored fingerprint.
    // Instead of absolute RSSI, we look at differences relative to the
    // strongest shared AP (e.g. AP1-AP2, AP1-AP3) to neutralize antenna
    // gain variations between different phone models/cases.
    private static double fingerprintDistance(java.util.Map<String, Integer> liveRssi,
                                               java.util.Map<String, Integer> otherRssi) {
        if (liveRssi.isEmpty() || otherRssi.isEmpty()) return -1;
        
        // Find strongest shared AP to use as baseline
        String anchorBssid = null;
        int maxRssi = -120;
        for (String bssid : liveRssi.keySet()) {
            if (otherRssi.containsKey(bssid)) {
                int r = liveRssi.get(bssid);
                if (r > maxRssi) { maxRssi = r; anchorBssid = bssid; }
            }
        }
        if (anchorBssid == null) return -1;

        int liveAnchor = liveRssi.get(anchorBssid);
        int otherAnchor = otherRssi.get(anchorBssid);

        double sumSq = 0;
        int count = 0;
        for (java.util.Map.Entry<String, Integer> e : liveRssi.entrySet()) {
            Integer other = otherRssi.get(e.getKey());
            if (other != null) {
                // Differential RSSI: (RSSI_i - RSSI_anchor)
                double liveDiff = e.getValue() - liveAnchor;
                double otherDiff = other - otherAnchor;
                double error = liveDiff - otherDiff;
                sumSq += error * error;
                count++;
            } else {
                sumSq += 400; // Fixed mismatch penalty
                count++;
            }
        }
        return Math.sqrt(sumSq / count);
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
        // NaN for a tag recorded before the x/y columns existed (a device
        // still on an older export/DB that never got a value written) --
        // estimateLocationFromAnchor() below skips those, since they have
        // no asserted position to correct toward.
        double x = Double.NaN, y = Double.NaN;
        java.util.Map<String, Integer> rssiByBssid = new java.util.HashMap<>();
    }

    private List<PlaceFingerprint> allPlaceFingerprints() {
        java.util.LinkedHashMap<Long, PlaceFingerprint> byTs = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT ts, floor, label, bssid, rssi, x, y FROM place_fingerprints ORDER BY ts", null)) {
            while (cur.moveToNext()) {
                long ts = cur.getLong(0);
                String floor = cur.getString(1);
                String label = cur.getString(2);
                double x = cur.isNull(5) ? Double.NaN : cur.getDouble(5);
                double y = cur.isNull(6) ? Double.NaN : cur.getDouble(6);
                PlaceFingerprint pf = byTs.computeIfAbsent(ts, k -> {
                    PlaceFingerprint p = new PlaceFingerprint();
                    p.floor = floor;
                    p.label = label;
                    p.x = x;
                    p.y = y;
                    return p;
                });
                pf.rssiByBssid.put(cur.getString(3), cur.getInt(4));
            }
        }
        return new ArrayList<>(byTs.values());
    }

    // Registered-anchor counterpart of estimateLocationFromFingerprint()
    // above: matches a live scan against manually-tagged place_fingerprints
    // (see MappingCollector.addPlaceTag()) instead of every passively-
    // collected radio_scans row. An anchor's x/y is a real place the user
    // deliberately tagged, not just wherever dead reckoning happened to be
    // when an automatic scan landed, so a confident match here is a much
    // stronger "I am definitely back at this exact spot" signal -- see
    // MappingCollector.applyAnchorCorrection(), which uses this to correct
    // much harder than the passive blend. Same {x, y, avgMatchDistance}
    // shape and null-when-nothing-comparable behavior as
    // estimateLocationFromFingerprint().
    public double[] estimateLocationFromAnchor(java.util.Map<String, Integer> liveRssi, int k) {
        if (liveRssi == null || liveRssi.isEmpty()) return null;
        List<PlaceFingerprint> all = allPlaceFingerprints();
        if (all.isEmpty()) return null;

        List<double[]> scored = new ArrayList<>(); // {distance, x, y}
        for (PlaceFingerprint pf : all) {
            if (Double.isNaN(pf.x) || Double.isNaN(pf.y)) continue;
            double dist = fingerprintDistance(liveRssi, pf.rssiByBssid);
            if (dist < 0) continue;
            scored.add(new double[]{dist, pf.x, pf.y});
        }
        if (scored.isEmpty()) return null;

        scored.sort((a, b) -> Double.compare(a[0], b[0]));
        int n = Math.min(k, scored.size());
        double wSum = 0, wx = 0, wy = 0, distSum = 0;
        for (int i = 0; i < n; i++) {
            double[] s = scored.get(i);
            double w = 1.0 / (s[0] + 1.0);
            wSum += w;
            wx += w * s[1];
            wy += w * s[2];
            distSum += s[0];
        }
        return new double[]{wx / wSum, wy / wSum, distSum / n};
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

    // Chronological (x, y, floor) trail from the most recent motion
    // samples, for the Settings 3D path drawing -- floor lets that view
    // show the route's actual rise/fall instead of only ever drawing it
    // flat. Small on-demand read, same reasoning as estimateApPositions()
    // above.
    public List<double[]> recentPath(int limit) {
        List<double[]> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT x, y, floor_delta FROM motion_samples ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cur.moveToNext()) {
                out.add(new double[]{cur.getDouble(0), cur.getDouble(1), cur.getDouble(2)});
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    // Like recentPath(), but scoped to one session instead of "most recent
    // N rows overall" -- the continuous per-second heartbeat
    // (MappingCollector.motionRecordTick) plus per-step rows means a fixed
    // row-count limit now covers much less real time than it used to
    // (denser writes eat the budget faster), so the drawn path visibly
    // shrank/lost its start the longer a walk went on. `limit` here is
    // just a sanity cap against a many-hours background session ballooning
    // the view's render cost, not something a normal walk should ever hit.
    public List<double[]> pathForSession(long sessionId, int limit) {
        List<double[]> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT x, y, floor_delta FROM motion_samples WHERE session_id=? ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(sessionId), String.valueOf(limit)})) {
            while (cur.moveToNext()) {
                out.add(new double[]{cur.getDouble(0), cur.getDouble(1), cur.getDouble(2)});
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
        writer.write("type,session_id,ts,heading_deg,pitch_deg,roll_deg,step_count,x,y,floor_delta," +
                "accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z,pressure_hpa,top_rssi," +
                "grav_x,grav_y,grav_z,lin_accel_x,lin_accel_y,lin_accel_z," +
                "vert_accel,floor_ref_pressure,floor_offset_raw,connected_rssi,floor,label\n");
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cur = db.rawQuery(
                "SELECT session_id, ts, heading_deg, pitch_deg, roll_deg, step_count, x, y, floor_delta, " +
                        "accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, pressure_hpa, top_rssi, " +
                        "grav_x, grav_y, grav_z, lin_accel_x, lin_accel_y, lin_accel_z, " +
                        "vert_accel, floor_ref_pressure, floor_offset_raw, connected_rssi " +
                        "FROM motion_samples ORDER BY session_id, ts", null)) {
            while (cur.moveToNext()) {
                writer.write(csvRow("motion", cur.getLong(0), cur.getLong(1),
                        String.valueOf(cur.getFloat(2)), String.valueOf(cur.getFloat(3)), String.valueOf(cur.getFloat(4)),
                        String.valueOf(cur.getInt(5)), cur.getDouble(6), cur.getDouble(7),
                        String.valueOf(cur.getInt(8)),
                        String.valueOf(cur.getFloat(9)), String.valueOf(cur.getFloat(10)), String.valueOf(cur.getFloat(11)),
                        String.valueOf(cur.getFloat(12)), String.valueOf(cur.getFloat(13)), String.valueOf(cur.getFloat(14)),
                        String.valueOf(cur.getFloat(15)), String.valueOf(cur.getFloat(16)), String.valueOf(cur.getFloat(17)),
                        String.valueOf(cur.getFloat(18)), String.valueOf(cur.getInt(19)),
                        String.valueOf(cur.getFloat(20)), String.valueOf(cur.getFloat(21)), String.valueOf(cur.getFloat(22)),
                        String.valueOf(cur.getFloat(23)), String.valueOf(cur.getFloat(24)), String.valueOf(cur.getFloat(25)),
                        String.valueOf(cur.getFloat(26)), String.valueOf(cur.getFloat(27)), String.valueOf(cur.getFloat(28)),
                        String.valueOf(cur.getInt(29)),
                        "", ""));
            }
        }
        try (Cursor cur = db.rawQuery(
                "SELECT session_id, ts, floor, label, x, y FROM waypoints ORDER BY session_id, ts", null)) {
            while (cur.moveToNext()) {
                writer.write(csvRow("waypoint", cur.getLong(0), cur.getLong(1),
                        "", "", "", "", cur.getDouble(4), cur.getDouble(5),
                        "", "", "", "", "", "", "", "", "", "", "", "",
                        "", "", "", "", "", "", "", "", "", "",
                        cur.getString(2), cur.getString(3)));
            }
        }
    }

    private static String csvRow(String type, long sessionId, long ts, String heading, String pitch, String roll,
                                  String stepCount, double x, double y, String floorDelta,
                                  String accelX, String accelY, String accelZ,
                                  String gyroX, String gyroY, String gyroZ,
                                  String magX, String magY, String magZ,
                                  String pressureHpa, String topRssi,
                                  String gravX, String gravY, String gravZ,
                                  String linAccelX, String linAccelY, String linAccelZ,
                                  String vertAccel, String floorRefPressure, String floorOffsetRaw,
                                  String connectedRssi,
                                  String floor, String label) {
        return String.join(",", type, String.valueOf(sessionId), String.valueOf(ts), heading, pitch, roll,
                stepCount, String.valueOf(x), String.valueOf(y), csvEscape(floorDelta),
                csvEscape(accelX), csvEscape(accelY), csvEscape(accelZ),
                csvEscape(gyroX), csvEscape(gyroY), csvEscape(gyroZ),
                csvEscape(magX), csvEscape(magY), csvEscape(magZ),
                csvEscape(pressureHpa), csvEscape(topRssi),
                csvEscape(gravX), csvEscape(gravY), csvEscape(gravZ),
                csvEscape(linAccelX), csvEscape(linAccelY), csvEscape(linAccelZ),
                csvEscape(vertAccel), csvEscape(floorRefPressure), csvEscape(floorOffsetRaw),
                csvEscape(connectedRssi),
                csvEscape(floor), csvEscape(label)) + "\n";
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

    // Dumps every row of one table as a JSONArray of {column: value}
    // objects -- shared by exportAllData() below. `table` is always one of
    // the hardcoded literals passed by exportAllData(), never external
    // input, so concatenating it into the query has no injection surface.
    // getString(i) stringifies every column uniformly (ints/reals/text
    // alike), which is fine for a debug/export dump.
    private JSONArray dumpTable(SQLiteDatabase db, String table) throws JSONException {
        JSONArray arr = new JSONArray();
        try (Cursor cur = db.rawQuery("SELECT * FROM " + table, null)) {
            while (cur.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cur.getColumnCount(); i++) {
                    row.put(cur.getColumnName(i), cur.isNull(i) ? JSONObject.NULL : cur.getString(i));
                }
                arr.put(row);
            }
        }
        return arr;
    }

    // Everything this feature has collected, as one JSON document -- lets
    // the user pull the whole local dataset off the device as a file (see
    // MainActivity's "내보내기" button), since nothing here is uploaded on
    // its own (see class doc).
    public JSONObject exportAllData() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject out = new JSONObject();
        out.put("exported_at", System.currentTimeMillis());
        out.put("sessions", dumpTable(db, "sessions"));
        out.put("radio_scans", dumpTable(db, "radio_scans"));
        out.put("motion_samples", dumpTable(db, "motion_samples"));
        out.put("waypoints", dumpTable(db, "waypoints"));
        out.put("place_fingerprints", dumpTable(db, "place_fingerprints"));
        out.put("radio_rtt", dumpTable(db, "radio_rtt"));
        return out;
    }
}
