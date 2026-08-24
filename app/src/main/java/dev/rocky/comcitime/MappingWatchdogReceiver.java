package dev.rocky.comcitime;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

// Periodically re-asserts that the indoor-mapping background service is
// running, since it can otherwise silently stop for long stretches --
// most commonly an OEM battery manager killing it while the phone sits
// idle with the screen off for hours (e.g. a school day) -- with nothing
// to bring it back until the user happens to reopen the app (see
// MainActivity.onCreate()/refreshMappingStatus()). This receiver is
// manifest-registered rather than a plain in-app timer, so AlarmManager
// can wake the app and re-deliver this even if the process was fully
// killed in the meantime; startForegroundService() here is a no-op aside
// from a fresh notification post if the service (and its collector) is
// already running (see MappingService.onStartCommand()).
public class MappingWatchdogReceiver extends BroadcastReceiver {
    private static final String ACTION_CHECK = "dev.rocky.comcitime.action.MAPPING_WATCHDOG";
    private static final long INTERVAL_MS = 20 * 60_000L; // 20 min: frequent enough to
    // recover well within a single class period, infrequent enough to be a
    // negligible battery cost riding on Android's own inexact-alarm batching.

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        if (prefs.mappingConsentDone() && hasMappingPermissions(context)) {
            context.startForegroundService(new Intent(context, MappingService.class));
        }
    }

    private static boolean hasMappingPermissions(Context context) {
        boolean fineLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean activityRecognition = Build.VERSION.SDK_INT < 29
                || context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        return fineLocation && activityRecognition;
    }

    // Called every time something already confirms the mapping feature
    // should be active (MainActivity launch, boot, a successful manual
    // start) -- setInexactRepeating() with the same PendingIntent just
    // replaces the existing schedule, so this is safe to call repeatedly.
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pendingIntent(context);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pi);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, MappingWatchdogReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }
}
