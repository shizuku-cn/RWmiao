package com.shizuku.rwmiao.module.drawing;

import android.graphics.Paint;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves launcher instances from the authoritative unit list at the same
 * low-frequency cadence as the other world overlays, then draws their current
 * ammunition in the shared post-map HUD pass.
 */
final class AmmoDrawing {
    private final Map<Object, Integer> tracked = new WeakHashMap<>();
    private final Map<Object, AmmoLabel> labels = new WeakHashMap<>();

    void clear() {
        tracked.clear();
        labels.clear();
    }

    boolean hasTracked() {
        return !tracked.isEmpty();
    }

    Iterable<Map.Entry<Object, Integer>> tracked() {
        return tracked.entrySet();
    }

    void refresh(Iterable<?> units, Drawing.RuntimeAccess runtime) throws IllegalAccessException {
        tracked.clear();
        for (Object unit : units) {
            if (unit == null || runtime.dead.getBoolean(unit)
                    || runtime.attached.get(unit) != null) continue;
            int kind = runtime.ammoKind(unit);
            if (kind != 0) tracked.put(unit, kind);
        }
    }

    void drawScreen(Object renderer, Drawing.RendererAccess draw, Drawing.DrawConfig config,
                    Drawing.RuntimeAccess runtime, Object unit, int kind, int relation,
                    float screenX, float screenY, float width, float height) throws Throwable {
        if (screenX < -100.0f || screenY < -100.0f
                || screenX > width + 100.0f || screenY > height + 100.0f) {
            return;
        }
        AmmoLabel label = label(runtime, unit, kind);
        Paint paint = relation == 0 ? config.selfText
                : relation == 1 ? config.enemyText : config.allyText;
        draw.text.invoke(renderer, label.text, screenX, screenY, paint);
    }

    private AmmoLabel label(Drawing.RuntimeAccess runtime, Object unit, int kind)
            throws IllegalAccessException {
        int count = Math.max(0, runtime.ammoCount(unit, kind));
        AmmoLabel label = labels.get(unit);
        if (label == null || label.count != count) {
            label = new AmmoLabel(count, Integer.toString(count));
            labels.put(unit, label);
        }
        return label;
    }

    private static final class AmmoLabel {
        final int count;
        final String text;

        AmmoLabel(int count, String text) {
            this.count = count;
            this.text = text;
        }
    }
}
