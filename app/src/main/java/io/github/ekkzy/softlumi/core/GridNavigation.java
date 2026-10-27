package io.github.ekkzy.softlumi.core;

/** Deterministic D-pad movement for a partially filled grid. */
public final class GridNavigation {
    public enum Direction { LEFT, RIGHT, UP, DOWN }
    private GridNavigation() {}

    public static int move(int index, int itemCount, int columns, Direction direction) {
        if (itemCount <= 0) return -1;
        if (columns <= 0) throw new IllegalArgumentException("columns must be positive");
        int current = Math.max(0, Math.min(index, itemCount - 1));
        if (direction == null) return current;
        switch (direction) {
            case LEFT: return current % columns == 0 ? current : current - 1;
            case RIGHT: return current % columns == columns - 1 ? current
                    : Math.min(current + 1, itemCount - 1);
            case UP: return current < columns ? current : current - columns;
            case DOWN: return current / columns == (itemCount - 1) / columns ? current
                    : Math.min(current + columns, itemCount - 1);
            default: return current;
        }
    }
}
