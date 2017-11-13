package android.os;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;


public class SystemProperties {
    public static String get(@NonNull String key) { return ""; }
    public static String get(@NonNull String key, @Nullable String def) { return def; }

    public static long getLong(String propBuildDate, long i) { return i; }
}
