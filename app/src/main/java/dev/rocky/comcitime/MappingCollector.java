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
//    gyroscope's yaw rate (gyroYawDeg below), only softly pulled toward
//    the fused heading a few percent per reading (HEADING_COMPASS_PULL)
//    -- immune to any single bad magnetometer reading, but still bounded
//    so per-sample gyro bias can't compound into unlimited drift over a
//    long walk, with the Wi-Fi fingerprint correction below as a second
//    line of defense on top of that.
//  - A fixed, user-entered stride length, for step distance. Actual
//    stride varies with walking speed and isn't something most people
//    know precisely. Instead each step's length is estimated from that
//    step's own accelerometer swing via Weinberg's formula (see
//    estimateStepLength()), a standard step-length-estimation technique.
//
// Steps themselves come from a custom accelerometer peak-detector
// (processCustomStepDetection()) instead of the platform's hardware
// TYPE_STEP_DETECTOR, which on many devices has a noticeable detection
// lag -- the custom detector fires the instant a step's peak crosses
// threshold, so the position update tracks actual footfalls more
// closely. A lightweight zero-velocity-style stationary detector
// (processStationaryDetection(), based on short-window accelerometer
// variance) also lets heading correction pull faster toward the compass
// while the phone is known to be still, when a compass reading isn't
// being muddied by step-related vibration.
//
// The resulting trajectory only needs to be locally consistent, not
// metrically perfect: every Wi-Fi scan is tagged with the position dead
// reckoning reports at that instant, building a fingerprint map (see
// MappingDb.estimateLocationFromFingerprint) that later scans get matched
// against, and each match corrects accumulated dead-reckoning drift via a
// small per-axis Kalman filter (see applyFingerprintCorrection()): each
// step's dead reckoning is the process model (adding STEP_PROCESS_NOISE
// uncertainty), each fingerprint match is a measurement whose uncertainty
// scales with how far (in RSSI-space) its nearest neighbors were. This
// mirrors the WiFi-kNN + Kalman-smoothing approach used by published
// Android indoor/outdoor positioning systems (e.g. the ESRI Cup-winning
// "In-outdoorSeamlessPositioningNavigationSystem" project), scaled down to
// one filter dimension per axis instead of a full multi-sensor state
// vector, which is enough for this experimental single-building scope.
public class MappingCollector {

    public interface Listener {
        void onScanCount(int count);
        void onHeadingSteps(float headingDeg, int steps);
    }

    // Well under Android's Wi-Fi scan throttle (4 scans / 2 min foreground),
    // so requestScan() doesn't silently get dropped by the platform.
    private static final long SCAN_INTERVAL_MS = 30_000;

    // How many samples of live raw-sensor history to keep for the
    // Settings graphs (pushRawHistorySample() is called every
    // MainActivity.MAPPING_TICK_MS, ~300ms, while that section is open)
    // -- a bit over a minute's worth.
    public static final int RAW_HISTORY_SIZE = 240;

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
    // Fraction of the gyro-vs-compass heading gap corrected per fused-
    // heading update (~SENSOR_DELAY_UI, tens of ms). Bumped up from an
    // initial 0.02 once the gyro's CW/CCW sign convention was fixed (see
    // the TYPE_GYROSCOPE case below) -- with the sign right, a larger
    // pull catches up to the compass faster without overshooting.
    private static final double HEADING_COMPASS_PULL = 0.05;
    // While the phone is detected stationary (see processStationaryDetection()),
    // pull harder toward the compass heading -- a compass reading isn't
    // being muddied by step-related vibration/magnetic noise right then,
    // so it's safe to trust more.
    private static final double HEADING_COMPASS_PULL_STATIONARY = 0.15;

    // Running min/max of accelerometer magnitude since the last step,
    // feeding Weinberg's per-step dynamic stride-length estimate (see
    // estimateStepLength()) instead of a fixed constant.
    private float accelMinInStep = Float.MAX_VALUE, accelMaxInStep = -Float.MAX_VALUE;
    private double lastStepLengthM = 0.7;
    private static final double STEP_LENGTH_K = 0.5; // Weinberg's empirical constant

    // Per-axis position uncertainty (variance, m^2) for the Kalman filter
    // described in the class doc. Starts moderately uncertain (~1m stdev);
    // resetOrigin() drops it near zero since a manual reset asserts a
    // known ground-truth point.
    private double posVarX = 1.0, posVarY = 1.0;
    private static final double STEP_PROCESS_NOISE = 0.05 * 0.05; // m^2 added per step

    // Last Wi-Fi scan's {bssid: rssi}, used both to persist the scan and
    // to match it against the fingerprint map for the drift correction
    // described in the class doc.
    private java.util.Map<String, Integer> lastScanRssi = new java.util.HashMap<>();

    // Custom low-latency step detector (accelerometer peak detection),
    // used instead of the platform's TYPE_STEP_DETECTOR -- see class doc.
    private static final float PEAK_THRESHOLD = 1.2f; // m/s^2 above gravity
    private static final long STEP_COOLDOWN_MS = 250;
    private long lastStepTimeMs = 0;
    private boolean peakSearching = true;

    // Cadence-consistency confirmation: a single accelerometer peak
    // crossing PEAK_THRESHOLD isn't credited as a step by itself -- a hand
    // tremor, a desk tap, or picking the phone up all cross that threshold
    // once too, and raising PEAK_THRESHOLD to filter those out just makes
    // real light footsteps get missed instead (the tradeoff the amplitude
    // threshold can't escape). What a genuine footstep has that an
    // isolated jolt never does is rhythm: it's followed by another peak
    // roughly a stride later. So a peak is only ever committed to
    // stepCount/position once a second peak confirms it within
    // MAX_STEP_INTERVAL_MS -- gating on *timing between peaks*, not their
    // amplitude, so it doesn't trade off against light-footstep detection.
    private static final long MAX_STEP_INTERVAL_MS = 1100; // ~55 steps/min lower bound
    private boolean inGaitStreak = false;
    private boolean pendingStepPresent = false;
    private double pendingStepLenM = 0;
    private double pendingStepDirDeg = 0;

    // Stationary detection (short-window accelerometer variance), used to
    // trust the compass more while the phone is known to be still -- see
    // class doc and HEADING_COMPASS_PULL_STATIONARY above.
    private static final float STATIONARY_THRESHOLD = 0.08f; // m/s^2 variance
    private boolean isStationary = true;
    private final float[] accelWindow = new float[10];
    private int accelWindowIdx = 0;

    // ---- lower-priority raw signals (see class doc): latest value only,
    // plus a rolling per-axis history buffer per signal for the debug
    // graphs, so accelerometer/gyroscope/magnetometer are shown as their
    // actual X/Y/Z components rather than collapsed into one magnitude
    // number (accelMag below is kept separately since Weinberg's formula
    // in estimateStepLength() genuinely needs the magnitude, not an axis).
    private float accelMag = 0f;
    private float accelX, accelY, accelZ, gyroX, gyroY, gyroZ, magX, magY, magZ;
    private float pressureHpa = 0f, refPressureHpa = 0f;
    private int lastTopRssi = -120;
    private int screenRotationDeg = -1;
    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private final float[] accelXHistory = new float[RAW_HISTORY_SIZE];
    private final float[] accelYHistory = new float[RAW_HISTORY_SIZE];
    private final float[] accelZHistory = new float[RAW_HISTORY_SIZE];
    private final float[] gyroXHistory = new float[RAW_HISTORY_SIZE];
    private final float[] gyroYHistory = new float[RAW_HISTORY_SIZE];
    private final float[] gyroZHistory = new float[RAW_HISTORY_SIZE];
    private final float[] magXHistory = new float[RAW_HISTORY_SIZE];
    private final float[] magYHistory = new float[RAW_HISTORY_SIZE];
    private final float[] magZHistory = new float[RAW_HISTORY_SIZE];
    // Separate from all of the above: the gyro-only integrated heading
    // over time (see gyroYawDeg/HEADING_COMPASS_PULL), so a walked test
    // loop's drift is directly visible as a trend line instead of only
    // inferred from the path drawing.
    private final float[] gyroYawHistory = new float[RAW_HISTORY_SIZE];
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
                case Sensor.TYPE_GAME_ROTATION_VECTOR:
                case Sensor.TYPE_ROTATION_VECTOR:
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                    SensorManager.getOrientation(rotationMatrix, orientation);
                    float deg = (float) Math.toDegrees(orientation[0]);
                    headingDeg = (deg + 360f) % 360f;
                    pitchDeg = (float) Math.toDegrees(orientation[1]);
                    rollDeg = (float) Math.toDegrees(orientation[2]);
                    if (Double.isNaN(gyroYawDeg)) {
                        gyroYawDeg = headingDeg; // one-time bootstrap
                    } else {
                        // Continuous small pull toward the compass heading
                        // so gyro-integration bias doesn't drift forever
                        // over a long session (see class doc) -- a fixed
                        // one-time bootstrap alone lets small per-sample
                        // gyro error compound into a growing rotation over
                        // many minutes, which is what a walked loop not
                        // closing on itself looks like. The weight is kept
                        // small so a single bad indoor-magnetic reading
                        // can't yank heading off course either, except
                        // while stationary (see field doc) when it's safe
                        // to trust the compass more.
                        double diff = shortestAngleDiffDeg(headingDeg, gyroYawDeg);
                        double pull = isStationary ? HEADING_COMPASS_PULL_STATIONARY : HEADING_COMPASS_PULL;
                        gyroYawDeg = ((gyroYawDeg + diff * pull) % 360 + 360) % 360;
                    }
                    break;
                case Sensor.TYPE_STEP_DETECTOR:
                    // Position updates now come from the custom low-latency
                    // peak detector in processCustomStepDetection() (driven
                    // off TYPE_ACCELEROMETER below), not this hardware
                    // event -- kept registered only so a device without a
                    // usable accelerometer stream still has a step source,
                    // but not counted here to avoid double-counting steps.
                    break;
                case Sensor.TYPE_ACCELEROMETER:
                    accelX = event.values[0]; accelY = event.values[1]; accelZ = event.values[2];
                    accelMag = vectorMag(event.values);
                    accelMinInStep = Math.min(accelMinInStep, accelMag);
                    accelMaxInStep = Math.max(accelMaxInStep, accelMag);
                    // Stationary detection runs first so processCustomStepDetection()
                    // below sees isStationary for *this* sample, not the
                    // previous one.
                    processStationaryDetection(accelMag);
                    processCustomStepDetection(accelMag);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    gyroX = event.values[0]; gyroY = event.values[1]; gyroZ = event.values[2];
                    if (lastGyroTimestampNs != 0 && !Double.isNaN(gyroYawDeg)) {
                        double dt = (event.timestamp - lastGyroTimestampNs) / 1_000_000_000.0;
                        // Android's gyroscope Z axis is CCW-positive, but
                        // compass azimuth (headingDeg) is CW-positive --
                        // subtracting dYaw (instead of adding) aligns the
                        // two conventions so gyro-integrated turns match
                        // the direction the compass would report.
                        double dYaw = Math.toDegrees(gyroZ) * dt;
                        gyroYawDeg = ((gyroYawDeg - dYaw) % 360 + 360) % 360;
                    }
                    lastGyroTimestampNs = event.timestamp;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    magX = event.values[0]; magY = event.values[1]; magZ = event.values[2];
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

    // Shortest signed angular gap from `current` to `target`, in [-180, 180)
    // -- without this, blending two headings across the 0/360 wraparound
    // (e.g. target=5, current=355) would pull the wrong way around.
    private static double shortestAngleDiffDeg(double target, double current) {
        return (target - current + 540) % 360 - 180;
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

    // Fires the moment the accelerometer magnitude's swing away from
    // gravity crosses PEAK_THRESHOLD, then waits for it to fall back
    // before searching for the next peak -- lower latency than the
    // platform's hardware step detector, which on many devices only
    // reports a step some tens to hundreds of ms after it actually
    // happened. STEP_COOLDOWN_MS guards against a single footfall's
    // vibration registering as more than one step.
    private void processCustomStepDetection(float mag) {
        long now = System.currentTimeMillis();
        float gravity = 9.81f;
        float relativeMag = Math.abs(mag - gravity);

        if (peakSearching && relativeMag > PEAK_THRESHOLD && (now - lastStepTimeMs) > STEP_COOLDOWN_MS) {
            onPeakDetected(now);
            peakSearching = false;
        } else if (!peakSearching && relativeMag < PEAK_THRESHOLD / 2) {
            peakSearching = true;
        }
    }

    // One accelerometer peak just crossed PEAK_THRESHOLD -- decide whether
    // to credit it as a step immediately, use it to confirm a step stashed
    // a stride ago, or stash it as pending itself. See the
    // MAX_STEP_INTERVAL_MS comment above for why.
    private void onPeakDetected(long now) {
        long gapMs = now - lastStepTimeMs;
        double stepLen = estimateStepLength();
        double dirDeg = Double.isNaN(gyroYawDeg) ? headingDeg : gyroYawDeg;
        accelMinInStep = Float.MAX_VALUE;
        accelMaxInStep = -Float.MAX_VALUE;

        if (inGaitStreak && gapMs <= MAX_STEP_INTERVAL_MS) {
            // Continuing an already-confirmed walking streak.
            applyStep(stepLen, dirDeg);
        } else if (pendingStepPresent && gapMs <= MAX_STEP_INTERVAL_MS) {
            // This peak arrived on-rhythm after the stashed one -- both are
            // real steps, and the streak is now established.
            commitPendingStep();
            applyStep(stepLen, dirDeg);
            inGaitStreak = true;
        } else {
            // Isolated peak, or the previous streak's rhythm broke. Don't
            // credit it yet -- only a rhythmic follow-up peak can confirm
            // it later, which a lone jolt never produces.
            inGaitStreak = false;
            stashPendingStep(stepLen, dirDeg);
        }
        lastStepTimeMs = now;
    }

    // Shared by an immediately-committed peak and a pending peak confirmed
    // one stride later, so both update position/variance identically using
    // the length/heading captured at the moment that peak actually happened.
    private void applyStep(double stepLenM, double dirDeg) {
        isStationary = false;
        stepCount++;
        double rad = Math.toRadians(dirDeg);
        posX += stepLenM * Math.sin(rad);
        posY += stepLenM * Math.cos(rad);
        posVarX += STEP_PROCESS_NOISE;
        posVarY += STEP_PROCESS_NOISE;
        lastStepLengthM = stepLenM;
        recordMotionSample();
    }

    private void stashPendingStep(double stepLenM, double dirDeg) {
        pendingStepPresent = true;
        pendingStepLenM = stepLenM;
        pendingStepDirDeg = dirDeg;
    }

    private void commitPendingStep() {
        pendingStepPresent = false;
        applyStep(pendingStepLenM, pendingStepDirDeg);
    }

    // Zero-velocity-style stationary check: true once the accelerometer
    // magnitude's variance over a short rolling window drops below
    // STATIONARY_THRESHOLD, i.e. the phone isn't actively bouncing with
    // footsteps. Feeds HEADING_COMPASS_PULL_STATIONARY above; a confirmed
    // step (applyStep()) always forces this back to false immediately --
    // a merely-pending, unconfirmed peak deliberately does not, so an
    // isolated jolt can't fake "walking" here either.
    private void processStationaryDetection(float mag) {
        accelWindow[accelWindowIdx] = mag;
        accelWindowIdx = (accelWindowIdx + 1) % accelWindow.length;

        float mean = 0;
        for (float v : accelWindow) mean += v;
        mean /= accelWindow.length;

        float variance = 0;
        for (float v : accelWindow) variance += (v - mean) * (v - mean);
        variance /= accelWindow.length;

        isStationary = variance < STATIONARY_THRESHOLD;
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
    public boolean isStationary() { return isStationary; }
    public boolean isInGaitStreak() { return inGaitStreak; }

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
    public float[] getAccelXHistory() { return accelXHistory; }
    public float[] getAccelYHistory() { return accelYHistory; }
    public float[] getAccelZHistory() { return accelZHistory; }
    public float[] getGyroXHistory() { return gyroXHistory; }
    public float[] getGyroYHistory() { return gyroYHistory; }
    public float[] getGyroZHistory() { return gyroZHistory; }
    public float[] getMagXHistory() { return magXHistory; }
    public float[] getMagYHistory() { return magYHistory; }
    public float[] getMagZHistory() { return magZHistory; }
    public float[] getGyroYawHistory() { return gyroYawHistory; }
    public float[] getPressureHistory() { return pressureHistory; }
    public float[] getRssiHistory() { return rssiHistory; }

    // Called every MainActivity.MAPPING_TICK_MS from the Settings UI tick
    // (only while that section is visible) to snapshot the current raw
    // values into the rolling history buffers the debug graphs read from.
    public void pushRawHistorySample() {
        shiftAndSet(accelXHistory, accelX);
        shiftAndSet(accelYHistory, accelY);
        shiftAndSet(accelZHistory, accelZ);
        shiftAndSet(gyroXHistory, gyroX);
        shiftAndSet(gyroYHistory, gyroY);
        shiftAndSet(gyroZHistory, gyroZ);
        shiftAndSet(magXHistory, magX);
        shiftAndSet(magYHistory, magY);
        shiftAndSet(magZHistory, magZ);
        shiftAndSet(gyroYawHistory, Double.isNaN(gyroYawDeg) ? 0f : (float) gyroYawDeg);
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
        posVarX = 0.01;
        posVarY = 0.01;
    }

    // Rough position uncertainty (meters, one standard deviation) from the
    // Kalman filter's current variance, for display -- lets Settings show
    // that this is an estimate with a real error bound, not an exact fix.
    public double getPositionUncertaintyM() {
        return Math.sqrt((posVarX + posVarY) / 2);
    }

    // Kalman measurement update: a Wi-Fi fingerprint match is a noisy
    // position measurement whose variance (r) scales with how far its
    // nearest neighbors were in RSSI-space (a tight cluster of close
    // matches is trusted far more than a vague one). See class doc.
    private void applyFingerprintCorrection(double mx, double my, double matchDistance) {
        double r = Math.max(0.25, Math.min(25.0, matchDistance * matchDistance * 0.02));
        double kx = posVarX / (posVarX + r);
        double ky = posVarY / (posVarY + r);
        posX += kx * (mx - posX);
        posY += ky * (my - posY);
        posVarX *= (1 - kx);
        posVarY *= (1 - ky);
    }

    public void start() {
        if (running) return;
        running = true;
        stepCount = 0;
        posX = 0;
        posY = 0;
        refPressureHpa = 0f;
        historyCount = 0;
        posVarX = 1.0;
        posVarY = 1.0;
        gyroYawDeg = Double.NaN;
        lastGyroTimestampNs = 0;
        accelMinInStep = Float.MAX_VALUE;
        accelMaxInStep = -Float.MAX_VALUE;
        inGaitStreak = false;
        pendingStepPresent = false;
        sessionId = db.startSession();

        ctx.registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        registerIfAvailable(Sensor.TYPE_GAME_ROTATION_VECTOR);
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
            // Kalman fusion (see class doc): a fingerprint match is fed in
            // as a measurement, weighted by both the filter's current
            // uncertainty and the match's own confidence, so accumulated
            // gyro/step drift gets corrected by every fresh scan instead
            // of only ever growing.
            double[] match = db.estimateLocationFromFingerprint(rssiByBssid, 5);
            if (match != null) {
                handler.post(() -> applyFingerprintCorrection(match[0], match[1], match[2]));
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
