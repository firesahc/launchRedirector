package com.example.launchRedirector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_EXPORT = 101;
    private static final int REQUEST_CODE_IMPORT = 102;

    private final List<RuleEntry> ruleEntries = new ArrayList<>();
    private SharedPreferences prefs;
    private ListView listView;
    private RuleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("redirect_config", Context.MODE_PRIVATE);
        listView = findViewById(R.id.list_view);
        adapter = new RuleAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.btn_add).setOnClickListener(v -> startActivity(new Intent(this, EditActivity.class)));
        findViewById(R.id.btn_apply).setOnClickListener(v -> restartLauncher());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportConfig());
        findViewById(R.id.btn_import).setOnClickListener(v -> importConfig());

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < ruleEntries.size()) {
                showActionDialog(ruleEntries.get(position).pkg);
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        ruleEntries.clear();
        Map<String, ?> allEntries = prefs.getAll();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String pkg = entry.getKey();
            Object value = entry.getValue();
            if (TextUtils.isEmpty(pkg) || value == null) {
                continue;
            }
            String rule = String.valueOf(value);
            if (TextUtils.isEmpty(rule)) {
                continue;
            }
            ruleEntries.add(new RuleEntry(pkg, rule));
        }

        ruleEntries.sort(Comparator
                .comparing((RuleEntry e) -> getAppLabel(e.pkg), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.pkg, String.CASE_INSENSITIVE_ORDER));
        adapter.notifyDataSetChanged();
    }

    private void showActionDialog(String pkg) {
        new AlertDialog.Builder(this)
                .setTitle("编辑：" + getAppLabel(pkg))
                .setItems(new CharSequence[]{"修改", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(this, EditActivity.class);
                        intent.putExtra("pkg", pkg);
                        startActivity(intent);
                    } else {
                        prefs.edit().remove(pkg).apply();
                        refreshList();
                    }
                })
                .show();
    }

    private String getAppLabel(String pkg) {
        try {
            CharSequence label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }

    private void restartLauncher() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                os.writeBytes("pkill -f com.miui.home\n");
                os.writeBytes("exit\n");
                os.flush();
            }
            Toast.makeText(this, "已发送重启桌面命令", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "需要 Root 权限以重启桌面", Toast.LENGTH_SHORT).show();
            XposedBridge.log("launchRedirector: 重启桌面失败 " + e.getMessage());
        }
    }

    private void exportConfig() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "redirect_rules.json");
        startActivityForResult(intent, REQUEST_CODE_EXPORT);
    }

    private void importConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_CODE_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_CODE_EXPORT) {
            writeExportFile(uri);
        } else if (requestCode == REQUEST_CODE_IMPORT) {
            readImportFile(uri);
        }
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

            JSONObject json = new JSONObject(sb.toString());
            SharedPreferences.Editor editor = prefs.edit();
            Iterator<String> keys = json.keys();
            int count = 0;
            while (keys.hasNext()) {
                String key = keys.next();
                editor.putString(key, json.getString(key));
                count++;
            }
            editor.apply();

            refreshList();
            Toast.makeText(this, "成功导入 " + count + " 条规则", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            XposedBridge.log("launchRedirector: 导入失败 " + e.getMessage());
            Toast.makeText(this, "导入失败，已输出错误日志", Toast.LENGTH_LONG).show();
        }
    }

    private final class RuleAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return ruleEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return ruleEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_rule_entry, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            RuleEntry entry = ruleEntries.get(position);
            holder.tvTitle.setText(getAppLabel(entry.pkg));
            holder.tvPkg.setText(entry.pkg);
            holder.tvRule.setText(entry.rule);
            return convertView;
        }
    }

    private static final class ViewHolder {
        final TextView tvTitle;
        final TextView tvPkg;
        final TextView tvRule;

        ViewHolder(View root) {
            tvTitle = root.findViewById(R.id.tv_item_title);
            tvPkg = root.findViewById(R.id.tv_item_pkg);
            tvRule = root.findViewById(R.id.tv_item_rule);
        }
    }

    private static final class RuleEntry {
        final String pkg;
        final String rule;

        RuleEntry(String pkg, String rule) {
            this.pkg = pkg;
            this.rule = rule;
        }
    }
}
