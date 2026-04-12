package com.example.launch_redirector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private ListView listView;
    private List<String> pkgList = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("redirect_config", Context.MODE_PRIVATE);
        listView = findViewById(R.id.list_view);

        findViewById(R.id.btn_add).setOnClickListener(v -> startActivity(new Intent(this, EditActivity.class)));
        findViewById(R.id.btn_apply).setOnClickListener(v -> restartLauncher());

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
                .setTitle("操作")
                .setItems(new String[]{"修改", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(this, EditActivity.class);
                        intent.putExtra("pkg", pkg);
                        startActivity(intent);
                    } else {
                        prefs.edit().remove(pkg).apply();
                        setPrefsReadable();
                        refreshList();
                    }
                }).show();
    }

    private void restartLauncher() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("pkill -f com.miui.home\n");
            os.writeBytes("exit\n");
            os.flush();
        } catch (Exception e) {
            Toast.makeText(this, "需要Root权限以重启桌面", Toast.LENGTH_SHORT).show();
        }
    }

    private void setPrefsReadable() {
        File f = new File(getDataDir(), "shared_prefs/redirect_config.xml");
        if (f.exists()) f.setReadable(true, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }
}