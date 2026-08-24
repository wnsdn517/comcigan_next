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

    @Override
    public void onCreate() {
        super.onCreate();
        collector = new MappingCollector(this);
    }

    // Same reasoning as LiveNotifyService: every startForegroundService()
    // call needs a startForeground() to follow, even if the service (and
    // its collector) is already running, or the system kills the process
    // with ForegroundServiceDidNotStartInTimeException on a second start.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NotificationHelper.ID_MAPPING, NotificationHelper.buildMapping(this));
        try {
            if (!collector.isRunning()) collector.start();
            runningCollector = collector;
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
