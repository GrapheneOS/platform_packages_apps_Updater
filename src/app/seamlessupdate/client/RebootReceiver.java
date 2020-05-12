package app.seamlessupdate.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.io.IOException;

public class RebootReceiver extends BroadcastReceiver {
    static void reboot(final Context context) {
        context.getSystemService(PowerManager.class).reboot(null);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        reboot(context);
    }
}
