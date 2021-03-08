package app.seamlessupdate.client;

import android.content.Context;
import android.content.Intent;

import androidx.legacy.content.WakefulBroadcastReceiver;

public class TriggerUpdateReceiver extends WakefulBroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        startWakefulService(context, new Intent(context, Service.class));
    }
}
