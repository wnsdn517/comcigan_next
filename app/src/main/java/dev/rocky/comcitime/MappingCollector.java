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
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.annotation.SuppressLint;
import android.os.Build;
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
// Dead reckoning deliberately avoids one thing a naive implementation
// would lean on:
//  - A fixed, user-entered stride length, for step distance. Actual
//    stride varies with walking speed and isn't something most people
//    know precisely. Instead each step's length is estimated from that
//    step's own accelerometer swing via Weinberg's formula (see
//    estimateStepLength()), a standard step-length-estimation technique.
//
// Direction of travel is the magnetometer-fused compass heading
// (headingDeg) by default -- it's an absolute reference with no drift,
// unlike a gyroscope, so it's the better default whenever it can be
// trusted. The problem is exactly that "whenever": indoors, magnetic
// fields near rebar/wiring/metal furniture can be badly distorted right
// where this matters most, silently. updateMagneticReliability() (fed by
// TYPE_MAGNETIC_FIELD) runs the two-signal disturbance detector from the
// PDR/AHRS literature -- Afzal, Renaudin & Lachapelle (2011), "Magnetic
// Perturbations Detection and Heading Estimation Using Magnetometers"
// (magnitude-and-angle-based detector), and Fan et al. (2014), "Accurate
// Orientation Estimation Using AHRS under Conditions of Magnetic
// Distortion" (dip-angle-vs-gravity consistency): a locally undisturbed
// field keeps (a) a magnitude within Earth's ~25-65 microtesla range and
// (b) a dip angle relative to gravity that stays essentially constant
// over a short window, since it's a fixed geophysical property of the
// location, not something a straight walk would change. Either signal
// breaking flags magneticReliable = false. While reliable, gyroYawDeg
// (the gyroscope's integrated yaw) is pulled hard toward headingDeg so
// it's ready to take over accurately the instant reliability drops; while
// unreliable, that pull is cut to zero (a known-bad compass reading can't
// corrupt it) and gyroYawDeg free-integrates alone as the fallback
// direction source (see the TYPE_ROTATION_VECTOR case and applyStep()'s
// dirDeg selection) until the compass is trustworthy again.
//
// Steps themselves come from a custom peak-detector
// (processCustomStepDetection()) instead of the platform's hardware
// TYPE_STEP_DETECTOR, which on many devices has a noticeable detection
// lag -- the custom detector fires the instant a step's peak crosses
// threshold, so the position update tracks actual footfalls more
// closely. A lightweight zero-velocity-style stationary detector
// (processStationaryDetection(), based on short-window variance) also lets
// heading correction pull faster toward the compass while the phone is
// known to be still, when a compass reading isn't being muddied by
// step-related vibration. Both are driven off TYPE_LINEAR_ACCELERATION
// (the platform's own gravity-compensated acceleration, via its sensor
// fusion using the device's actual current attitude), not the raw
// accelerometer -- an earlier version used raw accel magnitude minus a
// fixed 9.81 gravity constant, which silently assumed the phone was lying
// flat and inflated apparent motion for real vertical hand movement
// whenever the phone was actually held near-vertical instead (a common
// in-hand carry angle).
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
//
// That passive fingerprint map has no real "ground truth" of its own,
// though -- it's built entirely from dead reckoning's own (possibly
// already-drifted) position estimates, so it can only ever smooth the
// trajectory locally, not anchor it to an absolute point. addPlaceTag()
// (the "여기 표시" UI action) additionally registers the current Wi-Fi
// scan as an anchor tied to a real, deliberately-tagged place; matching
// against those (see MappingDb.estimateLocationFromAnchor(), applied via
// applyAnchorCorrection()) is a separate, much harder correction than the
// passive blend, since an anchor is a real assertion ("I am at this exact
// place") rather than just wherever the trajectory happened to be. Walking
// a loop tagging anchors around each floor (once is enough; more
// coverage only helps) is what actually keeps a long session's
// accumulated drift bounded instead of only ever growing.
public class MappingCollector {

    public interface Listener {
        void onScanCount(int count);
        void onHeadingSteps(float headingDeg, int steps);
    }

    // Well under Android's Wi-Fi scan throttle (4 scans / 2 min foreground),
    // so requestScan() doesn't silently get dropped by the platform.
    private static final long SCAN_INTERVAL_MS = 30_000;

    // recordMotionSample() otherwise only ever fires from applyStep(), so
    // standing still, turning in place, or any movement the step detector
    // doesn't credit as a step left an actual gap in the recorded path --
    // this heartbeat records current position/heading/floor at a steady
    // cadence regardless of stepping, so the path covers the whole
    // session continuously instead of only the moments a step landed.
    private static final long MOTION_RECORD_INTERVAL_MS = 1000;

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
    private final WifiRttManager wifiRttManager;
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
    // Learned gyro Z bias (rad/s), tracked only while isStationary -- see
    // the TYPE_GYROSCOPE case in onSensorChanged() for how it's used and
    // why. A slow exponential moving average so a brief false-negative
    // stationary read doesn't yank the estimate around.
    private double gyroZBias = 0.0;
    private static final double GYRO_BIAS_LEARN_RATE = 0.02;
    // Fraction of the gyro-vs-compass heading gap corrected per fused-
    // heading update (~SENSOR_DELAY_UI, tens of ms), applied only while
    // magneticReliable (see class doc/updateMagneticReliability()) -- the
    // compass is primary direction source while reliable, so gyroYawDeg
    // only needs to stay resynced and ready as the fallback, not do the
    // steering itself. Pull is cut to zero entirely during a detected
    // disturbance (see the TYPE_ROTATION_VECTOR case below).
    private static final double HEADING_COMPASS_PULL = 0.3;
    // While the phone is detected stationary (see processStationaryDetection()),
    // pull harder toward the compass heading -- a compass reading isn't
    // being muddied by step-related vibration right then, so it's safe to
    // resync gyroYawDeg even faster.
    private static final double HEADING_COMPASS_PULL_STATIONARY = 0.5;

    // Magnetic disturbance detection (see class doc for the two papers
    // this follows). A locally undisturbed field has a magnitude in
    // Earth's normal range and a dip angle (relative to gravity) that
    // stays essentially constant over a short window; indoor ferrous/
    // electrical interference breaks one or both, so both are checked.
    private static final float MAG_MIN_UT = 20f, MAG_MAX_UT = 70f; // Earth's ~25-65uT, widened for sensor noise
    private static final float DIP_VARIANCE_THRESHOLD_DEG2 = 9f; // ~3 degree stdev
    // 30 samples at SENSOR_DELAY_GAME (~20ms) is the same ~600ms real-time
    // window this was sized for back when sensors were registered at the
    // 3x-slower SENSOR_DELAY_UI (~60ms) -- see registerIfAvailable().
    private final float[] dipAngleWindow = new float[30];
    private int dipAngleWindowIdx = 0;
    private boolean magneticReliable = true;

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
    // Frequencies for the same scan, kept alongside lastScanRssi so a
    // manual place tag (see addPlaceTag()) can persist real frequencies
    // instead of a placeholder.
    private java.util.Map<String, Integer> lastScanFreqByBssid = new java.util.HashMap<>();
    // SSIDs for the same scan, purely for the live per-AP signal-strength
    // readout (see MainActivity's mappingApRssiText) -- BSSIDs alone aren't
    // human-readable, and nothing here persists SSID to the DB.
    private java.util.Map<String, String> lastScanSsidByBssid = new java.util.HashMap<>();

    // Latest live place-recognition result (MappingDb.recognizePlace()),
    // refreshed on every Wi-Fi scan alongside the fingerprint-correction
    // match above -- cheap, since rssiByBssid is already computed for that
    // in recordScanResults(). volatile is enough since this is a read-only
    // cached value for the UI tick to poll, unlike applyFingerprintCorrection()
    // which must run on the main thread because it mutates position state.
    private volatile MappingDb.PlaceMatch lastPlaceMatch;

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
    // stepCount/position once REQUIRE_CONFIRM_PEAKS peaks in a row land
    // within MAX_STEP_INTERVAL_MS of each other -- gating on *timing
    // between peaks*, not their amplitude, so it doesn't trade off against
    // light-footstep detection.
    private static final long MAX_STEP_INTERVAL_MS = 1100; // ~55 steps/min lower bound
    // Two was too easy to satisfy from incidental phone handling while
    // seated (e.g. picking the phone up, then adjusting it, twice within
    // ~1s) -- a real recorded session showed exactly this: sitting still
    // for over two minutes, then two unrelated jolts landing in rhythm by
    // chance got credited as steps and walked the position forward using
    // whatever heading the resting phone happened to have. Three needs
    // one more rhythmic beat, which ordinary jostling rarely produces by
    // chance, at the cost of one extra step's worth of latency
    // (unnoticeable) before genuine walking starts moving the dot.
    private static final int REQUIRE_CONFIRM_PEAKS = 3;
    private boolean inGaitStreak = false;
    // Buffered peaks not yet confirmed into real steps -- holds up to
    // REQUIRE_CONFIRM_PEAKS - 1 of them while waiting for one more
    // rhythmic peak; see onPeakDetected().
    private final double[] pendingStepLenM = new double[REQUIRE_CONFIRM_PEAKS - 1];
    private final double[] pendingStepDirDeg = new double[REQUIRE_CONFIRM_PEAKS - 1];
    private int pendingStepCount = 0;

    // Stationary detection (short-window accelerometer variance), used to
    // trust the compass more while the phone is known to be still -- see
    // class doc and HEADING_COMPASS_PULL_STATIONARY above.
    private static final float STATIONARY_THRESHOLD = 0.08f; // m/s^2 variance
    private boolean isStationary = true;
    // 30 samples at SENSOR_DELAY_GAME (~20ms) preserves the same ~600ms
    // real-time window STATIONARY_THRESHOLD was tuned against at the
    // 3x-slower SENSOR_DELAY_UI (~60ms) this used to be registered at --
    // see registerIfAvailable().
    private final float[] accelWindow = new float[30];
    private int accelWindowIdx = 0;

    // Particle Filter (Sensor Fusion)
    private static final int NUM_PARTICLES = 100;
    private static final double MOTION_NOISE_STD = 0.2; // 20cm motion noise
    private final double[][] particles = new double[NUM_PARTICLES][2]; // {x, y}

    // Heuristic Drift Elimination (HDE)
    private static final float GYRO_VARIANCE_THRESHOLD = 0.005f;
    private final float[] gyroWindow = new float[180]; // ~3 seconds at SENSOR_DELAY_GAME (~20ms)
    private int gyroWindowIdx = 0;

    // Barometric Activity Recognition
    public enum Activity { STILL, WALKING, STAIRS, ELEVATOR }
    private Activity currentActivity = Activity.STILL;
    public Activity getCurrentActivity() { return currentActivity; }
    private static final float ELEVATOR_PRESSURE_RATE = 0.2f; // hPa/s
    private float lastPressureHpa = 0f;
    private long lastPressureTime = 0;

    // ---- lower-priority raw signals (see class doc): latest value only,
    // plus a rolling per-axis history buffer per signal for the debug
    // graphs, so accelerometer/gyroscope/magnetometer are shown as their
    // actual X/Y/Z components rather than collapsed into one magnitude
    // number (accelMag below is kept separately since Weinberg's formula
    // in estimateStepLength() genuinely needs the magnitude, not an axis).
    private float accelMag = 0f;
    private float accelX, accelY, accelZ, gyroX, gyroY, gyroZ, magX, magY, magZ;
    // Platform-fused gravity-only vector (TYPE_GRAVITY), used instead of
    // the raw accelerometer for updateMagneticReliability()'s dip-angle
    // check below -- see that method's doc for why the raw accelerometer
    // isn't actually gravity while walking.
    private float gravityX, gravityY, gravityZ;
    // Platform-fused, gravity-compensated acceleration (TYPE_LINEAR_
    // ACCELERATION) -- drives processStationaryDetection()/
    // processCustomStepDetection() instead of the raw accelerometer, see
    // class doc for why.
    private float linAccelX, linAccelY, linAccelZ, linAccelMag = 0f;
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
                        // Keeps gyroYawDeg resynced to the compass while
                        // the compass is trusted (see class doc), so it's
                        // ready to take over accurately the instant
                        // magneticReliable flips false. Pull is cut to
                        // zero during a detected disturbance -- a
                        // known-bad compass reading must never steer the
                        // fallback source that's about to be relied on.
                        double diff = shortestAngleDiffDeg(headingDeg, gyroYawDeg);
                        double pull = !magneticReliable ? 0
                                : (isStationary ? HEADING_COMPASS_PULL_STATIONARY : HEADING_COMPASS_PULL);
                        gyroYawDeg = ((gyroYawDeg + diff * pull) % 360 + 360) % 360;
                    }
                    break;
                case Sensor.TYPE_STEP_DETECTOR:
                    // Position updates now come from the custom low-latency
                    // peak detector in processCustomStepDetection() (driven
                    // off TYPE_LINEAR_ACCELERATION below), not this hardware
                    // event -- kept registered only so a device without a
                    // usable accelerometer stream still has a step source,
                    // but not counted here to avoid double-counting steps.
                    break;
                case Sensor.TYPE_ACCELEROMETER:
                    // Raw magnitude (accelMag) is kept only for
                    // estimateStepLength()'s Weinberg amplitude (accelMinInStep/
                    // accelMaxInStep) -- triggering/stationary detection moved
                    // to TYPE_LINEAR_ACCELERATION below, see class doc.
                    accelX = event.values[0]; accelY = event.values[1]; accelZ = event.values[2];
                    accelMag = vectorMag(event.values);
                    accelMinInStep = Math.min(accelMinInStep, accelMag);
                    accelMaxInStep = Math.max(accelMaxInStep, accelMag);
                    break;
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    linAccelX = event.values[0]; linAccelY = event.values[1]; linAccelZ = event.values[2];
                    linAccelMag = vectorMag(event.values);
                    // Stationary detection runs first so processCustomStepDetection()
                    // below sees isStationary for *this* sample, not the
                    // previous one.
                    processStationaryDetection(linAccelMag);
                    processCustomStepDetection(linAccelMag);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    gyroX = event.values[0]; gyroY = event.values[1]; gyroZ = event.values[2];
                    if (lastGyroTimestampNs != 0 && !Double.isNaN(gyroYawDeg)) {
                        double dt = (event.timestamp - lastGyroTimestampNs) / 1_000_000_000.0;
                        // A stationary phone should read exactly 0 rad/s,
                        // but every real gyroscope has a small constant
                        // bias, which integrating unconditionally turns
                        // into a slow heading drift even while genuinely
                        // not moving. gyroZBias below tracks that bias
                        // specifically during stationary windows (the one
                        // time the true angular velocity is known to be
                        // zero) and this subtracts it before integrating,
                        // so a held-still phone stops drifting instead of
                        // only ever accumulating error.
                        double dYaw = Math.toDegrees(gyroZ - gyroZBias) * dt;
                        gyroYawDeg = ((gyroYawDeg - dYaw) % 360 + 360) % 360;

                        // Heuristic Drift Elimination (HDE)
                        processHDE(gyroZ);
                    }
                    if (isStationary) {
                        gyroZBias += (gyroZ - gyroZBias) * GYRO_BIAS_LEARN_RATE;
                    }
                    lastGyroTimestampNs = event.timestamp;
                    break;
                case Sensor.TYPE_GRAVITY:
                    gravityX = event.values[0]; gravityY = event.values[1]; gravityZ = event.values[2];
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    magX = event.values[0]; magY = event.values[1]; magZ = event.values[2];
                    updateMagneticReliability();
                    break;
                case Sensor.TYPE_PRESSURE:
                    pressureHpa = event.values[0];
                    if (refPressureHpa == 0f) refPressureHpa = pressureHpa;
                    updateBarometricActivity(pressureHpa);
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

    // Angle between the magnetic field vector and the gravity vector --
    // the "dip angle" used by updateMagneticReliability() below. Takes the
    // platform-fused TYPE_GRAVITY reading (gravityX/Y/Z), not the raw
    // accelerometer: the raw accelerometer is only approximately gravity
    // while the phone is still, and is dominated by footstep/swing
    // acceleration while walking -- an earlier version of this used raw
    // accel here, which made the dip angle swing on every step (motion,
    // not real magnetic disturbance) and constantly, falsely reported the
    // compass as unreliable while simply walking around.
    private static double dipAngleDeg(float magX, float magY, float magZ,
                                       float gravX, float gravY, float gravZ) {
        double dot = magX * gravX + magY * gravY + magZ * gravZ;
        double magMag = Math.sqrt(magX * magX + magY * magY + magZ * magZ);
        double gravMag = Math.sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ);
        if (magMag < 1e-3 || gravMag < 1e-3) return 90;
        double cos = Math.max(-1, Math.min(1, dot / (magMag * gravMag)));
        return Math.toDegrees(Math.acos(cos));
    }

    // Magnetic disturbance detector (see class doc for the two papers this
    // follows): flags magneticReliable = false when either the field
    // magnitude leaves Earth's normal range, or the dip angle relative to
    // gravity -- a fixed geophysical property of the current location --
    // starts varying more than sensor noise alone would explain. Checking
    // dip-angle *variance* over a rolling window rather than comparing to
    // an absolute expected dip avoids needing to know Korea's actual
    // magnetic dip in advance; only its short-term stability matters.
    private void updateMagneticReliability() {
        float magMagnitude = vectorMag(new float[]{magX, magY, magZ});
        boolean magnitudeOk = magMagnitude >= MAG_MIN_UT && magMagnitude <= MAG_MAX_UT;

        // Falls back to raw accel only if this device never actually
        // delivered a TYPE_GRAVITY event (gravityX/Y/Z all exactly 0 is
        // otherwise physically impossible -- that would mean true
        // freefall -- so it's a safe "not received yet" sentinel).
        boolean haveGravity = gravityX != 0f || gravityY != 0f || gravityZ != 0f;
        double dip = haveGravity
                ? dipAngleDeg(magX, magY, magZ, gravityX, gravityY, gravityZ)
                : dipAngleDeg(magX, magY, magZ, accelX, accelY, accelZ);
        dipAngleWindow[dipAngleWindowIdx] = (float) dip;
        dipAngleWindowIdx = (dipAngleWindowIdx + 1) % dipAngleWindow.length;
        float mean = 0;
        for (float v : dipAngleWindow) mean += v;
        mean /= dipAngleWindow.length;
        float variance = 0;
        for (float v : dipAngleWindow) variance += (v - mean) * (v - mean);
        variance /= dipAngleWindow.length;

        magneticReliable = magnitudeOk && variance < DIP_VARIANCE_THRESHOLD_DEG2;
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

    // Fires the moment the linear-acceleration magnitude (real motion,
    // gravity already removed by the platform's own fusion -- see class
    // doc) crosses PEAK_THRESHOLD, then waits for it to fall back before
    // searching for the next peak -- lower latency than the platform's
    // hardware step detector, which on many devices only reports a step
    // some tens to hundreds of ms after it actually happened.
    // STEP_COOLDOWN_MS guards against a single footfall's vibration
    // registering as more than one step.
    //
    // This used to run off the RAW accelerometer's magnitude minus a fixed
    // 9.81 gravity constant, which quietly assumed gravity always
    // contributes exactly that much to the magnitude regardless of how the
    // phone is held -- true only for a phone lying flat. Held near-vertical
    // (typical in-hand carry) with real vertical hand motion, that fixed
    // subtraction doesn't track the actual dynamic swing correctly and
    // reported inflated "movement" even for motion that wasn't real
    // forward walking. TYPE_LINEAR_ACCELERATION is the platform's own
    // gravity-compensated acceleration (computed via sensor fusion using
    // the actual current device attitude, not a flat-phone assumption), so
    // switching to it fixes this regardless of how the phone is oriented.
    //
    // !isStationary is required too: the class doc's rhythm-consistency
    // confirmation (see MAX_STEP_INTERVAL_MS above) assumes an isolated
    // jolt isn't rhythmic, but in practice just holding the phone in hand
    // -- not walking at all -- produced small, quasi-periodic jitter
    // (hand tremor, breathing, shifting grip) that crossed PEAK_THRESHOLD
    // often enough to fool that check, walking the dead-reckoned position
    // steadily forward the whole time it was held. isStationary is
    // computed from the same linear-acceleration sample just above (in
    // onSensorChanged, stationary detection deliberately runs first) as a
    // short-window variance -- genuine walking's acceleration swing
    // reliably drives that variance well past STATIONARY_THRESHOLD, so
    // gating on it filters out exactly this kind of held-still jitter
    // without touching real steps.
    private void processCustomStepDetection(float linAccelMagnitude) {
        long now = System.currentTimeMillis();

        if (peakSearching && linAccelMagnitude > PEAK_THRESHOLD && !isStationary && (now - lastStepTimeMs) > STEP_COOLDOWN_MS) {
            onPeakDetected(now);
            peakSearching = false;
        } else if (!peakSearching && linAccelMagnitude < PEAK_THRESHOLD / 2) {
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
        // Compass primary, gyro fallback (see class doc): use headingDeg
        // whenever the magnetometer is currently trustworthy, and only
        // fall back to the free-integrating gyroYawDeg while a magnetic
        // disturbance is detected (or before the gyro has bootstrapped).
        double dirDeg = (!magneticReliable && !Double.isNaN(gyroYawDeg)) ? gyroYawDeg : headingDeg;
        accelMinInStep = Float.MAX_VALUE;
        accelMaxInStep = -Float.MAX_VALUE;

        if (inGaitStreak && gapMs <= MAX_STEP_INTERVAL_MS) {
            // Continuing an already-confirmed walking streak.
            applyStep(stepLen, dirDeg);
        } else if (pendingStepCount > 0 && gapMs <= MAX_STEP_INTERVAL_MS) {
            // This peak arrived on-rhythm after the stashed one(s).
            if (pendingStepCount < REQUIRE_CONFIRM_PEAKS - 1) {
                // Still short of REQUIRE_CONFIRM_PEAKS in a row -- stash
                // this one too and keep waiting for one more.
                pendingStepLenM[pendingStepCount] = stepLen;
                pendingStepDirDeg[pendingStepCount] = dirDeg;
                pendingStepCount++;
            } else {
                // Enough rhythmic peaks in a row -- all stashed ones plus
                // this one are real steps, and the streak is now established.
                for (int i = 0; i < pendingStepCount; i++) {
                    applyStep(pendingStepLenM[i], pendingStepDirDeg[i]);
                }
                applyStep(stepLen, dirDeg);
                pendingStepCount = 0;
                inGaitStreak = true;
            }
        } else {
            // Isolated peak, or the previous streak's rhythm broke. Don't
            // credit it yet -- only REQUIRE_CONFIRM_PEAKS-1 further rhythmic
            // follow-ups can confirm it later, which incidental jostling
            // rarely produces by chance.
            inGaitStreak = false;
            pendingStepCount = 1;
            pendingStepLenM[0] = stepLen;
            pendingStepDirDeg[0] = dirDeg;
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
        
        // Update Particle Filter (Motion Model)
        updateParticlesMotion(stepLenM, rad);
        
        posVarX += STEP_PROCESS_NOISE;
        posVarY += STEP_PROCESS_NOISE;
        lastStepLengthM = stepLenM;
        recordMotionSample();
    }

    // Zero-velocity-style stationary check: true once the linear-
    // acceleration magnitude's variance over a short rolling window drops
    // below STATIONARY_THRESHOLD, i.e. the phone isn't actively bouncing
    // with footsteps. Feeds HEADING_COMPASS_PULL_STATIONARY above; a
    // confirmed step (applyStep()) always forces this back to false
    // immediately -- a merely-pending, unconfirmed peak deliberately does
    // not, so an isolated jolt can't fake "walking" here either.
    // STATIONARY_THRESHOLD was originally tuned against raw-accelerometer-
    // magnitude variance (baseline ~9.81 m/s^2); now fed linear-
    // acceleration magnitude (baseline ~0) instead -- see class doc.
    // Variance is computed around the window's own mean either way, so a
    // constant baseline shift alone doesn't change it, but the platform's
    // linear-acceleration fusion is itself somewhat filtered/smoothed
    // compared to a raw accelerometer stream, so this threshold may need
    // re-tuning if real recorded data shows stationary detection behaving
    // differently now.
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
        if (isStationary) currentActivity = Activity.STILL;
    }

    private void processHDE(float gyroZ) {
        gyroWindow[gyroWindowIdx] = gyroZ;
        gyroWindowIdx = (gyroWindowIdx + 1) % gyroWindow.length;

        float mean = 0;
        for (float v : gyroWindow) mean += v;
        mean /= gyroWindow.length;

        float variance = 0;
        for (float v : gyroWindow) variance += (v - mean) * (v - mean);
        variance /= gyroWindow.length;

        // If variance is very low, we are walking straight.
        if (variance < GYRO_VARIANCE_THRESHOLD) {
            // Nudge toward nearest 90-degree axis
            double target = Math.round(gyroYawDeg / 90.0) * 90.0;
            double diff = shortestAngleDiffDeg(target, gyroYawDeg);
            gyroYawDeg = ((gyroYawDeg + diff * 0.005) % 360 + 360) % 360;
        }
    }

    private void updateBarometricActivity(float pressure) {
        long now = System.currentTimeMillis();
        if (lastPressureTime != 0) {
            float dt = (now - lastPressureTime) / 1000f;
            float rate = Math.abs(pressure - lastPressureHpa) / dt;
            
            if (rate > ELEVATOR_PRESSURE_RATE) {
                currentActivity = Activity.ELEVATOR;
            } else if (rate > 0.05f && inGaitStreak) {
                currentActivity = Activity.STAIRS;
            } else if (inGaitStreak) {
                currentActivity = Activity.WALKING;
            }
        }
        lastPressureHpa = pressure;
        lastPressureTime = now;
    }

    private void updateParticlesMotion(double stepLen, double rad) {
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double noisyLen = stepLen + rnd.nextGaussian() * MOTION_NOISE_STD;
            double noisyRad = rad + rnd.nextGaussian() * 0.05; // ~3 degrees
            particles[i][0] += noisyLen * Math.sin(noisyRad);
            particles[i][1] += noisyLen * Math.cos(noisyRad);
        }
    }

    private void updateParticlesMeasurement(double mx, double my, double std) {
        double totalWeight = 0;
        double[] weights = new double[NUM_PARTICLES];
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double dx = particles[i][0] - mx;
            double dy = particles[i][1] - my;
            double distSq = dx * dx + dy * dy;
            weights[i] = Math.exp(-distSq / (2 * std * std));
            totalWeight += weights[i];
        }

        if (totalWeight < 1e-9) { // Reset particles if they all died
            initParticles();
            return;
        }

        // Resample
        double[][] nextParticles = new double[NUM_PARTICLES][2];
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double r = rnd.nextDouble() * totalWeight;
            double count = 0;
            for (int j = 0; j < NUM_PARTICLES; j++) {
                count += weights[j];
                if (count >= r) {
                    nextParticles[i][0] = particles[j][0] + rnd.nextGaussian() * 0.1;
                    nextParticles[i][1] = particles[j][1] + rnd.nextGaussian() * 0.1;
                    break;
                }
            }
        }
        
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particles[i][0] = nextParticles[i][0];
            particles[i][1] = nextParticles[i][1];
        }

        // Update posX/posY to particle mean
        double avgX = 0, avgY = 0;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            avgX += particles[i][0];
            avgY += particles[i][1];
        }
        posX = avgX / NUM_PARTICLES;
        posY = avgY / NUM_PARTICLES;
    }

    private void initParticles() {
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particles[i][0] = posX + rnd.nextGaussian() * 0.5;
            particles[i][1] = posY + rnd.nextGaussian() * 0.5;
        }
    }

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            requestScan();
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private final Runnable motionRecordTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            recordMotionSample();
            handler.postDelayed(this, MOTION_RECORD_INTERVAL_MS);
        }
    };

    public MappingCollector(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = new MappingDb(this.ctx);
        this.wifiManager = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= 28) {
            this.wifiRttManager = (WifiRttManager) this.ctx.getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
        } else {
            this.wifiRttManager = null;
        }
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
    public java.util.Map<String, String> getLastScanSsidByBssid() { return lastScanSsidByBssid; }
    public MappingDb.PlaceMatch getLastPlaceMatch() { return lastPlaceMatch; }
    public float getPitchDeg() { return pitchDeg; }
    public float getRollDeg() { return rollDeg; }
    public int getStepCount() { return stepCount; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public long getSessionId() { return sessionId; }
    public boolean isStationary() { return isStationary; }
    public boolean isInGaitStreak() { return inGaitStreak; }
    public boolean isMagneticReliable() { return magneticReliable; }

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
        initParticles(); // otherwise the particle filter's own average
        // overwrites posX/posY right back to their pre-reset values on the
        // next update, since the particles themselves weren't moved
        persistPosition(); // so a later restart doesn't resurrect the old spot
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
    //
    // Zero-velocity gate: updateParticlesMeasurement() resamples every
    // particle with fresh injected noise on every call, which kept nudging
    // posX/posY by a few centimeters even while genuinely standing still
    // (no steps -> no real reason for the position to be moving at all,
    // so a repeated "correction" toward slightly different noisy RSSI
    // readings just reads as the dot jittering around in place). Skipping
    // the correction entirely while isStationary is the standard fix
    // (a "ZUPT" -- zero-velocity update) for this in pedestrian dead
    // reckoning.
    private void applyFingerprintCorrection(double mx, double my, double matchDistance) {
        if (isStationary) return;
        double std = Math.max(0.5, matchDistance * 0.1);
        updateParticlesMeasurement(mx, my, std);

        // Old EKF style variance update (kept for metadata)
        double r = std * std;
        double kx = posVarX / (posVarX + r);
        double ky = posVarY / (posVarY + r);
        posVarX *= (1 - kx);
        posVarY *= (1 - ky);
    }

    // A registered anchor (MappingDb.PlaceFingerprint tagged via
    // addPlaceTag()) is a deliberate "this exact place" claim, not just
    // wherever a passive scan happened to be recorded -- so a confident
    // match is trusted with a much tighter measurement std than
    // applyFingerprintCorrection()'s passive blend above, pulling harder
    // toward the anchor's asserted coordinate. This is what actually
    // bounds accumulated dead-reckoning drift on a long walk: registering
    // anchors around a building (one per floor, ideally a full loop -- the
    // more of the building is covered, the more often a walk passes near
    // one) means every return trip near a known point snaps position back
    // close to ground truth instead of drift only ever growing between
    // manual origin resets. ANCHOR_MATCH_MAX_DISTANCE guards against a
    // weak/ambiguous match (e.g. an unrelated place with a coincidentally
    // similar RSSI signature) triggering a hard correction toward the
    // wrong anchor -- a starting heuristic threshold (same RMS
    // differential-RSSI-dB units as fingerprintDistance()), not measured
    // against real hardware. Same ZUPT gate as applyFingerprintCorrection()
    // for the same reason: no real reason position should move while the
    // phone is genuinely standing still.
    private static final double ANCHOR_MATCH_MAX_DISTANCE = 12.0;

    private void applyAnchorCorrection(double ax, double ay, double matchDistance) {
        if (isStationary) return;
        if (matchDistance > ANCHOR_MATCH_MAX_DISTANCE) return;
        double std = Math.max(0.3, matchDistance * 0.03);
        updateParticlesMeasurement(ax, ay, std);

        double r = std * std;
        double kx = posVarX / (posVarX + r);
        double ky = posVarY / (posVarY + r);
        posVarX *= (1 - kx);
        posVarY *= (1 - ky);
    }

    public void start() {
        if (running) return;
        running = true;
        stepCount = 0;
        // Resume from the last known spot instead of snapping back to
        // (0,0) on every restart -- see Prefs.lastPosX()/persistPosition()
        // and the class doc above. Ongoing Wi-Fi fingerprint correction
        // (applyFingerprintCorrection()) still narrows this down further
        // as scans come in, same as it always has.
        Prefs prefs = new Prefs(ctx);
        posX = prefs.lastPosX();
        posY = prefs.lastPosY();
        initParticles();
        refPressureHpa = 0f;
        historyCount = 0;
        posVarX = 1.0;
        posVarY = 1.0;
        gyroYawDeg = Double.NaN;
        lastGyroTimestampNs = 0;
        gyroZBias = 0.0;
        accelMinInStep = Float.MAX_VALUE;
        accelMaxInStep = -Float.MAX_VALUE;
        inGaitStreak = false;
        pendingStepCount = 0;
        magneticReliable = true;
        java.util.Arrays.fill(dipAngleWindow, 0f);
        dipAngleWindowIdx = 0;
        sessionId = db.startSession();

        registerScanReceiver();

        registerIfAvailable(Sensor.TYPE_GAME_ROTATION_VECTOR);
        registerIfAvailable(Sensor.TYPE_ROTATION_VECTOR);
        registerIfAvailable(Sensor.TYPE_STEP_DETECTOR);
        registerIfAvailable(Sensor.TYPE_ACCELEROMETER);
        registerIfAvailable(Sensor.TYPE_GYROSCOPE);
        registerIfAvailable(Sensor.TYPE_MAGNETIC_FIELD);
        registerIfAvailable(Sensor.TYPE_PRESSURE);
        registerIfAvailable(Sensor.TYPE_GRAVITY);
        registerIfAvailable(Sensor.TYPE_LINEAR_ACCELERATION);

        orientationEventListener = new OrientationEventListener(ctx) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) screenRotationDeg = orientation;
            }
        };
        if (orientationEventListener.canDetectOrientation()) orientationEventListener.enable();

        startLocationUpdates();

        handler.post(scanTick);
        handler.postDelayed(motionRecordTick, MOTION_RECORD_INTERVAL_MS);
    }

    // Wi-Fi scan results are a system broadcast (WifiManager.SCAN_RESULTS_
    // AVAILABLE_ACTION), which doesn't need an export flag even on
    // targetSdk 33+, but a couple of very common real-world causes of
    // "collection just silently stops" are guarded against here anyway:
    // devices/ROMs that enforce the flag requirement more strictly than
    // AOSP does, and any other unexpected registration failure. Without
    // this try/catch, an exception here would propagate out of start()
    // and crash MappingService before runningCollector ever gets set,
    // which looks to the user exactly like "permission granted but never
    // actually starts" (see MainActivity.refreshMappingStatus()).
    private void registerScanReceiver() {
        try {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(scanReceiver, filter);
            }
        } catch (Exception ignored) {
            // Wi-Fi scans just won't be picked up automatically -- everything
            // else (steps/heading/etc.) keeps working, so this shouldn't take
            // the whole collector down.
        }
    }

    private void registerIfAvailable(int sensorType) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        // SENSOR_DELAY_GAME (~20ms/50Hz) instead of SENSOR_DELAY_UI
        // (~60ms/16Hz): the step detector's peak search
        // (processCustomStepDetection()) is looking for a footfall
        // impulse that only lasts on the order of 100-200ms, so a 60ms
        // sample spacing was coarse enough to blur or miss the actual
        // peak. Faster sampling also tightens the stationary-variance
        // window (accelWindow/gyroWindow) to a shorter, more current
        // slice of real time.
        if (sensor != null) sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME);
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
        persistPosition(); // catches whatever happened since the last step
        handler.removeCallbacks(scanTick);
        handler.removeCallbacks(motionRecordTick);
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

    // Manual sensor-value debugging: records every raw signal this
    // collector currently has (not just the automatic per-step motion
    // sample) at the exact instant the user taps a button for it, e.g.
    // right as something looks wrong on the live Settings graphs -- an
    // optional label works the same as addWaypoint()'s.
    public void snapshotSensors(String label) {
        if (sessionId < 0) return;
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        float aX = accelX, aY = accelY, aZ = accelZ;
        float gX = gyroX, gY = gyroY, gZ = gyroZ;
        float mX = magX, mY = magY, mZ = magZ;
        float p = pressureHpa, h = headingDeg, pitch = pitchDeg, roll = rollDeg;
        int rssi = lastTopRssi, floorDelta = getEstimatedFloorDelta();
        double x = posX, y = posY;
        dbExecutor.execute(() -> db.insertSensorSnapshot(sid, ts, label,
                aX, aY, aZ, gX, gY, gZ, mX, mY, mZ, p, h, pitch, roll, rssi, floorDelta, x, y));
    }

    // Sibling to addWaypoint(): snapshots the CURRENT live Wi-Fi scan
    // (lastScanRssi/lastScanFreqByBssid) under the same floor/label,
    // building a place->Wi-Fi-fingerprint directory independent of dead-
    // reckoning drift/accuracy -- a direct manual ground-truth tag, not
    // inferred from posX/posY. Also records the dead-reckoned position at
    // this exact moment as the tag's asserted anchor coordinate: unlike
    // radio_scans' x/y (wherever dead reckoning happened to be during
    // ordinary passive collection, however drifted that was), this one is
    // a deliberate "I am at this real place right now" claim, which is
    // what applyAnchorCorrection() below trusts to correct much harder
    // than the passive per-scan blend the next time this Wi-Fi signature
    // is recognized. No-ops if no Wi-Fi scan has landed yet; addWaypoint's
    // position tag still succeeds either way, since the two are
    // independent persistence paths sharing one user tap. Not session-
    // scoped (no sessionId gate) -- places are meant to accumulate across
    // sessions/days, unlike per-session dead-reckoning data.
    public void addPlaceTag(String floor, String label) {
        java.util.Map<String, Integer> rssi = new java.util.HashMap<>(lastScanRssi);
        if (rssi.isEmpty()) return;
        java.util.Map<String, Integer> freq = new java.util.HashMap<>(lastScanFreqByBssid);
        long ts = System.currentTimeMillis();
        double x = posX, y = posY;
        dbExecutor.execute(() -> db.runInTransaction(() -> {
            for (java.util.Map.Entry<String, Integer> e : rssi.entrySet()) {
                Integer f = freq.get(e.getKey());
                db.insertPlaceFingerprint(ts, floor, label, e.getKey(), e.getValue(), f != null ? f : 0, x, y);
            }
        }));
    }

    @SuppressLint("MissingPermission")
    private void startRttRanging(List<ScanResult> results) {
        if (Build.VERSION.SDK_INT < 28 || wifiRttManager == null) return;
        
        RangingRequest.Builder builder = new RangingRequest.Builder();
        int added = 0;
        for (ScanResult res : results) {
            if (res.is80211mcResponder()) {
                builder.addAccessPoint(res);
                added++;
            }
        }
        if (added == 0) return;

        try {
            wifiRttManager.startRanging(builder.build(), ctx.getMainExecutor(), new RangingResultCallback() {
                @Override
                public void onRangingFailure(int code) {}

                @Override
                public void onRangingResults(List<RangingResult> results) {
                    processRttResults(results);
                }
            });
        } catch (SecurityException ignored) {}
    }

    private void processRttResults(List<RangingResult> results) {
        if (Build.VERSION.SDK_INT < 28) return;
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        dbExecutor.execute(() -> db.runInTransaction(() -> {
            for (RangingResult res : results) {
                if (res.getStatus() == RangingResult.STATUS_SUCCESS) {
                    db.insertRadioRtt(sid, ts, res.getMacAddress().toString(),
                                     res.getDistanceMm(), res.getDistanceStdDevMm(), res.getRssi());

                    // Nudge towards AP if we have an estimate for it
                    // (Simplified PF update: treat as a localized anchor)
                    // ... (In a real implementation we'd use these as circular constraints)
                }
            }
        }));
    }
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
        java.util.Map<String, Integer> freqByBssid = new java.util.HashMap<>();
        java.util.Map<String, String> ssidByBssid = new java.util.HashMap<>();
        for (ScanResult r : results) {
            if (r.level > top) top = r.level;
            rssiByBssid.put(r.BSSID, r.level);
            freqByBssid.put(r.BSSID, r.frequency);
            ssidByBssid.put(r.BSSID, r.SSID);
        }
        lastTopRssi = top;
        lastScanRssi = rssiByBssid;
        lastScanFreqByBssid = freqByBssid;
        lastScanSsidByBssid = ssidByBssid;
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        double x = posX, y = posY;
        dbExecutor.execute(() -> {
            // One transaction for the whole scan's rows instead of one
            // auto-committed insert per BSSID -- see MappingDb.
            // runInTransaction()'s doc.
            db.runInTransaction(() -> {
                for (ScanResult r : results) {
                    db.insertRadioScan(sid, ts, r.BSSID, r.level, r.frequency, x, y);
                }
            });
            // Kalman fusion (see class doc): a fingerprint match is fed in
            // as a measurement, weighted by both the filter's current
            // uncertainty and the match's own confidence, so accumulated
            // gyro/step drift gets corrected by every fresh scan instead
            // of only ever growing.
            double[] match = db.estimateLocationFromFingerprint(rssiByBssid, 5);
            if (match != null) {
                handler.post(() -> applyFingerprintCorrection(match[0], match[1], match[2]));
            }

            // Stronger correction against manually-registered anchors (see
            // addPlaceTag()/applyAnchorCorrection()) -- independent of, and
            // in addition to, the passive blend just above. k=3 instead of
            // 5: anchors are deliberately placed and expected to be sparser
            // than passive scans, so fewer, closer neighbors are more
            // meaningful here than a wider average would be.
            double[] anchorMatch = db.estimateLocationFromAnchor(rssiByBssid, 3);
            if (anchorMatch != null) {
                handler.post(() -> applyAnchorCorrection(anchorMatch[0], anchorMatch[1], anchorMatch[2]));
            }

            // Perform RTT Ranging if available
            if (Build.VERSION.SDK_INT >= 28 && wifiRttManager != null) {
                startRttRanging(results);
            }

            // Piggybacks on the same rssiByBssid -- no extra Wi-Fi scan
            // needed for place recognition (see addPlaceTag()/class doc).
            lastPlaceMatch = db.recognizePlace(rssiByBssid, 5);
        });
        if (listener != null) listener.onScanCount(results.size());
    }

    private void recordMotionSample() {
        long sid = sessionId;
        long ts = System.currentTimeMillis();
        float h = headingDeg, p = pitchDeg, r = rollDeg;
        int steps = stepCount;
        double x = posX, y = posY;
        int floorDelta = getEstimatedFloorDelta();
        // Full raw sensor + signal context on every sample, not just
        // manually-triggered snapshotSensors() ones -- needed to do any
        // real post-hoc drift/stability analysis on an exported session
        // instead of only ever seeing the already-fused heading/position.
        float aX = accelX, aY = accelY, aZ = accelZ;
        float gX = gyroX, gY = gyroY, gZ = gyroZ;
        float mX = magX, mY = magY, mZ = magZ;
        float pressure = pressureHpa;
        int rssi = lastTopRssi;
        // Also gravX/Y/Z and linAccelX/Y/Z -- see class doc: lets a
        // suspected device-tilt-dependent step-detection bug actually be
        // verified against real data instead of guessed at.
        float gvX = gravityX, gvY = gravityY, gvZ = gravityZ;
        float laX = linAccelX, laY = linAccelY, laZ = linAccelZ;
        dbExecutor.execute(() -> db.insertMotionSample(sid, ts, h, p, r, steps, x, y, floorDelta,
                aX, aY, aZ, gX, gY, gZ, mX, mY, mZ, pressure, rssi,
                gvX, gvY, gvZ, laX, laY, laZ));
        if (listener != null) listener.onHeadingSteps(h, steps);
        persistPosition();
    }

    // Carries the dead-reckoned position across a restart (app reopen, the
    // service getting killed and restarted -- see class doc on start()) --
    // written on every step rather than only in stop(), since a killed
    // process never gets to call stop() at all. SharedPreferences.apply()
    // is an async, effectively-free write, so doing this every step (at
    // most a couple times a second while walking) is not worth throttling.
    private void persistPosition() {
        new Prefs(ctx).setLastPos((float) posX, (float) posY);
    }
}
