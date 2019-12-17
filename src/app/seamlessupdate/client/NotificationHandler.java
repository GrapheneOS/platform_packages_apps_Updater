package app.seamlessupdate.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static app.seamlessupdate.client.Service.isAbUpdate;

public class NotificationHandler {

    private static final int NOTIFICATION_ID_DOWNLOAD = 1;
    private static final int NOTIFICATION_ID_INSTALL = 2;
    private static final int NOTIFICATION_ID_REBOOT = 3;
    private static final int NOTIFICATION_ID_ERROR = 4;
    private static final String NOTIFICATION_CHANNEL_ID = "updates2";
    private static final String NOTIFICATION_CHANNEL_ID_PROGRESS = "progress";
    private static final String NOTIFICATION_CHANNEL_ID_ERROR = "update_error";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;

    private final Context context;
    private final NotificationManager notificationManager;

    NotificationHandler(Context context) {
        this.context = context;
        this.notificationManager = context.getSystemService(NotificationManager.class);
        createProgressNotificationChannel();
    }

    void showDownloadNotification(int progress, int max) {
        String title = context.getString(R.string.notification_download_title);
        Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(title)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        if (max <= 0) builder.setProgress(0, 0, true);
        else builder.setProgress(max, progress, false);
        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, builder.build());
    }

    void cancelDownloadNotification() {
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD);
    }

    void showRebootNotification() {
        final String title = context.getString(isAbUpdate() ? R.string.notification_title : R.string.notification_title_legacy);
        final String text = context.getString(isAbUpdate() ? R.string.notification_text : R.string.notification_text_legacy);
        final String rebootText = context.getString(isAbUpdate() ? R.string.notification_reboot_action : R.string.notification_reboot_action_legacy);
        final PendingIntent reboot = PendingIntent.getBroadcast(context, PENDING_REBOOT_ID, new Intent(context, RebootReceiver.class), 0);

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.enableLights(true);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);
        notificationManager.notify(NOTIFICATION_ID_REBOOT, new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .addAction(R.drawable.ic_restart, rebootText, reboot)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    void showInstallNotification(int progress, int max) {
        String title = context.getString(R.string.notification_install_title);
        Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(title)
                .setOngoing(true)
                .setProgress(max, progress, false)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        notificationManager.notify(NOTIFICATION_ID_INSTALL, builder.build());
    }

    void cancelInstallNotification() {
        notificationManager.cancel(NOTIFICATION_ID_INSTALL);
    }
	
    void showUpdateFailNotification(String errorText) {
        String title = context.getString(R.string.notification_update_fail_title);
        String text = context.getString(R.string.notification_update_fail_text);
        String details = context.getString(R.string.notification_update_fail_details);
        String expandedText = text.concat("\n").concat(details).concat(": ").concat(errorText);

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_ERROR,
                context.getString(R.string.notification_update_fail), NotificationManager.IMPORTANCE_HIGH);
        channel.enableLights(true);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);

        notificationManager.notify(NOTIFICATION_ID_ERROR, new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_ERROR)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(expandedText))
                .build());
    }

    private void createProgressNotificationChannel() {
        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                context.getString(R.string.notification_channel_progress), IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    private PendingIntent getPendingSettingsIntent() {
        return PendingIntent.getActivity(context, PENDING_SETTINGS_ID, new Intent(context, Settings.class), 0);
    }

}
