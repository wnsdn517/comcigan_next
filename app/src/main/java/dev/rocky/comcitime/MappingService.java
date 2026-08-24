package dev.rocky.comcitime;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

// Runs the indoor-mapping data collection continuously in the background,
// for as long as the user has consented (Prefs.mappingConsentDone()) and
// granted the needed permissions -- not a manual start/stop toggle. See
// MappingCollector/MappingDb for what's actually collected and why it
// can't be traced back to a specific person; see MainActivity for where
// this service gets started (once, right after consent, and again on
// every app launch/boot while still consented).
public class MappingService extends Service {

    // Exposed so MainActivity (same process) can show live status and let
    // the user tag an optional waypoint on the collector that's actually
    // running, without spinning up a second, redundant collector of its
    // own. Plain static field is fine here -- every access happens on the
    // main thread (service lifecycle callbacks and Activity code alike).
    private static MappingCollector runningCollector;

    private MappingCollector collector;
    private final android.os.Handler checkHandler = new android.os.Handler();
    private final Runnable checkTask = new Runnable() {
        @Override
        public void run() {
            MappingCollector col = getCollector();
            if (col != null) {
                try {
                    if (shouldCollect(col)) {
                        if (!col.isRunning()) col.start();
                        runningCollector = col;
                    } else if (col.isRunning()) {
                        col.stop();
                        runningCollector = null;
                    }
                } catch (Exception e) {
                    // See onStartCommand()'s try/catch for why this can't be
                    // allowed to crash the service.
                    android.util.Log.w("MappingService", "collector start/stop failed", e);
                }
            }
            checkHandler.postDelayed(this, 30000); // Check every 30s
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        collector = new MappingCollector(this);
        checkHandler.post(checkTask);
    }

    // Same reasoning as LiveNotifyService: every startForegroundService()
    // call needs a startForeground() to follow, even if the service (and
    // its collector) is already running, or the system kills the process
    // with ForegroundServiceDidNotStartInTimeException on a second start.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NotificationHelper.ID_MAPPING, NotificationHelper.buildMapping(this));
        try {
            MappingCollector col = getCollector();
            if (shouldCollect(col)) {
                if (!col.isRunning()) col.start();
                runningCollector = col;
            } else if (col.isRunning()) {
                col.stop();
                runningCollector = null;
            }
        } catch (Exception e) {
            // Never let a collection-side failure crash the whole service --
            // that would leave runningCollector null forever (looks to the
            // user like "permission granted but stuck on 'not started yet'",
            // see MainActivity.refreshMappingStatus()) with no way back
            // short of reopening the app. The periodic watchdog below will
            // keep retrying regardless.
            android.util.Log.w("MappingService", "collector failed to start", e);
        }
        MappingWatchdogReceiver.schedule(this);
        return START_STICKY;
    }

    private MappingCollector getCollector() {
        if (collector == null) collector = new MappingCollector(this);
        return collector;
    }

    private boolean shouldCollect(MappingCollector col) {
        Prefs prefs = new Prefs(this);
        if (prefs.testMode()) return true;

        float sLat = prefs.schoolLat();
        float sLon = prefs.schoolLon();
        if (sLat == 0f || sLon == 0f) return false;

        double lastLat = col.getLastLat();
        double lastLon = col.getLastLon();
        if (Double.isNaN(lastLat) || Double.isNaN(lastLon)) return true; // Keep running until we know where we are

        float[] results = new float[1];
        android.location.Location.distanceBetween(sLat, sLon, lastLat, lastLon, results);
        return results[0] < 300; // Only collect within 300m of school
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (collector != null) collector.stop();
        if (runningCollector == collector) runningCollector = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static MappingCollector getRunningCollector() {
        return runningCollector;
    }
}
