package com.example.launchRedirector;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;

/**
 * Shared utility methods used across activities.
 */
public final class AppUtils {

    private AppUtils() {}

    public static String getAppLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0));
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // App not installed or uninstalled - use package name as label
        }
        return pkg;
    }

    /**
     * Returns the first character of the label, or "?" if empty.
     */
    public static String getFirstChar(String label) {
        if (TextUtils.isEmpty(label)) return "?";
        return label.substring(0, 1);
    }
}
