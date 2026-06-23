package com.example.launchRedirector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

public class MainActivity extends AppCompatActivity {
    private static final int SU_TIMEOUT_SEC = 5;

    private final List<RuleEntry> ruleEntries = new ArrayList<>();
    private final Map<String, String> labelCache = new HashMap<>();
    private SharedPreferences prefs;
    private RecyclerView recyclerView;
    private RuleAdapter adapter;
    private volatile boolean restarting = false;
    private View emptyView;

    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;

    /** Callback for adapter long-press actions (avoids non-static inner class leak). */
    interface OnRuleActionListener {
        void onRuleLongClick(String pkg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("redirect_config", Context.MODE_PRIVATE);

        // --- Activity Result API (replaces deprecated startActivityForResult) ---
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri != null) writeExportFile(uri);
                });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri != null) readImportFile(uri);
                });

        // Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main_overflow);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        // RecyclerView
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter(ruleEntries, this::showActionDialog, labelCache);
        recyclerView.setAdapter(adapter);

        // Empty state
        emptyView = findViewById(R.id.empty_view);

        // FAB
        findViewById(R.id.fab_add).setOnClickListener(v ->
                startActivity(new Intent(this, EditActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_restart) {
            restartLauncher();
            return true;
        } else if (id == R.id.menu_import) {
            importConfig();
            return true;
        } else if (id == R.id.menu_export) {
            exportConfig();
            return true;
        }
        return false;
    }

    private void refreshList() {
        ruleEntries.clear();
        Map<String, ?> allEntries = prefs.getAll();

        List<RuleEntry> pending = new ArrayList<>();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String pkg = entry.getKey();
            Object value = entry.getValue();
            if (TextUtils.isEmpty(pkg) || value == null) continue;
            String rule = String.valueOf(value);
            if (TextUtils.isEmpty(rule)) continue;
            pending.add(new RuleEntry(pkg, rule));
        }

        if (pending.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
            return;
        }

        // Background label computation, guarded against Activity destruction
        new Thread(() -> {
            Map<String, String> newCache = new HashMap<>();
            for (RuleEntry e : pending) {
                newCache.put(e.pkg, AppUtils.getAppLabel(MainActivity.this, e.pkg));
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                labelCache.clear();
                labelCache.putAll(newCache);
                ruleEntries.addAll(pending);
                ruleEntries.sort(Comparator
                    .comparing((RuleEntry e) -> newCache.getOrDefault(e.pkg, e.pkg),
                               String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(e -> e.pkg, String.CASE_INSENSITIVE_ORDER));
                emptyView.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void showActionDialog(String pkg) {
        String label = AppUtils.getAppLabel(this, pkg);
        String rule = prefs.getString(pkg, "");

        new MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setMessage("包名：" + pkg + "\n规则：" + rule)
                .setPositiveButton("修改", (dialog, which) -> {
                    Intent intent = new Intent(this, EditActivity.class);
                    intent.putExtra("pkg", pkg);
                    startActivity(intent);
                })
                .setNegativeButton("删除", (dialog, which) -> {
                    prefs.edit().remove(pkg).apply();
                    refreshList();
                })
                .setNeutralButton("取消", null)
                .show();
    }

    // ── Restart / Import / Export ──

    private void restartLauncher() {
        if (restarting) {
            Toast.makeText(this, "正在重启桌面，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] scopePkgs = getResources().getStringArray(R.array.xposed_scope);
        if (scopePkgs == null || scopePkgs.length == 0) {
            Toast.makeText(this, "未配置作用域，请在 LSPosed 中设置", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homeResolves = getPackageManager().queryIntentActivities(homeIntent, 0);
        Set<String> launcherPkgs = new HashSet<>();
        if (homeResolves != null) {
            for (ResolveInfo ri : homeResolves) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    launcherPkgs.add(ri.activityInfo.packageName);
                }
            }
        }

        List<String> targets = new ArrayList<>();
        for (String pkg : scopePkgs) {
            if (!TextUtils.isEmpty(pkg) && launcherPkgs.contains(pkg)) {
                targets.add(pkg);
            }
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, "作用域中未检测到桌面应用", Toast.LENGTH_SHORT).show();
            return;
        }

        restarting = true;

        try {
            Process p = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(p.getOutputStream())) {
                for (String pkg : targets) {
                    os.writeBytes("am force-stop " + pkg + "\n");
                    os.writeBytes("killall " + pkg + "\n");
                }
                os.writeBytes("exit\n");
                os.flush();
            }

            new Thread(() -> {
                try {
                    if (!p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    p.destroyForcibly();
                    Thread.currentThread().interrupt();
                } finally {
                    restarting = false;
                }
            }).start();

            Toast.makeText(this, "已发送重启桌面命令", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            restarting = false;
            Toast.makeText(this, "需要 Root 权限以重启桌面", Toast.LENGTH_SHORT).show();
            XposedBridge.log("launchRedirector: su 执行失败 " + e.getMessage());
        } catch (SecurityException e) {
            restarting = false;
            Toast.makeText(this, "无权限执行 Root 命令", Toast.LENGTH_SHORT).show();
            XposedBridge.log("launchRedirector: su 权限不足 " + e.getMessage());
        }
    }

    private void exportConfig() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "redirect_rules.json");
        exportLauncher.launch(intent);
    }

    private void importConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        importLauncher.launch(intent);
    }

    private void writeExportFile(Uri uri) {
        try {
            Map<String, ?> allEntries = prefs.getAll();
            JSONObject json = new JSONObject(allEntries);

            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IllegalStateException("无法打开导出文件");
                }
                os.write(json.toString(4).getBytes(StandardCharsets.UTF_8));
            }

            Toast.makeText(this, "成功导出 " + allEntries.size() + " 条规则", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            XposedBridge.log("launchRedirector: 导出失败 " + e.getMessage());
            Toast.makeText(this, "导出失败，已输出错误日志", Toast.LENGTH_LONG).show();
        }
    }

    private void readImportFile(Uri uri) {
        JSONObject json = null;
        try {
            StringBuilder sb = new StringBuilder();
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new IllegalStateException("无法打开导入文件");
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }
            json = new JSONObject(sb.toString());
        } catch (Exception e) {
            XposedBridge.log("launchRedirector: 导入失败 " + e.getMessage());
            Toast.makeText(this, "导入失败，文件格式错误", Toast.LENGTH_LONG).show();
            return;
        }

        // Phase 1: detect conflicts
        List<String> conflicts = new ArrayList<>();
        boolean hasChanges = false;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String existingValue = prefs.getString(key, null);
            if (existingValue != null) {
                try {
                    if (!existingValue.equals(json.getString(key))) {
                        conflicts.add(key);
                    }
                } catch (JSONException ignored) {}
            } else {
                hasChanges = true;
            }
        }
        if (!conflicts.isEmpty()) hasChanges = true;

        // Phase 2: confirm and execute
        final JSONObject finalJson = json;
        if (conflicts.isEmpty() && !hasChanges) {
            Toast.makeText(this, "规则无变化，无需导入", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!conflicts.isEmpty()) {
            int showCount = Math.min(conflicts.size(), 5);
            String conflictList = TextUtils.join("\n", conflicts.subList(0, showCount));
            if (conflicts.size() > 5) {
                conflictList += "\n…及其他 " + (conflicts.size() - 5) + " 条";
            }
            new MaterialAlertDialogBuilder(this)
                .setTitle("规则冲突（" + conflicts.size() + " 条）")
                .setMessage("以下规则将被覆盖：\n" + conflictList)
                .setPositiveButton("确认覆盖", (d, w) -> doImport(finalJson))
                .setNegativeButton("取消", null)
                .show();
        } else {
            doImport(finalJson);
        }
    }

    private void doImport(JSONObject json) {
        if (isFinishing() || isDestroyed()) return;

        SharedPreferences.Editor editor = prefs.edit();
        Iterator<String> keys = json.keys();
        int count = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                String newValue = json.getString(key);
                String existingValue = prefs.getString(key, null);
                if (!newValue.equals(existingValue)) {
                    editor.putString(key, newValue);
                    count++;
                }
            } catch (JSONException ignored) {}
        }

        if (count > 0) {
            editor.apply();
        }
        refreshList();
        Toast.makeText(this, "成功导入 " + count + " 条规则", Toast.LENGTH_SHORT).show();
    }

    // ── RecyclerView Adapter (static to avoid implicit Activity reference) ──

    private static final class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.VH> {

        private final List<RuleEntry> ruleEntries;
        private final OnRuleActionListener listener;
        private final Map<String, String> labelCache;

        RuleAdapter(List<RuleEntry> ruleEntries, OnRuleActionListener listener, Map<String, String> labelCache) {
            this.ruleEntries = ruleEntries;
            this.listener = listener;
            this.labelCache = labelCache;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_rule_entry, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RuleEntry entry = ruleEntries.get(position);
            String label = labelCache.getOrDefault(entry.pkg, entry.pkg);
            holder.tvIcon.setText(AppUtils.getFirstChar(label));
            holder.tvTitle.setText(label);
            holder.tvPkg.setText(entry.pkg);
            holder.tvRule.setText(entry.rule);

            holder.itemView.setOnLongClickListener(v -> {
                listener.onRuleLongClick(entry.pkg);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return ruleEntries.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView tvIcon;
            final TextView tvTitle;
            final TextView tvPkg;
            final TextView tvRule;

            VH(@NonNull View root) {
                super(root);
                tvIcon = root.findViewById(R.id.tv_item_icon);
                tvTitle = root.findViewById(R.id.tv_item_title);
                tvPkg = root.findViewById(R.id.tv_item_pkg);
                tvRule = root.findViewById(R.id.tv_item_rule);
            }
        }
    }

    // ── Data class ──

    private static final class RuleEntry {
        final String pkg;
        final String rule;

        RuleEntry(String pkg, String rule) {
            this.pkg = pkg;
            this.rule = rule;
        }
    }
}
