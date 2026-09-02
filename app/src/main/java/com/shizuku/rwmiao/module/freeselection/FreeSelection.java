package com.shizuku.rwmiao.module.freeselection;

import android.graphics.Paint;
import android.view.MotionEvent;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.support.GameFrameDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_FREE_SELECTION;

public final class FreeSelection {
    private static final String TAG = "RWmiao";
    private static final int MAX_POINTS = 256;
    private static final float SAMPLE_DISTANCE = 6.0f;
    private static final int MINIMUM_POINTS = 12;
    private static final float MINIMUM_PERIMETER = 96.0f;
    private static final float MINIMUM_AREA = 1024.0f;
    private static final float CLOSE_DISTANCE = 64.0f;
    private static final float UNIT_TOUCH_DISTANCE = 24.0f;
    private static final float CAMERA_BLOCK_DISTANCE = 1.0f;
    private static final float CAMERA_ZOOM_ZONE_MIN_WIDTH = 96.0f;
    private static final float ACTION_PANEL_MIN_HEIGHT = 180.0f;
    private static final float ACTION_PANEL_MAX_HEIGHT = 300.0f;
    private static final int TOUCH_NOT_TRACKED = 0;
    private static final int TOUCH_TRACKED = 1;
    private static final int TOUCH_COMPLETED = 2;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Object pathLock = new Object();
    private final float[] screenXs = new float[MAX_POINTS];
    private final float[] screenYs = new float[MAX_POINTS];
    private final float[] worldXs = new float[MAX_POINTS];
    private final float[] worldYs = new float[MAX_POINTS];
    private final float[] pendingWorldXs = new float[MAX_POINTS];
    private final float[] pendingWorldYs = new float[MAX_POINTS];
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RuntimeAccess runtime;
    private RendererAccess rendererAccess;
    private Method touchMethod;
    private Method inputMethod;
    private Method drawMethod;
    private XposedInterface.HookHandle touchHook;
    private XposedInterface.HookHandle inputHook;
    private GameFrameDispatcher.Registration drawRegistration;
    private volatile boolean modeActive;
    private volatile boolean tracking;
    private Object frozenCameraEngine;
    private float frozenCameraX;
    private float frozenCameraY;
    private boolean cameraFrozen;
    private float gestureCameraX;
    private float gestureCameraY;
    private float gestureScale;
    private boolean pending;
    private boolean cancelNativePending;
    private boolean nativeGestureSuppressed;
    private int pointCount;
    private int pendingCount;

    public FreeSelection(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeWidth(2.0f);
        pathPaint.setColor(0xDD66E08A);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void install() throws Throwable {
        runtime = new RuntimeAccess();
        Class<?> input = loader.loadClass(host.target("appFramework.en"));
        touchMethod = host.findCompatibleMethod(input, "a", MotionEvent.class);
        if (touchMethod == null) throw new NoSuchMethodException("appFramework.en.a(MotionEvent)");
        inputMethod = host.findCompatibleMethod(runtime.uiClass, "a", float.class);
        if (inputMethod == null) throw new NoSuchMethodException("selection input method");
        drawMethod = runtime.uiClass.getDeclaredMethod("b", float.class);
        drawMethod.setAccessible(true);
        host.addGameResyncListener(this::onGameResync);
        refreshSettings();
    }

    private void onGameResync() {
        synchronized (pathLock) {
            tracking = false;
            pending = false;
            cancelNativePending = false;
            nativeGestureSuppressed = false;
            pointCount = 0;
            pendingCount = 0;
            cameraFrozen = false;
            frozenCameraEngine = null;
        }
    }

    public boolean enabled() {
        return host.selectionActionEnabled(KEY_FREE_SELECTION);
    }

    public String titleForSelection() {
        return modeActive ? "关闭框选" : "自由框选";
    }

    public void toggleMode() {
        if (!enabled()) return;
        synchronized (pathLock) {
            if (modeActive) {
                modeActive = false;
                pointCount = 0;
                tracking = false;
                nativeGestureSuppressed = false;
                cancelNativePending = true;
            } else {
                modeActive = true;
                nativeGestureSuppressed = false;
            }
        }
    }

    public void refreshSettings() {
        if (enabled()) {
            ensureHooks();
        } else {
            synchronized (pathLock) {
                modeActive = false;
                pointCount = 0;
                tracking = false;
                pending = false;
                pendingCount = 0;
                cancelNativePending = false;
                nativeGestureSuppressed = false;
            }
            disableHooks();
        }
    }

    private synchronized void ensureHooks() {
        if (touchHook == null && touchMethod != null) {
            touchHook = host.hookExecutable(touchMethod, chain -> {
                MotionEvent event = chain.getArg(0) instanceof MotionEvent
                        ? (MotionEvent) chain.getArg(0) : null;
                try {
                    beginCameraFreeze(event);
                } catch (Throwable t) {
                    host.log(5, TAG, "Free-selection camera freeze setup failed", t);
                }
                Object result;
                boolean suppressNative = false;
                try {
                    suppressNative = shouldSuppressNativeTouch(event);
                } catch (Throwable t) {
                    host.log(5, TAG, "Free-selection native touch guard failed", t);
                }
                if (suppressNative) {
                    result = consumedTouchResult();
                } else {
                    try {
                        result = chain.proceed();
                    } finally {
                        restoreFrozenCamera();
                    }
                }
                int captureState = TOUCH_NOT_TRACKED;
                try {
                    captureState = captureTouch(event);
                    if (captureState == TOUCH_TRACKED
                            && event != null
                            && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        result = consumedTouchResult();
                    }
                } catch (Throwable t) {
                    host.log(6, TAG, "Free-selection touch capture failed", t);
                } finally {
                    endCameraFreeze(event);
                    resetNativeGestureState(event);
                }
                if (captureState == TOUCH_COMPLETED) {
                    try {
                        applyPendingSelection();
                    } catch (Throwable t) {
                        host.log(6, TAG, "Free-selection completed gesture failed", t);
                    }
                }
                return result;
            });
        }
        if (inputHook == null && inputMethod != null) {
            inputHook = host.hookExecutable(inputMethod, chain -> {
                Object result;
                if (isNativeGestureSuppressed()) {
                    result = consumedInputResult();
                } else {
                    result = chain.proceed();
                }
                try {
                    applyPendingSelection();
                    restoreFrozenCamera();
                } catch (Throwable t) {
                    host.log(6, TAG, "Free-selection native selection failed", t);
                }
                return result;
            });
        }
        if (drawRegistration == null && drawMethod != null) {
            drawRegistration = host.frameDispatcher().register(drawMethod,
                    (renderer, delta) -> drawPath());
        }
    }

    private synchronized void disableHooks() {
        XposedInterface.HookHandle touch = touchHook;
        touchHook = null;
        if (touch != null) {
            try { touch.unhook(); } catch (Throwable ignored) { }
        }
        XposedInterface.HookHandle input = inputHook;
        inputHook = null;
        if (input != null) {
            try { input.unhook(); } catch (Throwable ignored) { }
        }
        GameFrameDispatcher.Registration registration = drawRegistration;
        drawRegistration = null;
        if (registration != null) registration.close();
        synchronized (pathLock) {
            cameraFrozen = false;
            frozenCameraEngine = null;
            nativeGestureSuppressed = false;
        }
    }

    private boolean shouldSuppressNativeTouch(MotionEvent event) throws Throwable {
        if (!modeActive || event == null) return false;
        synchronized (pathLock) {
            if (nativeGestureSuppressed) return true;
        }
        int action = event.getActionMasked();
        if (event.getPointerCount() != 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP
                || action != MotionEvent.ACTION_MOVE) {
            return false;
        }
        if (!hasMovedEnough(event)) return false;
        synchronized (pathLock) {
            if (nativeGestureSuppressed) return true;
            nativeGestureSuppressed = true;
        }
        try {
            cancelNativeInput();
        } catch (Throwable t) {
            host.log(5, TAG, "Free-selection native input cancel failed", t);
        }
        return true;
    }

    private boolean hasMovedEnough(MotionEvent event) {
        synchronized (pathLock) {
            return tracking && pointCount > 0
                    && FreeSelectionGeometry.distance(screenXs[pointCount - 1],
                    screenYs[pointCount - 1], event.getX(), event.getY()) >= CAMERA_BLOCK_DISTANCE;
        }
    }

    private void cancelNativeInput() throws Throwable {
        Object engine = host.findEngine(loader);
        Object ui = runtime.ui.get(engine);
        if (runtime.cancelAction != null) runtime.cancelAction.invoke(ui);
        if (runtime.resetPointer != null) runtime.resetPointer.invoke(ui);
        restoreFrozenCamera();
    }

    private Object consumedTouchResult() {
        Class<?> type = touchMethod == null ? boolean.class : touchMethod.getReturnType();
        return defaultResult(type, true);
    }

    private Object consumedInputResult() {
        Class<?> type = inputMethod == null ? void.class : inputMethod.getReturnType();
        return defaultResult(type, false);
    }

    private Object defaultResult(Class<?> type, boolean booleanTrue) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.valueOf(booleanTrue);
        if (type == char.class) return Character.valueOf('\0');
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0.0f);
        if (type == double.class) return Double.valueOf(0.0d);
        return null;
    }

    private boolean isNativeGestureSuppressed() {
        synchronized (pathLock) {
            return nativeGestureSuppressed;
        }
    }

    private void resetNativeGestureState(MotionEvent event) {
        if (event == null) return;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
        synchronized (pathLock) {
            nativeGestureSuppressed = false;
        }
    }

    private void beginCameraFreeze(MotionEvent event) throws Throwable {
        if (!modeActive || event == null) return;
        synchronized (pathLock) {
            if (cameraFrozen && event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
            Object engine = host.findEngine(loader);
            frozenCameraEngine = engine;
            frozenCameraX = runtime.cameraX.getFloat(engine);
            frozenCameraY = runtime.cameraY.getFloat(engine);
            cameraFrozen = true;
        }
    }

    private void restoreFrozenCamera() {
        synchronized (pathLock) {
            if (!cameraFrozen || frozenCameraEngine == null) return;
            try {
                runtime.cameraX.setFloat(frozenCameraEngine, frozenCameraX);
                runtime.cameraY.setFloat(frozenCameraEngine, frozenCameraY);
            } catch (Throwable t) {
                host.log(5, TAG, "Free-selection camera restore failed", t);
            }
        }
    }

    private void endCameraFreeze(MotionEvent event) {
        if (event == null) return;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
        synchronized (pathLock) {
            cameraFrozen = false;
            frozenCameraEngine = null;
        }
    }

    private int captureTouch(Object argument) throws Throwable {
        if (!modeActive || !(argument instanceof MotionEvent)) return TOUCH_NOT_TRACKED;
        MotionEvent event = (MotionEvent) argument;
        int action = event.getActionMasked();
        if (event.getPointerCount() != 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            clearGesture();
            return TOUCH_NOT_TRACKED;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            Object engine = host.findEngine(loader);
            Object ui = runtime.ui.get(engine);
            float x = event.getX();
            float y = event.getY();
            if (!runtime.isMapPoint(ui, x, y)
                || runtime.isNativeCameraZoomPoint(engine, x, y)
                || runtime.isNativeActionPoint(engine, x, y)
                || hasUnitAt(engine, x, y)) {
                clearGesture();
                return TOUCH_NOT_TRACKED;
            }
            synchronized (pathLock) {
                pointCount = 0;
                tracking = true;
                gestureCameraX = runtime.cameraX.getFloat(engine);
                gestureCameraY = runtime.cameraY.getFloat(engine);
                gestureScale = runtime.scale.getFloat(engine);
                if (gestureScale <= 0.0f) gestureScale = 1.0f;
                addPoint(engine, x, y);
            }
            return TOUCH_TRACKED;
        }

        if (!tracking) return TOUCH_NOT_TRACKED;
        Object engine = host.findEngine(loader);
        if (action == MotionEvent.ACTION_MOVE) {
            synchronized (pathLock) {
                addPointIfNeeded(engine, event.getX(), event.getY(), false);
            }
            return TOUCH_TRACKED;
        } else if (action == MotionEvent.ACTION_UP) {
            boolean completed = false;
            synchronized (pathLock) {
                addPointIfNeeded(engine, event.getX(), event.getY(), true);
                if (FreeSelectionGeometry.isClosed(screenXs, screenYs, pointCount,
                        CLOSE_DISTANCE, MINIMUM_POINTS, MINIMUM_PERIMETER, MINIMUM_AREA)) {
                    pendingCount = pointCount;
                    System.arraycopy(worldXs, 0, pendingWorldXs, 0, pointCount);
                    System.arraycopy(worldYs, 0, pendingWorldYs, 0, pointCount);
                    pending = true;
                    cancelNativePending = true;
                    modeActive = false;
                    completed = true;
                }
                pointCount = 0;
                tracking = false;
            }
            return completed ? TOUCH_COMPLETED : TOUCH_TRACKED;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            clearGesture();
        }
        return TOUCH_NOT_TRACKED;
    }

    private void addPointIfNeeded(Object engine, float x, float y, boolean force) throws Throwable {
        if (pointCount == 0 || force
                || FreeSelectionGeometry.distance(screenXs[pointCount - 1],
                screenYs[pointCount - 1], x, y) >= SAMPLE_DISTANCE) {
            addPoint(engine, x, y);
        }
    }

    private void addPoint(Object engine, float screenX, float screenY) throws Throwable {
        float scale;
        float cameraX;
        float cameraY;
        synchronized (pathLock) {
            scale = tracking ? gestureScale : runtime.scale.getFloat(engine);
            cameraX = tracking ? gestureCameraX : runtime.cameraX.getFloat(engine);
            cameraY = tracking ? gestureCameraY : runtime.cameraY.getFloat(engine);
        }
        if (scale <= 0.0f) scale = 1.0f;
        float worldX = screenX / scale + cameraX;
        float worldY = screenY / scale + cameraY;
        if (pointCount >= MAX_POINTS) {
            int last = MAX_POINTS - 1;
            screenXs[last] = screenX;
            screenYs[last] = screenY;
            worldXs[last] = worldX;
            worldYs[last] = worldY;
            return;
        }
        screenXs[pointCount] = screenX;
        screenYs[pointCount] = screenY;
        worldXs[pointCount] = worldX;
        worldYs[pointCount] = worldY;
        pointCount++;
    }

    private boolean hasUnitAt(Object engine, float screenX, float screenY) throws Throwable {
        float scale = runtime.scale.getFloat(engine);
        if (scale <= 0.0f) scale = 1.0f;
        Object all = runtime.allUnits.invoke(null);
        if (!(all instanceof Iterable)) return false;
        for (Object unit : (Iterable<?>) all) {
            if (unit == null || !runtime.battleUnit.isInstance(unit)
                    || runtime.dead.getBoolean(unit) || runtime.attached.get(unit) != null) {
                continue;
            }
            float unitX = (runtime.x.getFloat(unit) - runtime.cameraX.getFloat(engine)) * scale;
            float unitY = (groundY(unit) - runtime.cameraY.getFloat(engine)) * scale;
            if (FreeSelectionGeometry.distance(unitX, unitY, screenX, screenY)
                    <= UNIT_TOUCH_DISTANCE) return true;
        }
        return false;
    }

    private float groundY(Object unit) throws IllegalAccessException {
        return runtime.groundOffset == null
                ? runtime.y.getFloat(unit)
                : runtime.y.getFloat(unit) - runtime.groundOffset.getFloat(unit);
    }

    private void applyPendingSelection() throws Throwable {
        int count;
        boolean cancel;
        synchronized (pathLock) {
            if (!pending && !cancelNativePending) return;
            count = pending ? pendingCount : 0;
            pending = false;
            pendingCount = 0;
            cancel = cancelNativePending;
            cancelNativePending = false;
        }
        Object engine = host.findEngine(loader);
        Object ui = runtime.ui.get(engine);
        if (cancel && runtime.cancelAction != null) runtime.cancelAction.invoke(ui);
        if (count == 0) return;
        if (runtime.clearSelection == null) {
            host.log(5, TAG, "Native selection clear method was not found");
            return;
        }
        runtime.clearSelection.invoke(ui);
        Object all = runtime.allUnits.invoke(null);
        if (!(all instanceof Iterable)) return;
        for (Object unit : (Iterable<?>) all) {
            if (unit == null || !runtime.battleUnit.isInstance(unit)
                    || runtime.dead.getBoolean(unit) || runtime.attached.get(unit) != null) {
                continue;
            }
            if (!FreeSelectionGeometry.contains(pendingWorldXs, pendingWorldYs, count,
                    runtime.x.getFloat(unit), groundY(unit))) continue;
            if (runtime.addSelection != null) {
                runtime.addSelection.invoke(ui, unit);
            } else if (runtime.addSelectionWithFlag != null) {
                runtime.addSelectionWithFlag.invoke(ui, unit, false);
            }
        }
    }

    private void clearGesture() {
        synchronized (pathLock) {
            pointCount = 0;
            tracking = false;
        }
    }

    private void drawPath() {
        if (!modeActive) return;
        synchronized (pathLock) {
            if (pointCount < 2) return;
            try {
                Object engine = host.findEngine(loader);
                Object targetRenderer = runtime.renderer.get(engine);
                if (targetRenderer == null) return;
                RendererAccess draw = rendererAccess;
                if (draw == null || draw.type != targetRenderer.getClass()) {
                    draw = new RendererAccess(targetRenderer.getClass());
                    rendererAccess = draw;
                }
                if (draw.line == null) return;
                if (draw.save != null) draw.save.invoke(targetRenderer);
                try {
                    for (int i = 1; i < pointCount; i++) {
                        draw.line.invoke(targetRenderer, screenXs[i - 1], screenYs[i - 1],
                                screenXs[i], screenYs[i], pathPaint);
                    }
                    if (FreeSelectionGeometry.distance(screenXs[0], screenYs[0],
                            screenXs[pointCount - 1], screenYs[pointCount - 1]) <= CLOSE_DISTANCE) {
                        draw.line.invoke(targetRenderer, screenXs[pointCount - 1],
                                screenYs[pointCount - 1], screenXs[0], screenYs[0], pathPaint);
                    }
                } finally {
                    if (draw.restore != null) draw.restore.invoke(targetRenderer);
                }
            } catch (Throwable t) {
                host.log(6, TAG, "Free-selection path drawing failed", t);
            }
        }
    }

    final class RuntimeAccess {
        final Class<?> uiClass = loader.loadClass(host.target("gameFramework.f.i"));
        final Class<?> unit = loader.loadClass(host.target("game.units.ce"));
        final Class<?> battleUnit = loader.loadClass(host.target("game.units.bp"));
        final Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        final Field ui = host.findField(engine, "bP");
        final Field renderer = host.findField(engine, "bL");
        final Field cameraX = host.findField(engine, "ct");
        final Field cameraY = host.findField(engine, "cu");
        final Field scale = host.findField(engine, "cU");
        final Field screenWidth = optionalField(engine, "cC");
        final Field screenHeight = host.findField(engine, "cE");
        final Field screenRight = optionalField(engine, "ci");
        final Field actionPanelWidth = optionalField(engine, "cn");
        final Field uiScale = optionalField(engine, "cg");
        final Field dead = host.findField(unit, "bX");
        final Field attached = host.findField(unit, "cP");
        final Field x = host.findField(unit, "eq");
        final Field y = host.findField(unit, "er");
        final Field groundOffset = optionalField(unit, "es");
        final Method allUnits = unit.getDeclaredMethod("bn");
        final Method mapActive = host.findCompatibleMethod(uiClass, "a",
                float.class, float.class);
        final Method cancelAction = host.findNoArgMethod(uiClass, "e");
        final Method resetPointer = host.findNoArgMethod(uiClass, "a");
        final Method clearSelection = host.findNoArgMethod(uiClass, "h");
        final Method addSelection = host.findCompatibleMethod(uiClass, "c", unit);
        final Method addSelectionWithFlag = host.findCompatibleMethod(uiClass, "a",
                unit, boolean.class);

        RuntimeAccess() throws Throwable {
            allUnits.setAccessible(true);
            if (mapActive == null) throw new NoSuchMethodException("map hit-test method");
            if (clearSelection == null || (addSelection == null && addSelectionWithFlag == null)) {
                throw new NoSuchMethodException("native selection methods");
            }
        }

        boolean isMapPoint(Object uiObject, float x, float y) throws Throwable {
            Object result = mapActive.invoke(uiObject, x, y);
            return Boolean.TRUE.equals(result);
        }

        boolean isNativeActionPoint(Object engineObject, float x, float y)
                throws IllegalAccessException {
            float bottom = screenHeight.getFloat(engineObject);
            float right = screenRight == null
                    ? (screenWidth == null ? bottom : screenWidth.getFloat(engineObject))
                    : screenRight.getFloat(engineObject);
            float panelWidth = actionPanelWidth == null
                    ? 0.0f
                    : actionPanelWidth.getFloat(engineObject);
            float scaleValue = uiScale == null ? 1.0f : uiScale.getFloat(engineObject);
            if (scaleValue <= 0.0f) scaleValue = 1.0f;
            if (right <= 0.0f || bottom <= 0.0f) return false;
            float fallbackWidth = Math.max(260.0f * scaleValue, right * 0.30f);
            panelWidth = Math.max(panelWidth, fallbackWidth);
            float panelHeight = Math.max(ACTION_PANEL_MIN_HEIGHT * scaleValue,
                    Math.min(ACTION_PANEL_MAX_HEIGHT * scaleValue, bottom * 0.35f));
            float left = right - panelWidth - 12.0f * scaleValue;
            float top = bottom - panelHeight;
            return x >= left && x <= right + 12.0f * scaleValue
                    && y >= top && y <= bottom + 12.0f * scaleValue;
        }

        boolean isNativeCameraZoomPoint(Object engineObject, float x, float y)
                throws IllegalAccessException {
            float width = screenWidth == null
                    ? screenHeight.getFloat(engineObject)
                    : screenWidth.getFloat(engineObject);
            float scaleValue = uiScale == null ? 1.0f : uiScale.getFloat(engineObject);
            if (scaleValue <= 0.0f) scaleValue = 1.0f;
            float zoneWidth = Math.max(CAMERA_ZOOM_ZONE_MIN_WIDTH * scaleValue,
                    width * 0.05f);
            return x >= 0.0f && x <= zoneWidth
                    && y >= 0.0f && y <= screenHeight.getFloat(engineObject);
        }

        private Field optionalField(Class<?> type, String name) {
            try {
                return host.findField(type, name);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    final class RendererAccess {
        final Class<?> type;
        final Method save;
        final Method restore;
        final Method line;

        RendererAccess(Class<?> type) {
            this.type = type;
            save = host.findNoArgMethod(type, "i");
            restore = host.findNoArgMethod(type, "j");
            line = host.findCompatibleMethod(type, "a", float.class, float.class,
                    float.class, float.class, Paint.class);
        }
    }
}
