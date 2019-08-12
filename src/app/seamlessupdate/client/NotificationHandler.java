package app.seamlessupdate.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.FileProvider;

import java.io.File;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static app.seamlessupdate.client.Service.isAbUpdate;

public class NotificationHandler {

    private static final int NOTIFICATION_ID_DOWNLOAD = 1;
    private static final int NOTIFICATION_ID_INSTALL = 2;
    private static final int NOTIFICATION_ID_REBOOT = 3;
    private static final String NOTIFICATION_CHANNEL_ID = "updates2";
    private static final String NOTIFICATION_CHANNEL_ID_PROGRESS = "progress";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;
    private static final int PENDING_CHANGELOG_ID = 3;

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
                .setContentIntent(getPendingChangelogIntent())
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
                .setContentIntent(getPendingChangelogIntent())
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    void showInstallNotification(int progress, int max) {
        String title = context.getString(R.string.notification_install_title);
        Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingChangelogIntent())
                .setContentTitle(title)
                .setOngoing(true)
                .setProgress(max, progress, false)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        notificationManager.notify(NOTIFICATION_ID_INSTALL, builder.build());
    }

    void cancelInstallNotification() {
        notificationManager.cancel(NOTIFICATION_ID_INSTALL);
    }

    private void createProgressNotificationChannel() {
        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                context.getString(R.string.notification_channel_progress), IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    private PendingIntent getPendingChangelogIntent() {
        Intent intent = Settings.getChangelogIntent(context, "current.html");
        if (intent != null) {
            return PendingIntent.getActivity(context, PENDING_CHANGELOG_ID, intent, 0);
        } else {
            return null;
        }
    }

}
