package com.shizuku.rwmiao.module.drawing;

/** Draws the line from a unit to its current target. */
final class TargetLineDrawing {
    void draw(Object renderer, Drawing.RendererAccess draw, Drawing.DrawConfig config,
              Drawing.RuntimeAccess runtime, Object unit, int relation,
              float x, float y, float cameraX, float cameraY, float scale,
              float width, float height) throws Throwable {
        Object target = runtime.target.get(unit);
        if (target == null || runtime.dead.getBoolean(target)) return;
        float targetX = (runtime.x.getFloat(target) - cameraX) * scale;
        float targetY = (runtime.y.getFloat(target) - cameraY) * scale;
        if (sameOutsideSide(x, y, targetX, targetY, width, height)) return;
        draw.line.invoke(renderer, x, y, targetX, targetY,
                relation == 0 ? config.selfLine
                        : relation == 1 ? config.enemyLine : config.allyLine);
    }

    private boolean sameOutsideSide(float x1, float y1, float x2, float y2,
                                    float width, float height) {
        return (x1 < 0.0f && x2 < 0.0f) || (y1 < 0.0f && y2 < 0.0f)
                || (x1 > width && x2 > width) || (y1 > height && y2 > height);
    }
}
