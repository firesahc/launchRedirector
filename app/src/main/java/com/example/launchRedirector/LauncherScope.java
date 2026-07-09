package com.example.launchRedirector;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LauncherScope {

    public static final List<String> PACKAGES =
            Collections.unmodifiableList(Arrays.asList("com.miui.home"));

    private static final Set<String> PACKAGE_SET = new HashSet<>(PACKAGES);

    private LauncherScope() {}

    public static boolean contains(String packageName) {
        return PACKAGE_SET.contains(packageName);
    }

    public static List<String> getPackages() {
        return PACKAGES;
    }

    public static List<String> findScopedHomePackages(PackageManager packageManager) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);

        List<ResolveInfo> homeResolves = packageManager.queryIntentActivities(homeIntent, 0);
        Set<String> launcherPkgs = new HashSet<>();
        if (homeResolves != null) {
            for (ResolveInfo ri : homeResolves) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    launcherPkgs.add(ri.activityInfo.packageName);
                }
            }
        }

        List<String> targets = new ArrayList<>();
        for (String pkg : PACKAGES) {
            if (!TextUtils.isEmpty(pkg) && AppUtils.isValidPkg(pkg) && launcherPkgs.contains(pkg)) {
                targets.add(pkg);
            }
        }
        return targets;
    }
}
