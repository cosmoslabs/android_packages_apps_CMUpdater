/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater.utils;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.preference.PreferenceManager;
import android.support.v4.os.EnvironmentCompat;
import android.util.Log;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.cyanogenmod.updater.R;
import com.cyanogenmod.updater.misc.Constants;
import com.cyanogenmod.updater.service.UpdateCheckService;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {
    private static final String LOG_TAG = "Utils";

    public static String getId(Context context) {
        return getProp(context.getString(R.string.conf_build_id_property));
    }

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
    private static final Utils.UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback();
    private static UpdateEngine mUpdateEngine = null;

    private static UpdateEngine getUpdateEngine(android.os.UpdateEngineCallback callback) {
        if (mUpdateEngine == null) {
            mUpdateEngine = new UpdateEngine();
            mUpdateEngine.bind(mUpdateEngineCallback, new Handler(Looper.getMainLooper()));
        }
        mUpdateEngineCallback.setCallback(callback);

        return mUpdateEngine;
    }

    private Utils() {
        // this class is not supposed to be instantiated
    }

    public static File makeUpdateFolder(Context ctx) {
        File updateFolder = new File(ctx.getString(R.string.conf_updates_folder));
        if(updateFolder.exists()) {
            updateFolder.mkdirs();
        }
        return updateFolder;
    }

    public static void cancelNotification(Context context) {
        final NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(R.string.not_new_updates_found_title);
        nm.cancel(R.string.not_download_success);
    }

    public static String getDeviceType(Context ctx) {
        return getProp(ctx.getString(R.string.conf_device_property));
    }

    public static String getInstalledVersion(Context ctx) {
        return getProp(ctx.getString(R.string.conf_version_property));
    }

    public static int getIncremental(Context ctx) {
        String incrementalProp = getProp(ctx.getString(R.string.conf_version_incremental_property));
        if(incrementalProp == null) {
            return -1;
        }
        return Integer.parseInt(incrementalProp);
    }

    public static String getProp(String prop) {
        try {
            Process process = Runtime.getRuntime().exec("getprop " + prop);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                    process.getInputStream()));
            StringBuilder log = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                log.append(line);
            }
            return log.toString();
        } catch (IOException e) {
            // Runtime error
        }
        return null;
    }
    
    public static String getUserAgentString(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            return pi.packageName + "/" + pi.versionName;
        } catch (PackageManager.NameNotFoundException nnfe) {
            return null;
        }
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo != null && netInfo.isConnected()) {
            return true;
        }
        return false;
    }

    public static void scheduleUpdateService(Context context, int updateFrequency) {
        // Load the required settings from preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long lastCheck = prefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0);

        // Get the intent ready
        Intent i = new Intent(context, UpdateCheckService.class);
        i.setAction(UpdateCheckService.ACTION_CHECK);
        PendingIntent pi = PendingIntent.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        // Clear any old alarms and schedule the new alarm
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);

        if (updateFrequency != Constants.UPDATE_FREQ_NONE) {
            am.setRepeating(AlarmManager.RTC_WAKEUP, lastCheck + updateFrequency, updateFrequency, pi);
        }
    }

    public static void triggerUpdate(final Context context, String updateFileName) throws IOException {
        String updatePackagePath = makeUpdateFolder(context).getPath() + "/" + updateFileName;

        if(isABUpdate(new File(updatePackagePath))) {

            final ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(false);
            int paddingInPixels = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    12, context.getResources().getDisplayMetrics());

            progressBar.setPadding(paddingInPixels*2, paddingInPixels, paddingInPixels*2, 0);

            ((Activity)context)
            final AlertDialog progressDialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.installing_update)
                    .setView(progressBar)
                    .setCancelable(false)
                    .create();

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
            } catch (IOException | IllegalArgumentException e) {
                // todo show error
                return;
            }

            progressDialog.show();
            UpdateEngine updateEngine =getUpdateEngine(new android.os.UpdateEngineCallback() {

                @Override
                public void onStatusUpdate(int status, float percent) {
                    Log.v(LOG_TAG, String.format("ABUpdate Status : %d %f", status, percent));
                    switch (status) {
                        case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                        case UpdateEngine.UpdateStatusConstants.FINALIZING: {
                            progressBar.setProgress(Math.round(percent * 100));
                        }
                        break;

                        case UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT: {
                            progressDialog.dismiss();
                            new AlertDialog.Builder(context)
                                    .setTitle(R.string.installation_failed)
                                    .setMessage(R.string.failed_to_install_update)
                                    .setPositiveButton(context.getText(R.string.dialog_ok),
                                            new DialogInterface.OnClickListener() {

                                                @Override
                                                public void onClick(DialogInterface dialogInterface, int i) {
                                                    dialogInterface.dismiss();
                                                }
                                            })
                                    .show();

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
                            progressBar.setProgress(100);
                            progressDialog.setTitle(R.string.installing_update);
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {

                                @Override
                                public void run() {
                                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                                    pm.reboot(null);
                                }
                            }, 250);
                        }
                        break;

                        default: {
                            progressDialog.dismiss();
                            new AlertDialog.Builder(context)
                                    .setTitle(R.string.installation_failed)
                                    .setMessage(R.string.failed_to_install_update)
                                    .setPositiveButton(context.getText(R.string.dialog_ok),
                                            new DialogInterface.OnClickListener() {

                                                @Override
                                                public void onClick(DialogInterface dialogInterface, int i) {
                                                    dialogInterface.dismiss();
                                                }
                                            })
                                    .show();

                            mUpdateEngineCallback.setCallback(null);
                        }
                        break;
                    }
                }
            });

            new File(updatePackagePath).setReadable(true, false);
            updateEngine.applyPayload("file://" + updatePackagePath,
                    offset, 0, headerKeyValuePairs);
        }
        else {
            // Just in case /cache/recovery directory doesn't exist
            (new File("/cache/recovery/")).mkdirs();

            // Reboot into recovery and trigger the update
            android.os.RecoverySystem.installPackage(context, new File(updatePackagePath));
        }
    }

    private static void copy(File src, File dst) throws IOException {
        FileInputStream inStream = new FileInputStream(src);
        FileOutputStream outStream = new FileOutputStream(dst);
        FileChannel inChannel = inStream.getChannel();
        FileChannel outChannel = outStream.getChannel();
        inChannel.transferTo(0, inChannel.size(), outChannel);
        inStream.close();
        outStream.close();
    }

    private static boolean isABUpdate(File file) throws IOException {
        ZipFile zipFile = new ZipFile(file);
        boolean isAB = isABUpdate(zipFile);
        zipFile.close();
        return isAB;
    }

    public static boolean isABUpdate(ZipFile zipFile) {
        return zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null;
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(LOG_TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }

    public static String getUpdateType(Context ctx) {
        String updateType = "alpha";
        try {
            String releaseType = Utils.getProp(ctx.getString(R.string.conf_build_type_property));

            // Treat anything that is not SNAPSHOT as NIGHTLY
            if (!releaseType.isEmpty()) {
                updateType = releaseType;
            }
        } catch (RuntimeException ignored) {
        }

        return updateType;
    }

    public static String getProductName(Context ctx) {
        return Utils.getProp(ctx.getString(R.string.conf_product_name_property));
    }

    public static String getServerUrl(Context ctx) {
        return "https://staging.lunarwireless.com/updates/";
//        String serverFromProps = Utils.getProp(ctx.getString(R.string.conf_update_server_url_property));
//        if( serverFromProps == null ) {
//            return ctx.getString(R.string.conf_update_server_default_url);
//        }
//
//        return serverFromProps;
    }

    public static String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    public static String getStringFromFile (File file) throws Exception {
        FileInputStream fin = new FileInputStream(file);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }

    public static boolean hasLeanback(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
