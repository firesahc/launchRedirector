package com.example.launchRedirector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.app.ActivityManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CONTENT_URI = "content://com.example.launchRedirector/config/";
    private static final String COLUMN_URI = "uri";
    private static final String NO_REDIRECT = "__NO_REDIRECT__";

    private static final ConcurrentHashMap<String, String> REDIRECT_CACHE = new ConcurrentHashMap<>();

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

                        if (context == null || intent == null) {
                            return;
                        }

                        // 只处理桌面图标入口，避免普通的 MAIN 启动或应用内部跳转被误改。
                        if (!Intent.ACTION_MAIN.equals(intent.getAction()) || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                            return;
                        }

                        if (intent.getComponent() == null) {
                            return;
                        }

                        String targetPkg = intent.getComponent().getPackageName();
                        if (targetPkg == null || targetPkg.isEmpty()) {
                            return;
                        }

                        // 只有“冷启动”才重定向：目标包的进程完全不存在时才允许修改 intent。
                        // 如果进程仍在后台，保持系统原生的回到现有任务逻辑。
                        if (isPackageProcessAlive(context, targetPkg)) {
                            return;
                        }

                        String redirect = resolveRedirect(context, targetPkg);
                        if (redirect == null || redirect.isEmpty()) {
                            return;
                        }

                        Intent newIntent = buildRedirectIntent(targetPkg, redirect, intent);
                        if (newIntent != null) {
                            param.args[4] = newIntent;
                        }
                    }
                }
        );
    }

    private Intent buildRedirectIntent(String targetPkg, String redirect, Intent originalIntent) {
        try {
            Intent newIntent;
            if (redirect.contains("://")) {
                newIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirect));
            } else {
                String className = redirect.startsWith(".") ? targetPkg + redirect : redirect;
                newIntent = new Intent(Intent.ACTION_MAIN);
                newIntent.setClassName(targetPkg, className);
            }

            if (originalIntent.getExtras() != null) {
                newIntent.putExtras(originalIntent.getExtras());
            }

            newIntent.addFlags(originalIntent.getFlags());
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return newIntent;
        } catch (Throwable t) {
            XposedBridge.log("launchRedirector: 构建跳转 Intent 失败: " + t.getMessage());
            return null;
        }
    }

    private boolean isPackageProcessAlive(Context context, String packageName) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }

        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null || processes.isEmpty()) {
            return false;
        }

        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (info == null || info.processName == null) {
                continue;
            }
            String processName = info.processName;
            if (processName.equals(packageName) || processName.startsWith(packageName + ":")) {
                return true;
            }
        }
        return false;
    }

    private String resolveRedirect(Context context, String targetPkg) {
        String cached = REDIRECT_CACHE.get(targetPkg);
        if (cached != null) {
            return NO_REDIRECT.equals(cached) ? null : cached;
        }

        String redirectUri = null;
        Cursor cursor = null;
        try {
            Uri queryUri = Uri.parse(CONTENT_URI + targetPkg);
            cursor = context.getContentResolver().query(queryUri, new String[]{COLUMN_URI}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(COLUMN_URI);
                if (columnIndex >= 0) {
                    redirectUri = cursor.getString(columnIndex);
                } else {
                    redirectUri = cursor.getString(0);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("launchRedirector: " + targetPkg + " 规则查询失败: " + t.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        REDIRECT_CACHE.put(targetPkg, redirectUri == null || redirectUri.isEmpty() ? NO_REDIRECT : redirectUri);
        return redirectUri;
    }
}
