package app.seamlessupdate.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        Log.d(TAG, "Boot received.");

        if (context.getSystemService(UserManager.class).isSystemUser()) {
            Settings.getPreferences(context).edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, false).apply();
            PeriodicJob.schedule(context);
        } else {
            context.getPackageManager().setApplicationEnabledSetting(context.getPackageName(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        }
    }
}
