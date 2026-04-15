package com.example.launchRedirector;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

public class EditActivity extends Activity {

    private EditText etPkg;
    private EditText etUri;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        prefs = getSharedPreferences("redirect_config", Context.MODE_PRIVATE);
        etPkg = findViewById(R.id.edit_pkg);
        etUri = findViewById(R.id.edit_uri);

        String pkg = getIntent().getStringExtra("pkg");
        if (pkg != null) {
            etPkg.setText(pkg);
            etPkg.setEnabled(false);
            etUri.setText(prefs.getString(pkg, ""));
        }

        findViewById(R.id.btn_save).setOnClickListener(v -> saveRule());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
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

        prefs.edit().putString(pkg, uri).apply();
        finish();
    }
}
