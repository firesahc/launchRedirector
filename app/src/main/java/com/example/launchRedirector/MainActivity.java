package com.example.launchRedirector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

public class MainActivity extends Activity {
    private ListView listView;
    private List<String> pkgList = new ArrayList<>();
    private SharedPreferences prefs;

    // 请求码
    private static final int REQUEST_CODE_EXPORT = 101;
    private static final int REQUEST_CODE_IMPORT = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("redirect_config", Context.MODE_PRIVATE);
        listView = findViewById(R.id.list_view);

        // 绑定按钮事件
        findViewById(R.id.btn_add).setOnClickListener(v -> startActivity(new Intent(this, EditActivity.class)));
        findViewById(R.id.btn_apply).setOnClickListener(v -> restartLauncher());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportConfig());
        findViewById(R.id.btn_import).setOnClickListener(v -> importConfig());

        // 列表长按事件
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String pkg = pkgList.get(position);
            showActionDialog(pkg);
            return true;
        });
    }

    private void refreshList() {
        pkgList.clear();
        pkgList.addAll(prefs.getAll().keySet());
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, pkgList));
    }

    private void showActionDialog(String pkg) {
        new AlertDialog.Builder(this)
                .setTitle("操作 " + pkg)
                .setItems(new String[]{"修改", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(this, EditActivity.class);
                        intent.putExtra("pkg", pkg);
                        startActivity(intent);
                    } else {
                        prefs.edit().remove(pkg).apply();
                        refreshList();
                    }
                }).show();
    }

    // 重启桌面
    private void restartLauncher() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("pkill -f com.miui.home\n");
            os.writeBytes("exit\n");
            os.flush();
        } catch (Exception e) {
            Toast.makeText(this, "需要Root权限以重启桌面", Toast.LENGTH_SHORT).show();
            XposedBridge.log("launchRedirector: 重启桌面失败 " + e.getMessage());
        }
    }

    // 触发导出：调用系统文件管理器创建文件
    private void exportConfig() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "redirect_rules.json");
        startActivityForResult(intent, REQUEST_CODE_EXPORT);
    }

    // 触发导入：调用系统文件管理器选择文件
    private void importConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // 兼容性更好
        startActivityForResult(intent, REQUEST_CODE_IMPORT);
    }

    // 接收文件管理器的回调
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQUEST_CODE_EXPORT) {
                writeExportFile(uri);
            } else if (requestCode == REQUEST_CODE_IMPORT) {
                readImportFile(uri);
            }
        }
    }

    // 写入 JSON 数据到文件
    private void writeExportFile(Uri uri) {
        try {
            Map<String, ?> allEntries = prefs.getAll();
            JSONObject json = new JSONObject(allEntries);

            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                // 写入格式化后的 JSON，4代表缩进空格数
                os.write(json.toString(4).getBytes());
                os.close();
                Toast.makeText(this, "成功导出 " + allEntries.size() + " 条规则", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            XposedBridge.log("launchRedirector: 导出失败 " + e.getMessage());
            Toast.makeText(this, "导出失败，已输出错误日志", Toast.LENGTH_LONG).show();
        }
    }

    // 从文件读取 JSON 数据并保存
    private void readImportFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                is.close();

                // 解析 JSON
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
            }
        } catch (Exception e) {
            XposedBridge.log("launchRedirector: 导入失败 " + e.getMessage());
            Toast.makeText(this, "导入失败，已输出错误日志", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }
}