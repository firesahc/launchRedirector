package com.example.launch_redirector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private XSharedPreferences prefs;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.miui.home")) return;

        prefs = new XSharedPreferences("com.launchRedirector.pro", "redirect_config");

        XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
                Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class, int.class, Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[4];
                        if (intent == null || intent.getComponent() == null) return;

                        String targetPkg = intent.getComponent().getPackageName();
                        prefs.reload();
                        String redirectUri = prefs.getString(targetPkg, null);

                        if (redirectUri != null && Intent.ACTION_MAIN.equals(intent.getAction())) {
                            Intent newIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUri));
                            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            param.args[4] = newIntent;
                        }
                    }
                });
    }
}