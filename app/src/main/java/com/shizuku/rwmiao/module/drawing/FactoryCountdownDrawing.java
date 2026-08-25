package com.shizuku.rwmiao.module.drawing;

import android.graphics.Paint;

import java.util.Map;
import java.util.Locale;
import java.util.WeakHashMap;

/** Draws one current unit-production queue item at each producing factory. */
final class FactoryCountdownDrawing {
    private static final float LINE_SPACING = 1.15f;
    private final Map<Object, FactoryLabel> labels = new WeakHashMap<>();
    private final Map<Object, FactoryState> states = new WeakHashMap<>();

    void clear() {
        labels.clear();
        states.clear();
    }

    void draw(Object renderer, Drawing.RendererAccess draw, Drawing.DrawConfig config,
              Drawing.RuntimeAccess runtime, Object unit, int relation,
              float worldX, float worldY, float screenX, float screenY,
              float scale, float width, float height) throws Throwable {
        if (screenX < -140.0f || screenY < -100.0f
                || screenX > width + 140.0f || screenY > height + 100.0f
                || runtime.currentQueue == null || runtime.queueProgress == null
                || runtime.queueRate == null || runtime.factorySpeed == null
                || runtime.queueAction == null || runtime.productionAction == null
                || runtime.actionForQueue == null) {
            return;
        }

        Object queue = runtime.currentQueue.invoke(unit);
        if (queue == null) return;

        // The native queue also contains building-placement actions. The action's
        // native f() flag is the authoritative distinction: only true means a
        // unit is being produced.
        FactoryState state = states.get(unit);
        if (state == null || state.queue != queue) {
            Object actionId = runtime.queueAction.get(queue);
            Object action = actionId == null ? null
                    : runtime.actionForQueue.invoke(unit, actionId);
            boolean unitProduction = action != null
                    && Boolean.TRUE.equals(runtime.productionAction.invoke(action));
            state = new FactoryState(queue, action, unitProduction);
            states.put(unit, state);
        }
        if (state.action == null || !state.unitProduction) return;

        float progress = runtime.number(runtime.queueProgress.get(queue));
        float rate = runtime.number(runtime.queueRate.get(queue))
                * runtime.number(runtime.factorySpeed.invoke(unit));
        if (rate <= 0.0f || progress < 0.0f || progress >= 1.0f) return;

        // q.b * factory.ca() advances normalized progress per game tick; the
        // native simulation runs at 60 ticks per second.
        float ratePerSecond = rate * 60.0f;
        float totalSeconds = 1.0f / ratePerSecond;
        float remainingSeconds = (1.0f - progress) / ratePerSecond;
        int totalTenths = Math.max(0, Math.round(totalSeconds * 10.0f));
        int remainingTenths = Math.max(0, Math.round(remainingSeconds * 10.0f));
        FactoryLabel label = labels.get(unit);
        if (label == null || label.remainingTenths != remainingTenths
                || label.totalTenths != totalTenths) {
            label = new FactoryLabel(remainingTenths, totalTenths,
                    String.format(Locale.US, "%.1f", remainingTenths / 10.0f),
                    String.format(Locale.US, "(%.1f)", totalTenths / 10.0f));
            labels.put(unit, label);
        }

        Paint paint = relation == 0 ? config.selfFactoryText
                : relation == 1 ? config.enemyFactoryText : config.allyFactoryText;
        // Keep one logical text() overlay anchored at the building center.
        // The target Canvas backend does not interpret '\n' itself, so the
        // renderer helper lays out this one logical value as two baselines.
        if (draw.save != null && draw.restore != null && draw.scale != null
                && scale > 0.0f && scale != 1.0f) {
            draw.save.invoke(renderer);
            try {
                draw.scale.invoke(renderer, scale, scale);
                draw.multilineText(renderer, label.lines, worldX, worldY, paint,
                        LINE_SPACING);
            } finally {
                draw.restore.invoke(renderer);
            }
        } else {
            draw.multilineText(renderer, label.lines, screenX, screenY, paint, LINE_SPACING);
        }
    }

    private static final class FactoryLabel {
        final int remainingTenths;
        final int totalTenths;
        final String remainingText;
        final String totalText;
        final String[] lines;

        FactoryLabel(int remainingTenths, int totalTenths,
                     String remainingText, String totalText) {
            this.remainingTenths = remainingTenths;
            this.totalTenths = totalTenths;
            this.remainingText = remainingText;
            this.totalText = totalText;
            this.lines = new String[]{remainingText, totalText};
        }
    }

    private static final class FactoryState {
        final Object queue;
        final Object action;
        final boolean unitProduction;

        FactoryState(Object queue, Object action, boolean unitProduction) {
            this.queue = queue;
            this.action = action;
            this.unitProduction = unitProduction;
        }
    }
}
