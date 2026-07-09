package com.example.launchRedirector;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuleRepository {

    private static final String META_PREF_NAME = "redirect_config_meta";
    private static final String KEY_VERSION = "config_version";

    private final SharedPreferences rulesPrefs;
    private final SharedPreferences metaPrefs;

    public RuleRepository(Context context) {
        Context appContext = context.getApplicationContext();
        rulesPrefs = appContext.getSharedPreferences(AppUtils.PREF_NAME, Context.MODE_PRIVATE);
        metaPrefs = appContext.getSharedPreferences(META_PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<RuleEntry> getAllRules() {
        List<RuleEntry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> entry : rulesPrefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String pkg = entry.getKey();
            RedirectRule rule = RedirectRule.parse((String) entry.getValue());
            if (AppUtils.isValidPkg(pkg) && rule != null) {
                entries.add(new RuleEntry(pkg, rule));
            }
        }
        return entries;
    }

    public synchronized Map<String, RedirectRule> getRuleMap() {
        Map<String, RedirectRule> rules = new LinkedHashMap<>();
        for (RuleEntry entry : getAllRules()) {
            rules.put(entry.pkg, entry.rule);
        }
        return rules;
    }

    public synchronized String getRawRule(String pkg) {
        return rulesPrefs.getString(pkg, "");
    }

    public synchronized RedirectRule getRule(String pkg) {
        return RedirectRule.parse(getRawRule(pkg));
    }

    public synchronized boolean saveRule(String originalPkg, String pkg, RedirectRule rule) {
        SharedPreferences.Editor editor = rulesPrefs.edit();
        boolean changed = false;

        if (originalPkg != null && !originalPkg.equals(pkg)) {
            editor.remove(originalPkg);
            changed = true;
        }

        String oldValue = rulesPrefs.getString(pkg, null);
        if (!rule.getRawValue().equals(oldValue)) {
            editor.putString(pkg, rule.getRawValue());
            changed = true;
        }

        if (!changed) return true;
        if (!editor.commit()) return false;
        return bumpVersion();
    }

    public synchronized boolean removeRule(String pkg) {
        if (!rulesPrefs.contains(pkg)) return true;
        if (!rulesPrefs.edit().remove(pkg).commit()) return false;
        return bumpVersion();
    }

    public synchronized int importRules(Map<String, RedirectRule> incomingRules) {
        SharedPreferences.Editor editor = rulesPrefs.edit();
        int changed = 0;

        for (Map.Entry<String, RedirectRule> entry : incomingRules.entrySet()) {
            String pkg = entry.getKey();
            RedirectRule rule = entry.getValue();
            String oldValue = rulesPrefs.getString(pkg, null);
            if (!rule.getRawValue().equals(oldValue)) {
                editor.putString(pkg, rule.getRawValue());
                changed++;
            }
        }

        if (changed == 0) return 0;
        if (!editor.commit()) return -1;
        return bumpVersion() ? changed : -1;
    }

    public synchronized long getVersion() {
        return metaPrefs.getLong(KEY_VERSION, 0L);
    }

    private boolean bumpVersion() {
        long nextVersion = metaPrefs.getLong(KEY_VERSION, 0L) + 1L;
        return metaPrefs.edit().putLong(KEY_VERSION, nextVersion).commit();
    }
}
