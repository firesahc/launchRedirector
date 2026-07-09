package com.example.launchRedirector;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

public class EditActivity extends AppCompatActivity {

    private static final String TAG = "launchRedirector";

    /** Intent extra key for the target package name. */
    public static final String EXTRA_PKG = "pkg";

    private TextInputEditText etPkg;
    private TextInputEditText etUri;
    private String originalPkg;
    private PackageManager packageManager;
    private RuleRepository repository;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        packageManager = getPackageManager();
        repository = new RuleRepository(this);
        etPkg = findViewById(R.id.edit_pkg);
        etUri = findViewById(R.id.edit_uri);
        MaterialButton btnPickPkg = findViewById(R.id.btn_pick_pkg);
        MaterialButton btnSave = findViewById(R.id.btn_save);
        MaterialButton btnCancel = findViewById(R.id.btn_cancel);
        MaterialButton btnTest = findViewById(R.id.btn_test);

        // Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        originalPkg = getIntent().getStringExtra(EXTRA_PKG);
        if (originalPkg != null) {
            toolbar.setTitle(R.string.title_edit_rule);
            etPkg.setText(originalPkg);
            etUri.setText(repository.getRawRule(originalPkg));
        } else {
            toolbar.setTitle(R.string.title_add_rule);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        btnPickPkg.setOnClickListener(v -> showInstalledAppPicker());
        btnSave.setOnClickListener(v -> saveRule());
        btnCancel.setOnClickListener(v -> finish());
        btnTest.setOnClickListener(v -> testRule());
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdown();
        super.onDestroy();
    }

    private void showInstalledAppPicker() {
        ioExecutor.submit(() -> {
            List<AppEntry> apps = loadLaunchableApps();
            runOnUiThread(() -> showInstalledAppPicker(apps));
        });
    }

    private void showInstalledAppPicker(List<AppEntry> apps) {
        if (isFinishing() || isDestroyed()) return;
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.edit_no_launchable_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            items[i] = app.label + "  (" + app.pkg + ")";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_pick_app_title)
                .setItems(items, (dialog, which) -> etPkg.setText(apps.get(which).pkg))
                .setNegativeButton(R.string.cancel, null)
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
            Toast.makeText(this, R.string.edit_pkg_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AppUtils.isValidPkg(pkg)) {
            Toast.makeText(this, R.string.edit_pkg_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(uri)) {
            Toast.makeText(this, R.string.edit_uri_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        RedirectRule rule = RedirectRule.parse(uri);
        if (rule == null) {
            Toast.makeText(this, R.string.edit_uri_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        ioExecutor.submit(() -> {
            boolean saved = repository.saveRule(originalPkg, pkg, rule);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!saved) {
                    XposedBridge.log(TAG + ": commit() 返回 false，规则可能未保存 — " + pkg);
                    Toast.makeText(this, R.string.edit_save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                finish();
            });
        });
    }

    private void testRule() {
        String pkg = etPkg.getText().toString().trim();
        String uri = etUri.getText().toString().trim();

        if (TextUtils.isEmpty(pkg)) {
            Toast.makeText(this, R.string.edit_test_no_pkg, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AppUtils.isValidPkg(pkg)) {
            Toast.makeText(this, R.string.edit_pkg_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(uri)) {
            Toast.makeText(this, R.string.edit_test_no_uri, Toast.LENGTH_SHORT).show();
            return;
        }
        RedirectRule rule = RedirectRule.parse(uri);
        if (rule == null) {
            Toast.makeText(this, R.string.edit_uri_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent redirectIntent = RedirectIntentFactory.create(pkg, rule);
        if (packageManager.resolveActivity(redirectIntent, 0) == null) {
            Toast.makeText(this, R.string.edit_test_no_launcher_entry, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            startActivity(redirectIntent);
            Toast.makeText(this, R.string.edit_test_executing, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 模拟测试启动失败 — " + pkg + " — " + e.getMessage());
            Toast.makeText(this, R.string.edit_test_failed, Toast.LENGTH_LONG).show();
        }
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
