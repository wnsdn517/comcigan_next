package dev.rocky.comcitime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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
        }
    }
}
