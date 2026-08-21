package dev.rocky.comcitime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.OrientationEventListener;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Drives one indoor-mapping data-collection session: periodic Wi-Fi scans
// (BSSID/RSSI/frequency) plus dead reckoning from motion sensors, alongside
// a set of lower-priority raw signals (raw accelerometer/gyroscope/
// magnetometer, barometer, a rough GPS/GNSS fix, screen rotation) kept
// only as live in-memory history for the Settings debug graphs -- not
// persisted, since they're only useful as a live sanity check on what the
// higher-priority signals above are already doing with them. Everything
// that IS persisted goes through MappingDb on-device only -- there is no
// network upload in this build.
//
// Dead reckoning deliberately avoids two things a naive implementation
// would lean on:
//  - The magnetometer-fused compass heading, for the direction of travel.
//    Indoors, magnetic fields are heavily distorted by rebar/wiring/metal
//    furniture, so a compass heading can be badly wrong right where this
//    matters most. Instead, direction is tracked by integrating the raw
//    gyroscope's yaw rate (gyroYawDeg below), bootstrapped once from the
//    fused heading at startup -- immune to magnetic interference, at the
//    cost of slow drift, which the Wi-Fi fingerprint correction below
//    keeps in check.
//  - A fixed, user-entered stride length, for step distance. Actual
//    stride varies with walking speed and isn't something most people
//    know precisely. Instead each step's length is estimated from that
//    step's own accelerometer swing via Weinberg's formula (see
//    estimateStepLength()), a standard step-length-estimation technique.
//
// The resulting trajectory only needs to be locally consistent, not
// metrically perfect: every Wi-Fi scan is tagged with the position dead
// reckoning reports at that instant, building a fingerprint map (see
// MappingDb.estimateLocationFromFingerprint) that later scans get matched
// against -- and each time a scan finds a confident fingerprint match,
// the position is nudged toward it (see recordScanResults()), a simple
// complementary-filter fusion that pulls accumulated drift back in line
// without needing a full Kalman/particle filter for this experimental
// scope.
public class MappingCollector {

    public interface Listener {
        void onScanCount(int count);
        void onHeadingSteps(float headingDeg, int steps);
    }

    // Well under Android's Wi-Fi scan throttle (4 scans / 2 min foreground),
    // so requestScan() doesn't silently get dropped by the platform.
    private static final long SCAN_INTERVAL_MS = 30_000;

    // How many samples of live raw-sensor history to keep for the
    // Settings graphs (pushRawHistorySample() is called once/sec from the
    // UI tick while that section is open) -- a couple of minutes' worth.
    public static final int RAW_HISTORY_SIZE = 120;

    // Meters of altitude per hPa of pressure change, a standard
    // near-sea-level approximation good enough to guess a floor change
    // from the barometer without needing a full barometric-formula fit.
    private static final float METERS_PER_HPA = 8.3f;
    private static final float FLOOR_HEIGHT_M = 3.5f;

    private final Context ctx;
    private final MappingDb db;
    private final WifiManager wifiManager;
    private final SensorManager sensorManager;
    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private Listener listener;

    private long sessionId = -1;
    private boolean running = false;
    private int stepCount = 0;
    // Full 3D orientation from the fused rotation-vector sensor (itself
    // derived from gyroscope + accelerometer + magnetometer/compass) --
    // heading drives dead reckoning, pitch/roll are recorded alongside it
    // so the phone's tilt during each scan/step is captured too, not just
    // its heading.
    private float headingDeg = 0f, pitchDeg = 0f, rollDeg = 0f;
    private double posX = 0, posY = 0;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    // Gyroscope-integrated yaw, used for dead-reckoning direction instead
    // of the magnetometer-fused heading above (see class doc). NaN until
    // bootstrapped once from the first fused-heading reading.
    private double gyroYawDeg = Double.NaN;
    private long lastGyroTimestampNs = 0;

    // Running min/max of accelerometer magnitude since the last step,
    // feeding Weinberg's per-step dynamic stride-length estimate (see
    // estimateStepLength()) instead of a fixed constant.
    private float accelMinInStep = Float.MAX_VALUE, accelMaxInStep = -Float.MAX_VALUE;
    private double lastStepLengthM = 0.7;
    private static final double STEP_LENGTH_K = 0.5; // Weinberg's empirical constant

    // Last Wi-Fi scan's {bssid: rssi}, used both to persist the scan and
    // to match it against the fingerprint map for the drift correction
    // described in the class doc.
    private java.util.Map<String, Integer> lastScanRssi = new java.util.HashMap<>();

    // ---- lower-priority raw signals (see class doc): latest value only,
    // plus a rolling history buffer per signal for the debug graphs.
    private float accelMag = 0f, gyroMag = 0f, magFieldUt = 0f, pressureHpa = 0f, refPressureHpa = 0f;
    private int lastTopRssi = -120;
    private int screenRotationDeg = -1;
    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private final float[] accelHistory = new float[RAW_HISTORY_SIZE];
    private final float[] gyroHistory = new float[RAW_HISTORY_SIZE];
    private final float[] magHistory = new float[RAW_HISTORY_SIZE];
    private final float[] pressureHistory = new float[RAW_HISTORY_SIZE];
    private final float[] rssiHistory = new float[RAW_HISTORY_SIZE];
    private int historyCount = 0;
    private OrientationEventListener orientationEventListener;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (running) recordScanResults();
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override
        public void onProviderEnabled(String provider) {}
        @Override
        public void onProviderDisabled(String provider) {}
    };

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            switch (event.sensor.getType()) {
                case Sensor.TYPE_ROTATION_VECTOR:
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                    SensorManager.getOrientation(rotationMatrix, orientation);
                    float deg = (float) Math.toDegrees(orientation[0]);
                    headingDeg = (deg + 360f) % 360f;
                    pitchDeg = (float) Math.toDegrees(orientation[1]);
                    rollDeg = (float) Math.toDegrees(orientation[2]);
                    if (Double.isNaN(gyroYawDeg)) gyroYawDeg = headingDeg; // one-time bootstrap
                    break;
                case Sensor.TYPE_STEP_DETECTOR:
                    stepCount++;
                    double stepLen = estimateStepLength();
                    double dirDeg = Double.isNaN(gyroYawDeg) ? headingDeg : gyroYawDeg;
                    double rad = Math.toRadians(dirDeg);
                    posX += stepLen * Math.sin(rad);
                    posY += stepLen * Math.cos(rad);
                    lastStepLengthM = stepLen;
                    accelMinInStep = Float.MAX_VALUE;
                    accelMaxInStep = -Float.MAX_VALUE;
                    recordMotionSample();
                    break;
                case Sensor.TYPE_ACCELEROMETER:
                    accelMag = vectorMag(event.values);
                    accelMinInStep = Math.min(accelMinInStep, accelMag);
                    accelMaxInStep = Math.max(accelMaxInStep, accelMag);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    gyroMag = vectorMag(event.values);
                    if (lastGyroTimestampNs != 0 && !Double.isNaN(gyroYawDeg)) {
                        double dt = (event.timestamp - lastGyroTimestampNs) / 1_000_000_000.0;
                        // z-axis angular rate approximates yaw rate while the
                        // phone is held roughly upright, consistent with the
                        // rest of this class's simplifications.
                        double dYaw = Math.toDegrees(event.values[2]) * dt;
                        gyroYawDeg = ((gyroYawDeg + dYaw) % 360 + 360) % 360;
                    }
                    lastGyroTimestampNs = event.timestamp;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    magFieldUt = vectorMag(event.values);
                    break;
                case Sensor.TYPE_PRESSURE:
                    pressureHpa = event.values[0];
                    if (refPressureHpa == 0f) refPressureHpa = pressureHpa;
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private static float vectorMag(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    // Weinberg's dynamic step-length formula: length = K * (a_max - a_min)^(1/4),
    // over the accelerometer swing observed during the step that just
    // finished. Clamped to a physically plausible walking-stride range so
    // a noisy first step (before accelMinInStep/accelMaxInStep have a real
    // window) can't produce an absurd jump.
    private double estimateStepLength() {
        if (accelMaxInStep <= accelMinInStep) return lastStepLengthM;
        double swing = accelMaxInStep - accelMinInStep;
        double len = STEP_LENGTH_K * Math.pow(swing, 0.25);
        return Math.max(0.3, Math.min(1.0, len));
    }

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            requestScan();
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    public MappingCollector(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = new MappingDb(this.ctx);
        this.wifiManager = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
        this.sensorManager = (SensorManager) this.ctx.getSystemService(Context.SENSOR_SERVICE);
        this.locationManager = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public float getHeadingDeg() { return headingDeg; }
    public double getLastStepLengthM() { return lastStepLengthM; }
    public java.util.Map<String, Integer> getLastScanRssi() { return lastScanRssi; }
    public float getPitchDeg() { return pitchDeg; }
    public float getRollDeg() { return rollDeg; }
    public int getStepCount() { return stepCount; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }

    public float getAccelMag() { return accelMag; }
    public float getGyroMag() { return gyroMag; }
    public float getMagFieldUt() { return magFieldUt; }
    public float getPressureHpa() { return pressureHpa; }
    public int getLastTopRssi() { return lastTopRssi; }
    public int getScreenRotationDeg() { return screenRotationDeg; }
    public double getLastLat() { return lastLat; }
    public double getLastLon() { return lastLon; }

    // Rough floor change since this session started, from the barometric
    // pressure delta -- lowest-priority signal here (★★★☆☆), just a
    // sanity-check estimate, not a substitute for real floor detection.
    public int getEstimatedFloorDelta() {
        if (refPressureHpa == 0f || pressureHpa == 0f) return 0;
        float meters = (refPressureHpa - pressureHpa) * METERS_PER_HPA;
        return Math.round(meters / FLOOR_HEIGHT_M);
    }

    public int getHistoryCount() { return historyCount; }
    public float[] getAccelHistory() { return accelHistory; }
    public float[] getGyroHistory() { return gyroHistory; }
    public float[] getMagHistory() { return magHistory; }
    public float[] getPressureHistory() { return pressureHistory; }
    public float[] getRssiHistory() { return rssiHistory; }

    // Called once/sec from the Settings UI tick (only while that section
    // is visible) to snapshot the current raw values into the rolling
    // history buffers the debug graphs read from.
    public void pushRawHistorySample() {
        shiftAndSet(accelHistory, accelMag);
        shiftAndSet(gyroHistory, gyroMag);
        shiftAndSet(magHistory, magFieldUt);
        shiftAndSet(pressureHistory, pressureHpa);
        shiftAndSet(rssiHistory, lastTopRssi);
        if (historyCount < RAW_HISTORY_SIZE) historyCount++;
    }

    private static void shiftAndSet(float[] buf, float value) {
        System.arraycopy(buf, 1, buf, 0, buf.length - 1);
        buf[buf.length - 1] = value;
    }

    // Re-zeroes the dead-reckoning position so "distance from origin"
    // starts counting from wherever the phone is right now, instead of
    // from session start -- lets Settings offer a reset without having to
    // stop/restart the whole background service.
    public void resetOrigin() {
        posX = 0;
        posY = 0;
    }

    public void start() {
        if (running) return;
        running = true;
        stepCount = 0;
        posX = 0;
        posY = 0;
        refPressureHpa = 0f;
        historyCount = 0;
        gyroYawDeg = Double.NaN;
        lastGyroTimestampNs = 0;
        accelMinInStep = Float.MAX_VALUE;
        accelMaxInStep = -Float.MAX_VALUE;
        sessionId = db.startSession();

        ctx.registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        registerIfAvailable(Sensor.TYPE_ROTATION_VECTOR);
        registerIfAvailable(Sensor.TYPE_STEP_DETECTOR);
        registerIfAvailable(Sensor.TYPE_ACCELEROMETER);
        registerIfAvailable(Sensor.TYPE_GYROSCOPE);
        registerIfAvailable(Sensor.TYPE_MAGNETIC_FIELD);
        registerIfAvailable(Sensor.TYPE_PRESSURE);

        orientationEventListener = new OrientationEventListener(ctx) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) screenRotationDeg = orientation;
            }
        };
        if (orientationEventListener.canDetectOrientation()) orientationEventListener.enable();

        startLocationUpdates();

        handler.post(scanTick);
    }

    private void registerIfAvailable(int sensorType) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if (sensor != null) sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI);
    }

    // GPS/GNSS is the lowest-priority signal here (★★☆☆☆, just a rough
    // absolute position of the building) so this asks for an infrequent
    // fix and quietly does nothing if no provider is available -- never
    // worth failing the whole collector over.
    private void startLocationUpdates() {
        if (locationManager == null) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            String provider = locationManager.getBestProvider(criteria, true);
            if (provider != null) {
                locationManager.requestLocationUpdates(provider, 5 * 60_000L, 0f, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // no usable location provider on this device -- fine, GPS is optional here
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(scanTick);
        try {
            ctx.unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered -- nothing to do
        }
        sensorManager.unregisterListener(sensorListener);
        if (orientationEventListener != null) orientationEventListener.disable();
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
            // permission revoked mid-session -- nothing to clean up
        }
        long sid = sessionId;
        dbExecutor.execute(() -> db.endSession(sid));
    }

    // The waypoint is tagged at the position dead reckoning has already
    // computed automatically -- this is an optional ground-truth label on
    // top of that, not how the position itself is obtained.
    public void addWaypoint(String floor, String label) {
        if (sessionId < 0) return;
        long sid = sessionId;
        double x = posX, y = posY;
        dbExecutor.execute(() -> db.insertWaypoint(sid, floor, label, x, y));
    }

    // Synchronous on purpose -- only called from an explicit user tap
    // (not on a hot path), so a brief direct read is fine.
    public MappingDb.Counts counts() {
        return db.counts();
    }

    @SuppressWarnings("deprecation") // startScan() is the only API that works across minSdk 26..34
    private void requestScan() {
        try {
            wifiManager.startScan();
        } catch (Exception ignored) {
            // e.g. scan throttled or Wi-Fi off -- next tick will retry
        }
    }

    private void recordScanResults() {
        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            return; // permission revoked mid-session
        }
        int top = -120;
        java.util.Map<String, Integer> rssiByBssid = new java.util.HashMap<>();
        for (ScanResult r : results) {
            if (r.level > top) top = r.level;
            rssiByBssid.put(r.BSSID, r.level);
        }
        lastTopRssi = top;
        lastScanRssi = rssiByBssid;
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        double x = posX, y = posY;
        dbExecutor.execute(() -> {
            for (ScanResult r : results) {
                db.insertRadioScan(sid, ts, r.BSSID, r.level, r.frequency, x, y);
            }
            // Complementary-filter fusion (see class doc): a confident
            // fingerprint match nudges the dead-reckoned position back
            // toward it, so accumulated gyro/step drift gets corrected by
            // every fresh scan instead of only ever growing.
            double[] match = db.estimateLocationFromFingerprint(rssiByBssid, 5);
            if (match != null) {
                handler.post(() -> {
                    double blend = 0.25;
                    posX = posX * (1 - blend) + match[0] * blend;
                    posY = posY * (1 - blend) + match[1] * blend;
                });
            }
        });
        if (listener != null) listener.onScanCount(results.size());
    }

    private void recordMotionSample() {
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        float h = headingDeg, p = pitchDeg, r = rollDeg;
        int steps = stepCount;
        double x = posX, y = posY;
        dbExecutor.execute(() -> db.insertMotionSample(sid, ts, h, p, r, steps, x, y));
        if (listener != null) listener.onHeadingSteps(h, steps);
    }
}
