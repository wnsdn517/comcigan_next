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
// (BSSID/RSSI/frequency) plus a fused compass heading and step count from
// motion sensors, alongside a set of lower-priority raw signals (raw
// accelerometer/gyroscope/magnetometer, barometer, a rough GPS/GNSS fix,
// screen rotation) kept only as live in-memory history for the Settings
// debug graphs -- not persisted, since they're only useful as a live
// sanity check on what the higher-priority signals above are already
// doing with them. Everything that IS persisted goes through MappingDb
// on-device only -- there is no network upload in this build.
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

    // Stride length in meters, used to turn a step count into a distance
    // for dead reckoning. User-calibratable in Settings (Prefs.strideLengthM())
    // since actual stride varies by person; setStrideLengthM() applies the
    // change immediately to whichever session is currently running.
    private double strideLengthM;

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
                    break;
                case Sensor.TYPE_STEP_DETECTOR:
                    stepCount++;
                    double rad = Math.toRadians(headingDeg);
                    posX += strideLengthM * Math.sin(rad);
                    posY += strideLengthM * Math.cos(rad);
                    recordMotionSample();
                    break;
                case Sensor.TYPE_ACCELEROMETER:
                    accelMag = vectorMag(event.values);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    gyroMag = vectorMag(event.values);
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
        this.strideLengthM = new Prefs(this.ctx).strideLengthM();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public void setStrideLengthM(double meters) {
        this.strideLengthM = meters;
    }

    public float getHeadingDeg() { return headingDeg; }
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
        for (ScanResult r : results) if (r.level > top) top = r.level;
        lastTopRssi = top;
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        double x = posX, y = posY;
        dbExecutor.execute(() -> {
            for (ScanResult r : results) {
                db.insertRadioScan(sid, ts, r.BSSID, r.level, r.frequency, x, y);
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
