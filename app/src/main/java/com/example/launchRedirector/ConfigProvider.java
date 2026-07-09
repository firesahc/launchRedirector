package com.example.launchRedirector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

public class ConfigProvider extends ContentProvider {

    /** ContentProvider authority — keep in sync with AndroidManifest.xml. */
    public static final String AUTHORITY = "com.example.launchRedirector";

    public static final String PATH_CONFIG = "config";
    public static final String PATH_VERSION = "version";

    public static final String COLUMN_RULE = "rule";
    public static final String COLUMN_KIND = "kind";
    public static final String COLUMN_VERSION = "version";

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

        RuleRepository repository = new RuleRepository(context);
        List<String> segments = uri.getPathSegments();
        if (segments.size() == 1 && PATH_VERSION.equals(segments.get(0))) {
            MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_VERSION}, 1);
            cursor.addRow(new Object[]{repository.getVersion()});
            return cursor;
        }

        if (segments.size() != 2 || !PATH_CONFIG.equals(segments.get(0))) {
            return null;
        }

        String targetPkg = segments.get(1);
        if (!AppUtils.isValidPkg(targetPkg)) {
            return null;
        }

        RedirectRule rule = repository.getRule(targetPkg);
        if (rule == null) {
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(
                new String[]{COLUMN_RULE, COLUMN_KIND, COLUMN_VERSION}, 1);
        cursor.addRow(new Object[]{rule.getRawValue(), rule.getKind().name(), repository.getVersion()});
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
