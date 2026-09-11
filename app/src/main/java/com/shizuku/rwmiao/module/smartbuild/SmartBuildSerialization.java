package com.shizuku.rwmiao.module.smartbuild;

import android.graphics.PointF;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.SegmentCommands;
import com.shizuku.rwmiao.module.SimulationLifecycle;
import com.shizuku.rwmiao.module.support.GameTickDispatcher;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

public final class SmartBuildSerialization {
    private static final String TAG = "RWmiao";
    private static final String[] ORDER_TYPE_FIELDS = {"a"};
    private static final float POINT_EPSILON_SQUARED = 0.01f;
    private static final int DISPATCH_RETRY_TICKS = 180;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Object hooksLock = new Object();
    private final Object stateLock = new Object();
    private final ArrayList<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private final ArrayList<GameTickDispatcher.Registration> tickRegistrations =
            new ArrayList<>();
    private final ArrayList<BuildSequence> sequences = new ArrayList<>();
    private final Map<Object, ModuleDispatch> moduleCommands = new WeakHashMap<>();
    private final ThreadLocal<ModuleDispatch> processingModuleCommand = new ThreadLocal<>();
    private final SimulationLifecycle simulationLifecycle = new SimulationLifecycle();

    private volatile boolean enabled;
    private int nativeTick;
    private int blueprintSequence;
    private Class<?> unitClass;
    private Method executeCommand;
    private Method enqueueOrder;
    private Method currentOrder;
    private Method popOrder;
    private Method createCommand;
    private Method buildSetter;
    private Method repairSetter;
    private Method moveSetter;
    private Method attachUnit;
    private Method gameEngineInstance;
    private Field localTeamField;
    private Field blueprintAgeField;
    private Field blueprintRemovedField;
    private Field blueprintBuilderField;
    private Field blueprintRegistryField;
    private Field blueprintTypeField;
    private Field blueprintVariantField;
    private Field blueprintXField;
    private Field blueprintYField;
    private Field blueprintPendingField;

    public SmartBuildSerialization(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    public void refreshSettings(boolean shouldEnable) throws Throwable {
        synchronized (hooksLock) {
            if (shouldEnable && !enabled) {
                enabled = true;
                try {
                    installHooks();
                } catch (Throwable t) {
                    enabled = false;
                    clearState();
                    uninstallHooksLocked();
                    throw t;
                }
            } else if (!shouldEnable && enabled) {
                enabled = false;
                clearState();
                uninstallHooksLocked();
            }
        }
    }

    public void captureRelease(ArrayList<?> ignored) {
    }

    private void installHooks() throws Throwable {
        Class<?> commandClass = loader.loadClass(host.target("gameFramework.e"));
        unitClass = loader.loadClass(host.target("game.units.bp"));
        Class<?> orderClass = loader.loadClass(host.target("game.units.en"));
        Class<?> buildTypeClass = loader.loadClass(host.target("game.units.el"));
        Class<?> targetUnitClass = loader.loadClass(host.target("game.units.ce"));
        Class<?> inputClass = loader.loadClass(host.target("gameFramework.f.i"));
        Class<?> commandQueueClass = loader.loadClass(host.target("gameFramework.c"));
        Class<?> gameEngineClass = loader.loadClass(host.target("gameFramework.k"));
        Class<?> blueprintClass = loader.loadClass(host.target("gameFramework.d.a"));

        executeCommand = host.findNoArgMethod(commandClass, "h");
        enqueueOrder = host.findCompatibleMethod(unitClass, "b", orderClass);
        currentOrder = host.findNoArgMethod(unitClass, "ap");
        popOrder = host.findNoArgMethod(unitClass, "as");
        createCommand = host.findNoArgMethod(inputClass, "g");
        buildSetter = host.findCompatibleMethod(
                commandClass, "a", float.class, float.class, buildTypeClass, int.class);
        repairSetter = host.findCompatibleMethod(commandClass, "b", targetUnitClass);
        moveSetter = host.findCompatibleMethod(commandClass, "a", float.class, float.class);
        attachUnit = host.findCompatibleMethod(commandClass, "a", unitClass);
        gameEngineInstance = host.findNoArgMethod(gameEngineClass, "t");
        localTeamField = host.findField(gameEngineClass, "bp");
        blueprintAgeField = host.findField(blueprintClass, "b");
        blueprintRemovedField = host.findField(blueprintClass, "c");
        blueprintBuilderField = host.findField(blueprintClass, "o");
        blueprintRegistryField = host.findField(blueprintClass, "w");
        blueprintTypeField = host.findField(blueprintClass, "d");
        blueprintVariantField = host.findField(blueprintClass, "f");
        blueprintXField = host.findField(blueprintClass, "g");
        blueprintYField = host.findField(blueprintClass, "h");
        blueprintPendingField = host.findField(blueprintClass, "n");
        if (executeCommand == null || enqueueOrder == null || currentOrder == null
                || popOrder == null || createCommand == null || buildSetter == null
                || repairSetter == null || moveSetter == null || attachUnit == null
                || gameEngineInstance == null || localTeamField == null) {
            throw new NoSuchMethodException("智能建造序列原生方法不完整");
        }

        Method singleTick = host.findNoArgMethod(commandQueueClass, "c");
        Method multiplayerTick = host.findNoArgMethod(commandQueueClass, "d");
        if (singleTick != null) {
            tickRegistrations.add(host.tickDispatcher().register(singleTick,
                    ignored -> pollSequences()));
        }
        if (multiplayerTick != null && multiplayerTick != singleTick) {
            tickRegistrations.add(host.tickDispatcher().register(multiplayerTick,
                    ignored -> pollSequences()));
        }

        hooks.add(host.hookExecutable(executeCommand, chain -> {
            Object command = chain.getThisObject();
            ModuleDispatch module;
            synchronized (stateLock) {
                module = moduleCommands.get(command);
            }
            if (module != null) {
                processingModuleCommand.set(module);
                try {
                    return chain.proceed();
                } finally {
                    processingModuleCommand.remove();
                    synchronized (stateLock) {
                        moduleCommands.remove(command);
                    }
                }
            }

            Object order = command == null ? null : host.findFieldValue(command, "j");
            if (enabled && isBuild(order) && capturePlayerBuild(command, order)) {
                return null;
            }
            if (enabled && command != null && !booleanField(command, "e")) {
                cancelSequencesTouchedBy(command);
            }
            return chain.proceed();
        }));

        hooks.add(host.hookExecutable(enqueueOrder, chain -> {
            Object unit = chain.getThisObject();
            Object result = chain.proceed();
            ModuleDispatch module = processingModuleCommand.get();
            if (module != null && module.kind == DispatchKind.BUILD
                    && module.step != null && unit == module.step.leader && result != null) {
                synchronized (stateLock) {
                    if (!module.step.finished) module.step.activeBuildOrder = result;
                }
            }
            return result;
        }));

        hooks.add(host.hookExecutable(popOrder, chain -> {
            if (!enabled) return chain.proceed();
            Object unit = chain.getThisObject();
            Object order;
            try {
                order = currentOrder.invoke(unit);
            } catch (Throwable ignored) {
                return chain.proceed();
            }
            BuildStep step = activeStepForOrder(unit, order);
            if (step == null || !isBuild(order)) return chain.proceed();
            return null;
        }));

    }

    private boolean capturePlayerBuild(Object command, Object order) throws Throwable {
        ArrayList<Object> units = localUnits(command);
        // Serialization distributes a build sequence between multiple
        // selected builders. A one-builder command is native game behavior
        // and must never be captured or replayed by this feature.
        if (units.size() < 2) return false;

        boolean append = booleanField(command, "e");
        BuildStep step = new BuildStep(
                new PointF(number(host.findFieldValue(order, "e")),
                        number(host.findFieldValue(order, "f"))),
                host.findFieldValue(order, "b"),
                intValue(host.findFieldValue(order, "d")));
        BuildSequence sequence;
        synchronized (stateLock) {
            sequence = findSequenceLocked(units);
            if (sequence == null) {
                sequence = new BuildSequence(units, unitIds(units));
                sequences.add(sequence);
            }
            step.sequence = sequence;
            step.initialAppend = append && sequence.steps.isEmpty();
            step.leader = sequence.firstLiving();
            sequence.steps.addLast(step);
        }
        registerBlueprint(step);
        return true;
    }

    private void registerBlueprint(BuildStep step) {
        try {
            Object existing = findNativeBlueprint(step);
            if (existing != null) {
                blueprintBuilderField.set(existing, step.leader);
                blueprintAgeField.setFloat(existing, 0.0f);
                blueprintRemovedField.setBoolean(existing, false);
                synchronized (stateLock) {
                    if (!step.finished && !step.sequence.cancelled) step.blueprint = existing;
                }
                return;
            }
            SegmentCommands commands = host.segmentCommands();
            if (commands == null || step.leader == null) return;
            Object blueprint = commands.registerNativeBuildBlueprint(
                    step.leader, step.point.x, step.point.y,
                    step.buildType, step.variant, blueprintSequence++);
            synchronized (stateLock) {
                if (!step.finished && !step.sequence.cancelled) step.blueprint = blueprint;
                else markBlueprintRemoved(blueprint);
            }
        } catch (Throwable t) {
            host.log(5, TAG, "智能建造序列注册原生蓝图失败", t);
        }
    }

    private Object findNativeBlueprint(BuildStep step) {
        try {
            Object registry = blueprintRegistryField.get(null);
            if (!(registry instanceof Iterable)) return null;
            Object matched = null;
            for (Object blueprint : (Iterable<?>) registry) {
                if (blueprint == null
                        || !blueprintPendingField.getBoolean(blueprint)
                        || blueprintRemovedField.getBoolean(blueprint)) continue;
                Object type = blueprintTypeField.get(blueprint);
                if (type != step.buildType && (type == null || !type.equals(step.buildType))) {
                    continue;
                }
                if (blueprintVariantField.getInt(blueprint) != step.variant) continue;
                float dx = blueprintXField.getFloat(blueprint) - step.point.x;
                float dy = blueprintYField.getFloat(blueprint) - step.point.y;
                if (dx * dx + dy * dy <= POINT_EPSILON_SQUARED) matched = blueprint;
            }
            return matched;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void pollSequences() {
        if (!enabled) return;
        SimulationLifecycle.Observation lifecycle = observeLifecycle();
        if (lifecycle == SimulationLifecycle.Observation.NEW_MATCH) {
            clearState();
            return;
        }
        if (lifecycle == SimulationLifecycle.Observation.RESYNC) prepareAfterResync();
        nativeTick++;
        ArrayList<Action> actions = new ArrayList<>();
        synchronized (stateLock) {
            Iterator<BuildSequence> iterator = sequences.iterator();
            while (iterator.hasNext()) {
                BuildSequence sequence = iterator.next();
                sequence.removeUnavailable(this);
                if (sequence.units.isEmpty() || sequence.cancelled) {
                    iterator.remove();
                    removeBlueprints(sequence);
                    continue;
                }
                keepFutureBlueprints(sequence);
                BuildStep step = sequence.steps.peekFirst();
                if (step == null) {
                    iterator.remove();
                    continue;
                }
                if (step.leader == null || isUnavailableUnit(step.leader)) {
                    step.leader = sequence.firstLiving();
                    step.activeBuildOrder = null;
                    step.dispatched = false;
                }
                if (step.leader == null) continue;

                if (!step.dispatched) {
                    step.dispatched = true;
                    step.dispatchTick = nativeTick;
                    actions.add(Action.build(step));
                    continue;
                }

                Object active = step.activeBuildOrder;
                if (active == null) active = findStepOrder(step.leader, step);
                if (active != null) step.activeBuildOrder = active;
                if (step.target == null && active != null) {
                    step.target = repairTarget(active);
                }
                if (step.target == null && step.activeBuildOrder == null
                        && nativeTick - step.dispatchTick >= DISPATCH_RETRY_TICKS) {
                    step.dispatched = false;
                    continue;
                }
                if (step.target != null && !step.repairIssued) {
                    step.repairIssued = true;
                    actions.add(Action.repair(step, sequence.followers(step.leader)));
                }
                if (step.target != null
                        && (isCompletedTarget(step.target) || isUnavailableTarget(step.target))) {
                    finishStepLocked(sequence, step);
                }
            }
        }
        for (Action action : actions) dispatch(action);
    }

    private void dispatch(Action action) {
        try {
            if (action.kind == DispatchKind.BUILD) {
                issueBuild(action.step);
            } else if (action.kind == DispatchKind.REPAIR && !action.units.isEmpty()) {
                issueRepair(action.step, action.units);
            }
        } catch (Throwable t) {
            synchronized (stateLock) {
                if (action.step != null && !action.step.finished) {
                    if (action.kind == DispatchKind.BUILD) action.step.dispatched = false;
                    if (action.kind == DispatchKind.REPAIR) action.step.repairIssued = false;
                }
            }
            host.log(5, TAG, "智能建造序列原生指令下发失败", t);
        }
    }

    private void issueBuild(BuildStep step) throws Throwable {
        ArrayList<Object> followers = step.sequence.followers(step.leader);
        if (!followers.isEmpty()) {
            issueMove(followers, step.point.x, step.point.y, step.initialAppend);
        }
        Object command = createCommand.invoke(null);
        if (command == null) throw new IllegalStateException("native build command unavailable");
        setCommandFlags(command, step.initialAppend);
        buildSetter.invoke(command, step.point.x, step.point.y, step.buildType, step.variant);
        attachUnit.invoke(command, step.leader);
        synchronized (stateLock) {
            moduleCommands.put(command, new ModuleDispatch(DispatchKind.BUILD, step));
        }
    }

    private void issueRepair(BuildStep step, ArrayList<Object> followers) throws Throwable {
        Object command = createCommand.invoke(null);
        if (command == null) throw new IllegalStateException("native repair command unavailable");
        setCommandFlags(command, false);
        repairSetter.invoke(command, step.target);
        for (Object unit : followers) attachUnit.invoke(command, unit);
        synchronized (stateLock) {
            moduleCommands.put(command, new ModuleDispatch(DispatchKind.REPAIR, step));
        }
    }

    private void issueMove(ArrayList<Object> units, float x, float y, boolean append)
            throws Throwable {
        Object command = createCommand.invoke(null);
        if (command == null) throw new IllegalStateException("native move command unavailable");
        setCommandFlags(command, append);
        moveSetter.invoke(command, x, y);
        for (Object unit : units) attachUnit.invoke(command, unit);
        synchronized (stateLock) {
            moduleCommands.put(command, new ModuleDispatch(DispatchKind.MOVE, null));
        }
    }

    private void setCommandFlags(Object command, boolean append) throws Throwable {
        Field appendField = host.findField(command.getClass(), "e");
        Field queuedField = host.findField(command.getClass(), "h");
        appendField.setBoolean(command, append);
        queuedField.setBoolean(command, true);
    }

    private BuildStep activeStepForOrder(Object unit, Object order) {
        if (unit == null || order == null) return null;
        synchronized (stateLock) {
            for (BuildSequence sequence : sequences) {
                BuildStep step = sequence.steps.peekFirst();
                if (step != null && !step.finished && step.leader == unit
                        && step.activeBuildOrder == order) return step;
            }
        }
        return null;
    }

    private Object findStepOrder(Object unit, BuildStep step) {
        try {
            Object countValue = host.findFieldValue(unit, "O");
            Object queue = host.findFieldValue(unit, "Q");
            if (!(countValue instanceof Number) || queue == null) return null;
            int count = Math.min(Math.max(0, ((Number) countValue).intValue()),
                    Array.getLength(queue));
            for (int i = 0; i < count; i++) {
                Object order = Array.get(queue, i);
                String type = orderTypeName(order);
                if ("build".equals(type) && matchesStep(order, step)) return order;
                if ("repair".equals(type)
                        && matchesStepTarget(host.findFieldValue(order, "h"), step)) return order;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean matchesStep(Object order, BuildStep step) throws Throwable {
        Object type = host.findFieldValue(order, "b");
        if (type != step.buildType && (type == null || !type.equals(step.buildType))) return false;
        float dx = number(host.findFieldValue(order, "e")) - step.point.x;
        float dy = number(host.findFieldValue(order, "f")) - step.point.y;
        return dx * dx + dy * dy <= POINT_EPSILON_SQUARED
                && intValue(host.findFieldValue(order, "d")) == step.variant;
    }

    private Object repairTarget(Object order) {
        try {
            return order != null && "repair".equals(orderTypeName(order))
                    ? host.findFieldValue(order, "h") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void finishStepLocked(BuildSequence sequence, BuildStep step) {
        if (step.finished) return;
        step.finished = true;
        markBlueprintRemoved(step.blueprint);
        if (sequence.steps.peekFirst() == step) sequence.steps.removeFirst();
    }

    private void keepFutureBlueprints(BuildSequence sequence) {
        boolean first = true;
        for (BuildStep step : sequence.steps) {
            if (first && step.dispatched) {
                first = false;
                continue;
            }
            first = false;
            Object blueprint = step.blueprint;
            if (blueprint == null) continue;
            try {
                blueprintAgeField.setFloat(blueprint, 0.0f);
                blueprintRemovedField.setBoolean(blueprint, false);
                if (step.leader != null) blueprintBuilderField.set(blueprint, step.leader);
            } catch (Throwable ignored) {
            }
        }
    }

    private void removeBlueprints(BuildSequence sequence) {
        for (BuildStep step : sequence.steps) markBlueprintRemoved(step.blueprint);
    }

    private void markBlueprintRemoved(Object blueprint) {
        if (blueprint == null || blueprintRemovedField == null) return;
        try {
            blueprintRemovedField.setBoolean(blueprint, true);
        } catch (Throwable ignored) {
        }
    }

    private BuildSequence findSequenceLocked(ArrayList<Object> units) {
        for (BuildSequence sequence : sequences) {
            if (!sequence.cancelled && sequence.sameUnits(units)) return sequence;
        }
        return null;
    }

    private void cancelSequencesTouchedBy(Object command) {
        try {
            Object rawUnits = host.findFieldValue(command, "w");
            if (!(rawUnits instanceof Iterable)) return;
            ArrayList<Object> touched = new ArrayList<>();
            for (Object unit : (Iterable<?>) rawUnits) touched.add(unit);
            ArrayList<BuildSequence> cancelled = new ArrayList<>();
            synchronized (stateLock) {
                Iterator<BuildSequence> iterator = sequences.iterator();
                while (iterator.hasNext()) {
                    BuildSequence sequence = iterator.next();
                    if (sequence.overlaps(touched)) {
                        iterator.remove();
                        sequence.cancelled = true;
                        cancelled.add(sequence);
                    }
                }
            }
            for (BuildSequence sequence : cancelled) removeBlueprints(sequence);
        } catch (Throwable ignored) {
        }
    }

    private ArrayList<Object> localUnits(Object command) throws Throwable {
        ArrayList<Object> result = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Object raw = host.findFieldValue(command, "w");
        if (raw instanceof Iterable) {
            for (Object unit : (Iterable<?>) raw) {
                if (isLocalUnit(unit) && seen.put(unit, Boolean.TRUE) == null) result.add(unit);
            }
        }
        return result;
    }

    private boolean isLocalUnit(Object unit) {
        try {
            Object engine = gameEngineInstance.invoke(null);
            Object local = engine == null ? null : localTeamField.get(engine);
            return unit != null && local != null && host.findFieldValue(unit, "bZ") == local;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isUnavailableUnit(Object unit) {
        try {
            return unit == null || Boolean.TRUE.equals(host.findFieldValue(unit, "bX"));
        } catch (Throwable ignored) {
            return unit == null;
        }
    }

    private boolean isCompletedTarget(Object target) {
        try {
            Object health = host.findFieldValue(target, "cw");
            Object maximum = host.findFieldValue(target, "cx");
            Object progress = host.findFieldValue(target, "co");
            return health instanceof Number && maximum instanceof Number
                    && progress instanceof Number
                    && ((Number) health).floatValue() >= ((Number) maximum).floatValue()
                    && ((Number) progress).floatValue() >= 1.0f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isUnavailableTarget(Object target) {
        try {
            return target == null || Boolean.TRUE.equals(host.findFieldValue(target, "bX"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isBuild(Object order) {
        try {
            return order != null && "build".equals(orderTypeName(order));
        } catch (Throwable ignored) {
            return false;
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

    private boolean booleanField(Object object, String name) {
        try {
            return Boolean.TRUE.equals(host.findFieldValue(object, name));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private float number(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    private int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private void clearState() {
        ArrayList<BuildSequence> old;
        synchronized (stateLock) {
            old = new ArrayList<>(sequences);
            sequences.clear();
            moduleCommands.clear();
            nativeTick = 0;
            blueprintSequence = 0;
            simulationLifecycle.reset(host.completedResyncGeneration());
        }
        for (BuildSequence sequence : old) removeBlueprints(sequence);
        processingModuleCommand.remove();
    }

    private SimulationLifecycle.Observation observeLifecycle() {
        try {
            Object engine = gameEngineInstance.invoke(null);
            int tick = ((Number) host.findFieldValue(engine, "bu")).intValue();
            return simulationLifecycle.observe(tick, host.completedResyncGeneration());
        } catch (Throwable ignored) {
            return SimulationLifecycle.Observation.INVALID;
        }
    }

    private void prepareAfterResync() {
        Map<Long, Object> live = liveUnitsById();
        synchronized (stateLock) {
            moduleCommands.clear();
            Iterator<BuildSequence> iterator = sequences.iterator();
            while (iterator.hasNext()) {
                BuildSequence sequence = iterator.next();
                sequence.rebind(live);
                if (sequence.units.isEmpty()) {
                    iterator.remove();
                    continue;
                }
                boolean current = true;
                for (BuildStep step : sequence.steps) {
                    step.leader = sequence.firstLiving();
                    step.blueprint = findNativeBlueprint(step);
                    step.activeBuildOrder = null;
                    step.target = null;
                    step.repairIssued = false;
                    step.dispatchTick = nativeTick;
                    if (current && step.leader != null) {
                        step.activeBuildOrder = findStepOrder(step.leader, step);
                        if (step.activeBuildOrder != null) {
                            step.target = repairTarget(step.activeBuildOrder);
                        }
                        if (step.target == null) step.target = findExistingTarget(step, live);
                        step.dispatched = step.activeBuildOrder != null || step.target != null;
                    } else {
                        step.dispatched = false;
                    }
                    current = false;
                }
            }
        }
        processingModuleCommand.remove();
    }

    private ArrayList<Long> unitIds(ArrayList<Object> units) {
        ArrayList<Long> result = new ArrayList<>();
        for (Object unit : units) {
            long id = unitId(unit);
            if (id >= 0 && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private long unitId(Object unit) {
        try {
            Object value = host.findFieldValue(unit, "ej");
            return value instanceof Number ? ((Number) value).longValue() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private Map<Long, Object> liveUnitsById() {
        HashMap<Long, Object> result = new HashMap<>();
        try {
            Class<?> registry = loader.loadClass(host.target("gameFramework.ah"));
            Object all = host.findField(registry, "et").get(null);
            if (all instanceof Iterable) {
                for (Object unit : (Iterable<?>) all) {
                    if (unit != null && unitClass.isInstance(unit) && !isUnavailableUnit(unit)) {
                        long id = unitId(unit);
                        if (id >= 0) result.put(id, unit);
                    }
                }
            }
        } catch (Throwable t) {
            host.log(5, TAG, "智能建造同步后单位重绑定失败", t);
        }
        return result;
    }

    private Object findExistingTarget(BuildStep step, Map<Long, Object> live) {
        for (Object unit : live.values()) if (matchesStepTarget(unit, step)) return unit;
        return null;
    }

    private boolean matchesStepTarget(Object target, BuildStep step) {
        if (target == null) return false;
        try {
            Method typeMethod = host.findNoArgMethod(target.getClass(), "q");
            Object type = typeMethod == null ? null : typeMethod.invoke(target);
            if (type != step.buildType && (type == null || !type.equals(step.buildType))) return false;
            float dx = number(host.findFieldValue(target, "eq")) - step.point.x;
            float dy = number(host.findFieldValue(target, "er")) - step.point.y;
            return dx * dx + dy * dy <= POINT_EPSILON_SQUARED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void uninstallHooksLocked() {
        for (GameTickDispatcher.Registration registration : tickRegistrations) {
            try {
                registration.close();
            } catch (Throwable ignored) {
            }
        }
        tickRegistrations.clear();
        for (XposedInterface.HookHandle hook : hooks) {
            try {
                hook.unhook();
            } catch (Throwable ignored) {
            }
        }
        hooks.clear();
        executeCommand = null;
        enqueueOrder = null;
        currentOrder = null;
        popOrder = null;
        createCommand = null;
        buildSetter = null;
        repairSetter = null;
        moveSetter = null;
        attachUnit = null;
        gameEngineInstance = null;
        localTeamField = null;
        blueprintAgeField = null;
        blueprintRemovedField = null;
        blueprintBuilderField = null;
        blueprintRegistryField = null;
        blueprintTypeField = null;
        blueprintVariantField = null;
        blueprintXField = null;
        blueprintYField = null;
        blueprintPendingField = null;
        unitClass = null;
    }

    private enum DispatchKind { MOVE, BUILD, REPAIR }

    private static final class ModuleDispatch {
        final DispatchKind kind;
        final BuildStep step;

        ModuleDispatch(DispatchKind kind, BuildStep step) {
            this.kind = kind;
            this.step = step;
        }
    }

    private static final class Action {
        final DispatchKind kind;
        final BuildStep step;
        final ArrayList<Object> units;

        private Action(DispatchKind kind, BuildStep step, ArrayList<Object> units) {
            this.kind = kind;
            this.step = step;
            this.units = units;
        }

        static Action build(BuildStep step) {
            return new Action(DispatchKind.BUILD, step, new ArrayList<>());
        }

        static Action repair(BuildStep step, ArrayList<Object> units) {
            return new Action(DispatchKind.REPAIR, step, units);
        }
    }

    private static final class BuildSequence {
        final ArrayList<Object> units = new ArrayList<>();
        final IdentityHashMap<Object, Boolean> unitSet = new IdentityHashMap<>();
        final ArrayList<Long> unitIds;
        final ArrayDeque<BuildStep> steps = new ArrayDeque<>();
        boolean cancelled;

        BuildSequence(ArrayList<Object> builders, ArrayList<Long> unitIds) {
            this.unitIds = new ArrayList<>(unitIds);
            for (Object unit : builders) {
                units.add(unit);
                unitSet.put(unit, Boolean.TRUE);
            }
        }

        void rebind(Map<Long, Object> live) {
            units.clear();
            unitSet.clear();
            for (Long id : unitIds) {
                Object unit = live.get(id);
                if (unit != null) {
                    units.add(unit);
                    unitSet.put(unit, Boolean.TRUE);
                }
            }
        }

        boolean sameUnits(ArrayList<Object> builders) {
            if (builders.size() != units.size()) return false;
            for (Object unit : builders) if (!unitSet.containsKey(unit)) return false;
            return true;
        }

        boolean overlaps(ArrayList<Object> builders) {
            for (Object unit : builders) if (unitSet.containsKey(unit)) return true;
            return false;
        }

        Object firstLiving() {
            return units.isEmpty() ? null : units.get(0);
        }

        ArrayList<Object> followers(Object leader) {
            ArrayList<Object> result = new ArrayList<>();
            for (Object unit : units) if (unit != leader) result.add(unit);
            return result;
        }

        void removeUnavailable(SmartBuildSerialization owner) {
            Iterator<Object> iterator = units.iterator();
            while (iterator.hasNext()) {
                Object unit = iterator.next();
                if (owner.isUnavailableUnit(unit)) {
                    iterator.remove();
                    unitSet.remove(unit);
                }
            }
        }
    }

    private static final class BuildStep {
        final PointF point;
        final Object buildType;
        final int variant;
        BuildSequence sequence;
        Object leader;
        Object blueprint;
        Object activeBuildOrder;
        Object target;
        boolean initialAppend;
        boolean dispatched;
        boolean repairIssued;
        boolean finished;
        int dispatchTick;

        BuildStep(PointF point, Object buildType, int variant) {
            this.point = point;
            this.buildType = buildType;
            this.variant = variant;
        }
    }
}
