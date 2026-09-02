package com.shizuku.rwmiao.module;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_SEGMENT_COMMAND;

public final class SegmentCommands {
    private static final String TAG = "RWmiao";
    private static final String[] ORDER_TYPE_FIELDS = {"a", "f521a"};

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Map<Long, SegmentState> states = new java.util.HashMap<>();
    private final Map<Long, ArrayDeque<Object>> overflowOrders = new HashMap<>();
    private Class<?> orderClass;
    private final Set<Object> manualTerminalCommands = Collections.newSetFromMap(
            new WeakHashMap<>());
    private final Set<Object> overflowDispatchCommands = Collections.newSetFromMap(
            new WeakHashMap<>());
    private final ThreadLocal<Boolean> splittingMove = new ThreadLocal<>();
    private final ThreadLocal<Boolean> moduleOwnedOrder = new ThreadLocal<>();
    private final ThreadLocal<Object> processingCommand = new ThreadLocal<>();
    private Method createNativeCommand;
    private Method setNativeMove;
    private Method addNativeUnit;
    private volatile Boolean enabledState;
    private volatile boolean freeBuildMode;
    private Class<?> inputClass;
    private Class<?> commandClass;
    private Class<?> unitClass;
    private Class<?> targetUnitClass;
    private Class<?> buildTypeClass;
    private Class<?> teamClass;
    private Class<?> nativeBlueprintClass;
    private Constructor<?> nativeBlueprintConstructor;
    private Method sharedBlueprintUnitMethod;
    private Method previewBlueprintUnitMethod;
    private Method blueprintOverlapMethod;
    private Method mapWorldToTileMethod;
    private Method buildTypeSpecialSnapMethod;
    private Method specialBuildPointMethod;
    private Method blueprintOffsetXMethod;
    private Method blueprintOffsetYMethod;
    private Method footprintRectMethod;
    private Field engineMapField;
    private Field mapWorldXField;
    private Field mapWorldYField;
    private Field mapTileWidthField;
    private Field mapTileHeightField;
    private Field unitXField;
    private Field unitYField;
    private Field unitQueueCountField;
    private Field placementGroupField;
    private Field blueprintTypeField;
    private Field blueprintTeamField;
    private Field blueprintVariantField;
    private Field blueprintXField;
    private Field blueprintYField;
    private Field blueprintOwnerTeamField;
    private Field blueprintPendingField;
    private Field blueprintBuilderField;
    private Field blueprintQueueLimitField;
    private Field blueprintGroupField;
    private Field blueprintAnimationField;
    private boolean nativeBlueprintRuntimeReady;
    private final RectF freeBuildFootprintRect = new RectF();
    private final ArrayList<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private final Map<ExactMethodKey, Method> exactMethods = new ConcurrentHashMap<>();
    private final Set<ExactMethodKey> missingExactMethods = ConcurrentHashMap.newKeySet();

    SegmentCommands(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        inputClass = loader.loadClass(host.target("gameFramework.f.i"));
        commandClass = loader.loadClass(host.target("gameFramework.e"));
        unitClass = loader.loadClass(host.target("game.units.bp"));
        targetUnitClass = loader.loadClass(host.target("game.units.ce"));
        buildTypeClass = loader.loadClass(host.target("game.units.el"));
        teamClass = loader.loadClass(host.target("game.p"));
        orderClass = loader.loadClass(host.target("game.units.en"));
        createNativeCommand = exactMethod(inputClass, "g");
        setNativeMove = exactMethod(commandClass, "a", float.class, float.class);
        addNativeUnit = exactMethod(commandClass, "a", unitClass);
        if (createNativeCommand == null || setNativeMove == null || addNativeUnit == null) {
            throw new NoSuchMethodException("native waypoint command methods");
        }
        try {
            resolveNativeBuildBlueprintRuntime();
        } catch (Throwable t) {
            nativeBlueprintRuntimeReady = false;
            host.log(5, TAG, "原生待建造蓝图运行时解析失败", t);
        }
        host.addGameResyncListener(this::onGameResync);
        refreshSettings();
    }

    private void onGameResync() {
        synchronized (overflowOrders) {
            overflowOrders.clear();
        }
        synchronized (manualTerminalCommands) {
            manualTerminalCommands.clear();
        }
        synchronized (overflowDispatchCommands) {
            overflowDispatchCommands.clear();
        }
        splittingMove.remove();
        moduleOwnedOrder.remove();
        processingCommand.remove();
    }

    private synchronized void ensureHooks() throws Throwable {
        if (!hooks.isEmpty()) return;
        hookGroundMove(inputClass);
        hookCommandTargets(commandClass, unitClass);
        hookTerminalOrders(commandClass);
        hookCommandExecution(commandClass);
        hookUnitQueue(unitClass);
    }

    private synchronized void disableHooks() {
        for (XposedInterface.HookHandle handle : hooks) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        hooks.clear();
    }

    private void hookGroundMove(Class<?> inputClass) throws Throwable {
        Method ground = exactMethod(inputClass, "a", float.class, float.class, Point.class);
        if (ground == null) throw new NoSuchMethodException("ground click a(float,float,Point)");
        hooks.add(host.hookExecutable(ground, chain -> {
            Object result = chain.proceed();
            try {
                markSelectedStarted();
            } catch (Throwable t) {
                host.log(5, TAG, "Waypoint state update skipped", t);
            }
            return result;
        }));
    }

    private void hookCommandTargets(Class<?> commandClass, Class<?> unitClass)
            throws Throwable {
        hooks.add(host.hookExecutable(addNativeUnit, chain -> {
            Object command = chain.getThisObject();
            Object unit = chain.getArg(0);
            try {
                if (!Boolean.TRUE.equals(splittingMove.get())
                        && isActive(unitId(unit)) && isMove(command)) {
                    issueIndependentMove(command, unit);
                    return null;
                }
            } catch (Throwable t) {
                host.log(5, TAG, "Independent waypoint split skipped", t);
            }
            Object result = chain.proceed();
            try {
                String orderName = orderTypeName(host.findFieldValue(command, "j"));
                if (enabled() && isRepeatableOrder(orderName)
                        && (isActive(unitId(unit))
                        || "reclaim".equals(orderName) && isReclaimBuilding(unit))) {
                    setBoolean(command, "e", true);
                    setBoolean(command, "h", true);
                }
                if ("attack".equals(orderName) && isActive(unitId(unit))) {
                    markManualTerminal(command);
                }
                if (isTerminal(command)) {
                    if (isActive(unitId(unit))) markManualTerminal(command);
                    finishUnit(unit);
                } else if (isSegmentOrder(orderName)) {
                    markUnitStarted(unit);
                }
            } catch (Throwable t) {
                host.log(5, TAG, "Terminal waypoint close skipped", t);
            }
            return result;
        }));
    }

    private void hookCommandExecution(Class<?> commandClass) {
        Method execute = exactMethod(commandClass, "h");
        if (execute == null) {
            host.log(4, TAG, "Native command execution method not found; overflow guard disabled");
            return;
        }
        hooks.add(host.hookExecutable(execute, chain -> {
            Object command = chain.getThisObject();
            Object previous = processingCommand.get();
            processingCommand.set(command);
            try {
                if (enabled() && replacesNativeQueue(command)) {
                    clearOverflowForCommand(command);
                }
                return chain.proceed();
            } finally {
                if (previous == null) processingCommand.remove();
                else processingCommand.set(previous);
            }
        }));
    }

    private void hookUnitQueue(Class<?> unitClass) {
        Method append = exactMethod(unitClass, "b", orderClass);
        if (append != null) {
            hooks.add(host.hookExecutable(append, chain -> {
                Object unit = chain.getThisObject();
                Object order = chain.getArg(0);
                try {
                    Object command = processingCommand.get();
                    if (enabled()
                            && command != null
                            && !isOverflowDispatch(command)
                            && booleanField(command, "e")
                            && isSegmentOrder(orderTypeName(order))
                            && !(freeBuildMode
                            && "build".equals(orderTypeName(order)))
                            && isNativeQueueFull(unit)) {
                        Object copy = copyOrder(order);
                        if (copy != null) {
                            enqueueOverflow(unit, copy);
                            return copy;
                        }
                    }
                } catch (Throwable t) {
                    host.log(5, TAG, "Waypoint overflow capture skipped", t);
                }
                return chain.proceed();
            }));
        } else {
            host.log(4, TAG, "Native unit append method not found; overflow guard disabled");
        }

        Method pop = exactMethod(unitClass, "as");
        if (pop == null) {
            host.log(4, TAG, "Native waypoint pop method not found; overflow drain disabled");
            return;
        }
        hooks.add(host.hookExecutable(pop, chain -> {
            Object unit = chain.getThisObject();
            Object result = chain.proceed();
            try {
                drainOverflow(unit);
            } catch (Throwable t) {
                host.log(5, TAG, "Waypoint overflow drain skipped", t);
            }
            return result;
        }));
    }

    private boolean replacesNativeQueue(Object command) throws Throwable {
        if (!booleanField(command, "e")) return true;
        try {
            return host.boolField(command, "p");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isNativeQueueFull(Object unit) throws Throwable {
        Object count = host.findFieldValue(unit, "O");
        return count instanceof Number && ((Number) count).intValue() >= 29;
    }

    private void enqueueOverflow(Object unit, Object order) {
        Long id = unitId(unit);
        if (id == null || order == null) return;
        synchronized (overflowOrders) {
            overflowOrders.computeIfAbsent(id, ignored -> new ArrayDeque<>()).addLast(order);
        }
    }

    private void drainOverflow(Object unit) throws Throwable {
        if (!enabled() || unit == null || isNativeQueueFull(unit)) return;
        Long id = unitId(unit);
        if (id == null) return;
        Object order;
        synchronized (overflowOrders) {
            ArrayDeque<Object> queue = overflowOrders.get(id);
            if (queue == null || queue.isEmpty()) return;
            order = queue.pollFirst();
            if (queue.isEmpty()) overflowOrders.remove(id);
        }
        if (order == null) return;
        try {
            dispatchOverflow(unit, order);
        } catch (Throwable t) {
            synchronized (overflowOrders) {
                overflowOrders.computeIfAbsent(id, ignored -> new ArrayDeque<>())
                        .addFirst(order);
            }
            throw t;
        }
    }

    private void dispatchOverflow(Object unit, Object order) throws Throwable {
        Object command = createCommandForUnit(unit);
        if (command == null) throw new IllegalStateException("native overflow command unavailable");
        setBoolean(command, "e", !isNativeQueueEmpty(unit));
        setBoolean(command, "h", true);
        synchronized (overflowDispatchCommands) {
            overflowDispatchCommands.add(command);
        }
        moduleOwnedOrder.set(Boolean.TRUE);
        try {
            if (!applyNativeOrder(command, order)) {
                throw new IllegalStateException("unsupported overflow order");
            }
            addOneUnit(command, unit);
        } finally {
            moduleOwnedOrder.remove();
        }
    }

    private Object createCommandForUnit(Object unit) throws Throwable {
        try {
            Object engine = host.findEngine(loader);
            Object queue = host.findField(engine.getClass(), "cc").get(engine);
            Object team = host.findFieldValue(unit, "bZ");
            Method create = exactMethod(queue.getClass(), "a", teamClass);
            if (create != null && team != null) return create.invoke(queue, team);
        } catch (Throwable t) {
            host.log(5, TAG, "Unit-owner command creation fallback", t);
        }
        return createNativeCommand.invoke(null);
    }

    private boolean isNativeQueueEmpty(Object unit) throws Throwable {
        Object count = host.findFieldValue(unit, "O");
        return !(count instanceof Number) || ((Number) count).intValue() == 0;
    }

    private boolean isOverflowDispatch(Object command) {
        synchronized (overflowDispatchCommands) {
            return overflowDispatchCommands.contains(command);
        }
    }

    private void clearOverflowForCommand(Object command) throws Throwable {
        Object units = host.findFieldValue(command, "w");
        if (!(units instanceof Iterable)) return;
        for (Object unit : (Iterable<?>) units) clearOverflow(unitId(unit));
    }

    private void clearOverflow(Long id) {
        if (id == null) return;
        synchronized (overflowOrders) {
            overflowOrders.remove(id);
        }
    }

    private void issueIndependentMove(Object source, Object unit) throws Throwable {
        Object order = host.findFieldValue(source, "j");
        if (order == null) throw new IllegalStateException("move order missing");
        float x = host.number(host.findFieldValue(order, "e"));
        float y = host.number(host.findFieldValue(order, "f"));
        boolean append = booleanField(source, "e");

        issueNativeMove(unit, x, y, append);
    }

    private Object issueNativeMove(Object unit, float x, float y, boolean append)
            throws Throwable {
        Object command = createNativeCommand.invoke(null);
        if (command == null) throw new IllegalStateException("native command unavailable");
        setBoolean(command, "e", append);
        setBoolean(command, "h", true);
        moduleOwnedOrder.set(Boolean.TRUE);
        try {
            setNativeMove.invoke(command, x, y);
        } finally {
            moduleOwnedOrder.remove();
        }
        splittingMove.set(Boolean.TRUE);
        try {
            addNativeUnit.invoke(command, unit);
        } finally {
            splittingMove.remove();
        }
        return command;
    }

    private void hookTerminalOrders(Class<?> commandClass) {
        for (Method method : commandClass.getDeclaredMethods()) {
            if (!isOrderSetterCandidate(method)) continue;
            method.setAccessible(true);
            hooks.add(host.hookExecutable(method, chain -> {
                Object result = chain.proceed();
                try {
                    Object command = chain.getThisObject();
                    configureSegmentOrder(command);
                    if (isTerminal(command)) {
                        if (hasActiveAttachedUnit(command)) markManualTerminal(command);
                        finishAttachedUnits(command);
                    }
                } catch (Throwable t) {
                    host.log(5, TAG, "Terminal order inspection skipped", t);
                }
                return result;
            }));
        }
    }

    private boolean isOrderSetterCandidate(Method method) {
        if (method.getReturnType() != void.class || method.getParameterCount() == 0) {
            return false;
        }
        String name = method.getName();
        if (!("a".equals(name) || "b".equals(name) || "c".equals(name)
                || "d".equals(name) || "e".equals(name) || "f".equals(name))) {
            return false;
        }
        Class<?>[] p = method.getParameterTypes();
        if (p.length == 1) {
            return p[0].getName().equals(host.target("game.units.ce"));
        }
        if (p.length == 2) {
            return p[0] == float.class && p[1] == float.class;
        }
        if (p.length == 3) {
            return p[0] == float.class && p[1] == float.class && p[2] == boolean.class;
        }
        return p.length == 4 && p[0] == float.class && p[1] == float.class
                && p[2].getName().equals(host.target("game.units.el"))
                && p[3] == int.class;
    }

    void refreshSettings() {
        enabledState = null;
        if (!enabled()) {
            clearAll();
            disableHooks();
        } else {
            try { ensureHooks(); }
            catch (Throwable t) { host.log(6, TAG, "Waypoint hook refresh failed", t); }
        }
    }

    boolean isEnabled() {
        return enabled();
    }

    public void setFreeBuildMode(boolean active) {
        freeBuildMode = active;
    }

    boolean hasActiveSelection() {
        try {
            Object engine = host.findEngine(loader);
            for (Object unit : selectedUnits(engine)) {
                if (isActive(unitId(unit))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean isManualTerminal(Object command) {
        synchronized (manualTerminalCommands) {
            return manualTerminalCommands.contains(command);
        }
    }

    void issueComputedPath(Object unit, List<PointF> points) throws Throwable {
        if (unit == null || points == null || points.isEmpty()) return;
        for (int index = 0; index < points.size(); index++) {
            PointF point = points.get(index);
            issueNativeMove(unit, point.x, point.y, index != 0);
        }
    }

    Object issueComputedPathHead(Object unit, PointF point) throws Throwable {
        if (unit == null || point == null) return null;
        return issueNativeMove(unit, point.x, point.y, false);
    }

    Object issueComputedPathAppend(Object unit, PointF point) throws Throwable {
        if (unit == null || point == null) return null;
        return issueNativeMove(unit, point.x, point.y, true);
    }

    Object issueAttack(Object unit, Object target) throws Throwable {
        return issueAttack(unit, target, true);
    }

    Object issueAttack(Object unit, Object target, boolean append) throws Throwable {
        Object command = createTerminalCommand(append);
        Method setter = exactMethod(command.getClass(), "a", targetUnitClass);
        if (setter == null) throw new NoSuchMethodException("native attack command");
        invokeModuleOwned(setter, command, target);
        addOneUnit(command, unit);
        return command;
    }

    public Object issueBuild(Object unit, float x, float y, Object buildType, int variant)
            throws Throwable {
        return issueBuild(unit, x, y, buildType, variant, true);
    }

    public Object issueBuild(Object unit, float x, float y, Object buildType, int variant,
                             boolean append) throws Throwable {
        Object command = createTerminalCommand(append);
        Method setter = exactMethod(command.getClass(), "a",
                float.class, float.class, buildTypeClass, int.class);
        if (setter == null) throw new NoSuchMethodException("native build command");
        invokeModuleOwned(setter, command, x, y, buildType, variant);
        addOneUnit(command, unit);
        return command;
    }

    public enum BuildToggleResult {
        NONE(false),
        ADDED(true),
        DELETED(false),
        REPLACED(true);

        private final boolean addsBuild;

        BuildToggleResult(boolean addsBuild) {
            this.addsBuild = addsBuild;
        }

        public boolean addsBuild() {
            return addsBuild;
        }
    }

    public boolean toggleBuildAt(Object unit, float x, float y,
                                 Object buildType, int variant)
            throws Throwable {
        return toggleBuildAtDetailed(unit, x, y, buildType, variant)
                != BuildToggleResult.NONE;
    }

    public synchronized BuildToggleResult toggleBuildAtDetailed(
            Object unit, float x, float y, Object buildType, int variant)
            throws Throwable {
        if (unit == null || buildType == null) return BuildToggleResult.NONE;
        BuildOverlap overlap = findPendingBuildOverlap(unit, x, y, buildType);
        if (overlap == null) {
            issueBuild(unit, x, y, buildType, variant, true);
            return BuildToggleResult.ADDED;
        }

        ArrayList<Object> orders = copyPendingOrders(unit);
        int matched = findCopiedBuildOrder(orders, overlap);
        if (matched < 0) {
            throw new IllegalStateException("overlapping native build order disappeared");
        }

        boolean same = overlap.type == buildType
                || overlap.type != null && overlap.type.equals(buildType);
        same = same && overlap.variant == variant;
        Object owner = host.findFieldValue(unit, "bZ");
        sendStopCommand(Collections.singletonList(unit), owner);
        clearOverflow(unitId(unit));

        boolean append = false;
        for (int i = 0; i < orders.size(); i++) {
            if (i == matched) continue;
            Object order = orders.get(i);
            Object command = createCommandForUnit(unit);
            if (command == null || !applyNativeOrder(command, order)) {
                host.log(5, TAG, "自由建造队列重放遇到不支持的原生指令");
                continue;
            }
            setBoolean(command, "e", append);
            setBoolean(command, "h", true);
            addOneUnit(command, unit);
            append = true;
        }
        if (!same) {
            issueBuild(unit, x, y, buildType, variant, append);
            return BuildToggleResult.REPLACED;
        }
        return BuildToggleResult.DELETED;
    }

    private BuildOverlap findPendingBuildOverlap(
            Object unit, float x, float y, Object buildType) throws Throwable {
        Field queueField = host.findField(unit.getClass(), "Q");
        Field countField = host.findField(unit.getClass(), "O");
        Object queue = queueField.get(unit);
        int count = queue == null ? 0 : Math.max(0, Math.min(
                ((Number) countField.get(unit)).intValue(), Array.getLength(queue)));
        for (int i = 0; i < count; i++) {
            BuildOverlap overlap = overlapForOrder(Array.get(queue, i), x, y, buildType);
            if (overlap != null) return overlap;
        }
        Long id = unitId(unit);
        if (id != null) {
            synchronized (overflowOrders) {
                ArrayDeque<Object> overflow = overflowOrders.get(id);
                if (overflow != null) {
                    for (Object order : overflow) {
                        BuildOverlap overlap = overlapForOrder(order, x, y, buildType);
                        if (overlap != null) return overlap;
                    }
                }
            }
        }
        return null;
    }

    private BuildOverlap overlapForOrder(
            Object order, float x, float y, Object buildType) throws Throwable {
        if (order == null || !"build".equals(orderTypeName(order))) return null;
        Object orderBuildType = host.findFieldValue(order, "b");
        if (orderBuildType == null) return null;
        float orderX = numberField(order, "e");
        float orderY = numberField(order, "f");
        if (!buildFootprintsOverlap(buildType, x, y, orderBuildType, orderX, orderY)) {
            return null;
        }
        return new BuildOverlap(orderBuildType, intField(order, "d"), orderX, orderY);
    }

    private int findCopiedBuildOrder(ArrayList<Object> orders, BuildOverlap overlap)
            throws Throwable {
        for (int i = 0; i < orders.size(); i++) {
            Object order = orders.get(i);
            if (!"build".equals(orderTypeName(order))) continue;
            Object type = host.findFieldValue(order, "b");
            boolean sameType = type == overlap.type
                    || type != null && type.equals(overlap.type);
            if (sameType && intField(order, "d") == overlap.variant
                    && Float.compare(numberField(order, "e"), overlap.x) == 0
                    && Float.compare(numberField(order, "f"), overlap.y) == 0) {
                return i;
            }
        }
        return -1;
    }

    public synchronized Object registerNativeBuildBlueprint(
            Object builder, float x, float y, Object buildType, int variant,
            int sequence) throws Throwable {
        requireNativeBlueprintRuntime();
        Object engine = host.findEngine(loader);
        Object player = host.findField(engine.getClass(), "bp").get(engine);
        Object ui = host.findField(engine.getClass(), "bP").get(engine);
        Object blueprint = nativeBlueprintConstructor.newInstance();
        blueprintTypeField.set(blueprint, buildType);
        blueprintTeamField.set(blueprint, player);
        blueprintVariantField.setInt(blueprint, variant);
        blueprintXField.setFloat(blueprint, x);
        blueprintYField.setFloat(blueprint, y);
        blueprintOwnerTeamField.set(blueprint, player);
        blueprintPendingField.setBoolean(blueprint, true);
        blueprintBuilderField.set(blueprint, builder);
        blueprintQueueLimitField.setBoolean(blueprint, false);
        blueprintGroupField.setInt(blueprint, placementGroupField.getInt(ui));
        blueprintAnimationField.setFloat(blueprint,
                1.0f + (0.15f * Math.min(28, Math.max(0, sequence))));
        return blueprint;
    }

    public synchronized boolean snapNativeBuildPoint(
            float worldX, float worldY, Object buildType, float[] result)
            throws Throwable {
        requireNativeBlueprintRuntime();
        if (buildType == null || result == null || result.length < 2) return false;
        Object engine = host.findEngine(loader);
        Object map = engineMapField.get(engine);
        Object preview = previewBlueprintUnitMethod.invoke(null, buildType);
        if (map == null || !unitClass.isInstance(preview)) return false;

        mapWorldToTileMethod.invoke(map, worldX, worldY);
        float x = host.number(mapWorldXField.get(map));
        float y = host.number(mapWorldYField.get(map));
        if (Boolean.TRUE.equals(buildTypeSpecialSnapMethod.invoke(buildType))) {
            Object point = specialBuildPointMethod.invoke(null, (int) x, (int) y);
            if (point instanceof Point) {
                x = ((Point) point).x;
                y = ((Point) point).y;
            }
        }
        x += host.number(blueprintOffsetXMethod.invoke(preview));
        y += host.number(blueprintOffsetYMethod.invoke(preview));
        result[0] = x;
        result[1] = y;
        return true;
    }

    public synchronized boolean freeBuildPlacementSpacing(
            Object buildType, float[] result) throws Throwable {
        requireNativeBlueprintRuntime();
        if (buildType == null || result == null || result.length < 2) return false;
        Object engine = host.findEngine(loader);
        Object map = engineMapField.get(engine);
        Object preview = previewBlueprintUnitMethod.invoke(null, buildType);
        if (map == null || !unitClass.isInstance(preview)) return false;

        float savedX = unitXField.getFloat(preview);
        float savedY = unitYField.getFloat(preview);
        try {
            unitXField.setFloat(preview, 0.0f);
            unitYField.setFloat(preview, 0.0f);
            footprintRectMethod.invoke(preview, map, freeBuildFootprintRect);
            float cellWidth = host.number(mapTileWidthField.get(map));
            float cellHeight = host.number(mapTileHeightField.get(map));
            if (!(cellWidth > 0.0f)) cellWidth = 10.0f;
            if (!(cellHeight > 0.0f)) cellHeight = 10.0f;
            float width = Math.abs(freeBuildFootprintRect.width());
            float height = Math.abs(freeBuildFootprintRect.height());
            result[0] = width > 0.0f ? width : cellWidth;
            result[1] = height > 0.0f ? height : cellHeight;
            return true;
        } finally {
            unitXField.setFloat(preview, savedX);
            unitYField.setFloat(preview, savedY);
        }
    }

    Object issueAttackMove(Object unit, float x, float y) throws Throwable {
        return issueAttackMove(unit, x, y, true);
    }

    Object issueAttackMove(Object unit, float x, float y, boolean append) throws Throwable {
        Object command = createTerminalCommand(append);
        Method setter = exactMethod(command.getClass(), "b", float.class, float.class);
        if (setter == null) throw new NoSuchMethodException("native attack-move command");
        invokeModuleOwned(setter, command, x, y);
        addOneUnit(command, unit);
        return command;
    }

    private Object createTerminalCommand(boolean append) throws Throwable {
        Object command = createNativeCommand.invoke(null);
        if (command == null) throw new IllegalStateException("native command unavailable");
        setBoolean(command, "e", append);
        setBoolean(command, "h", true);
        return command;
    }

    private void resolveNativeBuildBlueprintRuntime() throws Throwable {
        nativeBlueprintClass = loader.loadClass(host.target("gameFramework.d.a"));
        nativeBlueprintConstructor = nativeBlueprintClass.getDeclaredConstructor();
        nativeBlueprintConstructor.setAccessible(true);
        sharedBlueprintUnitMethod = exactMethod(targetUnitClass, "b", buildTypeClass);
        previewBlueprintUnitMethod = exactMethod(targetUnitClass, "d", buildTypeClass);
        blueprintOverlapMethod = exactMethod(nativeBlueprintClass, "a", unitClass, unitClass);
        Class<?> engineClass = loader.loadClass(host.target("gameFramework.k"));
        engineMapField = host.findField(engineClass, "bI");
        Class<?> mapClass = engineMapField.getType();
        mapWorldToTileMethod = exactMethod(mapClass, "b", float.class, float.class);
        mapWorldXField = host.findField(mapClass, "U");
        mapWorldYField = host.findField(mapClass, "V");
        mapTileWidthField = host.findField(mapClass, "n");
        mapTileHeightField = host.findField(mapClass, "o");
        buildTypeSpecialSnapMethod = exactMethod(buildTypeClass, "p");
        Class<?> specialSnapClass = loader.loadClass(host.target("gameFramework.f.l"));
        specialBuildPointMethod = exactMethod(specialSnapClass, "a", int.class, int.class);
        blueprintOffsetXMethod = exactMethod(unitClass, "cB");
        blueprintOffsetYMethod = exactMethod(unitClass, "cC");
        footprintRectMethod = exactMethod(unitClass, "a", mapClass, RectF.class);
        if (sharedBlueprintUnitMethod == null || previewBlueprintUnitMethod == null
                || blueprintOverlapMethod == null || mapWorldToTileMethod == null
                || buildTypeSpecialSnapMethod == null || specialBuildPointMethod == null
                || blueprintOffsetXMethod == null || blueprintOffsetYMethod == null
                || footprintRectMethod == null) {
            throw new NoSuchMethodException("native build blueprint footprint methods");
        }
        unitXField = host.findField(unitClass, "eq");
        unitYField = host.findField(unitClass, "er");
        unitQueueCountField = host.findField(unitClass, "O");
        placementGroupField = host.findField(inputClass, "ad");
        blueprintTypeField = host.findField(nativeBlueprintClass, "d");
        blueprintTeamField = host.findField(nativeBlueprintClass, "e");
        blueprintVariantField = host.findField(nativeBlueprintClass, "f");
        blueprintXField = host.findField(nativeBlueprintClass, "g");
        blueprintYField = host.findField(nativeBlueprintClass, "h");
        blueprintOwnerTeamField = host.findField(nativeBlueprintClass, "j");
        blueprintPendingField = host.findField(nativeBlueprintClass, "n");
        blueprintBuilderField = host.findField(nativeBlueprintClass, "o");
        blueprintQueueLimitField = host.findField(nativeBlueprintClass, "q");
        blueprintGroupField = host.findField(nativeBlueprintClass, "r");
        blueprintAnimationField = host.findField(nativeBlueprintClass, "s");
        nativeBlueprintRuntimeReady = true;
    }

    private void requireNativeBlueprintRuntime() {
        if (!nativeBlueprintRuntimeReady) {
            throw new IllegalStateException("native build blueprint runtime unavailable");
        }
    }

    private boolean buildFootprintsOverlap(Object currentType, float currentX, float currentY,
                                           Object pendingType, float pendingX, float pendingY)
            throws Throwable {
        requireNativeBlueprintRuntime();
        Object current = sharedBlueprintUnitMethod.invoke(null, currentType);
        Object pending = previewBlueprintUnitMethod.invoke(null, pendingType);
        if (!unitClass.isInstance(current) || !unitClass.isInstance(pending)) return false;
        float savedCurrentX = unitXField.getFloat(current);
        float savedCurrentY = unitYField.getFloat(current);
        float savedPendingX = unitXField.getFloat(pending);
        float savedPendingY = unitYField.getFloat(pending);
        try {
            unitXField.setFloat(current, currentX);
            unitYField.setFloat(current, currentY);
            unitXField.setFloat(pending, pendingX);
            unitYField.setFloat(pending, pendingY);
            return Boolean.TRUE.equals(blueprintOverlapMethod.invoke(null, current, pending));
        } finally {
            unitXField.setFloat(current, savedCurrentX);
            unitYField.setFloat(current, savedCurrentY);
            unitXField.setFloat(pending, savedPendingX);
            unitYField.setFloat(pending, savedPendingY);
        }
    }

    private void addOneUnit(Object command, Object unit) throws Throwable {
        splittingMove.set(Boolean.TRUE);
        try {
            addNativeUnit.invoke(command, unit);
        } finally {
            splittingMove.remove();
        }
    }

    boolean isUnitSelection(Object selected) {
        if (selected == null) return false;
        try {
            Object engine = host.findEngine(loader);
            Object me = host.findField(engine.getClass(), "bp").get(engine);
            return isOrderableUnit(selected, me);
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean hasSelectedUnitSelection() {
        if (!enabled()) return false;
        try {
            Object engine = host.findEngine(loader);
            Object me = host.findField(engine.getClass(), "bp").get(engine);
            for (Object unit : selectedUnits(engine)) {
                if (isOrderableUnit(unit, me)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    String titleForSelection() {
        if (!enabled()) return "分段指令";
        try {
            Object engine = host.findEngine(loader);
            Object me = host.findField(engine.getClass(), "bp").get(engine);
            for (Object unit : selectedUnits(engine)) {
                if (isOrderableUnit(unit, me) && isActive(unitId(unit))) {
                    return "取消分段";
                }
            }
        } catch (Throwable ignored) {
        }
        return "分段指令";
    }

    void toggleSelected() {
        if (!enabled()) return;
        try {
            Object engine = host.findEngine(loader);
            Object me = host.findField(engine.getClass(), "bp").get(engine);
            ArrayList<Object> selected = new ArrayList<>();
            boolean cancel = false;
            for (Object unit : selectedUnits(engine)) {
                if (!isOrderableUnit(unit, me)) continue;
                selected.add(unit);
                if (isActive(unitId(unit))) cancel = true;
            }
            if (selected.isEmpty()) return;
            if (cancel) {
                sendStopCommand(selected, me);
                synchronized (states) {
                    for (Object unit : selected) {
                        Long id = unitId(unit);
                        states.remove(id);
                    }
                }
            } else {
                synchronized (states) {
                    for (Object unit : selected) states.put(unitId(unit), new SegmentState());
                }
            }
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to toggle waypoint mode", t);
        }
    }

    void setSelected(boolean enable) {
        if (!enabled()) return;
        try {
            Object engine = host.findEngine(loader);
            Object me = host.findField(engine.getClass(), "bp").get(engine);
            ArrayList<Object> selected = new ArrayList<>();
            for (Object unit : selectedUnits(engine)) {
                if (isOrderableUnit(unit, me)) selected.add(unit);
            }
            if (selected.isEmpty()) return;
            if (!enable) {
                ArrayList<Object> active = new ArrayList<>();
                synchronized (states) {
                    for (Object unit : selected) {
                        if (isActive(unitId(unit))) active.add(unit);
                    }
                }
                if (active.isEmpty()) return;
                sendStopCommand(active, me);
                synchronized (states) {
                    for (Object unit : active) states.remove(unitId(unit));
                }
            } else {
                synchronized (states) {
                    for (Object unit : selected) {
                        if (!isActive(unitId(unit))) {
                            states.put(unitId(unit), new SegmentState());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to set waypoint mode", t);
        }
    }

    private boolean selectedRouteStarted() throws Throwable {
        Object engine = host.findEngine(loader);
        Object me = host.findField(engine.getClass(), "bp").get(engine);
        for (Object unit : selectedUnits(engine)) {
            if (!isOrderableUnit(unit, me)) continue;
            synchronized (states) {
                SegmentState state = states.get(unitId(unit));
                if (state != null && state.started) return true;
            }
        }
        return false;
    }

    private void markSelectedStarted() throws Throwable {
        if (!enabled()) return;
        Object engine = host.findEngine(loader);
        Object me = host.findField(engine.getClass(), "bp").get(engine);
        synchronized (states) {
            for (Object unit : selectedUnits(engine)) {
                if (!isOrderableUnit(unit, me)) continue;
                SegmentState state = states.get(unitId(unit));
                if (state != null) state.started = true;
            }
        }
    }

    private void markUnitStarted(Object unit) {
        Long id = unitId(unit);
        if (id == null) return;
        synchronized (states) {
            SegmentState state = states.get(id);
            if (state != null) state.started = true;
        }
    }

    private boolean isTerminal(Object command) throws Throwable {
        Object order = host.findFieldValue(command, "j");
        String name = orderTypeName(order);
        return "attackMove".equals(name)
                || "guard".equals(name);
    }

    private boolean isMove(Object command) throws Throwable {
        Object order = host.findFieldValue(command, "j");
        return "move".equals(orderTypeName(order));
    }

    private void configureSegmentOrder(Object command) throws Throwable {
        if (!enabled()
                || Boolean.TRUE.equals(moduleOwnedOrder.get())) return;
        Object order = host.findFieldValue(command, "j");
        String name = orderTypeName(order);
        if (!isSegmentOrder(name) || !selectedRouteStarted()) return;
        setBoolean(command, "e", true);
        setBoolean(command, "h", true);
    }

    private boolean isSegmentOrder(String name) {
        return "move".equals(name) || "attack".equals(name)
                || "attackMove".equals(name) || "build".equals(name)
                || "repair".equals(name) || "reclaim".equals(name)
                || "loadInto".equals(name) || "loadUp".equals(name)
                || "patrol".equals(name) || "guard".equals(name);
    }

    private boolean isRepeatableOrder(String name) {
        return "reclaim".equals(name)
                || "loadInto".equals(name)
                || "loadUp".equals(name);
    }

    private boolean isReclaimBuilding(Object unit) throws Throwable {
        if (unit == null) return false;
        Class<?> building = loader.loadClass(host.target("game.units.d.f"));
        return building.isInstance(unit);
    }

    private Object copyOrder(Object order) throws Throwable {
        if (order == null) return null;
        Constructor<?> constructor = order.getClass().getDeclaredConstructor();
        constructor.setAccessible(true);
        Object copy = constructor.newInstance();
        Method copier = exactMethod(order.getClass(), "c", orderClass);
        if (copier == null) return null;
        copier.invoke(copy, order);
        return copy;
    }

    private ArrayList<Object> copyPendingOrders(Object unit) throws Throwable {
        ArrayList<Object> result = new ArrayList<>();
        Field queueField = host.findField(unit.getClass(), "Q");
        Field countField = host.findField(unit.getClass(), "O");
        Object queue = queueField.get(unit);
        Object countObject = countField.get(unit);
        if (queue != null && countObject instanceof Number) {
            int count = Math.max(0, Math.min(((Number) countObject).intValue(),
                    Array.getLength(queue)));
            for (int i = 0; i < count; i++) {
                Object order = Array.get(queue, i);
                if (order == null) continue;
                Object copy = copyOrder(order);
                if (copy != null) result.add(copy);
            }
        }
        Long id = unitId(unit);
        if (id != null) {
            synchronized (overflowOrders) {
                ArrayDeque<Object> overflow = overflowOrders.get(id);
                if (overflow != null) {
                    for (Object order : overflow) {
                        Object copy = copyOrder(order);
                        if (copy != null) result.add(copy);
                    }
                }
            }
        }
        return result;
    }

    private boolean applyNativeOrder(Object command, Object order) throws Throwable {
        String name = orderTypeName(order);
        Class<?> target = targetUnitClass;
        if ("move".equals(name)) {
            Method method = exactMethod(command.getClass(), "a", float.class, float.class);
            if (method == null) return false;
            method.invoke(command, numberField(order, "e"), numberField(order, "f"));
            return true;
        }
        if ("attackMove".equals(name)) {
            Method method = exactMethod(command.getClass(), "b", float.class, float.class);
            if (method == null) return false;
            method.invoke(command, numberField(order, "e"), numberField(order, "f"));
            return true;
        }
        if ("patrol".equals(name)) {
            Method method = exactMethod(command.getClass(), "c", float.class, float.class);
            if (method == null) return false;
            method.invoke(command, numberField(order, "e"), numberField(order, "f"));
            return true;
        }
        Object targetUnit = host.findFieldValue(order, "h");
        if ("attack".equals(name)) return invokeTarget(command, "a", target, targetUnit);
        if ("repair".equals(name)) return invokeTarget(command, "b", target, targetUnit);
        if ("guard".equals(name)) return invokeTarget(command, "c", target, targetUnit);
        if ("reclaim".equals(name)) return invokeTarget(command, "d", target, targetUnit);
        if ("loadInto".equals(name)) return invokeTarget(command, "e", target, targetUnit);
        if ("loadUp".equals(name)) return invokeTarget(command, "f", target, targetUnit);
        if ("build".equals(name)) {
            Method method = exactMethod(command.getClass(), "a", float.class, float.class,
                    buildTypeClass, int.class);
            if (method == null) return false;
            method.invoke(command, numberField(order, "e"), numberField(order, "f"),
                    host.findFieldValue(order, "b"), intField(order, "d"));
            return true;
        }
        return false;
    }

    private boolean invokeTarget(Object command, String name, Class<?> targetClass,
                                 Object target) throws Throwable {
        if (target == null) return false;
        Method method = exactMethod(command.getClass(), name, targetClass);
        if (method == null) return false;
        method.invoke(command, target);
        return true;
    }

    private float numberField(Object object, String name) throws Throwable {
        return host.number(host.findFieldValue(object, name));
    }

    private int intField(Object object, String name) throws Throwable {
        Object value = host.findFieldValue(object, name);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private void invokeModuleOwned(Method method, Object receiver, Object... args)
            throws Throwable {
        moduleOwnedOrder.set(Boolean.TRUE);
        try {
            method.invoke(receiver, args);
        } finally {
            moduleOwnedOrder.remove();
        }
    }

    private String orderTypeName(Object order) throws Throwable {
        if (order == null) return null;
        for (String name : ORDER_TYPE_FIELDS) {
            try {
                Object value = host.findFieldValue(order, name);
                if (value instanceof Enum) return ((Enum<?>) value).name();
            } catch (Throwable ignored) {
            }
        }
        Class<?> current = order.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().isEnum()) continue;
                field.setAccessible(true);
                Object value = field.get(order);
                if (value instanceof Enum) return ((Enum<?>) value).name();
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void finishAttachedUnits(Object command) throws Throwable {
        Object units = host.findFieldValue(command, "w");
        if (!(units instanceof Iterable)) return;
        for (Object unit : (Iterable<?>) units) finishUnit(unit);
    }

    private boolean hasActiveAttachedUnit(Object command) throws Throwable {
        Object units = host.findFieldValue(command, "w");
        if (!(units instanceof Iterable)) return false;
        for (Object unit : (Iterable<?>) units) {
            if (isActive(unitId(unit))) return true;
        }
        return false;
    }

    private void markManualTerminal(Object command) {
        synchronized (manualTerminalCommands) {
            manualTerminalCommands.add(command);
        }
    }

    private void finishUnit(Object unit) {
        Long id = unitId(unit);
        if (id == null) return;
        synchronized (states) {
            states.remove(id);
        }
    }

    private void sendStopCommand(List<Object> units, Object me) throws Throwable {
        if (units.isEmpty() || me == null) return;
        Object engine = host.findEngine(loader);
        Object queue = host.findField(engine.getClass(), "cc").get(engine);
        Method create = exactMethod(queue.getClass(), "a", teamClass);
        if (create == null) throw new NoSuchMethodException("command queue create(team)");
        for (Object unit : units) {
            Object command = create.invoke(queue, me);
            Method move = exactMethod(command.getClass(), "a", float.class, float.class);
            Method addUnit = exactMethod(command.getClass(), "a", unitClass);
            if (move == null || addUnit == null) {
                throw new NoSuchMethodException("native stop command methods");
            }
            setBoolean(command, "e", false);
            setBoolean(command, "h", true);
            invokeModuleOwned(move, command,
                    host.number(host.findFieldValue(unit, "eq")),
                    host.number(host.findFieldValue(unit, "er")));
            addUnit.invoke(command, unit);
        }
    }

    private ArrayList<Object> selectedUnits(Object engine) throws Throwable {
        ArrayList<Object> result = new ArrayList<>();
        try {
            Class<?> registry = loader.loadClass(host.target("gameFramework.ah"));
            Object allUnits = host.findField(registry, "et").get(null);
            if (allUnits instanceof Iterable) {
                for (Object unit : (Iterable<?>) allUnits) {
                    if (unit != null && host.boolField(unit, "cI")) {
                        result.add(unit);
                    }
                }
            }
        } catch (Throwable t) {
            host.log(5, TAG, "Global selection lookup skipped", t);
        }
        if (!result.isEmpty()) return result;

        Object ui = host.findField(engine.getClass(), "bP").get(engine);
        Object selected = host.findFieldValue(ui, "bZ");
        if (selected instanceof Iterable) {
            for (Object unit : (Iterable<?>) selected) if (unit != null) result.add(unit);
        }
        return result;
    }

    private boolean isOrderableUnit(Object unit, Object me) throws Throwable {
        Class<?> baseUnit = loader.loadClass(host.target("game.units.bp"));
        Class<?> building = loader.loadClass(host.target("game.units.d.f"));
        return baseUnit.isInstance(unit) && !building.isInstance(unit)
                && me != null && host.findFieldValue(unit, "bZ") == me;
    }

    private boolean isActive(Long id) {
        synchronized (states) {
            return id != null && states.containsKey(id);
        }
    }

    private Long unitId(Object unit) {
        try {
            Object value = host.findFieldValue(unit, "ej");
            return value instanceof Number ? ((Number) value).longValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void clearAll() {
        synchronized (states) {
            states.clear();
        }
        synchronized (overflowOrders) {
            overflowOrders.clear();
        }
        synchronized (manualTerminalCommands) {
            manualTerminalCommands.clear();
        }
    }

    private void setBoolean(Object object, String name, boolean value) throws Throwable {
        Field field = host.findField(object.getClass(), name);
        if (field.getType() == boolean.class) field.setBoolean(object, value);
    }

    private boolean booleanField(Object object, String name) throws Throwable {
        Field field = host.findField(object.getClass(), name);
        return field.getType() == boolean.class && field.getBoolean(object);
    }

    private Method exactMethod(Class<?> type, String name, Class<?>... parameters) {
        ExactMethodKey key = new ExactMethodKey(type, name, parameters);
        Method cached = exactMethods.get(key);
        if (cached != null) return cached;
        if (missingExactMethods.contains(key)) return null;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != parameters.length) continue;
                Class<?>[] actual = method.getParameterTypes();
                boolean exact = true;
                for (int i = 0; i < actual.length; i++) {
                    if (actual[i] != parameters[i]) {
                        exact = false;
                        break;
                    }
                }
                if (exact) {
                    method.setAccessible(true);
                    exactMethods.putIfAbsent(key, method);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        missingExactMethods.add(key);
        return null;
    }

    private static final class ExactMethodKey {
        final Class<?> type;
        final String name;
        final Class<?>[] parameters;
        final int hash;

        ExactMethodKey(Class<?> type, String name, Class<?>[] parameters) {
            this.type = type;
            this.name = name;
            this.parameters = parameters;
            this.hash = 31 * (31 * System.identityHashCode(type) + name.hashCode())
                    + java.util.Arrays.hashCode(parameters);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ExactMethodKey)) return false;
            ExactMethodKey key = (ExactMethodKey) other;
            return type == key.type && Objects.equals(name, key.name)
                    && java.util.Arrays.equals(parameters, key.parameters);
        }
    }

    private boolean enabled() {
        Boolean cached = enabledState;
        if (cached != null) return cached;
        boolean value = host.selectionActionEnabled(KEY_SEGMENT_COMMAND);
        enabledState = value;
        return value;
    }

    private static final class SegmentState {
        boolean started;
    }

    private static final class BuildOverlap {
        final Object type;
        final int variant;
        final float x;
        final float y;

        BuildOverlap(Object type, int variant, float x, float y) {
            this.type = type;
            this.variant = variant;
            this.x = x;
            this.y = y;
        }
    }

}
