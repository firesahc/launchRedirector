package com.example.launchRedirector;

import android.content.Context;
import android.content.Intent;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "launchRedirector";
    private static final int SU_TIMEOUT_SEC = 5;

    private final Map<String, String> labelCache = new HashMap<>();
    private RuleRepository repository;
    private RecyclerView recyclerView;
    private RuleAdapter adapter;
    private volatile boolean restarting = false;
    private View emptyView;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

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

        repository = new RuleRepository(this);

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
        ioExecutor.shutdown();
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
        final Context appCtx = getApplicationContext();
        ioExecutor.submit(() -> {
            List<RuleEntry> pending = repository.getAllRules();
            Map<String, String> newCache = new HashMap<>();
            for (RuleEntry e : pending) {
                newCache.put(e.pkg, AppUtils.getAppLabel(appCtx, e.pkg));
            }
            pending.sort(Comparator
                    .comparing((RuleEntry e) -> newCache.getOrDefault(e.pkg, e.pkg),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(e -> e.pkg, String.CASE_INSENSITIVE_ORDER));

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (pending.isEmpty()) {
                    emptyView.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                    adapter.submitList(new ArrayList<>());
                    return;
                }

                labelCache.clear();
                labelCache.putAll(newCache);
                emptyView.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                adapter.submitList(pending);
            });
        });
    }

    private void showActionDialog(String pkg) {
        String label = AppUtils.getAppLabel(this, pkg);
        String rule = repository.getRawRule(pkg);

        new MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setMessage(String.format(getString(R.string.action_dialog_message), pkg, rule))
                .setPositiveButton(R.string.action_modify, (dialog, which) -> {
                    Intent intent = new Intent(this, EditActivity.class);
                    intent.putExtra(EditActivity.EXTRA_PKG, pkg);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.action_delete, (dialog, which) -> {
                    ioExecutor.submit(() -> {
                        boolean removed = repository.removeRule(pkg);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (!removed) {
                                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show();
                            }
                            refreshList();
                        });
                    });
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

        if (LauncherScope.getPackages().isEmpty()) {
            Toast.makeText(this, R.string.restart_no_scope, Toast.LENGTH_SHORT).show();
            return;
        }

        restarting = true;
        final Context appCtx = getApplicationContext();
        ioExecutor.submit(() -> {
            List<String> targets = LauncherScope.findScopedHomePackages(appCtx.getPackageManager());
            if (targets.isEmpty()) {
                runOnUiThread(() -> {
                    restarting = false;
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.restart_no_launcher, Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            LauncherRestarter.Result result = LauncherRestarter.forceStop(targets, SU_TIMEOUT_SEC);
            runOnUiThread(() -> {
                restarting = false;
                if (isFinishing() || isDestroyed()) return;

                switch (result.status) {
                    case SENT:
                        Toast.makeText(this, R.string.restart_sent, Toast.LENGTH_SHORT).show();
                        break;
                    case NEED_ROOT:
                        Toast.makeText(this, R.string.restart_need_root, Toast.LENGTH_SHORT).show();
                        XposedBridge.log(String.format(appCtx.getString(R.string.log_su_failed),
                                result.message));
                        break;
                    case NO_PERMISSION:
                        Toast.makeText(this, R.string.restart_no_permission, Toast.LENGTH_SHORT).show();
                        XposedBridge.log(String.format(appCtx.getString(R.string.log_su_permission),
                                result.message));
                        break;
                    case TIMEOUT:
                        Toast.makeText(this, R.string.restart_timeout, Toast.LENGTH_SHORT).show();
                        XposedBridge.log(TAG + ": su 命令超时 (" + SU_TIMEOUT_SEC + "s)，已强制终止");
                        break;
                    case INTERRUPTED:
                        Toast.makeText(this, R.string.restart_interrupted, Toast.LENGTH_SHORT).show();
                        XposedBridge.log(TAG + ": su 线程被中断");
                        break;
                }
            });
        });
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
        final Context appCtx = getApplicationContext();
        ioExecutor.submit(() -> {
            try {
                List<RuleEntry> rules = repository.getAllRules();
                String json = RuleImportExport.toJson(rules);

                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        throw new IllegalStateException(appCtx.getString(R.string.export_open_failed));
                    }
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            String.format(getString(R.string.export_success), rules.size()),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                XposedBridge.log(String.format(appCtx.getString(R.string.log_export_failed),
                        e.getMessage()));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void readImportFile(Uri uri) {
        final Context appCtx = getApplicationContext();
        ioExecutor.submit(() -> {
            try {
                String content = readText(uri, appCtx);
                RuleImportExport.ImportPlan plan = RuleImportExport.parseImportPlan(
                        content, repository.getRuleMap());
                runOnUiThread(() -> handleImportPlan(plan));
            } catch (Exception e) {
                XposedBridge.log(String.format(appCtx.getString(R.string.log_import_failed),
                        e.getMessage()));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.import_failed_format, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private String readText(Uri uri, Context appCtx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IllegalStateException(appCtx.getString(R.string.import_open_failed));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
        }
        return sb.toString();
    }

    private void handleImportPlan(RuleImportExport.ImportPlan plan) {
        if (isFinishing() || isDestroyed()) return;

        if (!plan.invalidEntries.isEmpty()) {
            if (plan.validRules.isEmpty()) {
                Toast.makeText(this, R.string.import_all_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            int showCount = Math.min(plan.invalidEntries.size(), 5);
            String invalidList = TextUtils.join("\n", plan.invalidEntries.subList(0, showCount));
            String title;
            String message;
            if (plan.invalidEntries.size() > 5) {
                title = String.format(getString(R.string.import_invalid_title),
                        plan.invalidEntries.size());
                message = String.format(getString(R.string.import_invalid_desc_long),
                        invalidList, plan.invalidEntries.size() - 5);
            } else {
                title = String.format(getString(R.string.import_invalid_title),
                        plan.invalidEntries.size());
                message = String.format(getString(R.string.import_invalid_desc_short), invalidList);
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.import_skip_and_continue,
                            (d, w) -> proceedImport(plan))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        proceedImport(plan);
    }

    private void proceedImport(RuleImportExport.ImportPlan plan) {
        if (plan.conflicts.isEmpty() && !plan.hasChanges) {
            Toast.makeText(this, R.string.import_no_changes, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!plan.conflicts.isEmpty()) {
            int showCount = Math.min(plan.conflicts.size(), 5);
            String conflictList = TextUtils.join("\n", plan.conflicts.subList(0, showCount));
            String title;
            String message;
            if (plan.conflicts.size() > 5) {
                title = String.format(getString(R.string.import_conflict_title),
                        plan.conflicts.size());
                message = String.format(getString(R.string.import_conflict_desc),
                    conflictList, plan.conflicts.size() - 5);
            } else {
                title = String.format(getString(R.string.import_conflict_title),
                        plan.conflicts.size());
                message = String.format(getString(R.string.import_conflict_desc_short), conflictList);
            }
            new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.import_confirm_overwrite,
                        (d, w) -> doImport(plan.validRules))
                .setNegativeButton(R.string.cancel, null)
                .show();
        } else {
            doImport(plan.validRules);
        }
    }

    private void doImport(Map<String, RedirectRule> rules) {
        if (isFinishing() || isDestroyed()) return;

        ioExecutor.submit(() -> {
            int count = repository.importRules(rules);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (count < 0) {
                    Toast.makeText(this, R.string.import_failed_format, Toast.LENGTH_LONG).show();
                    return;
                }
                refreshList();
                Toast.makeText(this, String.format(getString(R.string.import_success), count),
                        Toast.LENGTH_SHORT).show();
            });
        });
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
                            && oldItem.ruleValue().equals(newItem.ruleValue());
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
            holder.tvRule.setText(entry.ruleValue());

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

}
