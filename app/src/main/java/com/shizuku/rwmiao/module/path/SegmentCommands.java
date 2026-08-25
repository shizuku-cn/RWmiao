package com.shizuku.rwmiao.module;

import android.graphics.Point;
import android.graphics.PointF;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_SEGMENT_COMMAND;

/**
 * Native waypoint command coordinator.
 *
 * Instead of rebuilding a unit's route after every tap, this class changes the
 * queue flag on the command produced by the game's own input code. Therefore
 * movement, route rendering and multiplayer serialization all use the original
 * command lifecycle.
 *
 * 原生路径点指令协调器。模块不重复构造整条路线，而是在游戏创建指令时修改其
 * 入队标志，使移动、折线绘制及多人同步均沿用游戏自身的指令生命周期。
 */
final class SegmentCommands {
    private static final String TAG = "RWmiao";

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Map<Long, SegmentState> states = new java.util.HashMap<>();
    private final Set<Long> transportContinuationIds = new HashSet<>();
    private final Map<Long, TransportSnapshot> transportSnapshots = new HashMap<>();
    private final Map<Long, ArrayDeque<Object>> overflowOrders = new HashMap<>();
    private Class<?> orderClass;
    private Class<?> transportClass;
    private final Set<Object> manualTerminalCommands = Collections.newSetFromMap(
            new WeakHashMap<>());
    private final Set<Object> overflowDispatchCommands = Collections.newSetFromMap(
            new WeakHashMap<>());
    private final ThreadLocal<Boolean> splittingMove = new ThreadLocal<>();
    private final ThreadLocal<Boolean> replayingTransport = new ThreadLocal<>();
    private final ThreadLocal<Boolean> moduleOwnedOrder = new ThreadLocal<>();
    private final ThreadLocal<Object> processingCommand = new ThreadLocal<>();
    private Method createNativeCommand;
    private Method setNativeMove;
    private Method addNativeUnit;
    private volatile Boolean enabledState;
    private Class<?> inputClass;
    private Class<?> commandClass;
    private Class<?> unitClass;
    private final ArrayList<XposedInterface.HookHandle> hooks = new ArrayList<>();

    SegmentCommands(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        inputClass = loader.loadClass(host.target("gameFramework.f.i"));
        commandClass = loader.loadClass(host.target("gameFramework.e"));
        unitClass = loader.loadClass(host.target("game.units.bp"));
        orderClass = loader.loadClass(host.target("game.units.en"));
        try {
            transportClass = loader.loadClass(host.target("game.units.b.f"));
        } catch (Throwable t) {
            host.log(4, TAG, "Native transport class not found; transport continuation disabled", t);
        }
        createNativeCommand = exactMethod(inputClass, "g");
        setNativeMove = exactMethod(commandClass, "a", float.class, float.class);
        addNativeUnit = exactMethod(commandClass, "a", unitClass);
        if (createNativeCommand == null || setNativeMove == null || addNativeUnit == null) {
            throw new NoSuchMethodException("native waypoint command methods");
        }
        refreshSettings();
    }

    private synchronized void ensureHooks() throws Throwable {
        if (!hooks.isEmpty()) return;
        hookGroundMove(inputClass);
        hookCommandTargets(commandClass, unitClass);
        hookTerminalOrders(commandClass);
        hookCommandExecution(commandClass);
        hookUnitQueue(unitClass);
        hookTransportDetach(unitClass);
        hookTransportUnload();
    }

    private synchronized void disableHooks() {
        for (XposedInterface.HookHandle handle : hooks) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        hooks.clear();
    }

    /** Mark the first native move as accepted only after the game handled it. */
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
        host.log(4, TAG, "Waypoint ground-click hook installed: " + ground);
    }

    /**
     * A shared move command creates one formation group, which makes faster
     * units wait for the slowest unit at every waypoint. During segmented mode
     * each selected unit receives its own otherwise identical native command.
     *
     * 共享移动指令会创建一个编队组，导致快单位在每个路径点等待最慢单位。
     * 分段模式下将其拆成逐单位原生指令，使各单位独立推进自己的路径队列。
     */
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
                trackTransportContinuation(command, unit);
                String orderName = orderTypeName(host.findFieldValue(command, "j"));
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

    /**
     * The native unit queue has a hard limit of 29 entries.  When it is full,
     * bp.an() returns a scratch slot without increasing O, so the new order is
     * silently discarded.  Keep those recognized appended orders in a small
     * per-unit spill queue and send them back as the native queue advances.
     */
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
                if (!Boolean.TRUE.equals(replayingTransport.get())) {
                    drainOverflow(unit);
                }
            } catch (Throwable t) {
                host.log(5, TAG, "Waypoint overflow drain skipped", t);
            }
            return result;
        }));
    }

    /**
     * The native unload path clears a transported unit in bp.at() after it has
     * already detached the unit from the transport.  Capture at that exact
     * boundary instead of relying only on scanning the transport cargo list;
     * custom transport implementations can mutate that list before the tick
     * hook observes it.
     *
     * 原生卸载路径会在载荷已经脱离载具后调用 bp.at() 清空队列。这里在这个
     * 确切边界抓取剩余指令，避免改版载具提前修改货舱列表导致扫描漏掉单位。
     */
    private void hookTransportDetach(Class<?> unitClass) {
        Method clear = exactMethod(unitClass, "at");
        if (clear == null) {
            host.log(4, TAG, "Native unit clear method not found; direct transport capture disabled");
            return;
        }
        hooks.add(host.hookExecutable(clear, chain -> {
            Object unit = chain.getThisObject();
            try {
                Long id = unitId(unit);
                Object recentTransport = host.findFieldValue(unit, "bT");
                Object currentTransport = host.findFieldValue(unit, "cP");
                if (id != null
                        && isTransportTracked(id)
                        && currentTransport == null
                        && recentTransport != null
                        && transportClass != null
                        && transportClass.isInstance(recentTransport)) {
                    ArrayList<Object> orders = copyPendingOrders(unit);
                    if (!orders.isEmpty()) {
                        synchronized (transportContinuationIds) {
                            transportSnapshots.put(id,
                                    new TransportSnapshot(recentTransport, unit, orders));
                        }
                    }
                }
            } catch (Throwable t) {
                host.log(5, TAG, "Direct transport continuation capture skipped", t);
            }
            return chain.proceed();
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
            Class<?> teamClass = loader.loadClass(host.target("game.p"));
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

    /** Duplicate one move target while preserving the native append flag. */
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

    /**
     * Hook native order setters rather than UI action methods. This covers both
     * ground and unit targets and remains valid when a repackaged game changes
     * its input UI.
     */
    private void hookTerminalOrders(Class<?> commandClass) {
        for (Method method : commandClass.getDeclaredMethods()) {
            if (!isOrderSetterCandidate(method)) continue;
            method.setAccessible(true);
            hooks.add(host.hookExecutable(method, chain -> {
                Object result = chain.proceed();
                try {
                    Object command = chain.getThisObject();
                    if (!Boolean.TRUE.equals(replayingTransport.get())) {
                        configureSegmentOrder(command);
                    }
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

    /**
     * Native transport unloading clears the cargo unit's waypoint array in
     * bp.at(). Capture the remaining native orders before that call and replay
     * them after the transport has detached the unit.
     *
     * 原生载具卸载会在 bp.at() 中清空被装载单位的路径队列。这里仅跟踪曾经
     * 进入分段序列的载荷，在原生卸载完成后恢复剩余原生指令。
     */
    private void hookTransportUnload() {
        if (transportClass == null) return;
        Method tick = exactMethod(transportClass, "a", float.class);
        if (tick == null) {
            host.log(4, TAG, "Native transport tick method not found");
            return;
        }
        hooks.add(host.hookExecutable(tick, chain -> {
            Map<Long, Object> beforeUnits = Collections.emptyMap();
            try {
                beforeUnits = snapshotTransportUnits(chain.getThisObject());
            } catch (Throwable t) {
                host.log(5, TAG, "Transport snapshot skipped", t);
            }
            Object result = chain.proceed();
            try {
                restoreDetachedTransportUnits(chain.getThisObject(), beforeUnits);
            } catch (Throwable t) {
                host.log(5, TAG, "Transport continuation restore skipped", t);
            }
            return result;
        }));
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

    /** Send a computed route through the native command queue. */
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
        Class<?> targetClass = loader.loadClass(host.target("game.units.ce"));
        Object command = createTerminalCommand(append);
        Method setter = exactMethod(command.getClass(), "a", targetClass);
        if (setter == null) throw new NoSuchMethodException("native attack command");
        invokeModuleOwned(setter, command, target);
        addOneUnit(command, unit);
        return command;
    }

    Object issueBuild(Object unit, float x, float y, Object buildType, int variant)
            throws Throwable {
        return issueBuild(unit, x, y, buildType, variant, true);
    }

    Object issueBuild(Object unit, float x, float y, Object buildType, int variant,
                      boolean append) throws Throwable {
        Class<?> buildTypeClass = loader.loadClass(host.target("game.units.el"));
        Object command = createTerminalCommand(append);
        Method setter = exactMethod(command.getClass(), "a",
                float.class, float.class, buildTypeClass, int.class);
        if (setter == null) throw new NoSuchMethodException("native build command");
        invokeModuleOwned(setter, command, x, y, buildType, variant);
        addOneUnit(command, unit);
        return command;
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
                        clearTransportTracking(id);
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
        return "attack".equals(name) || "attackMove".equals(name)
                || "guard".equals(name);
    }

    private boolean isMove(Object command) throws Throwable {
        Object order = host.findFieldValue(command, "j");
        return "move".equals(orderTypeName(order));
    }

    /**
     * Apply the native append flag only to orders understood by this feature.
     * Unknown special actions retain the game's normal replacement behavior.
     *
     * 仅对本功能明确识别的指令设置原生追加标志；未知特殊动作保持游戏默认
     * 的替换行为，避免改变自定义单位技能或其它动作的语义。
     */
    private void configureSegmentOrder(Object command) throws Throwable {
        if (!enabled()
                || Boolean.TRUE.equals(moduleOwnedOrder.get())
                || Boolean.TRUE.equals(replayingTransport.get())) return;
        Object order = host.findFieldValue(command, "j");
        String name = orderTypeName(order);
        if (!isSegmentOrder(name) || !selectedRouteStarted()) return;
        setBoolean(command, "e", true);
        setBoolean(command, "h", true);
    }

    private boolean isSegmentOrder(String name) {
        return "move".equals(name) || "attack".equals(name)
                || "attackMove".equals(name) || "build".equals(name)
                || "repair".equals(name) || "loadInto".equals(name)
                || "loadUp".equals(name) || "reclaim".equals(name)
                || "patrol".equals(name) || "guard".equals(name);
    }

    private void trackTransportContinuation(Object command, Object unit) throws Throwable {
        String name = orderTypeName(host.findFieldValue(command, "j"));
        if (!("loadInto".equals(name) || "loadUp".equals(name))) return;
        Long id = unitId(unit);
        if (id == null || !isActive(id)) return;
        synchronized (transportContinuationIds) {
            transportContinuationIds.add(id);
        }
    }

    private Map<Long, Object> snapshotTransportUnits(Object transport) throws Throwable {
        synchronized (transportContinuationIds) {
            if (transportContinuationIds.isEmpty()) return Collections.emptyMap();
        }
        Object loaded = host.findFieldValue(transport, "o");
        if (!(loaded instanceof Iterable)) return Collections.emptyMap();
        Map<Long, Object> result = new HashMap<>();
        for (Object unit : (Iterable<?>) loaded) {
            Long id = unitId(unit);
            if (id == null || !isTransportTracked(id)) continue;
            ArrayList<Object> orders = copyPendingOrders(unit);
            if (!orders.isEmpty()) {
                synchronized (transportContinuationIds) {
                    transportSnapshots.put(id,
                            new TransportSnapshot(transport, unit, orders));
                }
            }
            result.put(id, unit);
        }
        return result;
    }

    private void restoreDetachedTransportUnits(Object transport, Map<Long, Object> before)
            throws Throwable {
        Map<Long, Object> candidates = new HashMap<>();
        if (before != null) candidates.putAll(before);
        synchronized (transportContinuationIds) {
            for (Map.Entry<Long, TransportSnapshot> entry : transportSnapshots.entrySet()) {
                TransportSnapshot snapshot = entry.getValue();
                if (snapshot != null && snapshot.transport == transport
                        && snapshot.unit != null) {
                    candidates.putIfAbsent(entry.getKey(), snapshot.unit);
                }
            }
        }
        if (candidates.isEmpty()) return;
        Set<Long> remaining = new HashSet<>();
        Object loaded = host.findFieldValue(transport, "o");
        if (loaded instanceof Iterable) {
            for (Object unit : (Iterable<?>) loaded) {
                Long id = unitId(unit);
                if (id != null) remaining.add(id);
            }
        }
        for (Map.Entry<Long, Object> entry : candidates.entrySet()) {
            Long id = entry.getKey();
            if (remaining.contains(id) || isStillAttached(entry.getValue())) continue;
            TransportSnapshot snapshot;
            synchronized (transportContinuationIds) {
                snapshot = transportSnapshots.remove(id);
                transportContinuationIds.remove(id);
            }
            clearOverflow(id);
            if (snapshot != null && !isDead(entry.getValue())) {
                replayTransportOrders(entry.getValue(), snapshot.orders);
            }
        }
    }

    private boolean isTransportTracked(Long id) {
        synchronized (transportContinuationIds) {
            return transportContinuationIds.contains(id);
        }
    }

    private boolean isStillAttached(Object unit) {
        try {
            return host.findFieldValue(unit, "cP") != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isDead(Object unit) {
        try {
            return host.boolField(unit, "bX");
        } catch (Throwable ignored) {
            return true;
        }
    }

    private ArrayList<Object> copyPendingOrders(Object unit) throws Throwable {
        ArrayList<Object> result = new ArrayList<>();
        Field queueField = host.findField(unit.getClass(), "Q");
        Field countField = host.findField(unit.getClass(), "O");
        Object queue = queueField.get(unit);
        Object countObject = countField.get(unit);
        if (queue == null || !(countObject instanceof Number)) return result;
        int count = Math.max(0, Math.min(((Number) countObject).intValue(),
                Array.getLength(queue)));
        for (int i = 0; i < count; i++) {
            Object order = Array.get(queue, i);
            if (order == null) continue;
            try {
                Object copy = copyOrder(order);
                if (copy != null) result.add(copy);
            } catch (Throwable t) {
                host.log(5, TAG, "Native order snapshot skipped", t);
            }
        }
        Long id = unitId(unit);
        if (id != null) {
            synchronized (overflowOrders) {
                ArrayDeque<Object> overflow = overflowOrders.get(id);
                if (overflow != null && !overflow.isEmpty()) {
                    for (Object order : overflow) {
                        try {
                            Object copy = copyOrder(order);
                            if (copy != null) result.add(copy);
                        } catch (Throwable t) {
                            host.log(5, TAG, "Overflow order snapshot skipped", t);
                        }
                    }
                }
            }
        }
        return result;
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

    private void replayTransportOrders(Object unit, List<Object> orders) throws Throwable {
        if (!enabled() || orders == null || orders.isEmpty()) return;
        try {
            replayingTransport.set(Boolean.TRUE);
            boolean append = false;
            for (Object order : orders) {
                Object command = createCommandForUnit(unit);
                if (command == null) break;
                setBoolean(command, "e", append);
                setBoolean(command, "h", true);
                if (!applyNativeOrder(command, order)) break;
                addOneUnit(command, unit);
                append = true;
            }
        } finally {
            replayingTransport.remove();
        }
    }

    private boolean applyNativeOrder(Object command, Object order) throws Throwable {
        String name = orderTypeName(order);
        Class<?> target = loader.loadClass(host.target("game.units.ce"));
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
            Class<?> buildType = loader.loadClass(host.target("game.units.el"));
            Method method = exactMethod(command.getClass(), "a", float.class, float.class,
                    buildType, int.class);
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

    private void invokeModuleOwned(Method method, Object receiver, Object... args)
            throws Throwable {
        moduleOwnedOrder.set(Boolean.TRUE);
        try {
            method.invoke(receiver, args);
        } finally {
            moduleOwnedOrder.remove();
        }
    }

    private float numberField(Object object, String name) throws Throwable {
        return host.number(host.findFieldValue(object, name));
    }

    private int intField(Object object, String name) throws Throwable {
        Object value = host.findFieldValue(object, name);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }


    private String orderTypeName(Object order) throws Throwable {
        if (order == null) return null;
        for (String name : new String[]{"a", "f521a"}) {
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

    /** Cancel by issuing the game's native move order at each current position. */
    private void sendStopCommand(List<Object> units, Object me) throws Throwable {
        if (units.isEmpty() || me == null) return;
        Object engine = host.findEngine(loader);
        Object queue = host.findField(engine.getClass(), "cc").get(engine);
        Class<?> teamClass = loader.loadClass(host.target("game.p"));
        Class<?> unitClass = loader.loadClass(host.target("game.units.bp"));
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

        // Some repackaged builds replace the global registry container. Keep
        // the UI selection collection only as a compatibility fallback.
        // 部分改包版本替换了全局单位容器，因此保留界面选中集合为兼容回退。
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

    private void clearTransportTracking(Long id) {
        if (id == null) return;
        synchronized (transportContinuationIds) {
            transportContinuationIds.remove(id);
            transportSnapshots.remove(id);
        }
        clearOverflow(id);
    }

    private void clearAll() {
        synchronized (states) {
            states.clear();
        }
        synchronized (transportContinuationIds) {
            transportContinuationIds.clear();
            transportSnapshots.clear();
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
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
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

    private static final class TransportSnapshot {
        final Object transport;
        final Object unit;
        final ArrayList<Object> orders;

        TransportSnapshot(Object transport, Object unit, ArrayList<Object> orders) {
            this.transport = transport;
            this.unit = unit;
            this.orders = orders;
        }
    }
}
