package com.example.launchRedirector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EditActivity extends Activity {

    public static final String EXTRA_TEST_LAUNCH = "launchRedirector_test_launch";
    public static final String EXTRA_TEST_TARGET_PKG = "launchRedirector_test_pkg";
    public static final String EXTRA_TEST_TARGET_URI = "launchRedirector_test_uri";

    private EditText etPkg;
    private EditText etUri;
    private String originalPkg;
    private PackageManager packageManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        packageManager = getPackageManager();
        etPkg = findViewById(R.id.edit_pkg);
        etUri = findViewById(R.id.edit_uri);
        Button btnPickPkg = findViewById(R.id.btn_pick_pkg);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnCancel = findViewById(R.id.btn_cancel);
        Button btnTest = findViewById(R.id.btn_test);

        originalPkg = getIntent().getStringExtra("pkg");
        if (originalPkg != null) {
            etPkg.setText(originalPkg);
            etUri.setText(getSharedPreferences("redirect_config", Context.MODE_PRIVATE).getString(originalPkg, ""));
        }

        btnPickPkg.setOnClickListener(v -> showInstalledAppPicker());
        btnSave.setOnClickListener(v -> saveRule());
        btnCancel.setOnClickListener(v -> finish());
        btnTest.setOnClickListener(v -> testRule());
    }

    private void showInstalledAppPicker() {
        List<AppEntry> apps = loadLaunchableApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可选择的已安装应用", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            items[i] = app.label + "  (" + app.pkg + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("选择本机安装软件")
                .setItems(items, (dialog, which) -> etPkg.setText(apps.get(which).pkg))
                .setNegativeButton("取消", null)
                .show();
    }

    private List<AppEntry> loadLaunchableApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolves = packageManager.queryIntentActivities(intent, 0);
        if (resolves == null) {
            return new ArrayList<>();
        }

        Map<String, AppEntry> dedup = new LinkedHashMap<>();
        for (ResolveInfo resolveInfo : resolves) {
            if (resolveInfo.activityInfo == null || resolveInfo.activityInfo.packageName == null) {
                continue;
            }
            String pkg = resolveInfo.activityInfo.packageName;
            CharSequence label = resolveInfo.loadLabel(packageManager);
            String labelText = TextUtils.isEmpty(label) ? pkg : label.toString();
            if (!dedup.containsKey(pkg)) {
                dedup.put(pkg, new AppEntry(pkg, labelText));
            }
        }

        List<AppEntry> apps = new ArrayList<>(dedup.values());
        apps.sort(Comparator
                .comparing((AppEntry e) -> e.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.pkg, String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    private void saveRule() {
        String pkg = etPkg.getText().toString().trim();
        String uri = etUri.getText().toString().trim();

        if (TextUtils.isEmpty(pkg)) {
            Toast.makeText(this, "请输入目标应用包名", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(uri)) {
            Toast.makeText(this, "请输入跳转地址或类名", Toast.LENGTH_SHORT).show();
            return;
        }

        android.content.SharedPreferences.Editor editor = getSharedPreferences("redirect_config", Context.MODE_PRIVATE).edit();
        if (!TextUtils.isEmpty(originalPkg) && !originalPkg.equals(pkg)) {
            editor.remove(originalPkg);
        }
        editor.putString(pkg, uri).apply();
        finish();
    }

    private void testRule() {
        String pkg = etPkg.getText().toString().trim();
        String uri = etUri.getText().toString().trim();

        if (TextUtils.isEmpty(pkg)) {
            Toast.makeText(this, "请先选择或输入包名", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(uri)) {
            Toast.makeText(this, "请先填写自定义链接或类名", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent launcherIntent = buildLauncherIntent(pkg);
        if (launcherIntent == null) {
            Toast.makeText(this, "未找到可启动入口，无法模拟桌面点击", Toast.LENGTH_LONG).show();
            return;
        }

        launcherIntent.putExtra(EXTRA_TEST_LAUNCH, true);
        launcherIntent.putExtra(EXTRA_TEST_TARGET_PKG, pkg);
        launcherIntent.putExtra(EXTRA_TEST_TARGET_URI, uri);

        try {
            startActivity(launcherIntent);
            Toast.makeText(this, "模拟桌面启动：" + getAppLabel(pkg), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "模拟测试失败", Toast.LENGTH_LONG).show();
        }
    }

    private Intent buildLauncherIntent(String pkg) {
        Intent launcherIntent = packageManager.getLaunchIntentForPackage(pkg);
        if (launcherIntent != null) {
            return launcherIntent;
        }

        Intent queryIntent = new Intent(Intent.ACTION_MAIN);
        queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        queryIntent.setPackage(pkg);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(queryIntent, 0);
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return null;
        }
        ResolveInfo resolveInfo = resolveInfos.get(0);
        if (resolveInfo.activityInfo == null) {
            return null;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
        return intent;
    }

    private String getAppLabel(String pkg) {
        try {
            CharSequence label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0));
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }

    private static final class AppEntry {
        final String pkg;
        final String label;

        AppEntry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }
}
