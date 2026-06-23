package com.example.launchRedirector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

public class MainActivity extends AppCompatActivity {
    private static final int SU_TIMEOUT_SEC = 5;

    private final Map<String, String> labelCache = new HashMap<>();
    private SharedPreferences prefs;
    private RecyclerView recyclerView;
    private RuleAdapter adapter;
    private volatile boolean restarting = false;
    private View emptyView;
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();

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

        prefs = getSharedPreferences(AppUtils.PREF_NAME, Context.MODE_PRIVATE);

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
        adapter = new RuleAdapter(this::showActionDialog, labelCache);
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

    @Override
    protected void onDestroy() {
        refreshExecutor.shutdown();
        super.onDestroy();
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
            adapter.submitList(null);
            return;
        }

        // Background label computation via single-thread executor (avoids stale-thread race)
        final Context appCtx = getApplicationContext();
        refreshExecutor.submit(() -> {
            Map<String, String> newCache = new HashMap<>();
            for (RuleEntry e : pending) {
                newCache.put(e.pkg, AppUtils.getAppLabel(appCtx, e.pkg));
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                labelCache.clear();
                labelCache.putAll(newCache);
                pending.sort(Comparator
                    .comparing((RuleEntry e) -> newCache.getOrDefault(e.pkg, e.pkg),
                               String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(e -> e.pkg, String.CASE_INSENSITIVE_ORDER));
                emptyView.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                adapter.submitList(pending);
            });
        });
    }

    private void showActionDialog(String pkg) {
        String label = AppUtils.getAppLabel(this, pkg);
        String rule = prefs.getString(pkg, "");

        new MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setMessage(String.format(getString(R.string.action_dialog_message), pkg, rule))
                .setPositiveButton(R.string.action_modify, (dialog, which) -> {
                    Intent intent = new Intent(this, EditActivity.class);
                    intent.putExtra("pkg", pkg);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.action_delete, (dialog, which) -> {
                    prefs.edit().remove(pkg).commit();
                    refreshList();
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    // ── Restart / Import / Export ──

    private void restartLauncher() {
        if (restarting) {
            Toast.makeText(this, R.string.restart_pending, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] scopePkgs = getResources().getStringArray(R.array.xposed_scope);
        if (scopePkgs == null || scopePkgs.length == 0) {
            Toast.makeText(this, R.string.restart_no_scope, Toast.LENGTH_SHORT).show();
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
            if (!TextUtils.isEmpty(pkg) && AppUtils.isValidPkg(pkg) && launcherPkgs.contains(pkg)) {
                targets.add(pkg);
            }
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.restart_no_launcher, Toast.LENGTH_SHORT).show();
            return;
        }

        restarting = true;

        // Offload su execution to background thread — avoids blocking UI
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su");
                try (DataOutputStream os = new DataOutputStream(p.getOutputStream())) {
                    for (String pkg : targets) {
                        os.writeBytes("am force-stop " + pkg + "\n");
                    }
                    os.writeBytes("exit\n");
                    os.flush();
                }

                if (!p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, R.string.restart_sent, Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, R.string.restart_need_root, Toast.LENGTH_SHORT).show();
                });
                XposedBridge.log(String.format(getString(R.string.log_su_failed), e.getMessage()));
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, R.string.restart_no_permission, Toast.LENGTH_SHORT).show();
                });
                XposedBridge.log(String.format(getString(R.string.log_su_permission), e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                restarting = false;
            }
        }).start();
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
                    throw new IllegalStateException(getString(R.string.export_open_failed));
                }
                os.write(json.toString(4).getBytes(StandardCharsets.UTF_8));
            }

            Toast.makeText(this, String.format(getString(R.string.export_success), allEntries.size()), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            XposedBridge.log(String.format(getString(R.string.log_export_failed), e.getMessage()));
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void readImportFile(Uri uri) {
        JSONObject json = null;
        try {
            StringBuilder sb = new StringBuilder();
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new IllegalStateException(getString(R.string.import_open_failed));
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
            XposedBridge.log(String.format(getString(R.string.log_import_failed), e.getMessage()));
            Toast.makeText(this, R.string.import_failed_format, Toast.LENGTH_LONG).show();
            return;
        }

        // Phase 0: validate rules format
        List<String> invalidEntries = new ArrayList<>();
        JSONObject validJson = new JSONObject();
        Iterator<String> allKeys = json.keys();
        while (allKeys.hasNext()) {
            String key = allKeys.next();
            if (!AppUtils.isValidPkg(key)) {
                invalidEntries.add(key);
                continue;
            }
            try {
                String value = json.getString(key);
                if (!AppUtils.isValidRuleValue(value)) {
                    invalidEntries.add(key + " → " + value);
                    continue;
                }
                validJson.put(key, value);
            } catch (JSONException e) {
                Log.w("launchRedirector", "Invalid value for key: " + key, e);
                invalidEntries.add(key);
            }
        }

        // Report invalid entries
        if (!invalidEntries.isEmpty()) {
            if (validJson.length() == 0) {
                Toast.makeText(this, R.string.import_all_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            int showCount = Math.min(invalidEntries.size(), 5);
            String invalidList = TextUtils.join("\n", invalidEntries.subList(0, showCount));
            String title;
            String message;
            if (invalidEntries.size() > 5) {
                title = String.format(getString(R.string.import_invalid_title), invalidEntries.size());
                message = String.format(getString(R.string.import_invalid_desc_long),
                    invalidList, invalidEntries.size() - 5);
            } else {
                title = String.format(getString(R.string.import_invalid_title), invalidEntries.size());
                message = String.format(getString(R.string.import_invalid_desc_short), invalidList);
            }
            new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.import_skip_and_continue, (d, w) -> proceedImport(validJson))
                .setNegativeButton(R.string.cancel, null)
                .show();
            return;
        }

        // Phase 1: detect conflicts
        proceedImport(validJson);
    }

    /** Continues import after validation passes. Extracted to avoid nesting. */
    private void proceedImport(JSONObject json) {
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
                } catch (JSONException e) {
                    Log.w("launchRedirector", "Conflict check failed for: " + key, e);
                }
            } else {
                hasChanges = true;
            }
        }
        if (!conflicts.isEmpty()) hasChanges = true;

        // Phase 2: confirm and execute
        final JSONObject finalJson = json;
        if (conflicts.isEmpty() && !hasChanges) {
            Toast.makeText(this, R.string.import_no_changes, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!conflicts.isEmpty()) {
            int showCount = Math.min(conflicts.size(), 5);
            String conflictList = TextUtils.join("\n", conflicts.subList(0, showCount));
            String title;
            String message;
            if (conflicts.size() > 5) {
                title = String.format(getString(R.string.import_conflict_title), conflicts.size());
                message = String.format(getString(R.string.import_conflict_desc),
                    conflictList, conflicts.size() - 5);
            } else {
                title = String.format(getString(R.string.import_conflict_title), conflicts.size());
                message = String.format(getString(R.string.import_conflict_desc_short), conflictList);
            }
            new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.import_confirm_overwrite, (d, w) -> doImport(finalJson))
                .setNegativeButton(R.string.cancel, null)
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
            } catch (JSONException e) {
                Log.w("launchRedirector", "Import failed for key: " + key, e);
            }
        }

        if (count > 0) {
            editor.commit();
        }
        refreshList();
        Toast.makeText(this, String.format(getString(R.string.import_success), count), Toast.LENGTH_SHORT).show();
    }

    // ── RecyclerView Adapter (static to avoid implicit Activity reference) ──

    private static final DiffUtil.ItemCallback<RuleEntry> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<RuleEntry>() {
                @Override
                public boolean areItemsTheSame(@NonNull RuleEntry oldItem, @NonNull RuleEntry newItem) {
                    return oldItem.pkg.equals(newItem.pkg);
                }

                @Override
                public boolean areContentsTheSame(@NonNull RuleEntry oldItem, @NonNull RuleEntry newItem) {
                    return oldItem.pkg.equals(newItem.pkg)
                            && oldItem.rule.equals(newItem.rule);
                }
            };

    private static final class RuleAdapter extends ListAdapter<RuleEntry, RuleAdapter.VH> {

        private final OnRuleActionListener listener;
        private final Map<String, String> labelCache;

        RuleAdapter(OnRuleActionListener listener, Map<String, String> labelCache) {
            super(DIFF_CALLBACK);
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
            RuleEntry entry = getItem(position);
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
