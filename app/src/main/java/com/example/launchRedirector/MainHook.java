package com.example.launchRedirector;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String CONTENT_URI = "content://com.example.launchRedirector/config/";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam){
        if (!"com.miui.home".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
                Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class, int.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param){
                        Context context = (Context) param.args[0];
                        Intent intent = (Intent) param.args[4];

                        if (context == null || intent == null || intent.getComponent() == null) return;

                        String targetPkg = intent.getComponent().getPackageName();

                        // --- 核心逻辑开始 ---

                        // 1. 只有是桌面点击（ACTION_MAIN）时才考虑重定向
                        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;

                        // 2. 检查目标应用是否已经在运行（是否有后台任务栈）
                        if (isAppRunning(context, targetPkg)) {
                            // 应用已在后台，不做任何修改，直接返回，让系统执行默认的“恢复前台”操作
                            return;
                        }

                        // 3. 应用未运行，查询 Provider 获取重定向 URI
                        String redirectUri = getRedirect(context, targetPkg);

                        if (redirectUri != null && !redirectUri.isEmpty()) {
                            Intent newIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUri));
                            // 必须保留桌面启动的核心标志，否则可能导致任务栈混乱
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                            param.args[4] = newIntent;
                        }
                    }
                });
    }

    private boolean isAppRunning(Context context, String packageName) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        // 优化点：先查进程级。如果连进程都没有，说明彻底死了（冷启动），直接返回 false
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

        // 进程不存在，绝对是冷启动
        if (!hasProcess) return false;

        // 进程存在，再查任务栈（确保它有 UI 界面在后台，而不是仅仅有个后台服务在跑）
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