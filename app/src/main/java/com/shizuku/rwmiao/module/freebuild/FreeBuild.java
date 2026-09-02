package com.shizuku.rwmiao.module.freebuild;

import android.view.MotionEvent;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.SegmentCommands;
import com.shizuku.rwmiao.module.SimulationLifecycle;
import com.shizuku.rwmiao.module.support.GameTickDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_FREE_BUILD;

public final class FreeBuild {
    private static final String TAG = "RWmiao";
    private static final float CAMERA_ZOOM_ZONE_MIN_WIDTH = 96.0f;
    private static final float ACTION_PANEL_MIN_HEIGHT = 180.0f;
    private static final float ACTION_PANEL_MAX_HEIGHT = 300.0f;
    private static final float DEFAULT_BUILD_CELL_SPACING = 10.0f;
    private static final int PENDING_OPERATION_CAPACITY = 4096;
    private static final int MAX_OPERATIONS_PER_TICK = 1;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Object lock = new Object();
    private final ArrayList<Object> selectedBuilders = new ArrayList<>();
    private final ArrayList<Object> dispatchBuilders = new ArrayList<>();
    private final ArrayList<Method> tickMethods = new ArrayList<>();
    private final ArrayList<GameTickDispatcher.Registration> tickRegistrations =
            new ArrayList<>();
    private final SimulationLifecycle simulationLifecycle = new SimulationLifecycle();

    private RuntimeAccess runtime;
    private Method touchMethod;
    private Method inputMethod;
    private Method productionActionMethod;
    private Method nativeBuildFrameMethod;
    private XposedInterface.HookHandle touchHook;
    private XposedInterface.HookHandle inputHook;
    private XposedInterface.HookHandle productionHook;
    private XposedInterface.HookHandle nativeBuildFrameHook;
    private XposedInterface.HookHandle clearSelectionHook;
    private XposedInterface.HookHandle cancelActionHook;

    private volatile boolean modeActive;
    private boolean tracking;
    private volatile boolean nativeInputSuppressed;
    private boolean internalNativeCancel;
    private volatile boolean suppressNativeActionEnd;
    private volatile boolean nativeReleaseBlocked;
    private Object buildType;
    private int buildVariant;
    private float cameraX;
    private float cameraY;
    private float scale;
    private float placementSpacingX = DEFAULT_BUILD_CELL_SPACING;
    private float placementSpacingY = DEFAULT_BUILD_CELL_SPACING;
    private int lastCellX;
    private int lastCellY;
    private boolean hasLastCell;
    private float lastPlacementX;
    private float lastPlacementY;
    private boolean hasLastPlacement;

    private final float[] pendingX = new float[PENDING_OPERATION_CAPACITY];
    private final float[] pendingY = new float[PENDING_OPERATION_CAPACITY];
    private final Object[] pendingBuildType = new Object[PENDING_OPERATION_CAPACITY];
    private final int[] pendingBuildVariant = new int[PENDING_OPERATION_CAPACITY];
    private final float[] pendingDispatch = new float[3];
    private final float[] nativeSnappedPoint = new float[2];
    private final float[] nativeSpacing = new float[2];
    private int pendingHead;
    private int pendingSize;
    private Object pendingBuildTypeForDispatch;
    private int nativeBlueprintSequence;

    public FreeBuild(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    public void install() throws Throwable {
        runtime = new RuntimeAccess();
        Class<?> input = loader.loadClass(host.target("appFramework.en"));
        touchMethod = host.findCompatibleMethod(input, "a", MotionEvent.class);
        if (touchMethod == null) throw new NoSuchMethodException("appFramework.en.a(MotionEvent)");
        inputMethod = host.findCompatibleMethod(runtime.uiClass, "a", float.class);
        nativeBuildFrameMethod = host.findCompatibleMethod(runtime.uiClass, "c", float.class);
        if (nativeBuildFrameMethod == null) {
            throw new NoSuchMethodException("native build preview frame");
        }

        Class<?> queue = loader.loadClass(host.target("gameFramework.c"));
        Method singlePlayerTick = host.findNoArgMethod(queue, "c");
        Method multiplayerTick = host.findNoArgMethod(queue, "d");
        if (singlePlayerTick == null && multiplayerTick == null) {
            throw new NoSuchMethodException("command queue tick c/d");
        }
        if (singlePlayerTick != null) tickMethods.add(singlePlayerTick);
        if (multiplayerTick != null && multiplayerTick != singlePlayerTick) {
            tickMethods.add(multiplayerTick);
        }

        Class<?> action = loader.loadClass(host.target("game.units.a.s"));
        productionActionMethod = host.findCompatibleMethod(runtime.uiClass, "a", action,
                boolean.class, runtime.unit, boolean.class, boolean.class,
                float.class, boolean.class);
        if (productionActionMethod == null) {
            throw new NoSuchMethodException("native build action executor");
        }
        refreshSettings();
    }

    public boolean enabled() {
        return host.selectionActionEnabled(KEY_FREE_BUILD);
    }

    public String titleForSelection() {
        return modeActive ? "取消建造" : "自由建造";
    }

    public boolean isApplicable(Object unit) {
        RuntimeAccess access = runtime;
        if (unit == null || access == null || !access.unit.isInstance(unit)) return false;
        try {
            if (access.dead != null && access.dead.getBoolean(unit)) return false;
            if (access.attached != null && access.attached.get(unit) != null) return false;

            Object engine = host.findEngine(loader);
            Object me = host.findField(engine.getClass(), "bp").get(engine);
            Object owner = access.owner == null ? null : access.owner.get(unit);
            if (me == null || owner != me) return false;

            Object actions = access.unitActions.invoke(unit);
            return hasBuildAction(actions) || hasBuildActionFromUnitType(unit);
        } catch (Throwable t) {
            host.log(5, TAG, "Free-build capability probe failed", t);
            return false;
        }
    }

    public void toggleMode(Object unit) {
        if (!enabled()) return;
        boolean activated = false;
        synchronized (lock) {
            if (modeActive) {
                deactivateLocked(true);
            } else {
                if (!isApplicable(unit)) return;
                selectedBuilders.clear();
                try {
                    collectSelectedBuildersLocked(unit, null, -1);
                } catch (Throwable t) {
                    host.log(5, TAG, "Free-build selection capture failed", t);
                }
                if (selectedBuilders.isEmpty()) return;
                buildType = null;
                buildVariant = 0;
                modeActive = true;
                nativeInputSuppressed = false;
                nativeReleaseBlocked = false;
                nativeBlueprintSequence = 0;
                placementSpacingX = DEFAULT_BUILD_CELL_SPACING;
                placementSpacingY = DEFAULT_BUILD_CELL_SPACING;
                clearGestureLocked();
                activated = true;
            }
        }
        if (!activated) {
            host.setFreeBuildQueueExpansion(false);
            disableHooks();
            return;
        }
        try {
            ensureHooks();
            SegmentCommands commands = host.segmentCommands();
            if (commands != null) commands.setFreeBuildMode(true);
            host.setFreeBuildQueueExpansion(true);
        } catch (Throwable t) {
            host.log(6, TAG, "Failed to activate free-build hooks", t);
            synchronized (lock) { deactivateLocked(false); }
            host.setFreeBuildQueueExpansion(false);
            disableHooks();
        }
    }

    public void setMode(Object unit, boolean active) {
        if (active == modeActive) return;
        toggleMode(unit);
    }

    public synchronized void refreshSettings() throws Throwable {
        if (!enabled()) {
            synchronized (lock) {
                deactivateLocked(false);
                selectedBuilders.clear();
                buildType = null;
            }
            disableHooks();
        } else if (!modeActive) {
            disableHooks();
        }
    }

    private synchronized void ensureHooks() {
        if (touchHook == null) {
            touchHook = host.hookExecutable(touchMethod, chain -> {
                MotionEvent event = chain.getArg(0) instanceof MotionEvent
                        ? (MotionEvent) chain.getArg(0) : null;
                boolean consumed = false;
                try {
                    consumed = captureTouch(event);
                } catch (Throwable t) {
                    host.log(6, TAG, "Free-build touch capture failed", t);
                }
                if (consumed) return consumedTouchResult();
                return chain.proceed();
            });
        }
        if (inputHook == null && inputMethod != null) {
            inputHook = host.hookExecutable(inputMethod, chain -> {
                if (isNativeInputSuppressed()) return consumedInputResult();
                if (shouldFreezeNativeCamera()) {
                    Object engine = host.findEngine(loader);
                    float savedScrollX = runtime.cameraScrollX.getFloat(engine);
                    float savedScrollY = runtime.cameraScrollY.getFloat(engine);
                    float savedCameraX = runtime.cameraX.getFloat(engine);
                    float savedCameraY = runtime.cameraY.getFloat(engine);
                    try {
                        return chain.proceed();
                    } finally {
                        runtime.cameraScrollX.setFloat(engine, savedScrollX);
                        runtime.cameraScrollY.setFloat(engine, savedScrollY);
                        runtime.cameraX.setFloat(engine, savedCameraX);
                        runtime.cameraY.setFloat(engine, savedCameraY);
                    }
                }
                return chain.proceed();
            });
        }
        if (productionHook == null) {
            productionHook = host.hookExecutable(productionActionMethod, chain -> {
                captureBuildAction(chain.getArg(0), chain.getArg(2));
                return chain.proceed();
            });
        }
        if (nativeBuildFrameHook == null) {
            nativeBuildFrameHook = host.hookExecutable(nativeBuildFrameMethod, chain -> {
                if (!modeActive || !nativeReleaseBlocked || runtime.releaseFlag == null) {
                    return chain.proceed();
                }
                Object ui = chain.getThisObject();
                if (!runtime.releaseFlag.getBoolean(ui)) {
                    return chain.proceed();
                }
                runtime.releaseFlag.setBoolean(ui, false);
                boolean previousSuppress = suppressNativeActionEnd;
                suppressNativeActionEnd = true;
                try {
                    return chain.proceed();
                } finally {
                    runtime.releaseFlag.setBoolean(ui, false);
                    suppressNativeActionEnd = previousSuppress;
                    nativeReleaseBlocked = false;
                }
            });
        }
        if (clearSelectionHook == null) {
            clearSelectionHook = host.hookExecutable(runtime.clearSelectionMethod, chain -> {
                Object result = chain.proceed();
                if (!internalNativeCancel && !suppressNativeActionEnd) deactivate();
                return result;
            });
        }
        if (cancelActionHook == null && runtime.cancelActionMethod != null) {
            cancelActionHook = host.hookExecutable(runtime.cancelActionMethod, chain -> {
                Object result = chain.proceed();
                if (!internalNativeCancel && !suppressNativeActionEnd) deactivate();
                return result;
            });
        }
    }

    private synchronized void ensureTickRegistrations() {
        if (!modeActive || tickRegistrations.size() == tickMethods.size()) return;
        for (Method tick : tickMethods) {
            tickRegistrations.add(host.tickDispatcher().register(tick,
                    queue -> drainPendingPlacements()));
        }
    }

    private synchronized void disableHooks() {
        SegmentCommands commands = host.segmentCommands();
        if (commands != null) commands.setFreeBuildMode(false);
        host.setFreeBuildQueueExpansion(false);
        unhook(touchHook); touchHook = null;
        unhook(inputHook); inputHook = null;
        unhook(productionHook); productionHook = null;
        unhook(nativeBuildFrameHook); nativeBuildFrameHook = null;
        unhook(clearSelectionHook); clearSelectionHook = null;
        unhook(cancelActionHook); cancelActionHook = null;
        for (GameTickDispatcher.Registration registration : tickRegistrations) {
            try { registration.close(); } catch (Throwable ignored) { }
        }
        tickRegistrations.clear();
        simulationLifecycle.reset(host.completedResyncGeneration());
        synchronized (lock) {
            nativeInputSuppressed = false;
            nativeReleaseBlocked = false;
            suppressNativeActionEnd = false;
            tracking = false;
            clearPendingOperationsLocked();
            dispatchBuilders.clear();
            selectedBuilders.clear();
        }
    }

    private void unhook(XposedInterface.HookHandle handle) {
        if (handle == null) return;
        try { handle.unhook(); } catch (Throwable ignored) { }
    }

    private boolean captureTouch(MotionEvent event) throws Throwable {
        if (!modeActive || event == null) return false;
        int action = event.getActionMasked();
        Object engine = host.findEngine(loader);
        float x = event.getX();
        float y = event.getY();
        if (event.getPointerCount() != 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            boolean wasTracking;
            boolean hadBuildAction;
            synchronized (lock) {
                wasTracking = tracking;
                hadBuildAction = buildType != null;
                if (tracking) clearGestureLocked();
                clearPendingOperationsLocked();
                nativeInputSuppressed = !hadBuildAction;
                nativeReleaseBlocked = wasTracking && hadBuildAction;
            }
            if (wasTracking) runtime.updateTouchPoint(engine, x, y, false);
            return wasTracking;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            boolean wasTracking;
            boolean hadBuildAction;
            synchronized (lock) {
                wasTracking = tracking;
                hadBuildAction = buildType != null;
                clearGestureLocked();
                clearPendingOperationsLocked();
                nativeInputSuppressed = !hadBuildAction;
                nativeReleaseBlocked = wasTracking && hadBuildAction;
            }
            if (wasTracking) runtime.updateTouchPoint(engine, x, y, false);
            return wasTracking;
        }

        Object ui = runtime.ui.get(engine);
        if (action == MotionEvent.ACTION_DOWN) {
            if (!selectionSnapshotValid()
                    || !runtime.isMapPoint(ui, x, y)
                    || runtime.isNativeCameraZoomPoint(engine, x, y)
                    || runtime.isNativeActionPoint(engine, x, y)) {
                synchronized (lock) {
                    clearGestureLocked();
                    clearPendingOperationsLocked();
                    nativeInputSuppressed = false;
                    nativeReleaseBlocked = false;
                }
                return false;
            }
            synchronized (lock) {
                cameraX = runtime.cameraX.getFloat(engine);
                cameraY = runtime.cameraY.getFloat(engine);
                scale = runtime.scale.getFloat(engine);
                if (scale <= 0.0f) scale = 1.0f;
                tracking = true;
                nativeInputSuppressed = buildType == null;
                nativeReleaseBlocked = false;
                hasLastCell = false;
                hasLastPlacement = false;
            }
            runtime.updateTouchPoint(engine, x, y, true);
            placeAt(engine, x, y);
            return true;
        }

        synchronized (lock) {
            if (!tracking) return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            runtime.updateTouchPoint(engine, x, y, true);
            placeAt(engine, x, y);
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            placeAt(engine, x, y);
            runtime.updateTouchPoint(engine, x, y, false);
            synchronized (lock) {
                boolean hadBuildAction = buildType != null;
                clearGestureLocked();
                nativeInputSuppressed = !hadBuildAction;
                nativeReleaseBlocked = hadBuildAction;
            }
            return true;
        }
        return true;
    }

    private void placeAt(Object engine, float screenX, float screenY) {
        Object type;
        int variant;
        float worldX;
        float worldY;
        synchronized (lock) {
            if (!tracking || !modeActive || selectedBuilders.isEmpty()
                    || buildType == null) return;
            type = buildType;
            variant = buildVariant;
            worldX = screenX / scale + cameraX;
            worldY = screenY / scale + cameraY;
        }
        float snappedX;
        float snappedY;
        try {
            SegmentCommands commands = host.segmentCommands();
            if (commands == null
                    || !commands.snapNativeBuildPoint(worldX, worldY, type, nativeSnappedPoint)) {
                return;
            }
            snappedX = nativeSnappedPoint[0];
            snappedY = nativeSnappedPoint[1];
        } catch (Throwable t) {
            host.log(5, TAG, "Free-build native point snap failed", t);
            return;
        }
        int cellX = Float.floatToIntBits(snappedX);
        int cellY = Float.floatToIntBits(snappedY);
        synchronized (lock) {
            if (!tracking || !modeActive
                    || (hasLastCell && cellX == lastCellX && cellY == lastCellY)) return;
            if (hasLastPlacement
                    && Math.abs(snappedX - lastPlacementX) < placementSpacingX
                    && Math.abs(snappedY - lastPlacementY) < placementSpacingY) {
                return;
            }
            lastCellX = cellX;
            lastCellY = cellY;
            hasLastCell = true;
            lastPlacementX = snappedX;
            lastPlacementY = snappedY;
            hasLastPlacement = true;
            enqueuePendingOperationLocked(snappedX, snappedY, type, variant);
        }
    }

    private void enqueuePendingOperationLocked(float x, float y, Object type, int variant) {
        if (pendingSize >= PENDING_OPERATION_CAPACITY) {
            pendingBuildType[pendingHead] = null;
            pendingHead = (pendingHead + 1) % PENDING_OPERATION_CAPACITY;
            pendingSize--;
        }
        int index = (pendingHead + pendingSize) % PENDING_OPERATION_CAPACITY;
        pendingX[index] = x;
        pendingY[index] = y;
        pendingBuildType[index] = type;
        pendingBuildVariant[index] = variant;
        pendingSize++;
    }

    private void clearPendingOperationsLocked() {
        for (int i = 0; i < pendingSize; i++) {
            pendingBuildType[(pendingHead + i) % PENDING_OPERATION_CAPACITY] = null;
        }
        pendingHead = 0;
        pendingSize = 0;
        pendingBuildTypeForDispatch = null;
    }

    private boolean popPendingOperationLocked(float[] result) {
        if (pendingSize == 0) return false;
        int index = pendingHead;
        result[0] = pendingX[index];
        result[1] = pendingY[index];
        result[2] = pendingBuildVariant[index];
        pendingBuildTypeForDispatch = pendingBuildType[index];
        pendingBuildType[index] = null;
        pendingHead = (pendingHead + 1) % PENDING_OPERATION_CAPACITY;
        pendingSize--;
        return true;
    }

    private void captureBuildAction(Object action, Object unit) {
        if (!modeActive || action == null || unit == null || !runtime.unit.isInstance(unit)) return;
        boolean captured = false;
        try {
            if (!isBuildAction(action)) return;
            Object type = buildType(action);
            if (type == null) return;
            int variant = buildVariant(action, unit);
            synchronized (lock) {
                boolean sameType = buildType == type
                        || buildType != null && buildType.equals(type);
                if (sameType && buildVariant == variant && !selectedBuilders.isEmpty()) {
                    return;
                }
            }
            if (!isApplicable(unit)) return;
            synchronized (lock) {
                boolean sameType = buildType == type
                        || buildType != null && buildType.equals(type);
                if (sameType && buildVariant == variant && !selectedBuilders.isEmpty()) {
                    return;
                }
                selectedBuilders.clear();
                collectSelectedBuildersLocked(unit, type, variant);
                if (selectedBuilders.isEmpty()) return;
                buildType = type;
                buildVariant = variant;
                nativeInputSuppressed = false;
                nativeReleaseBlocked = false;
                hasLastCell = false;
                hasLastPlacement = false;
                updatePlacementSpacingLocked(type);
                captured = true;
            }
            if (captured) ensureTickRegistrations();
        } catch (Throwable t) {
            host.log(5, TAG, "Free-build action capture failed", t);
        }
    }

    private int buildVariant(Object action, Object unit) throws Throwable {
        Method variantMethod = host.findCompatibleMethod(action.getClass(), "b",
                runtime.unit, boolean.class);
        if (variantMethod == null) return 0;
        Object value = variantMethod.invoke(action, unit, false);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private Object buildType(Object action) throws Throwable {
        Method method = host.findNoArgMethod(action.getClass(), "h");
        return method == null ? null : method.invoke(action);
    }

    private boolean hasBuildAction(Object actions) throws Throwable {
        if (!(actions instanceof Iterable)) return false;
        for (Object action : (Iterable<?>) actions) {
            try {
                if (isBuildAction(action)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean hasBuildActionFromUnitType(Object unit) throws Throwable {
        Method typeMethod = host.findNoArgMethod(unit.getClass(), "q");
        if (typeMethod == null) return false;
        Object type = typeMethod.invoke(unit);
        if (type == null) return false;
        Method actionsMethod = host.findCompatibleMethod(type.getClass(), "a", int.class);
        if (actionsMethod == null) return false;
        for (int level = 1; level <= 3; level++) {
            if (hasBuildAction(actionsMethod.invoke(type, level))) return true;
        }
        return false;
    }

    private boolean isBuildAction(Object action) throws Throwable {
        if (action == null) return false;
        Method kindMethod = host.findNoArgMethod(action.getClass(), "d");
        Object kind = kindMethod == null ? null : kindMethod.invoke(action);
        String name = kind instanceof Enum ? ((Enum<?>) kind).name() : String.valueOf(kind);
        return "placeBuilding".equals(name) && buildType(action) != null;
    }

    private boolean supportsBuildType(Object unit, Object type, int variant) throws Throwable {
        if (type == null) return true;
        Object actions = runtime.unitActions.invoke(unit);
        if (!(actions instanceof Iterable)) return false;
        for (Object action : (Iterable<?>) actions) {
            if (!isBuildAction(action)) continue;
            Object candidateType = buildType(action);
            boolean sameType = candidateType == type
                    || candidateType != null && candidateType.equals(type);
            if (sameType && buildVariant(action, unit) == variant) return true;
        }
        return false;
    }

    private void collectSelectedBuildersLocked(Object clicked, Object type, int variant)
            throws Throwable {
        Object engine = host.findEngine(loader);
        Object ui = runtime.ui.get(engine);
        Object selected = runtime.selected == null ? null : runtime.selected.get(ui);
        boolean clickedSeen = false;
        if (selected instanceof Iterable) {
            for (Object candidate : (Iterable<?>) selected) {
                if (candidate == clicked) clickedSeen = true;
                addBuilderIfCompatible(candidate, type, variant);
            }
        }
        if (!clickedSeen) addBuilderIfCompatible(clicked, type, variant);
    }

    private void addBuilderIfCompatible(Object candidate, Object type, int variant)
            throws Throwable {
        if (candidate == null || !isApplicable(candidate)
                || !supportsBuildType(candidate, type, variant)) return;
        for (Object existing : selectedBuilders) if (existing == candidate) return;
        selectedBuilders.add(candidate);
    }

    private boolean selectionSnapshotValid() {
        synchronized (lock) {
            try {
                return selectionSnapshotValidLocked();
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private boolean selectionSnapshotValidLocked() throws Throwable {
        if (selectedBuilders.isEmpty()) return false;
        Object engine = host.findEngine(loader);
        Object ui = runtime.ui.get(engine);
        Object selected = runtime.selected == null ? null : runtime.selected.get(ui);
        if (selected instanceof Iterable) {
            for (Object builder : selectedBuilders) {
                boolean found = false;
                for (Object candidate : (Iterable<?>) selected) {
                    if (candidate == builder) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        if (runtime.selectedFlag == null) return false;
        for (Object builder : selectedBuilders) {
            if (!runtime.selectedFlag.getBoolean(builder)) return false;
        }
        return true;
    }

    private void deactivate() {
        synchronized (lock) { deactivateLocked(true); }
        disableHooks();
    }

    private void deactivateLocked(boolean cancelNative) {
        modeActive = false;
        clearGestureLocked();
        clearPendingOperationsLocked();
        nativeInputSuppressed = false;
        nativeReleaseBlocked = false;
        suppressNativeActionEnd = false;
        buildType = null;
        nativeBlueprintSequence = 0;
        selectedBuilders.clear();
        if (cancelNative) {
            try { cancelNativeInput(); } catch (Throwable ignored) { }
        }
    }

    private void clearGestureLocked() {
        tracking = false;
        hasLastCell = false;
        hasLastPlacement = false;
    }

    private void updatePlacementSpacingLocked(Object type) {
        placementSpacingX = DEFAULT_BUILD_CELL_SPACING;
        placementSpacingY = DEFAULT_BUILD_CELL_SPACING;
        SegmentCommands commands = host.segmentCommands();
        if (commands == null) return;
        try {
            if (!commands.freeBuildPlacementSpacing(type, nativeSpacing)) return;
            placementSpacingX = nativeSpacing[0] > 0.0f
                    ? nativeSpacing[0] : DEFAULT_BUILD_CELL_SPACING;
            placementSpacingY = nativeSpacing[1] > 0.0f
                    ? nativeSpacing[1] : DEFAULT_BUILD_CELL_SPACING;
        } catch (Throwable t) {
            host.log(5, TAG, "Free-build footprint spacing lookup failed", t);
        }
    }

    private void drainPendingPlacements() {
        if (!handleSimulationLifecycle()) return;
        SegmentCommands commands = host.segmentCommands();
        for (int processed = 0; processed < MAX_OPERATIONS_PER_TICK; processed++) {
            Object type;
            float x;
            float y;
            int variant;
            boolean invalidSelection = false;
            synchronized (lock) {
                if (!modeActive) return;
                try {
                    if (selectedBuilders.isEmpty() || !selectionSnapshotValidLocked()) {
                        deactivateLocked(false);
                        invalidSelection = true;
                    }
                } catch (Throwable t) {
                    host.log(5, TAG, "Free-build selection validation skipped", t);
                    deactivateLocked(false);
                    invalidSelection = true;
                }
                if (invalidSelection || !popPendingOperationLocked(pendingDispatch)) {
                    type = null;
                    x = 0.0f;
                    y = 0.0f;
                    variant = 0;
                } else {
                    type = pendingBuildTypeForDispatch;
                    pendingBuildTypeForDispatch = null;
                    x = pendingDispatch[0];
                    y = pendingDispatch[1];
                    variant = (int) pendingDispatch[2];
                    dispatchBuilders.clear();
                    dispatchBuilders.addAll(selectedBuilders);
                }
            }
            if (invalidSelection) {
                disableHooks();
                return;
            }
            if (type == null) return;
            Object blueprintBuilder = null;
            for (Object unit : dispatchBuilders) {
                try {
                    if (commands != null) {
                        SegmentCommands.BuildToggleResult result =
                                commands.toggleBuildAtDetailed(unit, x, y, type, variant);
                        if (blueprintBuilder == null && result.addsBuild()) {
                            blueprintBuilder = unit;
                        }
                    } else {
                        issueNativeBuild(unit, x, y, type, variant, true);
                    }
                } catch (Throwable t) {
                    host.log(5, TAG, "Free-build native command failed", t);
                }
            }
            if (commands != null && blueprintBuilder != null) {
                try {
                    commands.registerNativeBuildBlueprint(blueprintBuilder, x, y,
                            type, variant, nativeBlueprintSequence++);
                } catch (Throwable t) {
                    host.log(5, TAG, "Free-build native blueprint registration failed", t);
                }
            }
        }
    }

    private boolean handleSimulationLifecycle() {
        try {
            Object engine = host.findEngine(loader);
            int tick = ((Number) host.findFieldValue(engine, "bu")).intValue();
            SimulationLifecycle.Observation observation = simulationLifecycle.observe(
                    tick, host.completedResyncGeneration());
            if (observation == SimulationLifecycle.Observation.NEW_MATCH) {
                synchronized (lock) { deactivateLocked(false); }
                disableHooks();
                return false;
            }
            if (observation != SimulationLifecycle.Observation.RESYNC) return true;
            boolean rebound;
            synchronized (lock) {
                dispatchBuilders.clear();
                selectedBuilders.clear();
                tracking = false;
                nativeInputSuppressed = false;
                nativeReleaseBlocked = false;
                suppressNativeActionEnd = false;
                collectSelectedBuildersLocked(null, buildType, buildVariant);
                rebound = !selectedBuilders.isEmpty();
                if (!rebound) deactivateLocked(false);
            }
            if (!rebound) disableHooks();
            return rebound;
        } catch (Throwable t) {
            host.log(5, TAG, "Free-build resync rebind failed", t);
            return true;
        }
    }

    private void cancelNativeInput() throws Throwable {
        Object engine = host.findEngine(loader);
        Object ui = runtime.ui.get(engine);
        internalNativeCancel = true;
        try {
            if (runtime.cancelActionMethod != null) runtime.cancelActionMethod.invoke(ui);
            if (runtime.resetPointerMethod != null) runtime.resetPointerMethod.invoke(ui);
        } finally {
            internalNativeCancel = false;
        }
    }

    private void issueNativeBuild(Object unit, float x, float y, Object type,
                                  int variant, boolean append) throws Throwable {
        Object command = runtime.createCommand.invoke(null);
        host.findField(command.getClass(), "e").setBoolean(command, append);
        host.findField(command.getClass(), "h").setBoolean(command, true);
        runtime.setBuildTarget.invoke(command, x, y, type, variant);
        runtime.addCommandUnit.invoke(command, unit);
    }

    private boolean isNativeInputSuppressed() {
        return nativeInputSuppressed;
    }

    private boolean shouldFreezeNativeCamera() {
        synchronized (lock) {
            return modeActive && buildType != null && (tracking || nativeReleaseBlocked);
        }
    }

    private Object consumedTouchResult() {
        return defaultResult(touchMethod == null ? boolean.class : touchMethod.getReturnType(), true);
    }

    private Object consumedInputResult() {
        return defaultResult(inputMethod == null ? void.class : inputMethod.getReturnType(), false);
    }

    private Object defaultResult(Class<?> type, boolean trueValue) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return trueValue;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    final class RuntimeAccess {
        final Class<?> uiClass = loader.loadClass(host.target("gameFramework.f.i"));
        final Class<?> unit = loader.loadClass(host.target("game.units.ce"));
        final Class<?> battleUnit = loader.loadClass(host.target("game.units.bp"));
        final Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        final Field ui = host.findField(engine, "bP");
        final Field cameraX = host.findField(engine, "ct");
        final Field cameraY = host.findField(engine, "cu");
        final Field cameraScrollX = host.findField(engine, "cv");
        final Field cameraScrollY = host.findField(engine, "cw");
        final Field scale = host.findField(engine, "cU");
        final Field touchSource = host.findField(engine, "an");
        final Field screenWidth = optionalField(engine, "cC");
        final Field screenHeight = host.findField(engine, "cE");
        final Field screenRight = optionalField(engine, "ci");
        final Field actionPanelWidth = optionalField(engine, "cn");
        final Field uiScale = optionalField(engine, "cg");
        final Field dead = optionalField(unit, "bX");
        final Field attached = optionalField(unit, "cP");
        final Field owner = optionalField(unit, "bZ");
        final Field selectedFlag = optionalField(unit, "cI");
        final Field selected = optionalField(uiClass, "bZ");
        final Field releaseFlag = optionalField(uiClass, "U");
        final Class<?> buildTypeClass = loader.loadClass(host.target("game.units.el"));
        final Class<?> commandClass = loader.loadClass(host.target("gameFramework.e"));
        final Method createCommand = host.findNoArgMethod(uiClass, "g");
        final Method setBuildTarget = host.findCompatibleMethod(commandClass, "a",
                float.class, float.class, buildTypeClass, int.class);
        final Method addCommandUnit = host.findCompatibleMethod(commandClass, "a", battleUnit);
        final Method mapActive = host.findCompatibleMethod(uiClass, "a", float.class, float.class);
        final Method cancelActionMethod = host.findNoArgMethod(uiClass, "e");
        final Method resetPointerMethod = host.findNoArgMethod(uiClass, "a");
        final Method clearSelectionMethod = host.findNoArgMethod(uiClass, "h");
        final Method unitActions = host.findNoArgMethod(unit, "N");
        final Method getCurrTouchPoint;
        final Method updateTouchPoint;

        RuntimeAccess() throws Throwable {
            Class<?> touchSourceClass = touchSource == null ? null : touchSource.getType();
            getCurrTouchPoint = touchSourceClass == null
                    ? null : host.findNoArgMethod(touchSourceClass, "getCurrTouchPoint");
            Class<?> touchPointClass = loader.loadClass(host.target("appFramework.ep"));
            updateTouchPoint = host.findCompatibleMethod(touchPointClass, "a",
                    float.class, float.class, boolean.class);
            if (mapActive == null || unitActions == null || clearSelectionMethod == null
                    || owner == null || touchSource == null || getCurrTouchPoint == null
                    || updateTouchPoint == null || releaseFlag == null
                    || createCommand == null || setBuildTarget == null || addCommandUnit == null) {
                throw new NoSuchMethodException("free-build runtime contract");
            }
        }

        void updateTouchPoint(Object engineObject, float x, float y, boolean pressed)
                throws Throwable {
            Object source = touchSource.get(engineObject);
            if (source == null) return;
            Object point = getCurrTouchPoint.invoke(source);
            if (point != null) updateTouchPoint.invoke(point, x, y, pressed);
        }

        boolean isMapPoint(Object uiObject, float x, float y) throws Throwable {
            return Boolean.TRUE.equals(mapActive.invoke(uiObject, x, y));
        }

        boolean isNativeActionPoint(Object engineObject, float x, float y)
                throws IllegalAccessException {
            float bottom = screenHeight.getFloat(engineObject);
            float right = screenRight == null
                    ? (screenWidth == null ? bottom : screenWidth.getFloat(engineObject))
                    : screenRight.getFloat(engineObject);
            float panelWidth = actionPanelWidth == null ? 0.0f
                    : actionPanelWidth.getFloat(engineObject);
            float scaleValue = uiScale == null ? 1.0f : uiScale.getFloat(engineObject);
            if (scaleValue <= 0.0f) scaleValue = 1.0f;
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
            float width = screenWidth == null ? screenHeight.getFloat(engineObject)
                    : screenWidth.getFloat(engineObject);
            float scaleValue = uiScale == null ? 1.0f : uiScale.getFloat(engineObject);
            if (scaleValue <= 0.0f) scaleValue = 1.0f;
            float zoneWidth = Math.max(CAMERA_ZOOM_ZONE_MIN_WIDTH * scaleValue,
                    width * 0.05f);
            return x >= 0.0f && x <= zoneWidth
                    && y >= 0.0f && y <= screenHeight.getFloat(engineObject);
        }

        private Field optionalField(Class<?> type, String name) {
            try { return host.findField(type, name); } catch (Throwable ignored) { return null; }
        }
    }
}
