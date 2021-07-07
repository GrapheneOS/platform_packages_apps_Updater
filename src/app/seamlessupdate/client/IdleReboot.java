package app.seamlessupdate.client;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

public class IdleReboot extends JobService {
    private static final String TAG = "IdleReboot";
    private static final int JOB_ID_IDLE_REBOOT = 3;
    private static final long MIN_LATENCY_MILLIS = 5 * 60 * 1000;

    static void schedule(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final ComponentName serviceName = new ComponentName(context, IdleReboot.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_IDLE_REBOOT, serviceName)
            .setRequiresDeviceIdle(true)
            .setMinimumLatency(MIN_LATENCY_MILLIS)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Job schedule failed");
        } else {
            Log.d(TAG, "Scheduled.");
        }
    }

    static void cancel(final Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID_IDLE_REBOOT);
        Log.d(TAG, "Canceled.");
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        RebootReceiver.reboot(this);
        return false;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        return false;
    }
}
