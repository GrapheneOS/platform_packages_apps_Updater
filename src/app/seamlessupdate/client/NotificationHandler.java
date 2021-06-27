package app.seamlessupdate.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;

import static android.app.NotificationManager.IMPORTANCE_LOW;

public class NotificationHandler {
    private static final int NOTIFICATION_ID_PROGRESS = 1;
    private static final int NOTIFICATION_ID_REBOOT = 2;
    private static final String NOTIFICATION_CHANNEL_ID = "updates2";
    private static final String NOTIFICATION_CHANNEL_ID_PROGRESS = "progress";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;

    private final Service service;
    private final NotificationManager notificationManager;

    NotificationHandler(Service service) {
        this.service = service;
        this.notificationManager = service.getSystemService(NotificationManager.class);

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                service.getString(R.string.notification_channel_progress), IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    Notification buildProgressNotification(int resId, int progress, int max) {
        Notification.Builder builder = new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(resId))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        if (max <= 0) builder.setProgress(0, 0, true);
        else builder.setProgress(max, progress, false);
        return builder.build();
    }

    void showInitialDownloadNotification() {
        service.startForeground(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_download_title, 0, 100));
    }

    void showDownloadNotification(int progress, int max) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_download_title, progress, max));
    }

    void showVerifyNotification(int progress, int max) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_verify_title, progress, max));
    }

    void showInstallNotification(int progress, int max) {
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_install_title, progress, max));
    }

    void cancelProgressNotification() {
        service.stopForeground(true);
    }

    void showRebootNotification() {
        final PendingIntent reboot = PendingIntent.getBroadcast(service, PENDING_REBOOT_ID,
                        new Intent(service, RebootReceiver.class), PendingIntent.FLAG_IMMUTABLE);

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                service.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.enableLights(true);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);
        notificationManager.notify(NOTIFICATION_ID_REBOOT, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID)
                .addAction(R.drawable.ic_restart, service.getString(R.string.notification_reboot_action), reboot)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_title))
                .setContentText(service.getString(R.string.notification_text))
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    private PendingIntent getPendingSettingsIntent() {
        return PendingIntent.getActivity(service, PENDING_SETTINGS_ID, new Intent(service,
                                Settings.class), PendingIntent.FLAG_IMMUTABLE);
    }
}
