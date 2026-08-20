package dev.rocky.comcitime;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

public class NotificationScheduler {

    public static final String EXTRA_TYPE = "type";
    public static final String TYPE_MORNING = "morning";
    public static final String TYPE_PERIOD = "period";
    public static final String TYPE_CHANGE_CHECK = "change_check";

    private static final int REQ_MORNING = 2001;
    private static final int REQ_PERIOD_BASE = 2100; // +period number
    private static final int REQ_CHANGE_CHECK = 2002;

    public static void rescheduleAll(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        cancelAll(ctx);
        if (prefs.notifyMorning()) scheduleMorning(ctx, prefs);
        if (prefs.notifyPeriod()) schedulePeriodAlarms(ctx, prefs);
        if (prefs.notifyChange()) scheduleChangeCheck(ctx);
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pendingFor(ctx, TYPE_MORNING, REQ_MORNING, 0));
        for (int p = 1; p <= 8; p++) am.cancel(pendingFor(ctx, TYPE_PERIOD, REQ_PERIOD_BASE + p, p));
        am.cancel(pendingFor(ctx, TYPE_CHANGE_CHECK, REQ_CHANGE_CHECK, 0));
    }

    private static void scheduleMorning(Context ctx, Prefs prefs) {
        String[] hm = prefs.morningTime().split(":");
        Calendar cal = nextOccurrence(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        setDailyExact(ctx, cal.getTimeInMillis(), TYPE_MORNING, REQ_MORNING, 0);
    }

    // Fires at the END of each period (break time), announcing the NEXT
    // class -- matches "쉬는시간마다 다음 수업 알림".
    private static void schedulePeriodAlarms(Context ctx, Prefs prefs) {
        for (int p = 1; p <= 8; p++) {
            String range = prefs.periodTime(p); // "HH:mm-HH:mm"
            String[] parts = range.split("-");
            if (parts.length != 2) continue;
            String[] endHm = parts[1].split(":");
            try {
                Calendar cal = nextOccurrence(Integer.parseInt(endHm[0].trim()), Integer.parseInt(endHm[1].trim()));
                setDailyExact(ctx, cal.getTimeInMillis(), TYPE_PERIOD, REQ_PERIOD_BASE + p, p);
            } catch (Exception ignored) {}
        }
    }

    // Polls a couple of times during the school day to catch schedule
    // changes as early as possible without hammering the API.
    private static void scheduleChangeCheck(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Calendar cal = nextOccurrence(6, 30); // once early, before the morning notification too
        PendingIntent pi = pendingFor(ctx, TYPE_CHANGE_CHECK, REQ_CHANGE_CHECK, 0);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    private static void setDailyExact(Context ctx, long triggerAt, String type, int reqCode, int periodExtra) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pendingFor(ctx, type, reqCode, periodExtra);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    private static PendingIntent pendingFor(Context ctx, String type, int reqCode, int periodExtra) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(EXTRA_TYPE, type);
        if (periodExtra > 0) intent.putExtra("period", periodExtra);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, reqCode, intent, flags);
    }

    private static Calendar nextOccurrence(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        Calendar now = (Calendar) cal.clone();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (!cal.after(now)) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal;
    }

    // AlarmReceiver re-arms the SAME alarm for tomorrow after it fires,
    // since AlarmManager one-shots don't repeat exactly-daily reliably
    // across DST etc. See AlarmReceiver.rearm().
    static void rearmSingle(Context ctx, String type, int reqCode, int periodExtra, int hour, int minute) {
        Calendar cal = nextOccurrence(hour, minute);
        setDailyExact(ctx, cal.getTimeInMillis(), type, reqCode, periodExtra);
    }

    static int reqCodeForPeriod(int period) { return REQ_PERIOD_BASE + period; }
    static int reqCodeMorning() { return REQ_MORNING; }
    static int reqCodeChangeCheck() { return REQ_CHANGE_CHECK; }
}
