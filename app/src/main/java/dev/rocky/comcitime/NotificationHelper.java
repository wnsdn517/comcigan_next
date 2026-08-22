package dev.rocky.comcitime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class NotificationHelper {

    public static final String CHANNEL_ALERTS = "comcitime_alerts";
    public static final String CHANNEL_LIVE = "comcitime_live";
    public static final String CHANNEL_MAPPING = "comcitime_mapping";

    public static final int ID_CHANGE = 1001;
    public static final int ID_PERIOD = 1002;
    public static final int ID_MORNING = 1003;
    public static final int ID_LIVE = 1004;
    public static final int ID_MAPPING = 1005;

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "시간표 알림", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("시간표 변동, 다음 수업, 아침 시간표 알림");
        nm.createNotificationChannel(alerts);

        NotificationChannel live = new NotificationChannel(
                CHANNEL_LIVE, "현재 수업 표시", NotificationManager.IMPORTANCE_LOW);
        live.setDescription("지금 몇 교시인지 상단바에 계속 표시");
        nm.createNotificationChannel(live);

        NotificationChannel mapping = new NotificationChannel(
                CHANNEL_MAPPING, "실내 지도 데이터 수집", NotificationManager.IMPORTANCE_MIN);
        mapping.setDescription("동의하신 실내 지도 만들기 기능이 백그라운드에서 동작 중임을 표시");
        nm.createNotificationChannel(mapping);
    }

    public static void show(Context ctx, int id, String channel, String title, String text, boolean autoCancel) {
        ensureChannels(ctx);
        Intent openIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, channel)
                : new Notification.Builder(ctx);
        builder.setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(autoCancel);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id, builder.build());
    }

    public static Notification buildLive(Context ctx, String title, String text) {
        ensureChannels(ctx);
        Intent openIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, ID_LIVE, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_LIVE)
                : new Notification.Builder(ctx);
        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return builder.build();
    }

    public static Notification buildMapping(Context ctx) {
        ensureChannels(ctx);
        Intent openIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, ID_MAPPING, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_MAPPING)
                : new Notification.Builder(ctx);
        builder.setContentTitle("실내 지도 데이터 수집 중")
                .setContentText("동의하신 실내 지도 만들기 기능이 백그라운드에서 동작하고 있어요.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MIN);
        return builder.build();
    }
}
