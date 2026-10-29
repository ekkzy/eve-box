package io.github.ekkzy.evebox.data;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Blocking package-manager adapter. Call catalog/icon loading on the UI owner's executor. */
public final class AppCatalog {
    private AppCatalog() {}

    public static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String, AppEntry> found = new LinkedHashMap<>();
        // Prefer the TV activity when an application declares both launcher categories.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> matches;
            try { matches = pm.queryIntentActivities(query, 0); }
            catch (RuntimeException ignored) { continue; }
            for (ResolveInfo match : matches) {
                ActivityInfo activity = match.activityInfo;
                if (activity == null || !activity.enabled || !activity.exported
                        || activity.applicationInfo == null || !activity.applicationInfo.enabled
                        || context.getPackageName().equals(activity.packageName)) continue;
                if (activity.permission != null && context.checkSelfPermission(activity.permission)
                        != PackageManager.PERMISSION_GRANTED) continue;
                if (found.containsKey(activity.packageName)) continue;
                CharSequence label;
                try { label = match.loadLabel(pm); }
                catch (RuntimeException ignored) { label = activity.packageName; }
                found.put(activity.packageName, new AppEntry(
                        new ComponentName(activity.packageName, activity.name),
                        label == null ? activity.packageName : label.toString(),
                        String.valueOf(activity.applicationInfo.sourceDir)+java.util.Arrays.toString(activity.applicationInfo.splitSourceDirs)));
            }
        }
        ArrayList<AppEntry> apps = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        apps.sort((left, right) -> {
            int labels = collator.compare(left.label, right.label);
            return labels == 0 ? left.id.compareTo(right.id) : labels;
        });
        return Collections.unmodifiableList(apps);
    }

    public static Drawable loadIcon(Context context, AppEntry entry) {
        try { return context.getPackageManager().getActivityIcon(entry.componentName); }
        catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return context.getPackageManager().getDefaultActivityIcon();
        }
    }

    public static boolean launch(Context context, String id) {
        ComponentName component = id == null ? null : ComponentName.unflattenFromString(id);
        if (component == null) return false;
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ActivityInfo activity = context.getPackageManager().getActivityInfo(component, 0);
            if (!activity.enabled || !activity.exported || activity.applicationInfo == null
                    || !activity.applicationInfo.enabled) return false;
            context.startActivity(intent);
            return true;
        } catch (PackageManager.NameNotFoundException | ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
