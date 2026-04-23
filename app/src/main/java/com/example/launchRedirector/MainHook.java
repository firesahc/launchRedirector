package com.example.launchRedirector;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CONTENT_URI = "content://com.example.launchRedirector/config/";
    private static final String EXTRA_TEST_LAUNCH = EditActivity.EXTRA_TEST_LAUNCH;
    private static final String EXTRA_TEST_TARGET_PKG = EditActivity.EXTRA_TEST_TARGET_PKG;
    private static final String EXTRA_TEST_TARGET_URI = EditActivity.EXTRA_TEST_TARGET_URI;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
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
                        Context context = (Context) param.args[0];
                        Intent intent = (Intent) param.args[4];

                        if (context == null || intent == null || intent.getComponent() == null) return;

                        String targetPkg = intent.getComponent().getPackageName();
                        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;

                        boolean testLaunch = intent.getBooleanExtra(EXTRA_TEST_LAUNCH, false);
                        String redirectUri = testLaunch
                                ? intent.getStringExtra(EXTRA_TEST_TARGET_URI)
                                : getRedirect(context, targetPkg);

                        if (TextUtils.isEmpty(redirectUri)) {
                            if (testLaunch) {
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
                            return;
                        }

                        Intent newIntent;
                        if (redirectUri.contains("://")) {
                            newIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUri));
                        } else {
                            newIntent = new Intent();
                            String className = redirectUri.startsWith(".") ? redirectPkg + redirectUri : redirectUri;
                            newIntent.setClassName(redirectPkg, className);
                            newIntent.setAction(Intent.ACTION_MAIN);
                        }

                        newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        newIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        param.args[4] = newIntent;
                    }
                });
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

    private String getRedirect(Context context, String targetPkg) {
        String redirectUri = null;
        try {
            Uri queryUri = Uri.parse(CONTENT_URI + targetPkg);
            Cursor cursor = context.getContentResolver().query(queryUri, null, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    redirectUri = cursor.getString(0);
                }
                cursor.close();
            }
        } catch (Exception e) {
            XposedBridge.log("launchRedirector: " + targetPkg + " 规则查询出现错误 " + e.getMessage());
        }
        return redirectUri;
    }
}
