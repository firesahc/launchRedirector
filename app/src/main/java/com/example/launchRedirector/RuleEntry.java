package com.example.launchRedirector;

public final class RuleEntry {
    public final String pkg;
    public final RedirectRule rule;

    public RuleEntry(String pkg, RedirectRule rule) {
        this.pkg = pkg;
        this.rule = rule;
    }

    public String ruleValue() {
        return rule.getRawValue();
    }
}
