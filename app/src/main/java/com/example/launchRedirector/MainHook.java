package com.example.launchRedirector;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "launchRedirector";
    private static final Uri VERSION_URI = new Uri.Builder()
            .scheme("content")
            .authority(ConfigProvider.AUTHORITY)
            .appendPath(ConfigProvider.PATH_VERSION)
            .build();

    /** Test-launch extras — consumed by this hook, produced by EditActivity. */
    public static final String EXTRA_TEST_LAUNCH = "launchRedirector_test_launch";
    public static final String EXTRA_TEST_TARGET_PKG = "launchRedirector_test_pkg";
    public static final String EXTRA_TEST_TARGET_URI = "launchRedirector_test_uri";

    /** Cache redirect lookups in the launcher process to avoid repeated IPC. */
    private static final ConcurrentHashMap<String, RedirectRule> REDIRECT_CACHE =
            new ConcurrentHashMap<>();
    private static volatile long cachedVersion = -1L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!LauncherScope.contains(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                lpparam.classLoader,
                "execStartActivity",
                Context.class,
                IBinder.class,
                IBinder.class,
                Activity.class,
                Intent.class,
                int.class,
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            handleExecStartActivity(param);
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": hook error, falling back to original intent: "
                                    + e.getMessage());
                            // leave param.args[4] untouched → original intent proceeds
                        }
                    }
                });
    }

    private void handleExecStartActivity(MethodHookParam param) {
        Context context = (Context) param.args[0];
        Intent intent = (Intent) param.args[4];

        if (context == null || intent == null || intent.getComponent() == null) return;

        String targetPkg = intent.getComponent().getPackageName();
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        // Only intercept launcher icon taps, not arbitrary MAIN intents
        if (!intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return;

        boolean testLaunch = intent.getBooleanExtra(EXTRA_TEST_LAUNCH, false);
        RedirectRule rule = testLaunch
                ? RedirectRule.parse(intent.getStringExtra(EXTRA_TEST_TARGET_URI))
                : getRedirect(context, targetPkg);

        if (rule == null) {
            if (testLaunch) {
                XposedBridge.log(TAG + ": test launch blocked — no redirect rule for " + targetPkg);
                param.setResult(null);
            }
            return;
        }

        String redirectPkg = testLaunch
                ? intent.getStringExtra(EXTRA_TEST_TARGET_PKG)
                : targetPkg;
        if (TextUtils.isEmpty(redirectPkg)) {
            redirectPkg = targetPkg;
        }

        if (!testLaunch && isAppRunning(context, targetPkg)) {
            XposedBridge.log(TAG + ": " + targetPkg + " 已在运行，跳过重定向");
            return;
        }

        Intent newIntent = RedirectIntentFactory.create(redirectPkg, rule);
        // Forward any original extras (e.g. shortcut data) to the redirect target
        if (intent.getExtras() != null) {
            newIntent.putExtras(intent.getExtras());
            newIntent.removeExtra(EXTRA_TEST_LAUNCH);
            newIntent.removeExtra(EXTRA_TEST_TARGET_PKG);
            newIntent.removeExtra(EXTRA_TEST_TARGET_URI);
        }
        // Verify the redirect target is resolvable before applying
        PackageManager pm = context.getPackageManager();
        if (pm.resolveActivity(newIntent, 0) == null) {
            XposedBridge.log(TAG + ": " + targetPkg + " 重定向目标不可解析，回退: "
                    + rule.getRawValue());
            return;
        }
        param.args[4] = newIntent;
        XposedBridge.log(TAG + ": " + targetPkg + " → " + rule.getRawValue());
    }

    private boolean isAppRunning(Context context, String packageName) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        boolean hasProcess = false;
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo info : processes) {
                if (info.processName.equals(packageName)) {
                    hasProcess = true;
                    break;
                }
            }
        }

        if (!hasProcess) return false;

        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(50);
        if (tasks != null) {
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task.baseActivity != null && packageName.equals(task.baseActivity.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private RedirectRule getRedirect(Context context, String targetPkg) {
        boolean versionKnown = refreshCacheVersion(context);

        if (versionKnown) {
            RedirectRule cached = REDIRECT_CACHE.get(targetPkg);
            if (cached != null) return cached;
        } else {
            REDIRECT_CACHE.remove(targetPkg);
        }

        RedirectRule rule = null;
        try {
            Uri queryUri = new Uri.Builder()
                    .scheme("content")
                    .authority(ConfigProvider.AUTHORITY)
                    .appendPath(ConfigProvider.PATH_CONFIG)
                    .appendPath(targetPkg)
                    .build();
            try (Cursor cursor = context.getContentResolver().query(queryUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int ruleIndex = cursor.getColumnIndex(ConfigProvider.COLUMN_RULE);
                    int versionIndex = cursor.getColumnIndex(ConfigProvider.COLUMN_VERSION);
                    if (ruleIndex >= 0) {
                        rule = RedirectRule.parse(cursor.getString(ruleIndex));
                    }
                    if (versionIndex >= 0) {
                        updateCacheVersion(cursor.getLong(versionIndex));
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": " + targetPkg + " 规则查询出现错误 " + e.getMessage());
            return null;
        }

        if (rule != null) {
            REDIRECT_CACHE.put(targetPkg, rule);
        }
        return rule;
    }

    private boolean refreshCacheVersion(Context context) {
        try (Cursor cursor = context.getContentResolver().query(VERSION_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int versionIndex = cursor.getColumnIndex(ConfigProvider.COLUMN_VERSION);
                if (versionIndex >= 0) {
                    updateCacheVersion(cursor.getLong(versionIndex));
                    return true;
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 配置版本查询出现错误 " + e.getMessage());
        }
        return false;
    }

    private void updateCacheVersion(long version) {
        if (version != cachedVersion) {
            REDIRECT_CACHE.clear();
            cachedVersion = version;
        }
    }
}
