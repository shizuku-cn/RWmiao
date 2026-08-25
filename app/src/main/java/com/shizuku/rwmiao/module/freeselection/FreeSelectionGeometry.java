package com.shizuku.rwmiao.module.freeselection;

/** Allocation-free geometry helpers for free-form selection and offline tests. */
public final class FreeSelectionGeometry {
    private static final double EPSILON = 0.0001d;

    private FreeSelectionGeometry() {
    }

    /** Even-odd containment with points on an edge included. */
    public static boolean contains(float[] xs, float[] ys, int count, float x, float y) {
        if (xs == null || ys == null || count < 3 || count > xs.length || count > ys.length) {
            return false;
        }
        boolean inside = false;
        int previous = count - 1;
        for (int current = 0; current < count; current++) {
            float x1 = xs[previous];
            float y1 = ys[previous];
            float x2 = xs[current];
            float y2 = ys[current];
            if (onSegment(x1, y1, x2, y2, x, y)) return true;
            if ((y1 > y) != (y2 > y)) {
                double intersection = x1 + (double) (y - y1) * (x2 - x1) / (y2 - y1);
                if (x < intersection) inside = !inside;
            }
            previous = current;
        }
        return inside;
    }

    public static float perimeter(float[] xs, float[] ys, int count) {
        if (xs == null || ys == null || count < 2) return 0.0f;
        float length = 0.0f;
        for (int i = 1; i < count; i++) {
            length += distance(xs[i - 1], ys[i - 1], xs[i], ys[i]);
        }
        return length;
    }

    /** Signed shoelace area, returned as an absolute value. */
    public static float area(float[] xs, float[] ys, int count) {
        if (xs == null || ys == null || count < 3) return 0.0f;
        double twice = 0.0d;
        for (int i = 0; i < count; i++) {
            int next = i + 1 == count ? 0 : i + 1;
            twice += (double) xs[i] * ys[next] - (double) xs[next] * ys[i];
        }
        return (float) (Math.abs(twice) * 0.5d);
    }

    /**
     * Closure validation. The fan fallback prevents a symmetric bow-tie from
     * being rejected solely because its signed shoelace terms cancel out.
     */
    public static boolean isClosed(float[] xs, float[] ys, int count,
                                   float closeDistance, int minimumPoints,
                                   float minimumPerimeter, float minimumArea) {
        if (xs == null || ys == null || count < minimumPoints || count < 3) return false;
        if (distance(xs[0], ys[0], xs[count - 1], ys[count - 1]) > closeDistance) {
            return false;
        }
        if (perimeter(xs, ys, count) < minimumPerimeter) return false;
        float enclosedArea = area(xs, ys, count);
        if (enclosedArea < minimumArea) {
            enclosedArea = fanArea(xs, ys, count);
        }
        return enclosedArea >= minimumArea;
    }

    public static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    private static float fanArea(float[] xs, float[] ys, int count) {
        double centerX = 0.0d;
        double centerY = 0.0d;
        for (int i = 0; i < count; i++) {
            centerX += xs[i];
            centerY += ys[i];
        }
        centerX /= count;
        centerY /= count;
        double total = 0.0d;
        for (int i = 0; i < count; i++) {
            int next = i + 1 == count ? 0 : i + 1;
            double ax = xs[i] - centerX;
            double ay = ys[i] - centerY;
            double bx = xs[next] - centerX;
            double by = ys[next] - centerY;
            total += Math.abs(ax * by - bx * ay) * 0.5d;
        }
        return (float) total;
    }

    private static boolean onSegment(float x1, float y1, float x2, float y2,
                                     float x, float y) {
        double cross = (double) (x - x1) * (y2 - y1)
                - (double) (y - y1) * (x2 - x1);
        if (Math.abs(cross) > EPSILON) return false;
        return x >= Math.min(x1, x2) - EPSILON && x <= Math.max(x1, x2) + EPSILON
                && y >= Math.min(y1, y2) - EPSILON && y <= Math.max(y1, y2) + EPSILON;
    }
}
