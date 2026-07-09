package com.example.launchRedirector;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuleImportExport {

    private RuleImportExport() {}

    public static String toJson(List<RuleEntry> rules) throws JSONException {
        JSONObject json = new JSONObject();
        for (RuleEntry entry : rules) {
            json.put(entry.pkg, entry.ruleValue());
        }
        return json.toString(4);
    }

    public static ImportPlan parseImportPlan(String content, Map<String, RedirectRule> existingRules)
            throws JSONException {
        JSONObject json = new JSONObject(content);
        Map<String, RedirectRule> validRules = new LinkedHashMap<>();
        List<String> invalidEntries = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        boolean hasChanges = false;

        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.opt(key);

            if (!AppUtils.isValidPkg(key) || !(value instanceof String)) {
                invalidEntries.add(key);
                continue;
            }

            RedirectRule rule = RedirectRule.parse((String) value);
            if (rule == null) {
                invalidEntries.add(key + " -> " + value);
                continue;
            }

            validRules.put(key, rule);
            RedirectRule existing = existingRules.get(key);
            if (existing == null) {
                hasChanges = true;
            } else if (!existing.getRawValue().equals(rule.getRawValue())) {
                conflicts.add(key);
                hasChanges = true;
            }
        }

        return new ImportPlan(validRules, invalidEntries, conflicts, hasChanges);
    }

    public static final class ImportPlan {
        public final Map<String, RedirectRule> validRules;
        public final List<String> invalidEntries;
        public final List<String> conflicts;
        public final boolean hasChanges;

        ImportPlan(
                Map<String, RedirectRule> validRules,
                List<String> invalidEntries,
                List<String> conflicts,
                boolean hasChanges
        ) {
            this.validRules = validRules;
            this.invalidEntries = invalidEntries;
            this.conflicts = conflicts;
            this.hasChanges = hasChanges;
        }
    }
}
