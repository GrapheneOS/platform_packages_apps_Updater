package app.seamlessupdate.client;

import static android.os.Build.DEVICE;
import static android.os.Build.FINGERPRINT;
import static android.os.Build.VERSION.INCREMENTAL;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Network;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RecoverySystem;
import android.os.ServiceSpecificException;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngine.ErrorCodeConstants;
import android.os.UpdateEngine.UpdateStatusConstants;
import android.os.UpdateEngineCallback;
import android.os.storage.StorageManager;
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

import libcore.io.IoUtils;

public class Service extends IntentService {
    private static final String TAG = "Service";
    static final String INTENT_EXTRA_NETWORK = "network";
    static final String INTENT_EXTRA_IS_USER_INITIATED = "is_user_initiated";
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 30000;
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

    private HttpURLConnection fetchData(final Network network, final String path) throws IOException {
        final URL url = new URL(getString(R.string.url) + path);
        final HttpURLConnection urlConnection = (HttpURLConnection) network.openConnection(url);
        urlConnection.setConnectTimeout(CONNECT_TIMEOUT);
        urlConnection.setReadTimeout(READ_TIMEOUT);
        return urlConnection;
    }

    private void applyUpdate(final boolean streaming, final long payloadOffset, final String[] headerKeyValuePairs) {
        notificationHandler.showInstallNotification(0);

        final CountDownLatch monitor = new CountDownLatch(1);
        final UpdateEngine engine = new UpdateEngine();
        engine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.d(TAG, "onStatusUpdate: " + status + ", " + percent * 100 + "%");
                if (status == UpdateStatusConstants.DOWNLOADING) {
                    notificationHandler.showInstallNotification(Math.round(percent * 100));
                } else if (status == UpdateStatusConstants.VERIFYING) {
                    notificationHandler.showValidateNotification(Math.round(percent * 100));
                } else if (status == UpdateStatusConstants.FINALIZING) {
                    notificationHandler.showFinalizeNotification(Math.round(percent * 100));
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
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
            notificationHandler.showVerifyNotification(0);
            RecoverySystem.verifyPackage(UPDATE_PATH, (int progress) -> {
                Log.d(TAG, "verifyPackage: " + progress + "%");
                notificationHandler.showVerifyNotification(progress);
            }, null);

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

        final Network network = intent.getParcelableExtra(INTENT_EXTRA_NETWORK, Network.class);
        final var serviceIsUserInitiated = intent.getBooleanExtra(INTENT_EXTRA_IS_USER_INITIATED, false);
        if (serviceIsUserInitiated) Log.d(TAG, "onHandeIntent() – service is user-initiated");

        final PowerManager pm = getSystemService(PowerManager.class);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:" + TAG);
        HttpURLConnection connection = null;
        InputStream input = null;
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
            notificationHandler.start();

            if (network == null) {
                throw new IOException("Network is unavailable");
            }

            final String channel = SystemProperties.get("sys.update.channel", Settings.getChannel(this));

            Log.d(TAG, "fetching metadata for " + DEVICE + "-" + channel);
            connection = fetchData(network, DEVICE + "-" + channel);
            final String[] metadata;
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                metadata = reader.readLine().split(" ");
            }

            final String targetIncremental = metadata[0];
            final long targetBuildDate = Long.parseLong(metadata[1]);
            final long sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (targetBuildDate <= sourceBuildDate) {
                notificationHandler.showUpdatedNotification(channel);
                Log.d(TAG, "targetBuildDate: " + targetBuildDate + " not higher than sourceBuildDate: " + sourceBuildDate);
                mUpdating = false;
                return;
            }
            final String targetDevice = metadata[2];
            if (!targetDevice.equals(DEVICE)) {
                throw new GeneralSecurityException("targetDevice: " + targetDevice + " does not match device: " + DEVICE);
            }
            final String targetChannel = metadata[3];
            if (!targetChannel.equals(channel)) {
                throw new GeneralSecurityException("targetChannel: " + targetChannel + " does not match channel: " + channel);
            }

            notificationHandler.showDownloadNotification(0, 100);

            String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null);
            long downloaded;
            long contentLength;

            final boolean streaming = SystemProperties.getBoolean("sys.update.streaming_test", false);

            final String streamingPrefix = streaming ? "-streaming" : "";
            final String incrementalUpdate = DEVICE + streamingPrefix + "-incremental-" + INCREMENTAL + "-" + targetIncremental + ".zip";
            final String fullUpdate = DEVICE + streamingPrefix + "-ota_update-" + targetIncremental + ".zip";

            if (incrementalUpdate.equals(downloadFile) || fullUpdate.equals(downloadFile)) {
                downloaded = UPDATE_PATH.length();
                Log.d(TAG, "resume fetch of " + downloadFile + " from " + downloaded + " bytes");
                connection = fetchData(network, downloadFile);
                connection.setRequestProperty("Range", "bytes=" + downloaded + "-");
                if (connection.getResponseCode() == HTTP_RANGE_NOT_SATISFIABLE) {
                    Log.d(TAG, "download completed previously");
                    onDownloadFinished(streaming, targetBuildDate, channel);
                    return;
                }
                contentLength = connection.getContentLengthLong() + downloaded;
                input = connection.getInputStream();
            } else {
                try {
                    Log.d(TAG, "fetch incremental " + incrementalUpdate);
                    downloadFile = incrementalUpdate;
                    connection = fetchData(network, downloadFile);
                    contentLength = connection.getContentLengthLong();
                    input = connection.getInputStream();
                } catch (final IOException e) {
                    Log.d(TAG, "incremental not found, fetch full update " + fullUpdate);
                    downloadFile = fullUpdate;
                    connection = fetchData(network, downloadFile);
                    contentLength = connection.getContentLengthLong();
                    input = connection.getInputStream();
                }
                downloaded = 0;
                Files.deleteIfExists(UPDATE_PATH.toPath());
            }

            notificationHandler.showDownloadNotification(downloaded, contentLength);

            final long requiredBytes = contentLength - downloaded;
            try {
                StorageManager sm = getSystemService(StorageManager.class);
                // TODO: allocating bytes for file descriptor is more reliable, but it breaks the current
                //  download resume code
                sm.allocateBytes(sm.getUuidForPath(UPDATE_PATH), requiredBytes, StorageManager.FLAG_ALLOCATE_AGGRESSIVE);
            } catch (IOException e) {
                // storage space is likely to become available later
                Log.d(TAG, "unable to allocate " + requiredBytes + " bytes, proceeding anyway", e);
            }

            try (final OutputStream output = new FileOutputStream(UPDATE_PATH, downloaded != 0)) {
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
                        notificationHandler.showDownloadNotification(downloaded, contentLength);
                        last = now;
                    }
                }
            }

            Log.d(TAG, "download completed");
            onDownloadFinished(streaming, targetBuildDate, channel);
        } catch (GeneralSecurityException | IOException | ServiceSpecificException e) {
            Log.e(TAG, "failed to download and install update", e);
            notificationHandler.showFailureNotification(e.getMessage());
            mUpdating = false;
            if (serviceIsUserInitiated) {
                // Either the user will try again immediately or the already scheduled periodic
                // job will pick it up under constraints (which will retry on failure)
                Log.w(TAG, "onHandleIntent() – service failed but failure is ignored because it was user-initiated");
            } else {
                PeriodicJob.scheduleRetry(this);
                Log.w(TAG, "onHandleIntent() – service failed but has been scheduled for retry");
            }
        } finally {
            IoUtils.closeQuietly(input);

            if (connection != null) {
                connection.disconnect();
            }
            notificationHandler.cancelProgressNotification();
            Log.d(TAG, "release wake lock");
            wakeLock.release();
        }
    }
}
