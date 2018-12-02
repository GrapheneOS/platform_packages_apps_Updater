package app.seamlessupdate.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.util.Log;

import java.io.IOException;

public class RebootReceiver extends BroadcastReceiver {
    private static final String TAG = "RebootReceiver";

    static void reboot(final Context context) {
        if (Service.isAbUpdate()) {
            context.getSystemService(PowerManager.class).reboot(null);
        } else {
            try {
                Service.UPDATE_PATH.setReadable(true, false);
                RecoverySystem.installPackage(context, Service.UPDATE_PATH);
            } catch (IOException e) {
                Log.e(TAG, "failed to reboot and install update", e);
            }
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        reboot(context);
    }
}
