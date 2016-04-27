/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater.misc;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import com.android.volley.toolbox.RequestFuture;
import com.cyanogenmod.updater.R;
import com.cyanogenmod.updater.UpdateApplication;
import com.cyanogenmod.updater.requests.ChangeLogRequest;
import com.cyanogenmod.updater.utils.Utils;

import java.io.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FetchChangeLogTask extends AsyncTask<UpdateInfo, Void, Void>
        implements DialogInterface.OnDismissListener {
    private static final String TAG = "FetchChangeLogTask";

    private Context mContext;
    private UpdateInfo mInfo;
    private TextView mChangeLogView;
    private View mProgressContainer;
    private AlertDialog mAlertDialog;

    public FetchChangeLogTask(Context context) {
        mContext = context;
    }

    @Override
    protected Void doInBackground(UpdateInfo... infos) {
        mInfo = infos[0];

        if (mInfo != null) {
            File changeLog = mInfo.getChangeLogFile(mContext);
            if( changeLog.exists() )    changeLog.delete();

            if (!changeLog.exists()) {
                fetchChangeLog(mInfo);
            }
        }
        return null;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        final View view = inflater.inflate(R.layout.change_log_dialog, null);
        mProgressContainer = view.findViewById(R.id.progress);
        mChangeLogView = (TextView) view.findViewById(R.id.changelog);

        // Prepare the dialog box
        mAlertDialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.changelog_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.dialog_close, null)
                .create();
        mAlertDialog.setOnDismissListener(this);
        mAlertDialog.show();
    }

    @Override
    protected void onPostExecute(Void aVoid) {
        super.onPostExecute(aVoid);
        File changeLog = mInfo.getChangeLogFile(mContext);

        if (changeLog.length() == 0) {
            // Change log is empty
            Toast.makeText(mContext, R.string.no_changelog_alert, Toast.LENGTH_SHORT).show();
        } else {
            try {
                mChangeLogView.setText(Html.fromHtml(Utils.getStringFromFile(changeLog)));
                mProgressContainer.setVisibility(View.GONE);
                ((ScrollView)mChangeLogView.getParent()).setVisibility(View.VISIBLE);
            }
            catch(Exception e) {
                Toast.makeText(mContext, R.string.no_changelog_alert, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void fetchChangeLog(final UpdateInfo info) {
        Log.d(TAG, "Getting change log for " + info + ", url " + info.getChangelogUrl());

        final Response.ErrorListener errorListener = new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                VolleyLog.e("Error: ", error.getMessage());
                if (mAlertDialog != null && mAlertDialog.isShowing()) {
                    mAlertDialog.dismiss();
                    Toast.makeText(mContext, R.string.no_changelog_alert,
                            Toast.LENGTH_SHORT).show();
                }
            }
        };
        // We need to make a blocking request here
        RequestFuture<String> future = RequestFuture.newFuture();
        ChangeLogRequest request = new ChangeLogRequest(Request.Method.GET, info.getChangelogUrl(),
                Utils.getUserAgentString(mContext), future, errorListener);
        request.setTag(TAG);

        ((UpdateApplication) mContext.getApplicationContext()).getQueue().add(request);
        Writer writer = null;
        try {
            String response = future.get(5000, TimeUnit.MILLISECONDS);
            writer = new BufferedWriter(
                    new FileWriter(info.getChangeLogFile(mContext)));

            writer.write(response);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            if( !request.isCanceled() ) {
                request.cancel();
            }
            if( writer != null ) {
                try {
                    writer.close();
                } catch(Exception e) {}
            }
        }
    }

    @Override
    public void onDismiss(DialogInterface dialogInterface) {
        // Cancel all pending requests
        ((UpdateApplication) mContext.getApplicationContext()).getQueue().cancelAll(TAG);
        // Clean up
        mChangeLogView = null;
        mAlertDialog = null;
    }
}
