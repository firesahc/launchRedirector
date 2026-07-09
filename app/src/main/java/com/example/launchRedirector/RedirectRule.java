package com.example.launchRedirector;

import java.util.regex.Pattern;

public final class RedirectRule {

    public enum Kind {
        URI,
        ACTIVITY
    }

    private static final Pattern URI_SCHEME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:[^\\s]+$");

    private static final String CLASS_IDENT = "[a-zA-Z_$][a-zA-Z0-9_$]*";
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\." + CLASS_IDENT + "(?:\\." + CLASS_IDENT + ")*"
                    + "|" + CLASS_IDENT + "(?:\\." + CLASS_IDENT + ")*");

    private final String rawValue;
    private final Kind kind;

    private RedirectRule(String rawValue, Kind kind) {
        this.rawValue = rawValue;
        this.kind = kind;
    }

    public static RedirectRule parse(String value) {
        if (value == null) return null;

        String normalized = value.trim();
        if (normalized.isEmpty()) return null;

        if (URI_SCHEME_PATTERN.matcher(normalized).matches()) {
            return new RedirectRule(normalized, Kind.URI);
        }

        if (CLASS_NAME_PATTERN.matcher(normalized).matches()) {
            return new RedirectRule(normalized, Kind.ACTIVITY);
        }

        return null;
    }

    public static boolean isValid(String value) {
        return parse(value) != null;
    }

    public String getRawValue() {
        return rawValue;
    }

    public Kind getKind() {
        return kind;
    }

    public String resolveActivityClass(String packageName) {
        if (kind != Kind.ACTIVITY) return null;
        if (rawValue.startsWith(".")) return packageName + rawValue;
        if (!rawValue.contains(".")) return packageName + "." + rawValue;
        return rawValue;
    }
}
