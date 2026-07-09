package com.example.launchRedirector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class RuleImportExportTest {

    @Test
    public void parseImportPlan_reportsInvalidConflictsAndNewRules() throws Exception {
        Map<String, RedirectRule> existing = new LinkedHashMap<>();
        existing.put("com.example.old", RedirectRule.parse(".OldActivity"));
        existing.put("com.example.same", RedirectRule.parse("mailto:same@example.com"));

        String json = "{"
                + "\"com.example.old\":\".NewActivity\","
                + "\"com.example.same\":\"mailto:same@example.com\","
                + "\"com.example.new\":\"tel:10086\","
                + "\"bad-package\":\".Bad\","
                + "\"com.example.invalid\":\"http://bad uri\""
                + "}";

        RuleImportExport.ImportPlan plan = RuleImportExport.parseImportPlan(json, existing);

        assertEquals(3, plan.validRules.size());
        assertEquals(Arrays.asList("com.example.old"), plan.conflicts);
        assertEquals(2, plan.invalidEntries.size());
        assertTrue(plan.hasChanges);
    }

    @Test
    public void toJson_exportsRawRuleValues() throws Exception {
        String json = RuleImportExport.toJson(Arrays.asList(
                new RuleEntry("com.example.app", RedirectRule.parse(".MainActivity")),
                new RuleEntry("com.example.link", RedirectRule.parse("mailto:user@example.com"))
        ));

        RuleImportExport.ImportPlan plan =
                RuleImportExport.parseImportPlan(json, new LinkedHashMap<>());
        assertEquals(".MainActivity", plan.validRules.get("com.example.app").getRawValue());
        assertEquals("mailto:user@example.com",
                plan.validRules.get("com.example.link").getRawValue());
    }
}
