package com.cyanogenmod.updater.service;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ProgressBar;

import com.cyanogenmod.updater.R;
import com.cyanogenmod.updater.misc.Constants;
import com.cyanogenmod.updater.misc.UpdateInfo;
import com.cyanogenmod.updater.utils.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public class ABUpdaterService extends Service {
    private static String LOG_TAG = "ABUpdaterService";

    private static final int NOTIFICATION_ID = 0x052890;
    private NotificationManager mNotificationManager;

    private NotificationCompat.Builder mNotificationBuilder;
    private NotificationCompat.BigTextStyle mNotificationStyle;

    public static final String EXTRA_UPDATE_INFO = "extra_update_info";
    public static final String EXTRA_UPDATE_PACKAGE_PATH = "extra_update_package_path";
    public static final String ACTION_INSTALL_UPDATE = "action_install_update";
    private UpdateInfo updateInfo;

    private static class UpdateEngineCallback extends android.os.UpdateEngineCallback {

        private android.os.UpdateEngineCallback callback;

        public void setCallback(android.os.UpdateEngineCallback cb) {
            this.callback = cb;
        }

        @Override
        public void onStatusUpdate(int status, float percent) {
            if(this.callback == null)   return;

            this.callback.onStatusUpdate(status, percent);
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            if(this.callback == null)   return;

            this.callback.onPayloadApplicationComplete(errorCode);
        }
    }
    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback();
    private UpdateEngine mUpdateEngine = null;

    private UpdateEngine getUpdateEngine(android.os.UpdateEngineCallback callback) {
        if (mUpdateEngine == null) {
            mUpdateEngine = new UpdateEngine();
            mUpdateEngine.bind(mUpdateEngineCallback, new Handler(Looper.getMainLooper()));
        }
        mUpdateEngineCallback.setCallback(callback);

        return mUpdateEngine;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mNotificationBuilder = new NotificationCompat.Builder(this);
        mNotificationBuilder.setSmallIcon(R.drawable.ic_system_update);
        mNotificationBuilder.setShowWhen(false);
        mNotificationStyle = new NotificationCompat.BigTextStyle();
        mNotificationBuilder.setStyle(mNotificationStyle);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_INSTALL_UPDATE.equals(intent.getAction())) {
            updateInfo = intent.getParcelableExtra(EXTRA_UPDATE_INFO);
            String updatePackagePath = intent.getStringExtra(EXTRA_UPDATE_PACKAGE_PATH);

            long offset;
            String[] headerKeyValuePairs;
            try {
                ZipFile zipFile = new ZipFile(updatePackagePath);
                offset = Utils.getZipEntryOffset(zipFile, Constants.AB_PAYLOAD_BIN_PATH);
                ZipEntry payloadPropEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH);
                try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                     InputStreamReader isr = new InputStreamReader(is);
                     BufferedReader br = new BufferedReader(isr)) {
                    List<String> lines = new ArrayList<>();
                    for (String line; (line = br.readLine()) != null;) {
                        lines.add(line);
                    }
                    headerKeyValuePairs = new String[lines.size()];
                    headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
                }
                zipFile.close();

                UpdateEngine updateEngine = getUpdateEngine(new android.os.UpdateEngineCallback() {

                    @Override
                    public void onStatusUpdate(int status, float percent) {
                        Log.v(LOG_TAG, String.format("ABUpdate Status : %d %f", status, percent));
                        switch (status) {
                            case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                            case UpdateEngine.UpdateStatusConstants.FINALIZING: {
                                handleInstallProgress(Math.round(percent * 100), updateInfo);
                            }
                            break;

                            case UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT: {
                                showInstallationFailed();
                                mUpdateEngineCallback.setCallback(null);
                            }
                            break;
                        }
                    }

                    @Override
                    public void onPayloadApplicationComplete(int errorCode) {
                        Log.v(LOG_TAG, String.format("ABUpdate Complete - Error Code : %d", errorCode));
                        switch (errorCode) {
                            case UpdateEngine.ErrorCodeConstants.SUCCESS: {
                                showRebootNotification();
                            }
                            break;

                            default: {
                                showInstallationFailed();
                                mUpdateEngineCallback.setCallback(null);
                            }
                            break;
                        }
                    }
                });

                new File(updatePackagePath).setReadable(true, false);
                updateEngine.applyPayload("file://" + updatePackagePath,
                        offset, 0, headerKeyValuePairs);

                mNotificationBuilder.mActions.clear();
                mNotificationBuilder.setProgress(0, 0, true);
                mNotificationStyle.setSummaryText(null);
                String text = getString(R.string.installing_update);
                mNotificationStyle.bigText(text);
                mNotificationBuilder.setTicker(text);
                mNotificationBuilder.setOngoing(true);
                mNotificationBuilder.setAutoCancel(false);
                startForeground(NOTIFICATION_ID, mNotificationBuilder.build());
                mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
            }
            catch (IOException | IllegalArgumentException e) {
                // TODO : show error
            }
            
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showRebootNotification() {
        stopForeground(STOP_FOREGROUND_DETACH);
        mNotificationBuilder.setProgress(100, 100, false);
        String text = getString(R.string.installing_update_finished);
        mNotificationStyle.bigText(text);
        mNotificationBuilder.addAction(R.drawable.ic_system_update,
                getString(R.string.reboot_now),
                PendingIntent.getBroadcast(this, 0, new Intent(Intent.ACTION_REBOOT),
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT););
        mNotificationBuilder.setTicker(text);
        mNotificationBuilder.setOngoing(false);
        mNotificationBuilder.setAutoCancel(true);
        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
        stopSelf();
    }

    private void showInstallationFailed() {
        stopForeground(STOP_FOREGROUND_DETACH);
        mNotificationBuilder.setProgress(0, 0, false);
        String text = getString(R.string.installation_failed);
        mNotificationStyle.bigText(text);
        mNotificationBuilder.setTicker(text);
        mNotificationBuilder.setOngoing(false);
        mNotificationBuilder.setAutoCancel(true);
        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
        stopSelf();
    }

    private void handleInstallProgress(int progress, UpdateInfo updateInfo) {
        mNotificationBuilder.setProgress(100, progress, false);

        setNotificationTitle(updateInfo);

        String percent = NumberFormat.getPercentInstance().format(progress / 100.f);
        mNotificationStyle.setSummaryText(percent);
        // TODO : figure out bigText
        mNotificationStyle.bigText("THIS IS THE BIG TEXT");

        mNotificationManager.notify(NOTIFICATION_ID, mNotificationBuilder.build());
    }

    private void setNotificationTitle(UpdateInfo update) {
        // TODO : add resource for big content title
        mNotificationStyle.setBigContentTitle("Installing Update");
        mNotificationBuilder.setContentTitle(update.getId()
                + "."
                + update.getIncremental());
    }
}
