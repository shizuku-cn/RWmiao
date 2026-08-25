package com.shizuku.rwmiao.module.drawing;

import com.shizuku.rwmiao.module.RWmiaoModule;

/** Draws the selected unit's attack radius. Kept separate from the frame coordinator. */
final class AttackRangeDrawing {
    private final RWmiaoModule host;

    AttackRangeDrawing(RWmiaoModule host) {
        this.host = host;
    }

    void draw(Object renderer, Drawing.RendererAccess draw, Drawing.DrawConfig config,
              Drawing.RuntimeAccess runtime, Object unit, int relation,
              float x, float y, float scale, float width, float height) throws Throwable {
        float radius = host.number(runtime.range.invoke(unit));
        float screenRadius = radius * scale;
        if (radius <= 0.0f || x + screenRadius < 0.0f || y + screenRadius < 0.0f
                || x - screenRadius > width || y - screenRadius > height) return;
        draw.circle.invoke(renderer, x, y, screenRadius,
                relation == 0 ? config.selfRange
                        : relation == 1 ? config.enemyRange : config.allyRange);
    }
}
