package app.seamlessupdate.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.text.Html;
import android.text.Spanned;

import java.util.ArrayList;
import java.util.List;

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;

public class NotificationHandler {
    private static enum Phase {
        CHECK, DOWNLOAD, VERIFY, INSTALL
    }

    private static final int NOTIFICATION_ID_PROGRESS = 1;
    private static final int NOTIFICATION_ID_REBOOT = 2;
    private static final int NOTIFICATION_ID_FAILURE = 3;
    private static final int NOTIFICATION_ID_UPDATED = 4;
    private static final String NOTIFICATION_CHANNEL_ID_PROGRESS = "progress";
    private static final String NOTIFICATION_CHANNEL_ID_REBOOT = "updates2";
    private static final String NOTIFICATION_CHANNEL_ID_FAILURE = "failure";
    private static final String NOTIFICATION_CHANNEL_ID_UPDATED = "updated";
    private static final int PENDING_REBOOT_ID = 1;
    private static final int PENDING_SETTINGS_ID = 2;

    private final Service service;
    private final NotificationManager notificationManager;

    private static Phase phase;

    NotificationHandler(Service service) {
        this.service = service;
        this.notificationManager = service.getSystemService(NotificationManager.class);

        final List<NotificationChannel> channels = new ArrayList<>();

        channels.add(new NotificationChannel(NOTIFICATION_CHANNEL_ID_PROGRESS,
                service.getString(R.string.notification_channel_progress), IMPORTANCE_LOW));

        final NotificationChannel reboot = new NotificationChannel(NOTIFICATION_CHANNEL_ID_REBOOT,
                service.getString(R.string.notification_channel_reboot), IMPORTANCE_HIGH);
        reboot.enableLights(true);
        reboot.enableVibration(true);
        channels.add(reboot);

        channels.add(new NotificationChannel(NOTIFICATION_CHANNEL_ID_FAILURE,
                service.getString(R.string.notification_channel_failure), IMPORTANCE_LOW));

        channels.add(new NotificationChannel(NOTIFICATION_CHANNEL_ID_UPDATED,
                service.getString(R.string.notification_channel_updated), IMPORTANCE_MIN));

        notificationManager.createNotificationChannels(channels);
    }

    private Notification buildProgressNotification(int resId, int progress, int max) {
        Notification.Builder builder = new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(resId))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp);
        if (max <= 0) builder.setProgress(0, 0, true);
        else builder.setProgress(max, progress, false);
        return builder.build();
    }

    void start() {
        phase = Phase.CHECK;
        notificationManager.cancelAll();
        service.startForeground(NOTIFICATION_ID_PROGRESS, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_PROGRESS)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_check_title))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp).build());
    }

    void showUpdatedNotification(final String channel) {
        final String channelText;
        if ("stable".equals(channel)) {
            channelText = service.getString(R.string.channel_stable);
        } else if ("beta".equals(channel)) {
            channelText = service.getString(R.string.channel_beta);
        } else {
            channelText = channel;
        }

        notificationManager.notify(NOTIFICATION_ID_UPDATED, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_UPDATED)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_updated_title))
                .setContentText(service.getString(R.string.notification_updated_text, channelText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    void showDownloadNotification(int progress, int max) {
        phase = Phase.DOWNLOAD;
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_download_title, progress, max));
    }

    void showVerifyNotification(int progress) {
        phase = Phase.VERIFY;
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_verify_title, progress, 100));
    }

    void showInstallNotification(int progress, int max) {
        phase = Phase.INSTALL;
        notificationManager.notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(R.string.notification_install_title, progress, max));
    }

    void cancelProgressNotification() {
        service.stopForeground(true);
    }

    void showRebootNotification() {
        final PendingIntent reboot = PendingIntent.getBroadcast(service, PENDING_REBOOT_ID,
                        new Intent(service, RebootReceiver.class), PendingIntent.FLAG_IMMUTABLE);

        Notification.Action rebootAction = new Notification.Action.Builder(
                Icon.createWithResource(service.getApplication(), R.drawable.ic_restart),
                service.getString(R.string.notification_reboot_action),
                reboot).build();

        notificationManager.notify(NOTIFICATION_ID_REBOOT, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_REBOOT)
                .addAction(rebootAction)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(R.string.notification_reboot_title))
                .setContentText(service.getString(R.string.notification_reboot_text))
                .setOngoing(true)
                .setShowWhen(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    void showFailureNotification(String exceptionMessage) {
        final int titleResId;
        final int contentResId;

        switch (phase) {
            case CHECK:
                titleResId = R.string.notification_failed_check_title;
                contentResId = R.string.notification_failed_check_text;
                break;

            case DOWNLOAD:
                titleResId = R.string.notification_failed_download_title;
                contentResId = R.string.notification_failed_download_text;
                break;

            case VERIFY:
                titleResId = R.string.notification_failed_verify_title;
                contentResId = R.string.notification_failed_verify_text;
                break;

            default:
                titleResId = R.string.notification_failed_install_title;
                contentResId = R.string.notification_failed_install_text;
        }

        String text = service.getString(contentResId) + "<br><br><tt>" + exceptionMessage + "</tt>";
        Spanned styledText = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY);

        notificationManager.notify(NOTIFICATION_ID_FAILURE, new Notification.Builder(service, NOTIFICATION_CHANNEL_ID_FAILURE)
                .setContentIntent(getPendingSettingsIntent())
                .setContentTitle(service.getString(titleResId))
                .setContentText(styledText)
                .setStyle(new Notification.BigTextStyle()
                    .bigText(styledText))
                .setShowWhen(true)
                .setSmallIcon(R.drawable.ic_system_update_white_24dp)
                .build());
    }

    private PendingIntent getPendingSettingsIntent() {
        return PendingIntent.getActivity(service, PENDING_SETTINGS_ID, new Intent(service,
                                Settings.class), PendingIntent.FLAG_IMMUTABLE);
    }
}
