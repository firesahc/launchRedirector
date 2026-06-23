package com.example.launchRedirector;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import java.util.regex.Pattern;

/**
 * Shared utility methods used across activities.
 */
public final class AppUtils {

    private AppUtils() {}

    /** SharedPreferences file name for redirect rules. */
    public static final String PREF_NAME = "redirect_config";

    // ── Validation patterns ──

    /** Valid Android package name: at least one dot, segments start with letter/underscore. */
    private static final Pattern PKG_PATTERN =
            Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+");

    public static boolean isValidPkg(String pkg) {
        return pkg != null && PKG_PATTERN.matcher(pkg).matches();
    }

    /** Rule value must be a URI (contains ://), full/relative class name, or flat class name. */
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)+"
                    + "|\\.[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*"
                    + "|[a-zA-Z_][a-zA-Z0-9_]*");

    public static boolean isValidRuleValue(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.contains("://")) return true;
        return CLASS_NAME_PATTERN.matcher(value).matches();
    }

    // ── Label helpers ──

    public static String getAppLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0));
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException e) {
            // App not installed — use package name as label
        }
        return pkg;
    }

    /**
     * Returns the first character of the label, or "?" if empty.
     * Safe for supplementary Unicode characters (emoji, etc.).
     */
    public static String getFirstChar(String label) {
        if (TextUtils.isEmpty(label)) return "?";
        int cp = label.codePointAt(0);
        return new String(Character.toChars(cp));
    }
}
