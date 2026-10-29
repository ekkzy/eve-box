package io.github.ekkzy.evebox.data;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Process;
import java.net.URISyntaxException;

/** Owner-bound activity intents published by other applications. Never logs provider payloads. */
public final class PublishedContentIntents {
    public enum TargetKind { VIEW, APP_ONLY, UNAVAILABLE }

    private PublishedContentIntents() {}

    /** A resolvable VIEW is a candidate, not evidence of opening the intended content. */
    public static TargetKind classify(Context context, String ownerPackage, String rawUri) {
        Intent intent = resolve(context, ownerPackage, rawUri);
        return intent == null ? TargetKind.UNAVAILABLE
                : Intent.ACTION_VIEW.equals(intent.getAction()) ? TargetKind.VIEW : TargetKind.APP_ONLY;
    }

    public static Intent resolve(Context context, String ownerPackage, String rawUri) {
        if (ownerPackage == null || ownerPackage.isEmpty() || rawUri == null || rawUri.isEmpty()
                || ownerPackage.length() > 256 || rawUri.length() > 16384) return null;
        try {
            Intent parsed = Intent.parseUri(rawUri, Intent.URI_INTENT_SCHEME);
            ComponentName declared = parsed.getComponent();
            if (declared != null && !ownerPackage.equals(declared.getPackageName())) return null;
            String action = parsed.getAction();
            if (action == null) parsed.setAction(Intent.ACTION_VIEW);
            else if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_MAIN.equals(action)) return null;
            Uri data = parsed.getData();
            if (data != null && ("file".equalsIgnoreCase(data.getScheme())
                    || "javascript".equalsIgnoreCase(data.getScheme()))) return null;
            boolean requestedRead = (parsed.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
            parsed.setSelector(null);
            parsed.setClipData(null);
            parsed.setPackage(ownerPackage);
            parsed.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Preserve the Watch Next policy: never delegate grants supplied only by a row.
            if (requestedRead && data != null && "content".equals(data.getScheme())
                    && context.checkUriPermission(data, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                parsed.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            ResolveInfo resolved = context.getPackageManager().resolveActivity(parsed, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
            if (activity == null || !activity.enabled || !activity.exported
                    || !ownerPackage.equals(activity.packageName)
                    || activity.applicationInfo == null || !activity.applicationInfo.enabled) return null;
            if (activity.permission != null && context.checkSelfPermission(activity.permission)
                    != PackageManager.PERMISSION_GRANTED) return null;
            parsed.setComponent(new ComponentName(activity.packageName, activity.name));
            return parsed;
        } catch (URISyntaxException | RuntimeException invalid) { return null; }
    }

    /** Revalidates at the click boundary. APP_ONLY never silently replaces a content card. */
    public static boolean launchView(Context context, String ownerPackage, String rawUri) {
        Intent intent = resolve(context, ownerPackage, rawUri);
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }
}
