package com.shizuku.rwmiao.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;

import com.shizuku.rwmiao.config.SettingsContract;
import com.shizuku.rwmiao.module.smartbuild.SmartBuildSerialization;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;

public final class BatchPlacement {
    private static final String TAG = "RWmiao";
    private static final int NATIVE_SUCCESS_LIMIT = 29;
    private static final int FREE_BUILD_QUEUE_LIMIT = 28;
    private static final int MAX_CONTINUATIONS = 1024;
    private static final int INITIAL_DYNAMIC_WAYPOINT_CAPACITY = 64;
    private static final int MAX_DYNAMIC_WAYPOINTS = 4096;
    private static final float DEFAULT_GRID_HALF_SIZE = 10.0f;
    private static final float POINT_EPSILON_SQUARED = 0.01f;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Object hooksLock = new Object();
    private final ThreadLocal<Boolean> nativeReentry = new ThreadLocal<>();
    private SmartBuildSerialization smartBuildSerialization;

    private volatile boolean enabled;
    private volatile boolean unlimitedEnabled;
    private volatile boolean smartBuildEnabled;
    private volatile boolean freeBuildQueueExpansion;
    private boolean hookInstalled;
    private XposedInterface.HookHandle placementHook;
    private XposedInterface.HookHandle waypointHook;
    private XposedInterface.HookHandle waypointAllocateHook;
    private Method placementMethod;
    private Method waypointAllocateMethod;
    private Field blockoutXField;
    private Field blockoutYField;
    private Field waypointCountField;
    private Field waypointQueueField;
    private Field waypointTypeField;
    private Method waypointCopyMethod;
    private Method waypointStateResetMethod;
    private Constructor<?> waypointConstructor;

    public BatchPlacement(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        refreshSettings();
    }

    void refreshSettings() throws Throwable {
        synchronized (hooksLock) {
            boolean shouldEnable = readBoolean(
                    SettingsContract.KEY_BATCH_PLACEMENT_UNLIMITED, false);
            boolean shouldEnableSmartBuild = readBoolean(
                    SettingsContract.KEY_SMART_BUILD_SERIALIZATION, false);
            unlimitedEnabled = shouldEnable;
            smartBuildEnabled = shouldEnableSmartBuild;
            if (shouldEnableSmartBuild && smartBuildSerialization == null) {
                smartBuildSerialization = new SmartBuildSerialization(host, loader);
            }
            if ((shouldEnable || shouldEnableSmartBuild || freeBuildQueueExpansion)
                    && !hookInstalled) {
                try {
                    installHook();
                    hookInstalled = true;
                    enabled = true;
                } catch (Throwable t) {
                    uninstallLocked();
                    throw t;
                }
            } else if (!shouldEnable && !shouldEnableSmartBuild
                    && !freeBuildQueueExpansion && hookInstalled) {
                uninstallLocked();
            } else {
                enabled = shouldEnable || shouldEnableSmartBuild || freeBuildQueueExpansion;
            }
            if (smartBuildSerialization != null) {
                smartBuildSerialization.refreshSettings(shouldEnableSmartBuild);
            }
        }
    }

    public void setFreeBuildQueueExpansion(boolean enabledForFreeBuild) throws Throwable {
        synchronized (hooksLock) {
            freeBuildQueueExpansion = enabledForFreeBuild;
            boolean shouldInstall = unlimitedEnabled || smartBuildEnabled
                    || freeBuildQueueExpansion;
            if (shouldInstall && !hookInstalled) {
                try {
                    installHook();
                    hookInstalled = true;
                } catch (Throwable t) {
                    uninstallLocked();
                    throw t;
                }
            } else if (!shouldInstall && hookInstalled) {
                uninstallLocked();
            }
            enabled = shouldInstall;
        }
    }

    private void installHook() throws Throwable {
        Class<?> placementClass = loader.loadClass(host.target("gameFramework.f.i"));
        Class<?> blockoutClass = loader.loadClass(host.target("game.units.bp"));
        Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
        Class<?> waypointClass = loader.loadClass(host.target("game.units.en"));
        placementMethod = host.findCompatibleMethod(
                placementClass,
                "a",
                blockoutClass,
                float.class,
                float.class,
                float.class,
                float.class,
                boolean.class,
                ArrayList.class,
                unitClass);
        if (placementMethod == null) {
            throw new NoSuchMethodException(
                    "找不到原生批量放置方法 i.a(bp,float,float,float,float,boolean,ArrayList,ce)");
        }
        blockoutXField = host.findField(blockoutClass, "eq");
        blockoutYField = host.findField(blockoutClass, "er");

        Method waypointMethod = null;
        try {
            waypointMethod = host.findCompatibleMethod(
                    blockoutClass, "b", waypointClass);
            waypointCountField = host.findField(blockoutClass, "O");
            waypointQueueField = host.findField(blockoutClass, "Q");
            try {
                waypointTypeField = host.findField(waypointClass, "a");
            } catch (Throwable ignored) {
                waypointTypeField = null;
            }
            waypointCopyMethod = host.findCompatibleMethod(
                    waypointClass, "c", waypointClass);
            waypointStateResetMethod = host.findNoArgMethod(blockoutClass, "L");
            waypointAllocateMethod = host.findNoArgMethod(blockoutClass, "an");
            waypointConstructor = waypointClass.getDeclaredConstructor();
            waypointConstructor.setAccessible(true);
            if (waypointCopyMethod == null || waypointStateResetMethod == null) {
                throw new NoSuchMethodException(
                        "找不到单位批量放置队列复制或状态重置方法");
            }
        } catch (Throwable t) {
            waypointMethod = null;
            waypointAllocateMethod = null;
            waypointCountField = null;
            waypointQueueField = null;
            waypointTypeField = null;
            waypointCopyMethod = null;
            waypointStateResetMethod = null;
            waypointConstructor = null;
            host.log(4, TAG, "单位队列扩展 Hook 不可用，保留批量预览/释放 Hook", t);
        }

        placementHook = host.hookExecutable(placementMethod, chain -> {
            if (Boolean.TRUE.equals(nativeReentry.get()) || !enabled) {
                return chain.proceed();
            }
            boolean preview = Boolean.TRUE.equals(chain.getArg(5));
            Object rawPoints = chain.getArg(6);
            Object blockout = chain.getArg(0);
            if (blockout == null || (!preview && !(rawPoints instanceof ArrayList))) {
                return chain.proceed();
            }

            float originalX = blockoutXField.getFloat(blockout);
            float originalY = blockoutYField.getFloat(blockout);
            Object result = chain.proceed();
            try {
                Object[] args = chainArgs(chain);
                if (preview) {
                    if (unlimitedEnabled) {
                        extendPreview(chain.getThisObject(), args, blockout);
                    }
                } else {
                    if (unlimitedEnabled) {
                        extendLine(chain.getThisObject(), args, (ArrayList<?>) rawPoints);
                    }
                    if (smartBuildEnabled && smartBuildSerialization != null) {
                        smartBuildSerialization.captureRelease((ArrayList<?>) rawPoints);
                    }
                }
            } catch (Throwable t) {
                host.log(5, TAG, "扩展原生批量放置失败，保留已生成的原生点", t);
            } finally {
                blockoutXField.setFloat(blockout, originalX);
                blockoutYField.setFloat(blockout, originalY);
            }
            return result;
        });

        if (waypointMethod != null) {
            try {
                waypointHook = host.hookExecutable(waypointMethod, chain -> {
                    if (!enabled || (!unlimitedEnabled && !freeBuildQueueExpansion)
                            || waypointCountField == null
                            || !isBuildWaypoint(chain.getArg(0))) {
                        return chain.proceed();
                    }
                    Object unit = chain.getThisObject();
                    if (unit == null
                            || waypointCountField.getInt(unit)
                            < (freeBuildQueueExpansion
                            ? FREE_BUILD_QUEUE_LIMIT : NATIVE_SUCCESS_LIMIT)) {
                        return chain.proceed();
                    }
                    try {
                        Object appended = appendWaypoint(unit, chain.getArg(0));
                        return appended != null ? appended : chain.proceed();
                    } catch (Throwable t) {
                        host.log(5, TAG, "扩展单位建造队列失败，回退原生队列逻辑", t);
                        return chain.proceed();
                    }
                });
            } catch (Throwable t) {
                waypointHook = null;
                host.log(4, TAG, "单位队列扩展 Hook 安装失败，保留批量预览/释放 Hook", t);
            }
        }

        if (waypointAllocateMethod != null && waypointCountField != null
                && waypointQueueField != null && waypointConstructor != null
                && waypointCopyMethod != null && waypointStateResetMethod != null) {
            try {
                waypointAllocateHook = host.hookExecutable(waypointAllocateMethod, chain -> {
                    if (!enabled || !freeBuildQueueExpansion) {
                        return chain.proceed();
                    }
                    Object unit = chain.getThisObject();
                    if (unit == null
                            || waypointCountField.getInt(unit) < FREE_BUILD_QUEUE_LIMIT) {
                        return chain.proceed();
                    }
                    try {
                        Object appended = appendWaypointSlot(unit);
                        return appended != null ? appended : chain.proceed();
                    } catch (Throwable t) {
                        host.log(5, TAG, "扩展单位建造队列分配槽位失败，回退原生队列逻辑", t);
                        return chain.proceed();
                    }
                });
            } catch (Throwable t) {
                waypointAllocateHook = null;
                host.log(4, TAG, "单位队列分配方法 Hook 安装失败", t);
            }
        }
    }

    private boolean isBuildWaypoint(Object waypoint) throws IllegalAccessException {
        if (waypoint == null) return false;
        if (waypointTypeField != null) {
            Object type = waypointTypeField.get(waypoint);
            return type instanceof Enum
                    && "build".equals(((Enum<?>) type).name());
        }
        for (Field field : waypoint.getClass().getDeclaredFields()) {
            if (!field.getType().isEnum()) continue;
            field.setAccessible(true);
            Object type = field.get(waypoint);
            if (type instanceof Enum && "build".equals(((Enum<?>) type).name())) {
                waypointTypeField = field;
                return true;
            }
        }
        return false;
    }

    private Object appendWaypoint(Object unit, Object source) throws Throwable {
        Object stored = appendWaypointSlot(unit);
        if (stored == null) return null;
        waypointCopyMethod.invoke(stored, source);
        return stored;
    }

    private Object appendWaypointSlot(Object unit) throws Throwable {
        int count = waypointCountField.getInt(unit);
        if (count >= MAX_DYNAMIC_WAYPOINTS) {
            host.log(5, TAG, "批量放置队列达到保护阈值，保留原生队列行为: " + count);
            return null;
        }

        Object queue = waypointQueueField.get(unit);
        int length = queue == null ? 0 : Array.getLength(queue);
        if (length <= count) {
            int newLength = length == 0
                    ? INITIAL_DYNAMIC_WAYPOINT_CAPACITY
                    : Math.max(length * 2, count + 1);
            newLength = Math.min(newLength, MAX_DYNAMIC_WAYPOINTS);
            if (newLength <= count) {
                host.log(5, TAG, "批量放置队列无法扩容，保留原生队列行为: " + count);
                return null;
            }
            Object expanded = Array.newInstance(waypointConstructor.getDeclaringClass(), newLength);
            if (queue != null && length > 0) {
                System.arraycopy(queue, 0, expanded, 0, length);
            }
            waypointQueueField.set(unit, expanded);
            queue = expanded;
        }

        Object stored = Array.get(queue, count);
        if (stored == null) {
            stored = waypointConstructor.newInstance();
            Array.set(queue, count, stored);
        }
        waypointCountField.setInt(unit, count + 1);
        waypointStateResetMethod.invoke(unit);
        return stored;
    }

    private void extendPreview(Object receiver, Object[] args, Object blockout)
            throws Throwable {
        float startX = number(args[1]);
        float startY = number(args[2]);
        float endX = number(args[3]);
        float endY = number(args[4]);
        float dx = endX - startX;
        float dy = endY - startY;
        float length = (float) Math.sqrt((dx * dx) + (dy * dy));
        if (!(length > 0.001f)) return;
        dx /= length;
        dy /= length;

        float step = gridHalfSize();
        float currentX = blockoutXField.getFloat(blockout);
        float currentY = blockoutYField.getFloat(blockout);
        int continuation = 0;
        while (continuation++ < MAX_CONTINUATIONS) {
            float remainingX = endX - currentX;
            float remainingY = endY - currentY;
            if ((remainingX * dx) + (remainingY * dy) <= step * 0.5f) return;

            float entryX = currentX;
            float entryY = currentY;
            Object[] nextArgs = args.clone();
            nextArgs[1] = Float.valueOf(currentX + (dx * step));
            nextArgs[2] = Float.valueOf(currentY + (dy * step));
            nextArgs[3] = Float.valueOf(endX);
            nextArgs[4] = Float.valueOf(endY);
            invokeNative(receiver, nextArgs);

            float nextX = blockoutXField.getFloat(blockout);
            float nextY = blockoutYField.getFloat(blockout);
            if (samePoint(entryX, entryY, nextX, nextY)) return;
            if (samePoint(currentX, currentY, nextX, nextY)) return;
            currentX = nextX;
            currentY = nextY;
        }
        if (continuation >= MAX_CONTINUATIONS) {
            host.log(5, TAG, "批量放置预览续接达到保护阈值，停止继续调用原生方法");
        }
    }

    private Object[] chainArgs(XposedInterface.Chain chain) {
        Object[] args = new Object[8];
        for (int i = 0; i < args.length; i++) {
            args[i] = chain.getArg(i);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private void extendLine(Object receiver, Object[] args, ArrayList<?> rawPoints)
            throws Throwable {
        if (rawPoints.size() < NATIVE_SUCCESS_LIMIT) return;

        float startX = number(args[1]);
        float startY = number(args[2]);
        float endX = number(args[3]);
        float endY = number(args[4]);
        float dx = endX - startX;
        float dy = endY - startY;
        float length = (float) Math.sqrt((dx * dx) + (dy * dy));
        if (!(length > 0.001f)) return;
        dx /= length;
        dy /= length;

        ArrayList<Object> points = (ArrayList<Object>) rawPoints;
        float step = gridHalfSize();
        int continuation = 0;
        while (points.size() >= NATIVE_SUCCESS_LIMIT
                && continuation++ < MAX_CONTINUATIONS) {
            PointF last = lastPoint(points);
            if (last == null) return;
            float remainingX = endX - last.x;
            float remainingY = endY - last.y;
            if ((remainingX * dx) + (remainingY * dy) <= step * 0.5f) return;

            int before = points.size();
            Object[] nextArgs = args.clone();
            nextArgs[1] = Float.valueOf(last.x + (dx * step));
            nextArgs[2] = Float.valueOf(last.y + (dy * step));
            nextArgs[3] = Float.valueOf(endX);
            nextArgs[4] = Float.valueOf(endY);
            invokeNative(receiver, nextArgs);
            removeReplayedPoints(points, before);
            if (points.size() <= before) return;
        }
        if (continuation >= MAX_CONTINUATIONS) {
            host.log(5, TAG, "批量放置续接达到保护阈值，停止继续调用原生方法");
        }

    }

    private void invokeNative(Object receiver, Object[] args) throws Throwable {
        Boolean previous = nativeReentry.get();
        nativeReentry.set(Boolean.TRUE);
        try {
            placementMethod.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause != null) throw cause;
            throw e;
        } finally {
            if (previous == null) nativeReentry.remove();
            else nativeReentry.set(previous);
        }
    }

    private PointF lastPoint(ArrayList<?> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            Object point = points.get(i);
            if (point instanceof PointF) return (PointF) point;
        }
        return null;
    }

    private void removeReplayedPoints(ArrayList<?> rawPoints, int before) {
        @SuppressWarnings("unchecked") ArrayList<Object> points = (ArrayList<Object>) rawPoints;
        for (int i = points.size() - 1; i >= before; i--) {
            Object candidate = points.get(i);
            if (!(candidate instanceof PointF)) continue;
            PointF point = (PointF) candidate;
            boolean replayed = false;
            for (int j = 0; j < i; j++) {
                Object previous = points.get(j);
                if (!(previous instanceof PointF)) continue;
                PointF old = (PointF) previous;
                float x = point.x - old.x;
                float y = point.y - old.y;
                if ((x * x) + (y * y) <= POINT_EPSILON_SQUARED) {
                    replayed = true;
                    break;
                }
            }
            if (replayed) points.remove(i);
        }
    }

    private float gridHalfSize() {
        try {
            Object engine = host.findEngine(loader);
            Object grid = host.findField(engine.getClass(), "bI").get(engine);
            int value = host.findField(grid.getClass(), "p").getInt(grid);
            return value > 0 ? value : DEFAULT_GRID_HALF_SIZE;
        } catch (Throwable ignored) {
            return DEFAULT_GRID_HALF_SIZE;
        }
    }

    private float number(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    private boolean samePoint(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (dx * dx) + (dy * dy) <= POINT_EPSILON_SQUARED;
    }

    private boolean readBoolean(String key, boolean fallback) {
        try {
            Context context = host.preferenceContext();
            if (context == null) return fallback;
            SharedPreferences preferences = context.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
            return preferences.getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void uninstallLocked() {
        if (waypointHook != null) {
            try {
                waypointHook.unhook();
            } catch (Throwable t) {
                host.log(5, TAG, "卸载批量放置队列 Hook 失败", t);
            }
        }
        if (waypointAllocateHook != null) {
            try {
                waypointAllocateHook.unhook();
            } catch (Throwable t) {
                host.log(5, TAG, "卸载批量放置分配 Hook 失败", t);
            }
        }
        if (placementHook != null) {
            try {
                placementHook.unhook();
            } catch (Throwable t) {
                host.log(5, TAG, "卸载批量放置 Hook 失败", t);
            }
        }
        waypointHook = null;
        waypointAllocateHook = null;
        placementHook = null;
        placementMethod = null;
        waypointAllocateMethod = null;
        blockoutXField = null;
        blockoutYField = null;
        waypointCountField = null;
        waypointQueueField = null;
        waypointTypeField = null;
        waypointCopyMethod = null;
        waypointStateResetMethod = null;
        waypointConstructor = null;
        if (smartBuildSerialization != null) {
            try {
                smartBuildSerialization.refreshSettings(false);
            } catch (Throwable t) {
                host.log(5, TAG, "卸载智能建造序列化 Hook 失败", t);
            }
        }
        smartBuildEnabled = false;
        unlimitedEnabled = false;
        freeBuildQueueExpansion = false;
        hookInstalled = false;
        enabled = false;
    }
}
