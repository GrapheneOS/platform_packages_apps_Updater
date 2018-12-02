package app.seamlessupdate.client;

import android.support.v4.content.WakefulBroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TriggerUpdateReceiver extends WakefulBroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        startWakefulService(context, new Intent(context, Service.class));
    }
}
