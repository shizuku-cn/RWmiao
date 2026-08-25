package com.shizuku.rwmiao.module.freeselection;

import android.graphics.Paint;
import android.view.MotionEvent;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.support.GameFrameDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_FREE_SELECTION;

/** Free-form map gesture capture and native selection application. */
public final class FreeSelection {
    private static final String TAG = "RWmiao";
    private static final int MAX_POINTS = 256;
    private static final float SAMPLE_DISTANCE = 6.0f;
    private static final int MINIMUM_POINTS = 12;
    private static final float MINIMUM_PERIMETER = 96.0f;
    private static final float MINIMUM_AREA = 1024.0f;
    private static final float CLOSE_DISTANCE = 24.0f;
    private static final float UNIT_TOUCH_DISTANCE = 24.0f;

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
    private boolean pending;
    private boolean cancelNativePending;
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
        refreshSettings();
    }

    public boolean enabled() {
        return host.selectionActionEnabled(KEY_FREE_SELECTION);
    }

    public String titleForSelection() {
        return modeActive ? "关闭自由框选" : "自由框选";
    }

    public void toggleMode() {
        if (!enabled()) return;
        synchronized (pathLock) {
            if (modeActive) {
                modeActive = false;
                pointCount = 0;
                tracking = false;
                cancelNativePending = true;
            } else {
                modeActive = true;
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
            }
            disableHooks();
        }
    }

    private synchronized void ensureHooks() {
        if (touchHook == null && touchMethod != null) {
            touchHook = host.hookExecutable(touchMethod, chain -> {
                Object result = chain.proceed();
                try {
                    captureTouch(chain.getArg(0));
                } catch (Throwable t) {
                    host.log(6, TAG, "Free-selection touch capture failed", t);
                }
                return result;
            });
        }
        if (inputHook == null && inputMethod != null) {
            inputHook = host.hookExecutable(inputMethod, chain -> {
                Object result = chain.proceed();
                try {
                    applyPendingSelection();
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
    }

    private void captureTouch(Object argument) throws Throwable {
        if (!modeActive || !(argument instanceof MotionEvent)) return;
        MotionEvent event = (MotionEvent) argument;
        int action = event.getActionMasked();
        if (event.getPointerCount() != 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            clearGesture();
            return;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            Object engine = host.findEngine(loader);
            Object ui = runtime.ui.get(engine);
            float x = event.getX();
            float y = event.getY();
            if (!runtime.isMapPoint(ui, x, y) || hasUnitAt(engine, x, y)) {
                clearGesture();
                return;
            }
            synchronized (pathLock) {
                pointCount = 0;
                tracking = true;
                addPoint(engine, x, y);
            }
            return;
        }

        if (!tracking) return;
        Object engine = host.findEngine(loader);
        if (action == MotionEvent.ACTION_MOVE) {
            synchronized (pathLock) {
                addPointIfNeeded(engine, event.getX(), event.getY(), false);
            }
        } else if (action == MotionEvent.ACTION_UP) {
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
                }
                pointCount = 0;
                tracking = false;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            clearGesture();
        }
    }

    private void addPointIfNeeded(Object engine, float x, float y, boolean force) throws Throwable {
        if (pointCount == 0 || force
                || FreeSelectionGeometry.distance(screenXs[pointCount - 1],
                screenYs[pointCount - 1], x, y) >= SAMPLE_DISTANCE) {
            addPoint(engine, x, y);
        }
    }

    private void addPoint(Object engine, float screenX, float screenY) throws Throwable {
        float scale = runtime.scale.getFloat(engine);
        if (scale <= 0.0f) scale = 1.0f;
        float worldX = screenX / scale + runtime.cameraX.getFloat(engine);
        float worldY = screenY / scale + runtime.cameraY.getFloat(engine);
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
        if (cancel && runtime.cancel != null) runtime.cancel.invoke(ui);
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
            if (runtime.selectOrCheck != null) {
                runtime.selectOrCheck.invoke(ui, unit);
            } else if (runtime.addSelection != null) {
                runtime.addSelection.invoke(ui, unit);
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
        final Field dead = host.findField(unit, "bX");
        final Field attached = host.findField(unit, "cP");
        final Field x = host.findField(unit, "eq");
        final Field y = host.findField(unit, "er");
        final Field groundOffset = optionalField(unit, "es");
        final Method allUnits = unit.getDeclaredMethod("bn");
        final Method mapActive = host.findCompatibleMethod(uiClass, "a",
                float.class, float.class);
        final Method cancel = host.findNoArgMethod(uiClass, "a");
        final Method clearSelection = host.findNoArgMethod(uiClass, "h");
        final Method selectOrCheck = host.findCompatibleMethod(uiClass, "b", unit);
        final Method addSelection = host.findCompatibleMethod(uiClass, "c", unit);

        RuntimeAccess() throws Throwable {
            allUnits.setAccessible(true);
            if (mapActive == null) throw new NoSuchMethodException("map hit-test method");
            if (clearSelection == null || (selectOrCheck == null && addSelection == null)) {
                throw new NoSuchMethodException("native selection methods");
            }
        }

        boolean isMapPoint(Object uiObject, float x, float y) throws Throwable {
            Object result = mapActive.invoke(uiObject, x, y);
            return Boolean.TRUE.equals(result);
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
