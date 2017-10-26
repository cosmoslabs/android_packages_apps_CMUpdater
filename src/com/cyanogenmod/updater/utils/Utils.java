/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater.utils;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.cyanogenmod.updater.R;
import com.cyanogenmod.updater.misc.Constants;
import com.cyanogenmod.updater.service.UpdateCheckService;

import java.io.*;
import java.lang.reflect.Method;

public class Utils {
    private Utils() {
        // this class is not supposed to be instantiated
    }

    public static File makeUpdateFolder(Context ctx) {
        return ctx.getDir(Utils.getUpdatesFolder(ctx), Context.MODE_PRIVATE);
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

    public static String getIncremental(Context ctx) {
        return getProp(ctx.getString(R.string.conf_version_incremental_property));
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

    /**
     * Method borrowed from OpenDelta. Using reflection voodoo instead calling
     * the hidden class directly, to dev/test outside of AOSP tree.
     * 
     * @author Jorrit "Chainfire" Jongma
     * @author The OmniROM Project
     */
    public static boolean setPermissions(String path, int mode, int uid, int gid) {
        try {
            Class<?> FileUtils = Utils.class.getClassLoader().loadClass("android.os.FileUtils");
            Method setPermissions = FileUtils.getDeclaredMethod("setPermissions", new Class[] {
                    String.class,
                    int.class,
                    int.class,
                    int.class
            });
            return ((Integer) setPermissions.invoke(
                    null,
                    new Object[] {
                            path,
                            Integer.valueOf(mode),
                            Integer.valueOf(uid),
                            Integer.valueOf(gid)
                    }) == 0);
        } catch (Exception e) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crash
            e.printStackTrace();
        }
        return false;
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

    public static void triggerUpdate(Context context, String updateFileName) throws IOException {
        String updatePackagePath = makeUpdateFolder(context).getPath() + "/" + updateFileName;

        // Reboot into recovery and trigger the update
        android.os.RecoverySystem.installPackage(context, new File(updatePackagePath));
    }

    public static String getUpdatesFolder(Context ctx) {
        return ctx.getString(R.string.conf_updates_folder);
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

    public static Long getBuildDateFromId(String id) {
        Long buildDate = -1L;
        try {
            String[] splitId = id.split("\\.");
            buildDate = Long.decode(splitId[1] + splitId[2]);
        }
        catch(Exception ex) {}

        return buildDate;
    }
}
