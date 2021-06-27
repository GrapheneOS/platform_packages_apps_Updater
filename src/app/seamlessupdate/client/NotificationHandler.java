package app.seamlessupdate.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import static android.app.NotificationManager.IMPORTANCE_LOW;

public class NotificationHandler {
    private static final int NOTIFICATION_ID_PROGRESS = 1;
    private static final int NOTIFICATION_ID_REBOOT = 2;
    private static final String NOTIFICATION_CHANNEL_ID = "updates2";
    private static final String NOTIFICATION_CHANNEL_ID_PROGRESS = "progress";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;

    private final Context context;
    private final NotificationManager notificationManager;

    NotificationHandler(Context context) {
        this.context = context;
        this.notificationManager = context.getSystemService(NotificationManager.class);

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                context.getString(R.string.notification_channel_progress), IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    void showDownloadNotification(int progress, int max) {
        Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(context.getString(R.string.notification_download_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        if (max <= 0) builder.setProgress(0, 0, true);
        else builder.setProgress(max, progress, false);
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, builder.build());
    }

    void showVerifyNotification(int progress, int max) {
        Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(context.getString(R.string.notification_verify_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(max, progress, false)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, builder.build());
    }

    void showInstallNotification(int progress, int max) {
        Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(context.getString(R.string.notification_install_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(max, progress, false)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, builder.build());
    }

    void cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS);
    }

    void showRebootNotification() {
        final PendingIntent reboot = PendingIntent.getBroadcast(context, PENDING_REBOOT_ID,
                        new Intent(context, RebootReceiver.class), PendingIntent.FLAG_IMMUTABLE);

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.enableLights(true);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);
        notificationManager.notify(NOTIFICATION_ID_REBOOT, new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .addAction(R.drawable.ic_restart, context.getString(R.string.notification_reboot_action), reboot)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(R.string.notification_text))
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    private PendingIntent getPendingSettingsIntent() {
        return PendingIntent.getActivity(context, PENDING_SETTINGS_ID, new Intent(context,
                                Settings.class), PendingIntent.FLAG_IMMUTABLE);
    }
}
