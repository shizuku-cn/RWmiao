package com.shizuku.rwmiao.module.support.path;

/** Immutable view of one native movement layer for a single path request. */
public final class SmartPathGrid {
    public final int width;
    public final int height;
    public final float worldToGrid;
    public final byte[] terrain;
    public final byte[] buildings;
    public final byte[] objects;

    public SmartPathGrid(int width, int height, float worldToGrid,
                         byte[] terrain, byte[] buildings, byte[] objects) {
        this.width = width;
        this.height = height;
        this.worldToGrid = worldToGrid;
        this.terrain = terrain;
        this.buildings = buildings;
        this.objects = objects;
    }

    public boolean valid() {
        int size = width * height;
        return width > 0 && height > 0 && size <= 250000 && worldToGrid > 0.0f
                && terrain != null && buildings != null && objects != null
                && terrain.length >= size && buildings.length >= size && objects.length >= size;
    }

    public boolean passable(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        // Native movement layers use column-major indexing: height*x+y.
        int index = (height * x) + y;
        return terrain[index] != -1 && buildings[index] != -1 && objects[index] != -1;
    }

    public boolean touchesBlockedCell(int x, int y) {
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                if ((offsetX != 0 || offsetY != 0)
                        && !passable(x + offsetX, y + offsetY)) return true;
            }
        }
        return false;
    }

    public boolean lineOfSightWorld(float x0, float y0, float x1, float y1) {
        return lineOfSight((int) (x0 * worldToGrid), (int) (y0 * worldToGrid),
                (int) (x1 * worldToGrid), (int) (y1 * worldToGrid));
    }

    public boolean lineOfSightToTarget(float x0, float y0, float x1, float y1) {
        return lineOfSight((int) (x0 * worldToGrid), (int) (y0 * worldToGrid),
                (int) (x1 * worldToGrid), (int) (y1 * worldToGrid), true);
    }

    public boolean lineOfSight(int x0, int y0, int x1, int y1) {
        return lineOfSight(x0, y0, x1, y1, false);
    }

    private boolean lineOfSight(int x0, int y0, int x1, int y1,
                                boolean allowBlockedEnd) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx - dy;
        while (true) {
            if (!passable(x0, y0) && !(allowBlockedEnd && x0 == x1 && y0 == y1)) return false;
            if (x0 == x1 && y0 == y1) return true;
            int twice = error * 2;
            int nx = x0;
            int ny = y0;
            if (twice > -dy) { error -= dy; nx += sx; }
            if (twice < dx) { error += dx; ny += sy; }
            if (nx != x0 && ny != y0 && (!passable(nx, y0) || !passable(x0, ny))) {
                return false;
            }
            x0 = nx;
            y0 = ny;
        }
    }
}
