package io.github.ekkzy.softlumi.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Ordered IDs, independent of whether an application is currently installed. */
public final class FavoriteOrder {
    public static final int LIMIT = 8;
    private FavoriteOrder() {}

    public static List<String> normalize(Collection<String> ids) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (ids != null) {
            int visited = 0;
            for (String raw : ids) {
                if (++visited > 512 || unique.size() == LIMIT) break;
                if (raw == null) continue;
                String id = raw.trim();
                if (!id.isEmpty() && id.length() <= 512 && id.indexOf('\n') < 0
                        && id.indexOf('\r') < 0) unique.add(id);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    public static List<String> add(List<String> ids, String id) {
        List<String> copy = new ArrayList<>(normalize(ids));
        copy.add(id);
        return normalize(copy);
    }

    public static List<String> remove(List<String> ids, String id) {
        List<String> copy = new ArrayList<>(normalize(ids));
        copy.remove(id);
        return normalize(copy);
    }

    public static List<String> move(List<String> ids, String id, int destination) {
        List<String> copy = new ArrayList<>(normalize(ids));
        if (!copy.remove(id)) return normalize(copy);
        copy.add(Math.max(0, Math.min(destination, copy.size())), id);
        return normalize(copy);
    }
}
