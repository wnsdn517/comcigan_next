package dev.rocky.comcitime;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Prefs prefs = new Prefs(context);
            if (!prefs.schoolCode().isEmpty()) {
                NotificationScheduler.rescheduleAll(context);
                if (prefs.liveNotify()) {
                    context.startForegroundService(new Intent(context, LiveNotifyService.class));
                }
            }
            if (prefs.mappingConsentDone() && hasMappingPermissions(context)) {
                context.startForegroundService(new Intent(context, MappingService.class));
                MappingWatchdogReceiver.schedule(context);
            }
        }
    }

    private boolean hasMappingPermissions(Context context) {
        boolean fineLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean activityRecognition = Build.VERSION.SDK_INT < 29
                || context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        return fineLocation && activityRecognition;
    }
}
