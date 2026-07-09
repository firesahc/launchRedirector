package com.example.launchRedirector;

import android.content.Intent;
import android.net.Uri;

public final class RedirectIntentFactory {

    private RedirectIntentFactory() {}

    public static Intent create(String packageName, RedirectRule rule) {
        Intent intent;
        if (rule.getKind() == RedirectRule.Kind.URI) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(rule.getRawValue()));
        } else {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(packageName, rule.resolveActivityClass(packageName));
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }
}
