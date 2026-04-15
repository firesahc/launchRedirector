package com.example.launchRedirector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class ConfigProvider extends ContentProvider {

    private static final String PREF_NAME = "redirect_config";
    private static final String COLUMN_URI = "uri";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null || uri == null) {
            return null;
        }

        String targetPkg = uri.getLastPathSegment();
        if (targetPkg == null || targetPkg.isEmpty()) {
            return null;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String redirectUri = prefs.getString(targetPkg, null);
        if (redirectUri == null || redirectUri.isEmpty()) {
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_URI}, 1);
        cursor.addRow(new Object[]{redirectUri});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
