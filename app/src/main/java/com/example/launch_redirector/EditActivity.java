package com.example.launch_redirector;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import java.io.File;

public class EditActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        EditText etPkg = findViewById(R.id.edit_pkg);
        EditText etUri = findViewById(R.id.edit_uri);
        SharedPreferences prefs = getSharedPreferences("redirect_config", Context.MODE_PRIVATE);

        String pkg = getIntent().getStringExtra("pkg");
        if (pkg != null) {
            etPkg.setText(pkg);
            etPkg.setEnabled(false);
            etUri.setText(prefs.getString(pkg, ""));
        }

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            String p = etPkg.getText().toString().trim();
            String u = etUri.getText().toString().trim();
            if (!p.isEmpty() && !u.isEmpty()) {
                prefs.edit().putString(p, u).apply();
                File f = new File(getDataDir(), "shared_prefs/redirect_config.xml");
                if (f.exists()) f.setReadable(true, false);
                finish();
            }
        });
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
    }
}