package com.example.launch_redirector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // 从请求的 Uri 中提取包名 (例如 com.ss.android.ugc.aweme)
        String targetPkg = uri.getLastPathSegment();

        if (targetPkg != null && getContext() != null) {
            // 安全读取本应用的 SharedPreferences
            SharedPreferences prefs = getContext().getSharedPreferences("redirect_config", Context.MODE_PRIVATE);
            String redirectUri = prefs.getString(targetPkg, null);

            if (redirectUri != null) {
                // 如果找到规则，打包成 Cursor 发送给 Hook 进程
                MatrixCursor cursor = new MatrixCursor(new String[]{"uri"});
                cursor.addRow(new Object[]{redirectUri});
                return cursor;
            }
        }
        return null; // 没找到规则就返回空
    }

    // --- 以下方法对于我们的需求用不到，直接返回默认值即可 ---
    @Override
    public String getType(Uri uri) { return null; }
    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}