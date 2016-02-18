/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater.misc;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.cyanogenmod.updater.utils.Utils;

import java.io.File;
import java.io.Serializable;

public class UpdateInfo implements Parcelable, Serializable {
    private static final long serialVersionUID = 5499890003569313403L;

    public static final String CHANGELOG_EXTENSION = ".changelog.html";

    private String mUiName;
    private String mFileName;
    private String mType;
    private long mBuildDate;
    private String mDownloadUrl;
    private String mChangelogUrl;
    private String mMd5Sum;
    private String mId;
    private String mFromId;

    private Boolean mIsNewerThanInstalled;


    private UpdateInfo() {
        // Use the builder
    }

    private UpdateInfo(Parcel in) {
        readFromParcel(in);
    }

    public File getChangeLogFile(Context context) {
        return new File(context.getCacheDir(), mFileName + CHANGELOG_EXTENSION);
    }

    /**
     * Get name for UI display
     */
    public String getName() {
        return mUiName;
    }

    /**
     * Get file name
     */
    public String getFileName() {
        return mFileName;
    }

    /**
     * Set file name
     */
    public void setFileName(String fileName) {
        mFileName = fileName;
    }

    /**
     * Get build type
     */
    public String getType() {
        return mType;
    }

   /**
     * Get MD5
     */
    public String getMD5Sum() {
        return mMd5Sum;
    }

    /**
     * Get build date
     */
    public long getDate() {
        return mBuildDate;
    }

    /**
     * Get download location
     */
    public String getDownloadUrl() {
        return mDownloadUrl;
    }

    /**
     * Get changelog location
     */
    public String getChangelogUrl() {
        return mChangelogUrl;
    }

    /**
     * Get incremental version
     */
    public String getIncremental() {
        return mId;
    }

    /**
     * Get id of the previous build in an incremental update
     */
    public String getFromId() { return mFromId; }

    /**
     * Whether or not this is an incremental update
     */
    public boolean isIncremental() {
        return (mFromId != null && !mFromId.isEmpty() && mId != null && !mId.isEmpty());
    }

    public boolean isNewerThanInstalled() {
        if (mIsNewerThanInstalled != null) {
            return mIsNewerThanInstalled;
        }

        mIsNewerThanInstalled = (this.getIncremental() != Utils.getIncremental()) && mBuildDate > Utils.getInstalledBuildDate();

        return mIsNewerThanInstalled;
    }

    @Override
    public String toString() {
        return "UpdateInfo: " + mFileName;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof UpdateInfo)) {
            return false;
        }

        UpdateInfo ui = (UpdateInfo) o;
        return TextUtils.equals(mFileName, ui.mFileName)
                && mType.equals(ui.mType)
                && mBuildDate == ui.mBuildDate
                && TextUtils.equals(mDownloadUrl, ui.mDownloadUrl)
                && TextUtils.equals(mMd5Sum, ui.mMd5Sum)
                && TextUtils.equals(mId, ui.mId);
    }

    public static final Parcelable.Creator<UpdateInfo> CREATOR = new Parcelable.Creator<UpdateInfo>() {
        public UpdateInfo createFromParcel(Parcel in) {
            return new UpdateInfo(in);
        }

        public UpdateInfo[] newArray(int size) {
            return new UpdateInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mUiName);
        out.writeString(mFileName);
        out.writeString(mType);
        out.writeLong(mBuildDate);
        out.writeString(mDownloadUrl);
        out.writeString(mMd5Sum);
        out.writeString(mId);
    }

    private void readFromParcel(Parcel in) {
        mUiName = in.readString();
        mFileName = in.readString();
        mType = in.readString();
        mBuildDate = in.readLong();
        mDownloadUrl = in.readString();
        mMd5Sum = in.readString();
        mId = in.readString();
    }

    public static class Builder {
        private String mUiName;
        private String mFileName;
        private String mType;
        private int mApiLevel;
        private long mBuildDate;
        private String mDownloadUrl;
        private String mChangelogUrl;
        private String mMd5Sum;
        private String mId;
        private String mFromId;


        public Builder setName(String uiName) {
            mUiName = uiName;
            return this;
        }

        public Builder setFileName(String fileName) {
            initializeName(fileName);
            return this;
        }

        public Builder setType(String type) {
            mType = type;
            return this;
        }

        public Builder setBuildDate(long buildDate) {
            mBuildDate = buildDate;
            return this;
        }

        public Builder setDownloadUrl(String downloadUrl) {
            mDownloadUrl = downloadUrl;
            return this;
        }

        public Builder setChangelogUrl(String changelogUrl) {
            mChangelogUrl = changelogUrl;
            return this;
        }

        public Builder setMD5Sum(String md5Sum) {
            mMd5Sum = md5Sum;
            return this;
        }

        public Builder setId(String id) {
            mId = id;
            return this;
        }

        public Builder setFromId(String fromId) {
            this.mFromId = fromId;
            return this;
        }

        public UpdateInfo build() {
            UpdateInfo info = new UpdateInfo();
            info.mUiName = mUiName;
            info.mFileName = mFileName;
            info.mType = mType;
            info.mBuildDate = mBuildDate;
            info.mDownloadUrl = mDownloadUrl;
            info.mChangelogUrl = mChangelogUrl;
            info.mMd5Sum = mMd5Sum;
            info.mId = mId;
            info.mFromId = mFromId;
            return info;
        }


        private void initializeName(String fileName) {
            mFileName = fileName;
            mUiName = (!TextUtils.isEmpty(fileName)) ? fileName : null;
        }
    }
}
