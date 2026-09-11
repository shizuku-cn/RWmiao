package com.shizuku.rwmiao.module;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_FORMATION_BUTTON_COUNT;
import static com.shizuku.rwmiao.config.SettingsContract.MAX_FORMATION_BUTTON_COUNT;
import static com.shizuku.rwmiao.config.SettingsContract.MIN_FORMATION_BUTTON_COUNT;

final class FormationButtons {
    private static final String TAG = "RWmiao";

    private final RWmiaoModule host;
    private final ClassLoader loader;

    private XposedInterface.HookHandle renderHook;

    private Field engineInputField;
    private Field inputPanelField;
    private Field panelEngineField;
    private Field panelGroupsField;
    private Field panelInputField;
    private Field panelFeedbackPaintField;
    private Field panelFeedbackRectField;

    private Field engineSettingsField;
    private Field engineWidthField;
    private Field engineScaleField;
    private Field engineRightField;
    private Field enginePanelWidthField;
    private Field engineRendererField;
    private Field showGroupsField;

    private Field groupUnitsField;
    private Field groupPressedField;
    private Field groupAnimationDField;
    private Field groupAnimationEField;
    private Field groupAnimationFField;
    private Field groupVisibleField;

    private Field inputPaintField;
    private Field inputActionField;
    private Field inputInteractionField;
    private Field inputBusyField;
    private Field inputEmptyLabelField;

    private Method drawButtonMethod;
    private Method feedbackMethod;
    private Method rendererFillMethod;
    private Method animationMethod;
    private Method groupCameraMethod;
    private Method groupAddMethod;
    private Method clearSelectionMethod;
    private Method cancelActionMethod;
    private Method selectUnitMethod;
    private Method resetPointerMethod;

    private int nativeDefault = MIN_FORMATION_BUTTON_COUNT;
    private volatile int requested = MIN_FORMATION_BUTTON_COUNT;
    private volatile boolean visibilityPending = true;
    private volatile boolean disableAfterNextFrame;
    private volatile Object appliedPanel;

    FormationButtons(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        refreshSettings();
    }

    synchronized void refreshSettings() throws Throwable {
        boolean explicitlyConfigured = host.preferenceContains(KEY_FORMATION_BUTTON_COUNT);
        int detected = renderHook == null ? detectNativeDefault() : nativeDefault;
        if (detected >= MIN_FORMATION_BUTTON_COUNT) {
            nativeDefault = detected;
        }

        if (!explicitlyConfigured) {
            requested = nativeDefault;
            deactivateOnNextFrame();
            return;
        }

        int value = clamp(host.preferenceInt(KEY_FORMATION_BUTTON_COUNT, nativeDefault));
        requested = value;
        if (detected >= MIN_FORMATION_BUTTON_COUNT && value == nativeDefault) {
            deactivateOnNextFrame();
            return;
        }

        disableAfterNextFrame = false;
        visibilityPending = true;
        appliedPanel = null;
        if (renderHook == null) {
            installRenderHook();
        }
    }

    int nativeDefaultCount() {
        int detected = renderHook == null ? detectNativeDefault() : nativeDefault;
        return detected < MIN_FORMATION_BUTTON_COUNT ? MIN_FORMATION_BUTTON_COUNT : detected;
    }

    private int clamp(int value) {
        return Math.max(MIN_FORMATION_BUTTON_COUNT,
                Math.min(MAX_FORMATION_BUTTON_COUNT, value));
    }

    private int detectNativeDefault() {
        try {
            Object engine = host.findEngine(loader);
            if (engine == null) return -1;
            if (engineInputField == null) engineInputField = host.findField(engine.getClass(), "bP");
            Object input = engineInputField.get(engine);
            if (input == null) return -1;
            if (inputPanelField == null) inputPanelField = host.findField(input.getClass(), "g");
            Object panel = inputPanelField.get(input);
            if (panel == null) return -1;
            if (panelGroupsField == null) panelGroupsField = host.findField(panel.getClass(), "aA");
            Object value = panelGroupsField.get(panel);
            if (!(value instanceof ArrayList)) return -1;

            Class<?> groupClass = loader.loadClass(host.target("gameFramework.f.av"));
            Field visible = host.findField(groupClass, "g");
            int count = 0;
            ArrayList<?> groups = (ArrayList<?>) value;
            for (int i = 0; i < groups.size() && i < MAX_FORMATION_BUTTON_COUNT; i++) {
                Object group = groups.get(i);
                if (group != null && visible.getBoolean(group)) count++;
                else break;
            }
            return clamp(count);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void installRenderHook() throws Throwable {
        Class<?> panelClass = loader.loadClass(host.target("gameFramework.f.a"));
        Method render = host.findCompatibleMethod(panelClass, "f", float.class);
        if (render == null || render.getReturnType() != void.class) {
            throw new NoSuchMethodException("formation renderer f(float)");
        }

        if (panelEngineField == null) panelEngineField = host.findField(panelClass, "b");
        if (panelGroupsField == null) panelGroupsField = host.findField(panelClass, "aA");
        panelInputField = host.findField(panelClass, "a");
        panelFeedbackPaintField = host.findField(panelClass, "i");
        panelFeedbackRectField = host.findField(panelClass, "s");

        Class<?> groupClass = loader.loadClass(host.target("gameFramework.f.av"));
        groupUnitsField = host.findField(groupClass, "a");
        groupPressedField = host.findField(groupClass, "b");
        groupAnimationDField = host.findField(groupClass, "d");
        groupAnimationEField = host.findField(groupClass, "e");
        groupAnimationFField = host.findField(groupClass, "f");
        groupVisibleField = host.findField(groupClass, "g");
        groupCameraMethod = host.findNoArgMethod(groupClass, "a");
        groupAddMethod = host.findNoArgMethod(groupClass, "b");

        Class<?> inputClass = panelInputField.getType();
        inputPaintField = host.findField(inputClass, "aC");
        inputActionField = host.findField(inputClass, "ac");
        inputInteractionField = host.findField(inputClass, "T");
        inputBusyField = host.findField(inputClass, "I");
        inputEmptyLabelField = host.findField(inputClass, "bN");

        Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
        clearSelectionMethod = host.findNoArgMethod(inputClass, "h");
        cancelActionMethod = host.findNoArgMethod(inputClass, "e");
        resetPointerMethod = host.findNoArgMethod(inputClass, "a");
        selectUnitMethod = host.findCompatibleMethod(inputClass, "c", unitClass);
        if (selectUnitMethod == null) {
            selectUnitMethod = host.findCompatibleMethod(inputClass, "a", unitClass, boolean.class);
        }
        if (groupAddMethod == null || clearSelectionMethod == null || selectUnitMethod == null) {
            throw new NoSuchMethodException("native formation selection methods");
        }

        Class<?> callbackClass = loader.loadClass(host.target("gameFramework.f.a.i"));
        drawButtonMethod = host.findCompatibleMethod(inputClass, "a",
                int.class, int.class, int.class, int.class, String.class,
                boolean.class, int.class, Paint.class, boolean.class, callbackClass);
        if (drawButtonMethod == null) {
            throw new NoSuchMethodException("native formation button helper");
        }

        Class<?> engineClass = loader.loadClass(host.target("gameFramework.k"));
        engineSettingsField = host.findField(engineClass, "bN");
        showGroupsField = host.findField(engineSettingsField.getType(), "showUnitGroups");
        engineWidthField = host.findField(engineClass, "cE");
        engineScaleField = host.findField(engineClass, "cg");
        engineRightField = host.findField(engineClass, "ci");
        enginePanelWidthField = host.findField(engineClass, "cn");
        engineRendererField = host.findField(engineClass, "bL");

        Class<?> rendererClass = engineRendererField.getType();
        rendererFillMethod = host.findCompatibleMethod(rendererClass, "b", Rect.class, Paint.class);
        feedbackMethod = host.findCompatibleMethod(panelClass, "a", int.class, int.class,
                int.class, String.class, String.class, Paint.class, float.class);

        Class<?> animationClass = loader.loadClass(host.target("gameFramework.f"));
        animationMethod = host.findCompatibleMethod(animationClass, "a", float.class, float.class);

        renderHook = host.hookExecutable(render, chain -> {
            Object panel = chain.getThisObject();
            float delta = chain.getArg(0) instanceof Number
                    ? ((Number) chain.getArg(0)).floatValue() : 0.0f;

            if (disableAfterNextFrame) {
                try {
                    applyNativeSlotVisibility(panel, nativeDefault);
                } catch (Throwable t) {
                    host.log(5, TAG, "Failed to restore native formation slots", t);
                }
                Object result = chain.proceed();
                disableAfterNextFrame = false;
                visibilityPending = true;
                appliedPanel = null;
                XposedInterface.HookHandle handle = renderHook;
                renderHook = null;
                if (handle != null) {
                    try { handle.unhook(); } catch (Throwable ignored) { }
                }
                return result;
            }

            Object engine;
            Object settings;
            try {
                engine = panelEngineField.get(panel);
                settings = engine == null ? null : engineSettingsField.get(engine);
            } catch (Throwable t) {
                host.log(5, TAG, "Formation renderer state lookup failed", t);
                return chain.proceed();
            }
            boolean nativeVisible;
            try {
                nativeVisible = settings != null && showGroupsField.getBoolean(settings);
            } catch (Throwable t) {
                host.log(5, TAG, "Formation visibility lookup failed", t);
                return chain.proceed();
            }
            if (!nativeVisible) {
                return chain.proceed();
            }

            if (visibilityPending || appliedPanel != panel) {
                try {
                    applyNativeSlotVisibility(panel, requested);
                } catch (Throwable t) {
                    host.log(5, TAG, "Failed to apply native formation slot visibility", t);
                    return chain.proceed();
                }
            }

            boolean oldVisibility = showGroupsField.getBoolean(settings);
            showGroupsField.setBoolean(settings, false);
            Object result;
            try {
                result = chain.proceed();
            } finally {
                showGroupsField.setBoolean(settings, oldVisibility);
            }
            try {
                drawNativeGroups(panel, delta);
            } catch (Throwable t) {
                host.log(5, TAG, "Native formation strip draw failed", t);
            }
            return result;
        });
    }

    private void applyNativeSlotVisibility(Object panel, int count) throws Throwable {
        Object value = panelGroupsField.get(panel);
        if (!(value instanceof ArrayList)) return;
        ArrayList<?> groups = (ArrayList<?>) value;
        int visibleCount = clamp(count);
        for (int i = 0; i < groups.size() && i < MAX_FORMATION_BUTTON_COUNT; i++) {
            Object group = groups.get(i);
            if (group != null) groupVisibleField.setBoolean(group, i < visibleCount);
        }
        appliedPanel = panel;
        visibilityPending = false;
    }

    private void drawNativeGroups(Object panel, float delta) throws Throwable {
        Object engine = panelEngineField.get(panel);
        Object input = panelInputField.get(panel);
        Object value = panelGroupsField.get(panel);
        if (engine == null || input == null || !(value instanceof ArrayList)) return;

        ArrayList<?> groups = (ArrayList<?>) value;
        int count = Math.min(clamp(requested), Math.min(MAX_FORMATION_BUTTON_COUNT, groups.size()));
        if (count < MIN_FORMATION_BUTTON_COUNT) return;

        float bottom = engineWidthField.getFloat(engine);
        float scale = engineScaleField.getFloat(engine);
        float right = engineRightField.getFloat(engine);
        float stripWidth = enginePanelWidthField.getFloat(engine);
        int y = (int) (bottom - (30.0f * scale));
        int originalBaseX = (int) ((right - stripWidth) + 10.0f);
        int originalStep = ((int) (stripWidth - 20.0f)) / MIN_FORMATION_BUTTON_COUNT;
        if (originalStep <= 0) return;

        int originalBand = originalStep * MIN_FORMATION_BUTTON_COUNT;
        int desiredBand = originalStep * count;
        int maxBand = originalBand * 3;
        int actualBand = Math.min(desiredBand, maxBand);
        int baseX = originalBaseX - Math.max(0, actualBand - originalBand);
        int step = actualBand / count;
        int width = step - 5;
        int height = (int) (31.0f * scale);
        if (step <= 0 || width <= 0 || height <= 0) return;

        Paint inputPaint = (Paint) inputPaintField.get(input);
        if (inputPaint == null) return;
        float animationStep = 0.01f * delta;

        for (int visualPosition = 0; visualPosition < count; visualPosition++) {
            int groupIndex = count - 1 - visualPosition;
            Object group = groups.get(groupIndex);
            if (group == null) continue;

            updateAnimation(group, animationStep);
            int color = Color.argb(50,
                    (int) (100.0f + groupAnimationFField.getFloat(group) * 100.0f),
                    (int) (100.0f + groupAnimationEField.getFloat(group) * 100.0f),
                    (int) (100.0f + groupAnimationDField.getFloat(group) * 100.0f));
            String label = groupLabel(input, group, groupIndex);

            int x = baseX + visualPosition * step;
            boolean hit = (Boolean) drawButtonMethod.invoke(input, x, y, width, height,
                    label, true, color, inputPaint, false, (Object) null);
            boolean blocked = inputActionField.get(input) != null
                    || inputInteractionField.getBoolean(input);
            boolean pressed = hit && !blocked;
            handleNativeInteraction(panel, engine, input, group, x, y, width, height,
                    delta, pressed);
        }
    }

    private void updateAnimation(Object group, float amount) throws Throwable {
        if (animationMethod == null) {
            groupAnimationDField.setFloat(group, approach(groupAnimationDField.getFloat(group), amount));
            groupAnimationEField.setFloat(group, approach(groupAnimationEField.getFloat(group), amount));
            groupAnimationFField.setFloat(group, approach(groupAnimationFField.getFloat(group), amount));
            return;
        }
        groupAnimationDField.setFloat(group,
                ((Number) animationMethod.invoke(null, groupAnimationDField.getFloat(group), amount)).floatValue());
        groupAnimationEField.setFloat(group,
                ((Number) animationMethod.invoke(null, groupAnimationEField.getFloat(group), amount)).floatValue());
        groupAnimationFField.setFloat(group,
                ((Number) animationMethod.invoke(null, groupAnimationFField.getFloat(group), amount)).floatValue());
    }

    private float approach(float value, float amount) {
        if (value > amount) return value - amount;
        if (value < -amount) return value + amount;
        return 0.0f;
    }

    private String groupLabel(Object input, Object group, int index) throws Throwable {
        Object units = groupUnitsField.get(group);
        if (units instanceof ArrayList && !((ArrayList<?>) units).isEmpty()) {
            return String.valueOf(((ArrayList<?>) units).size());
        }
        return inputEmptyLabelField.getBoolean(input) ? "Empty" : "(" + (index + 1) + ")";
    }

    private void handleNativeInteraction(Object panel, Object engine, Object input,
                                         Object group, int x, int y, int width, int height,
                                         float delta, boolean pressed) throws Throwable {
        if (pressed) {
            groupPressedField.setFloat(group, groupPressedField.getFloat(group) + delta);
            if (resetPointerMethod != null) resetPointerMethod.invoke(input);
            drawNativeFeedback(panel, engine, groupPressedField.getFloat(group),
                    x, y, width, height);
            return;
        }

        float pressedFor = groupPressedField.getFloat(group);
        if (pressedFor != 0.0f && !inputBusyField.getBoolean(input)) {
            if (pressedFor > 100.0f) {
                clearGroup(group);
                groupAddMethod.invoke(group);
                groupAnimationFField.setFloat(group, 1.0f);
            } else if (pressedFor > 50.0f) {
                groupAddMethod.invoke(group);
                selectGroup(input, group);
                groupAnimationEField.setFloat(group, 1.0f);
            } else if (hasUnits(group)) {
                selectGroup(input, group);
                groupAnimationDField.setFloat(group, 1.0f);
            } else {
                clearGroup(group);
                groupAddMethod.invoke(group);
                groupAnimationEField.setFloat(group, 1.0f);
            }
        }
        groupPressedField.setFloat(group, 0.0f);
    }

    private void drawNativeFeedback(Object panel, Object engine, float pressedFor,
                                    int x, int y, int width, int height) throws Throwable {
        if (feedbackMethod == null || rendererFillMethod == null) return;
        Paint paint = (Paint) panelFeedbackPaintField.get(panel);
        Rect rect = (Rect) panelFeedbackRectField.get(panel);
        Object renderer = engineRendererField.get(engine);
        if (paint == null || rect == null || renderer == null) return;

        paint.reset();
        paint.setColor(Color.argb(120, 200, 0, 0));
        float progress;
        String title;
        String subtitle;
        if (pressedFor < 50.0f) {
            progress = pressedFor / 50.0f;
            paint.setColor(Color.argb((int) (150.0f + (40.0f * progress)), 0, 200, 0));
            title = "Select Group";
            subtitle = "(Hold for more..)";
        } else if (pressedFor < 100.0f) {
            progress = (pressedFor - 50.0f) / 50.0f;
            paint.setColor(Color.argb((int) (150.0f + (40.0f * progress)), 200, 0, 0));
            title = "Add to Group";
            subtitle = "(Hold for more..)";
        } else {
            progress = 0.0f;
            title = "Replace Group";
            subtitle = "";
        }
        feedbackMethod.invoke(panel, x, y, width, title, subtitle, paint, progress);

        int feedbackHeight = height;
        rect.set(x, (int) ((y + feedbackHeight) - (feedbackHeight * progress)),
                x + width, feedbackHeight + y);
        rendererFillMethod.invoke(renderer, rect, paint);
    }

    private boolean hasUnits(Object group) throws IllegalAccessException {
        Object units = groupUnitsField.get(group);
        return units instanceof ArrayList && !((ArrayList<?>) units).isEmpty();
    }

    private void clearGroup(Object group) throws IllegalAccessException {
        Object units = groupUnitsField.get(group);
        if (units instanceof ArrayList) ((ArrayList<?>) units).clear();
    }

    private void selectGroup(Object input, Object group) throws Throwable {
        if (cancelActionMethod != null) cancelActionMethod.invoke(input);
        clearSelectionMethod.invoke(input);
        Object units = groupUnitsField.get(group);
        if (units instanceof ArrayList) {
            for (Object unit : (ArrayList<?>) units) {
                if (unit != null) {
                    if (selectUnitMethod.getParameterCount() == 1) {
                        selectUnitMethod.invoke(input, unit);
                    } else {
                        selectUnitMethod.invoke(input, unit, false);
                    }
                }
            }
        }
        if (groupCameraMethod != null) groupCameraMethod.invoke(group);
    }

    private void deactivateOnNextFrame() {
        if (renderHook == null) {
            disableAfterNextFrame = false;
            visibilityPending = true;
            appliedPanel = null;
            return;
        }
        disableAfterNextFrame = true;
    }
}
