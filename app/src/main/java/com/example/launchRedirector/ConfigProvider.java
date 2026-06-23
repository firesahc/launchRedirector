package com.example.launchRedirector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class ConfigProvider extends ContentProvider {

    /** ContentProvider authority — keep in sync with AndroidManifest.xml. */
    public static final String AUTHORITY = "com.example.launchRedirector";

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

        SharedPreferences prefs = context.getSharedPreferences(AppUtils.PREF_NAME, Context.MODE_PRIVATE);
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
        return "vnd.android.cursor.item/vnd.example.redirect_rule";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("ConfigProvider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("ConfigProvider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("ConfigProvider is read-only");
    }
}
