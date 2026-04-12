package com.example.launchRedirector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // 必须与 AndroidManifest.xml 中的 authorities 保持一致！
    private static final String CONTENT_URI = "content://com.example.launchRedirector/config/";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.miui.home".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
                Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class, int.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 第一个参数是 Context
                        Context context = (Context) param.args[0];
                        // 第五个参数是 Intent
                        Intent intent = (Intent) param.args[4];

                        if (context == null || intent == null || intent.getComponent() == null) return;

                        String targetPkg = intent.getComponent().getPackageName();
                        String redirectUri = null;

                        try {
                            // 发起跨进程查询，询问模块应用：“这个包名有规则吗？”
                            Uri queryUri = Uri.parse(CONTENT_URI + targetPkg);
                            Cursor cursor = context.getContentResolver().query(queryUri, null, null, null, null);

                            if (cursor != null) {
                                if (cursor.moveToFirst()) {
                                    redirectUri = cursor.getString(0); // 拿到了 URI！
                                }
                                cursor.close();
                            }
                        } catch (Exception e) {
                            XposedBridge.log("读取配置失败: " + e.getMessage());
                        }

                        // 如果读到了重定向规则，并且桌面确实是在主启动该应用
                        if (redirectUri != null && !redirectUri.isEmpty() && Intent.ACTION_MAIN.equals(intent.getAction())) {
                            XposedBridge.log("成功拦截并重定向: " + targetPkg + " -> " + redirectUri);

                            Intent newIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUri));
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                            // 替换原始 Intent
                            param.args[4] = newIntent;
                        }
                    }
                });
    }
}