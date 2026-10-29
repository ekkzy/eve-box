package io.github.ekkzy.evebox.data;

import android.content.ComponentName;

public final class AppEntry {
    public final String id;
    public final String packageName;
    public final String label;
    public final ComponentName componentName;
    public final String artworkVersion;

    public AppEntry(ComponentName componentName, String label) {
        this(componentName,label,"");
    }
    public AppEntry(ComponentName componentName, String label, String artworkVersion) {
        if (componentName == null) throw new IllegalArgumentException("componentName required");
        this.componentName = componentName;
        this.packageName = componentName.getPackageName();
        this.id = componentName.flattenToString();
        this.label = label == null || label.trim().isEmpty() ? packageName : label.trim();
        this.artworkVersion = artworkVersion == null ? "" : artworkVersion;
    }
}
