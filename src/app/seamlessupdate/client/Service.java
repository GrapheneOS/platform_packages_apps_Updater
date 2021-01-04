package app.seamlessupdate.client;

import static android.os.Build.DEVICE;
import static android.os.Build.FINGERPRINT;
import static android.os.Build.VERSION.INCREMENTAL;
import static android.os.UpdateEngine.UpdateStatusConstants.DOWNLOADING;
import static android.os.UpdateEngine.UpdateStatusConstants.FINALIZING;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RecoverySystem;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngine.ErrorCodeConstants;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Service extends IntentService {
    private static final String TAG = "Service";
    private static final int CONNECT_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;
    private static final File CARE_MAP_PATH = new File("/data/ota_package/care_map.pb");
    private static final File UPDATE_PATH = new File("/data/ota_package/update.zip");
    private static final String PREFERENCE_DOWNLOAD_FILE = "download_file";
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private NotificationHandler notificationHandler;
    private boolean mUpdating = false;

    public Service() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationHandler = new NotificationHandler(this);
    }

    private HttpURLConnection fetchData(final String path) throws IOException {
        final URL url = new URL(getString(R.string.url) + path);
        final HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(CONNECT_TIMEOUT);
        urlConnection.setReadTimeout(READ_TIMEOUT);
        return urlConnection;
    }

    private void applyUpdate(final boolean streaming, final long payloadOffset, final String[] headerKeyValuePairs) {
        final CountDownLatch monitor = new CountDownLatch(1);
        final UpdateEngine engine = new UpdateEngine();
        engine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.d(TAG, "onStatusUpdate: " + status + ", " + percent * 100 + "%");
                if (status == DOWNLOADING) {
                    notificationHandler.showInstallNotification(Math.round(percent * 100), 200);
                } else if (status == FINALIZING) {
                    notificationHandler.showInstallNotification(Math.round(percent * 100) + 100, 200);
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                notificationHandler.cancelInstallNotification();
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "onPayloadApplicationComplete success");
                    annoyUser();
                } else {
                    Log.d(TAG, "onPayloadApplicationComplete: " + errorCode);
                    mUpdating = false;
                }
                UPDATE_PATH.delete();
                monitor.countDown();
            }
        });
        if (streaming) {
            final SharedPreferences preferences = Settings.getPreferences(this);
            final String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE.replace("-streaming", ""), null);
            engine.applyPayload(getString(R.string.url) + downloadFile, payloadOffset, 0, headerKeyValuePairs);
        } else {
            UPDATE_PATH.setReadable(true, false);
            engine.applyPayload("file://" + UPDATE_PATH, payloadOffset, 0, headerKeyValuePairs);
        }
        try {
            monitor.await();
        } catch (InterruptedException e) {}
    }

    private static ZipEntry getEntry(final ZipFile zipFile, final String name) throws GeneralSecurityException {
        final ZipEntry entry = zipFile.getEntry(name);
        if (entry == null) {
            throw new GeneralSecurityException("missing zip entry: " + name);
        }
        return entry;
    }

    private void onDownloadFinished(final boolean streaming, final long targetBuildDate, final String channel) throws IOException, GeneralSecurityException {
        try {
            RecoverySystem.verifyPackage(UPDATE_PATH,
                (int progress) -> Log.d(TAG, "verifyPackage: " + progress + "%"), null);

            final ZipFile zipFile = new ZipFile(UPDATE_PATH);

            final ZipEntry metadata = getEntry(zipFile, "META-INF/com/android/metadata");
            final BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(metadata)));
            String device = null;
            String serialno = null;
            String type = null;
            String sourceIncremental = null;
            String sourceFingerprint = null;
            String streamingPropertyFiles[] = null;
            long timestamp = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                final String[] pair = line.split("=");
                if ("post-timestamp".equals(pair[0])) {
                    timestamp = Long.parseLong(pair[1]);
                } else if ("serialno".equals(pair[0])) {
                    serialno = pair[1];
                } else if ("pre-device".equals(pair[0])) {
                    device = pair[1];
                } else if ("ota-type".equals(pair[0])) {
                    type = pair[1];
                } else if ("ota-streaming-property-files".equals(pair[0])) {
                    streamingPropertyFiles = pair[1].trim().split(",");
                } else if ("pre-build-incremental".equals(pair[0])) {
                    sourceIncremental = pair[1];
                } else if ("pre-build".equals(pair[0])) {
                    sourceFingerprint = pair[1];
                }
            }
            if (timestamp != targetBuildDate) {
                throw new GeneralSecurityException("timestamp does not match server metadata");
            }
            if (!DEVICE.equals(device)) {
                throw new GeneralSecurityException("device mismatch");
            }
            if (serialno != null) {
                throw new GeneralSecurityException("serialno constraint not permitted");
            }
            if (!"AB".equals(type)) {
                throw new GeneralSecurityException("package is not an A/B update");
            }
            if (sourceIncremental != null && !sourceIncremental.equals(INCREMENTAL)) {
                throw new GeneralSecurityException("source incremental mismatch");
            }
            if (sourceFingerprint != null && !sourceFingerprint.equals(FINGERPRINT)) {
                throw new GeneralSecurityException("source fingerprint mismatch");
            }

            long payloadOffset = 0;
            for (final String streamingPropertyFile : streamingPropertyFiles) {
                final String properties[] = streamingPropertyFile.split(":");
                if ("payload.bin".equals(properties[0])) {
                    payloadOffset = Long.parseLong(properties[1]);
                }
            }

            Files.deleteIfExists(CARE_MAP_PATH.toPath());
            final ZipEntry careMapEntry = zipFile.getEntry("care_map.pb");
            if (careMapEntry == null) {
                Log.w(TAG, "care_map.pb missing");
            } else {
                Files.copy(zipFile.getInputStream(careMapEntry), CARE_MAP_PATH.toPath());
                CARE_MAP_PATH.setReadable(true, false);
            }

            final ZipEntry payloadProperties = getEntry(zipFile, "payload_properties.txt");
            final BufferedReader propertiesReader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(payloadProperties)));
            applyUpdate(streaming, payloadOffset, propertiesReader.lines().toArray(String[]::new));
        } catch (GeneralSecurityException e) {
            UPDATE_PATH.delete();
            throw e;
        }
    }

    private void annoyUser() {
        PeriodicJob.cancel(this);
        final SharedPreferences preferences = Settings.getPreferences(this);
        preferences.edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, true).apply();
        if (Settings.getIdleReboot(this)) {
            IdleReboot.schedule(this);
        }
        notificationHandler.showRebootNotification();
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        Log.d(TAG, "onHandleIntent");

        final PowerManager pm = getSystemService(PowerManager.class);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        HttpURLConnection connection = null;
        try {
            wakeLock.acquire();

            if (mUpdating) {
                Log.d(TAG, "updating already, returning early");
                return;
            }
            final SharedPreferences preferences = Settings.getPreferences(this);
            if (preferences.getBoolean(Settings.KEY_WAITING_FOR_REBOOT, false)) {
                Log.d(TAG, "updated already, waiting for reboot");
                return;
            }
            mUpdating = true;

            final String channel = SystemProperties.get("sys.update.channel", Settings.getChannel(this));

            Log.d(TAG, "fetching metadata for " + DEVICE + "-" + channel);
            connection = fetchData(DEVICE + "-" + channel);
            InputStream input = connection.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            final String[] metadata = reader.readLine().split(" ");
            reader.close();

            final String targetIncremental = metadata[0];
            final long targetBuildDate = Long.parseLong(metadata[1]);
            final long sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (targetBuildDate <= sourceBuildDate) {
                Log.d(TAG, "targetBuildDate: " + targetBuildDate + " not higher than sourceBuildDate: " + sourceBuildDate);
                mUpdating = false;
                return;
            }
            final String targetChannel = metadata[3];
            if (!targetChannel.equals(channel)) {
                throw new GeneralSecurityException("targetChannel: " + targetChannel + " does not match channel: " + channel);
            }

            String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null);
            long downloaded = UPDATE_PATH.length();
            int contentLength;

            final boolean streaming = SystemProperties.getBoolean("sys.update.streaming_test", false);

            final String streamingPrefix = streaming ? "-streaming" : "";
            final String incrementalUpdate = DEVICE + streamingPrefix + "-incremental-" + INCREMENTAL + "-" + targetIncremental + ".zip";
            final String fullUpdate = DEVICE + streamingPrefix + "-ota_update-" + targetIncremental + ".zip";

            if (incrementalUpdate.equals(downloadFile) || fullUpdate.equals(downloadFile)) {
                Log.d(TAG, "resume fetch of " + downloadFile + " from " + downloaded + " bytes");
                connection = fetchData(downloadFile);
                connection.setRequestProperty("Range", "bytes=" + downloaded + "-");
                if (connection.getResponseCode() == HTTP_RANGE_NOT_SATISFIABLE) {
                    Log.d(TAG, "download completed previously");
                    onDownloadFinished(streaming, targetBuildDate, channel);
                    return;
                }
                contentLength = connection.getContentLength() + (int) downloaded;
                input = connection.getInputStream();
            } else {
                try {
                    Log.d(TAG, "fetch incremental " + incrementalUpdate);
                    downloadFile = incrementalUpdate;
                    connection = fetchData(downloadFile);
                    contentLength = connection.getContentLength();
                    input = connection.getInputStream();
                } catch (final IOException e) {
                    Log.d(TAG, "incremental not found, fetch full update " + fullUpdate);
                    downloadFile = fullUpdate;
                    connection = fetchData(downloadFile);
                    contentLength = connection.getContentLength();
                    input = connection.getInputStream();
                }
                downloaded = 0;
                Files.deleteIfExists(UPDATE_PATH.toPath());
            }

            notificationHandler.showDownloadNotification((int) downloaded, contentLength);

            final OutputStream output = new FileOutputStream(UPDATE_PATH, downloaded != 0);
            preferences.edit().putString(PREFERENCE_DOWNLOAD_FILE, downloadFile).commit();

            int bytesRead;
            long last = System.nanoTime();
            final byte[] buffer = new byte[8192];
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                downloaded += bytesRead;
                final long now = System.nanoTime();
                if (now - last > 1000 * 1000 * 1000) {
                    Log.d(TAG, "downloaded " + downloaded + " from " + contentLength + " bytes");
                    notificationHandler.showDownloadNotification((int) downloaded, contentLength);
                    last = now;
                }
            }
            output.close();

            Log.d(TAG, "download completed");
            notificationHandler.cancelDownloadNotification();
            onDownloadFinished(streaming, targetBuildDate, channel);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "failed to download and install update", e);
            mUpdating = false;
            PeriodicJob.scheduleRetry(this);
        } finally {
            Log.d(TAG, "release wake locks");
            wakeLock.release();
            if (connection != null) {
                connection.disconnect();
            }
            notificationHandler.cancelDownloadNotification();
            notificationHandler.cancelInstallNotification();
            TriggerUpdateReceiver.completeWakefulIntent(intent);
        }
    }
}
