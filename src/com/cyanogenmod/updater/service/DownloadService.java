/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater.service;

import android.app.DownloadManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.Log;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import com.cyanogenmod.updater.R;
import com.cyanogenmod.updater.UpdateApplication;
import com.cyanogenmod.updater.misc.Constants;
import com.cyanogenmod.updater.misc.UpdateInfo;
import com.cyanogenmod.updater.receiver.DownloadReceiver;
import com.cyanogenmod.updater.requests.UpdatesJsonObjectRequest;
import com.cyanogenmod.updater.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;

public class DownloadService extends IntentService
        implements Response.Listener<JSONObject>, Response.ErrorListener {
    private static final String TAG = DownloadService.class.getSimpleName();

    private static final String EXTRA_UPDATE_INFO = "update_info";

    private SharedPreferences mPrefs;
    private UpdateInfo mInfo = null;

    public static void start(Context context, UpdateInfo ui) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) ui);
        context.startService(intent);
    }

    public DownloadService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mInfo = intent.getParcelableExtra(EXTRA_UPDATE_INFO);

        if (mInfo == null) {
            Log.e(TAG, "Intent UpdateInfo extras were null");
            return;
        }

        try {
            getIncremental();
        } catch (IOException e) {
            downloadFullZip();
        }
    }

    private void getIncremental() throws IOException {
        String sourceIncremental = Utils.getIncremental(getBaseContext());
        Log.d(TAG, "Looking for incremental ota for source=" + sourceIncremental + ", target="
                + mInfo.getIncremental());

        UpdatesJsonObjectRequest request = buildRequest(sourceIncremental);
        ((UpdateApplication) getApplicationContext()).getQueue().add(request);
    }

    private UpdatesJsonObjectRequest buildRequest(String sourceIncremental) {
        URI requestUri = URI.create(Utils.getServerUrl(getBaseContext()) + buildRequestPath(sourceIncremental));
        UpdatesJsonObjectRequest request = new UpdatesJsonObjectRequest(requestUri.toASCIIString(),
                Utils.getUserAgentString(this), this, this);

        return request;
    }

    private String buildRequestPath(String sourceIncremental) {
        return String.format("/build/%s/%s/%s/%s", Utils.getUpdateType(getBaseContext()),
                Utils.getDeviceType(getBaseContext()),
                sourceIncremental, mInfo.getIncremental());
    }

    private UpdateInfo jsonToInfo(JSONObject obj) {
        try {
            if (obj == null || obj.has("error")) {
                return null;
            }

            if (!obj.has("result")) {
                return null;
            }

            JSONObject deltaUpdateResult = obj.getJSONObject("result");
            return new UpdateInfo.Builder(getBaseContext())
                    .setFileName(deltaUpdateResult.getString("filename"))
                    .setDownloadUrl(deltaUpdateResult.getString("url"))
                    .setMD5Sum(deltaUpdateResult.getString("md5sum"))
                    .setType(deltaUpdateResult.getString("channel"))
                    .setFromId(deltaUpdateResult.getString("fromId"))
                    .setId(deltaUpdateResult.getString("toId"))
                    .build();

        } catch (JSONException e) {
            Log.e(TAG, "JSONException", e);
            return null;
        }
    }

    private long enqueueDownload(String downloadUrl) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        String userAgent = Utils.getUserAgentString(this);
        if (userAgent != null) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.setTitle(getString(R.string.app_name));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);


        // TODO: this could/should be made configurable
        request.setAllowedOverMetered(true);

        final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        return dm.enqueue(request);
    }

    private void downloadIncremental(UpdateInfo incrementalUpdateInfo) {
        Log.v(TAG, "Downloading incremental zip: " + incrementalUpdateInfo.getDownloadUrl());
        // Build the name of the file to download, adding .partial at the end.  It will get
        // stripped off when the download completes
        String sourceIncremental = Utils.getIncremental(getBaseContext());
        String targetIncremental = mInfo.getIncremental();
        String fileName = "incremental-" + sourceIncremental + "-" + targetIncremental + ".zip";
        String incrementalFilePath = "file://" + getUpdateDirectory().getAbsolutePath() + "/" + fileName + ".partial";

        long downloadId = enqueueDownload(incrementalUpdateInfo.getDownloadUrl(), incrementalFilePath);

        // Store in shared preferences
        mPrefs.edit()
                .putLong(Constants.DOWNLOAD_ID, downloadId)
                .putString(Constants.DOWNLOAD_MD5, incrementalUpdateInfo.getMD5Sum())
                .putString(Constants.DOWNLOAD_INCREMENTAL_FOR, mInfo.getFileName())
                .apply();

        Utils.cancelNotification(this);

        Intent intent = new Intent(DownloadReceiver.ACTION_DOWNLOAD_STARTED);
        intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
        sendBroadcast(intent);
    }

    private void downloadFullZip() {
        Log.v(TAG, "Downloading full zip");

        long downloadId = enqueueDownload(mInfo.getDownloadUrl());

        // Store in shared preferences
        mPrefs.edit()
                .putLong(Constants.DOWNLOAD_ID, downloadId)
                .putString(Constants.DOWNLOAD_MD5, mInfo.getMD5Sum())
                .apply();

        Utils.cancelNotification(this);

        Intent intent = new Intent(DownloadReceiver.ACTION_DOWNLOAD_STARTED);
        intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
        sendBroadcast(intent);
    }

    private File getUpdateDirectory() {
        // If directory doesn't exist, create it
        return Utils.makeUpdateFolder(getApplicationContext());
    }

    @Override
    public void onErrorResponse(VolleyError error) {
        VolleyLog.e("Error: ", error.getMessage());
    }

    @Override
    public void onResponse(JSONObject response) {
        VolleyLog.v("Response:%n %s", response);

        UpdateInfo incrementalUpdateInfo = jsonToInfo(response);
        if (incrementalUpdateInfo == null) {
            downloadFullZip();
        } else {
            downloadIncremental(incrementalUpdateInfo);
        }
    }
}
