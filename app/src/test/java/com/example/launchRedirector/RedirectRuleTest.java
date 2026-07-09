package com.example.launchRedirector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RedirectRuleTest {

    @Test
    public void parse_acceptsCommonUriSchemes() {
        assertRuleKind("https://example.com/path", RedirectRule.Kind.URI);
        assertRuleKind("mailto:user@example.com", RedirectRule.Kind.URI);
        assertRuleKind("tel:10086", RedirectRule.Kind.URI);
        assertRuleKind("market://details?id=com.example.app", RedirectRule.Kind.URI);
    }

    @Test
    public void parse_acceptsActivityClassForms() {
        assertRuleKind(".MainActivity", RedirectRule.Kind.ACTIVITY);
        assertRuleKind("com.example.app.MainActivity", RedirectRule.Kind.ACTIVITY);
        assertRuleKind("MainActivity", RedirectRule.Kind.ACTIVITY);
    }

    @Test
    public void parse_rejectsBlankAndWhitespaceUri() {
        assertNull(RedirectRule.parse(""));
        assertNull(RedirectRule.parse("   "));
        assertNull(RedirectRule.parse("https://example.com/a b"));
    }

    @Test
    public void resolveActivityClass_expandsRelativeAndFlatNames() {
        assertEquals("com.example.app.MainActivity",
                RedirectRule.parse(".MainActivity").resolveActivityClass("com.example.app"));
        assertEquals("com.example.app.MainActivity",
                RedirectRule.parse("MainActivity").resolveActivityClass("com.example.app"));
        assertEquals("com.other.Entry",
                RedirectRule.parse("com.other.Entry").resolveActivityClass("com.example.app"));
    }

    @Test
    public void isValid_matchesParser() {
        assertTrue(RedirectRule.isValid("mailto:user@example.com"));
        assertTrue(RedirectRule.isValid(".MainActivity"));
        assertFalse(RedirectRule.isValid("http://bad uri"));
    }

    private static void assertRuleKind(String rawValue, RedirectRule.Kind expectedKind) {
        RedirectRule rule = RedirectRule.parse(rawValue);
        assertNotNull(rule);
        assertEquals(expectedKind, rule.getKind());
        assertEquals(rawValue, rule.getRawValue());
    }
}
