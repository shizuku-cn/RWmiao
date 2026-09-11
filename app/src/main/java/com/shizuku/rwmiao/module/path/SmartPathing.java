package com.shizuku.rwmiao.module;

import android.graphics.Point;
import android.graphics.PointF;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import io.github.libxposed.api.XposedInterface;
import com.shizuku.rwmiao.module.support.GameTickDispatcher;
import com.shizuku.rwmiao.module.support.path.AStarPathfinder;
import com.shizuku.rwmiao.module.support.path.FlowFieldPathfinder;
import com.shizuku.rwmiao.module.support.path.SmartPathGrid;

import static com.shizuku.rwmiao.config.SettingsContract.DEFAULT_SMART_PATHING_THRESHOLD;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_SMART_PATHING;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_SMART_PATHING_THRESHOLD;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_SHOW_SMART_PATH_ACTION;

final class SmartPathing {
    private static final String TAG = "RWmiao";
    private static final int GRID_CACHE_TICKS = 30;
    private static final String[] COMMAND_QUEUE_FIELDS = {"b", "d"};

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final SegmentCommands commands;
    private final AStarPathfinder pathfinder = new AStarPathfinder();
    private final FlowFieldPathfinder flowPathfinder = new FlowFieldPathfinder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "RWmiao-Path");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentLinkedQueue<PathResult> ready = new ConcurrentLinkedQueue<>();
    private final ArrayList<StagedRoute> stagedRoutes = new ArrayList<>();
    private final ConcurrentHashMap<Long, Long> unitTokens = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();
    private final Map<Object, CachedGrid> gridCache = new WeakHashMap<>();
    private final Set<Object> inspectedCommands = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> generatedCommands = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Long> manualEnabled = new HashSet<>();
    private final Set<Long> manualDisabled = new HashSet<>();
    private final SimulationLifecycle simulationLifecycle = new SimulationLifecycle();
    private volatile Boolean enabledState;
    private volatile Boolean showActionState;
    private volatile Integer thresholdState;
    private volatile PendingMove pendingMove;
    private Method groundMethod;
    private final ArrayList<Method> tickMethods = new ArrayList<>();
    private final ArrayList<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private final ArrayList<GameTickDispatcher.Registration> tickRegistrations = new ArrayList<>();

    SmartPathing(RWmiaoModule host, ClassLoader loader, SegmentCommands commands) {
        this.host = host;
        this.loader = loader;
        this.commands = commands;
    }

    void install() throws Throwable {
        if (commands == null) throw new IllegalStateException("native waypoint emitter missing");
        Class<?> input = loader.loadClass(host.target("gameFramework.f.i"));
        groundMethod = host.findCompatibleMethod(input, "a", float.class, float.class, Point.class);
        if (groundMethod == null) throw new NoSuchMethodException("ground click a(float,float,Point)");

        Class<?> queueClass = loader.loadClass(host.target("gameFramework.c"));
        Method singleTick = host.findNoArgMethod(queueClass, "c");
        Method multiplayerTick = host.findNoArgMethod(queueClass, "d");
        if (singleTick != null) tickMethods.add(singleTick);
        if (multiplayerTick != null && multiplayerTick != singleTick) tickMethods.add(multiplayerTick);
        refreshSettings();
    }

    private synchronized void ensureHooks() {
        if (!hooks.isEmpty()) return;
        hooks.add(host.hookExecutable(groundMethod, chain -> {
            Object result = chain.proceed();
            try {
                scheduleMove(((Number) chain.getArg(0)).floatValue(),
                        ((Number) chain.getArg(1)).floatValue());
            } catch (Throwable t) {
                host.log(5, TAG, "Smart move request skipped", t);
            }
            return result;
        }));
        ensureTickHooks();
    }

    private synchronized void ensureTickHooks() {
        if (!runtimeAvailable() || !tickRegistrations.isEmpty()) return;
        for (Method tick : tickMethods) tickRegistrations.add(hookQueueTick(tick));
    }

    private synchronized void disableHooks() {
        for (XposedInterface.HookHandle handle : hooks) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        hooks.clear();
        for (GameTickDispatcher.Registration registration : tickRegistrations) {
            try { registration.close(); } catch (Throwable ignored) { }
        }
        tickRegistrations.clear();
    }

    void refreshSettings() {
        enabledState = null;
        showActionState = null;
        thresholdState = null;
        if (!runtimeAvailable()) {
            long token = nextToken.incrementAndGet();
            unitTokens.replaceAll((id, ignored) -> token);
            ready.clear();
            synchronized (stagedRoutes) { stagedRoutes.clear(); }
            disableHooks();
        } else {
            ensureHooks();
        }
        synchronized (gridCache) {
            gridCache.clear();
        }
    }

    private GameTickDispatcher.Registration hookQueueTick(Method tick) {
        return host.tickDispatcher().register(tick, queue -> {
            try {
                Object engine = host.findEngine(loader);
                SimulationLifecycle.Observation lifecycle = simulationLifecycle.observe(
                        currentTick(engine), host.completedResyncGeneration());
                if (lifecycle == SimulationLifecycle.Observation.RESYNC) {
                    invalidateWorldState(false);
                } else if (lifecycle == SimulationLifecycle.Observation.NEW_MATCH) {
                    invalidateWorldState(true);
                }
                flushPendingMove();
                flushStagedRoutes(queue);
                drainReady();
                inspectTerminalCommands(queue);
            } catch (Throwable t) {
                host.log(5, TAG, "Smart terminal queue inspection skipped", t);
            }
        });
    }

    private void scheduleMove(float targetX, float targetY) throws Throwable {
        if (!available() || commands.hasActiveSelection()) return;
        Object engine = host.findEngine(loader);
        ArrayList<Object> selected = selectedOwnUnits(engine);
        if (selected.isEmpty()) return;
        long token = nextToken.incrementAndGet();
        if (selected.size() > threshold()) {
            for (Object unit : selected) unitTokens.put(unitId(unit), token);
            pendingMove = null;
            return;
        }
        ArrayList<Object> active = smartEnabledUnits(selected);
        if (active.isEmpty()) return;
        for (Object unit : active) unitTokens.put(unitId(unit), token);
        PendingMove move = new PendingMove(
                token, currentTick(engine), targetX, targetY, active);
        pendingMove = move;

        if (isMultiplayer(engine)) {
            List<UnitRequest> requests = captureRequests(
                    engine, move.units, targetX, targetY, null, token);
            if (!requests.isEmpty() && pendingMove == move) {
                pendingMove = null;
                submit(requests);
            }
        }
    }

    private boolean isMultiplayer(Object engine) {
        try {
            Object network = host.findFieldValue(engine, "bU");
            return network != null && host.boolField(network, "C");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void flushPendingMove() throws Throwable {
        PendingMove pending = pendingMove;
        if (pending == null) return;
        Object engine = host.findEngine(loader);
        if (currentTick(engine) - pending.tick < 2 || pendingMove != pending) return;
        pendingMove = null;
        submit(captureRequests(engine, pending.units,
                pending.targetX, pending.targetY, null, pending.token));
    }

    private void inspectTerminalCommands(Object queue) throws Throwable {
        if (!available()) return;
        for (String fieldName : COMMAND_QUEUE_FIELDS) {
            Object pending = host.findFieldValue(queue, fieldName);
            if (!(pending instanceof Iterable)) continue;
            if (pending instanceof java.util.Collection
                    && ((java.util.Collection<?>) pending).isEmpty()) continue;
            ArrayList<Object> snapshot = new ArrayList<>();
            for (Object command : (Iterable<?>) pending) if (command != null) snapshot.add(command);
            if (snapshot.isEmpty()) continue;
            Set<Object> batchBuildUnits = batchBuildUnits(snapshot);
            for (Object command : snapshot) {
                synchronized (inspectedCommands) {
                    if (inspectedCommands.contains(command)) continue;
                    inspectedCommands.add(command);
                }
                synchronized (generatedCommands) {
                    if (generatedCommands.contains(command)) continue;
                }
                if (commands.isManualTerminal(command)) continue;
                TerminalSpec terminal = terminalSpec(command);
                if (terminal == null) continue;
                ArrayList<Object> units = ownCommandUnits(command);
                if (units.isEmpty()) continue;
                if ("build".equals(terminal.type)
                        && containsIdentity(batchBuildUnits, units)) continue;
                long token = nextToken.incrementAndGet();
                if (units.size() > threshold()) {
                    for (Object unit : units) unitTokens.put(unitId(unit), token);
                    continue;
                }
                units = smartEnabledUnits(units);
                if (units.isEmpty()) continue;
                for (Object unit : units) unitTokens.put(unitId(unit), token);
                Object engine = host.findEngine(loader);
                List<UnitRequest> requests = captureRequests(
                        engine, units, terminal.pathX(), terminal.pathY(), terminal, token);
                ArrayList<Object> routedUnits = new ArrayList<>();
                for (UnitRequest request : requests) routedUnits.add(request.unit);
                removeCommandUnits(command, routedUnits);
                submit(requests);
            }
        }
    }

    private void submit(List<UnitRequest> requests) {
        if (requests.isEmpty()) return;
        worker.execute(() -> {
            IdentityHashMap<SmartPathGrid, ArrayList<UnitRequest>> groups = new IdentityHashMap<>();
            for (UnitRequest request : requests) {
                groups.computeIfAbsent(request.grid, ignored -> new ArrayList<>()).add(request);
            }
            for (ArrayList<UnitRequest> group : groups.values()) {
                UnitRequest first = group.get(0);
                if (group.size() >= 4 && !constrainedTarget(first)
                        && !isDirectAction(first)) {
                    ArrayList<FlowFieldPathfinder.Input> inputs = new ArrayList<>();
                    for (UnitRequest request : group) {
                        inputs.add(new FlowFieldPathfinder.Input(
                                request.startX, request.startY, request.radius));
                    }
                    List<List<PointF>> routes = flowPathfinder.findAll(first.grid, inputs,
                            first.targetX, first.targetY, first.terminal == null,
                            () -> groupCancelled(group));
                    boolean complete = routes.size() == group.size()
                            && !groupCancelled(group);
                    if (complete) {
                        for (int i = 0; i < group.size(); i++) {
                            UnitRequest request = group.get(i);
                            List<PointF> route = routes.get(i);
                            if (route.isEmpty() && request.terminal == null
                                    && tokenCurrent(request.unitId, request.token)) {
                                route = pathfinder.find(request.grid,
                                        request.startX, request.startY,
                                        request.targetX, request.targetY,
                                        () -> !tokenCurrent(request.unitId, request.token));
                            }
                            if (!route.isEmpty() || request.terminal != null) {
                                ready.add(new PathResult(request, route));
                            }
                        }
                    }
                    if (complete) continue;
                }
                submitIndividual(group);
            }
        });
    }

    private void submitIndividual(List<UnitRequest> group) {
        for (UnitRequest request : group) {
            if (!tokenCurrent(request.unitId, request.token)) continue;
            List<PointF> route = isDirectAction(request)
                    ? pathfinder.findActionApproach(request.grid,
                    request.startX, request.startY, request.targetX, request.targetY,
                    request.targetRadius,
                    () -> !tokenCurrent(request.unitId, request.token))
                    : pathfinder.find(request.grid,
                    request.startX, request.startY, request.targetX, request.targetY,
                    () -> !tokenCurrent(request.unitId, request.token));
            if ((!route.isEmpty() || request.terminal != null)
                    && tokenCurrent(request.unitId, request.token)) {
                ready.add(new PathResult(request, route));
            }
        }
    }

    private boolean isDirectAction(UnitRequest request) {
        return request.terminal != null
                && ("attack".equals(request.terminal.type)
                || "build".equals(request.terminal.type));
    }

    private boolean constrainedTarget(UnitRequest request) {
        int x = Math.max(0, Math.min(request.grid.width - 1,
                (int) (request.targetX * request.grid.worldToGrid)));
        int y = Math.max(0, Math.min(request.grid.height - 1,
                (int) (request.targetY * request.grid.worldToGrid)));
        return !request.grid.passable(x, y) || request.grid.touchesBlockedCell(x, y);
    }

    private Set<Object> batchBuildUnits(List<Object> commands) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> repeated = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object command : commands) {
            try {
                TerminalSpec terminal = terminalSpec(command);
                if (terminal == null || !"build".equals(terminal.type)) continue;
                for (Object unit : ownCommandUnits(command)) {
                    if (!seen.add(unit)) repeated.add(unit);
                }
            } catch (Throwable ignored) {
            }
        }
        return repeated;
    }

    private boolean containsIdentity(Set<Object> candidates, List<Object> units) {
        for (Object unit : units) if (candidates.contains(unit)) return true;
        return false;
    }

    private boolean groupCancelled(List<UnitRequest> group) {
        for (UnitRequest request : group) {
            if (!tokenCurrent(request.unitId, request.token)) return true;
        }
        return false;
    }

    private void drainReady() throws Throwable {
        boolean multiplayer = isMultiplayer(host.findEngine(loader));
        PathResult result;
        while ((result = ready.poll()) != null) {
            UnitRequest request = result.request;
            if (!available() || !tokenCurrent(request.unitId, request.token)
                    || !unitStillOwned(request.unit)) continue;
            List<PointF> route = result.route;
            boolean appendTerminal = false;
            if (request.terminal != null) {
                route = terminalApproach(request, route);
                appendTerminal = !route.isEmpty();
            }
            if (request.terminal == null && multiplayer) {
                route = normalizeMultiplayerEndpoint(request, route);
            }
            if (multiplayer && !route.isEmpty()) {
                Object barrier = commands.issueComputedPathHead(request.unit, route.get(0));
                if (barrier != null && (route.size() > 1 || request.terminal != null)) {
                    synchronized (stagedRoutes) {
                        stagedRoutes.add(new StagedRoute(request, route, barrier));
                    }
                }
                continue;
            }
            if (!route.isEmpty()) commands.issueComputedPath(request.unit, route);
            if (request.terminal != null) {
                Object finalCommand = issueTerminal(
                        request.unit, request.terminal, appendTerminal);
                if (finalCommand != null) {
                    synchronized (generatedCommands) {
                        generatedCommands.add(finalCommand);
                    }
                }
            }
        }
    }

    private void flushStagedRoutes(Object queue) throws Throwable {
        synchronized (stagedRoutes) {
            if (stagedRoutes.isEmpty()) return;
            for (int index = stagedRoutes.size() - 1; index >= 0; index--) {
                StagedRoute staged = stagedRoutes.get(index);
                UnitRequest request = staged.request;
                if (!tokenCurrent(request.unitId, request.token)
                        || !unitStillOwned(request.unit)) {
                    stagedRoutes.remove(index);
                    continue;
                }
                if (commandQueued(queue, staged.barrier, "d")
                        || commandQueued(queue, staged.barrier, "c")) continue;
                if (staged.nextIndex < staged.route.size()) {
                    Object next = commands.issueComputedPathAppend(
                            request.unit, staged.route.get(staged.nextIndex));
                    staged.nextIndex++;
                    staged.barrier = next;
                    if (next != null) continue;
                }
                if (request.terminal != null) {
                    Object finalCommand = issueTerminal(request.unit, request.terminal, true);
                    if (finalCommand != null) {
                        synchronized (generatedCommands) {
                            generatedCommands.add(finalCommand);
                        }
                    }
                }
                stagedRoutes.remove(index);
            }
        }
    }

    private boolean commandQueued(Object queue, Object command, String fieldName) {
        try {
            Object value = host.findFieldValue(queue, fieldName);
            if (!(value instanceof Iterable)) return false;
            for (Object queued : (Iterable<?>) value) if (queued == command) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private List<PointF> normalizeMultiplayerEndpoint(UnitRequest request,
                                                       List<PointF> route) {
        if (route.isEmpty()) return route;
        PointF last = route.get(route.size() - 1);
        if (Math.abs(last.x - request.targetX) > 0.5f
                || Math.abs(last.y - request.targetY) > 0.5f) return route;
        int x = Math.max(0, Math.min(request.grid.width - 1,
                (int) (last.x * request.grid.worldToGrid)));
        int y = Math.max(0, Math.min(request.grid.height - 1,
                (int) (last.y * request.grid.worldToGrid)));
        if (!request.grid.touchesBlockedCell(x, y)) return route;
        ArrayList<PointF> safe = new ArrayList<>(route);
        safe.set(safe.size() - 1, new PointF(
                (x + 0.5f) / request.grid.worldToGrid,
                (y + 0.5f) / request.grid.worldToGrid));
        return safe;
    }

    private List<PointF> terminalApproach(UnitRequest request, List<PointF> route) {
        if (route.isEmpty()) return route;
        float targetX = request.terminal.x;
        float targetY = request.terminal.y;
        float targetRadius = request.targetRadius;
        if ("attack".equals(request.terminal.type) && request.terminal.target != null) {
            try {
                targetX = host.number(host.findFieldValue(request.terminal.target, "eq"));
                targetY = host.number(host.findFieldValue(request.terminal.target, "er"));
                targetRadius = Math.max(0.0f,
                        host.number(host.findFieldValue(request.terminal.target, "cl")));
            } catch (Throwable ignored) {
            }
        }
        if (clearTerminalApproach(request, request.startX, request.startY,
                targetX, targetY, targetRadius)) {
            return Collections.emptyList();
        }
        for (int i = 0; i < route.size(); i++) {
            PointF point = route.get(i);
            if (clearTerminalApproach(request, point.x, point.y,
                    targetX, targetY, targetRadius)) {
                return new ArrayList<>(route.subList(0, i + 1));
            }
        }
        return route;
    }

    private boolean clearTerminalApproach(UnitRequest request, float x, float y,
                                          float targetX, float targetY,
                                          float targetRadius) {
        return "attack".equals(request.terminal.type)
                ? request.grid.lineOfSightToTargetFootprint(
                x, y, targetX, targetY, targetRadius)
                : request.grid.lineOfSightToTarget(x, y, targetX, targetY);
    }

    private Object issueTerminal(Object unit, TerminalSpec terminal, boolean append)
            throws Throwable {
        if ("attack".equals(terminal.type)) {
            if (terminal.target == null || host.boolField(terminal.target, "bX")) return null;
            return commands.issueAttack(unit, terminal.target, append);
        }
        if ("build".equals(terminal.type)) {
            return commands.issueBuild(unit, terminal.x, terminal.y,
                    terminal.buildType, terminal.variant, append);
        }
        if ("attackMove".equals(terminal.type)) {
            return commands.issueAttackMove(unit, terminal.x, terminal.y, append);
        }
        return null;
    }

    private List<UnitRequest> captureRequests(Object engine, List<Object> units,
                                              float targetX, float targetY,
                                              TerminalSpec terminal, long token) throws Throwable {
        Object map = host.findFieldValue(engine, "bI");
        Object pathEngine = host.findFieldValue(engine, "bR");
        if (map == null || pathEngine == null) return Collections.emptyList();
        float scale = host.number(host.findFieldValue(map, "r"));
        if (scale <= 0.0f) return Collections.emptyList();
        int tick = currentTick(engine);
        Class<?> movementType = loader.loadClass(host.target("game.units.cg"));
        Method layerFor = host.findCompatibleMethod(pathEngine.getClass(), "a", movementType);
        if (layerFor == null) return Collections.emptyList();

        ArrayList<Object> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingLong(this::unitId));
        IdentityHashMap<Object, SmartPathGrid> requestGrids = new IdentityHashMap<>();
        ArrayList<UnitRequest> result = new ArrayList<>();
        float targetRadius = 0.0f;
        if (terminal != null && "attack".equals(terminal.type)
                && terminal.target != null) {
            try {
                targetRadius = Math.max(0.0f,
                        host.number(host.findFieldValue(terminal.target, "cl")));
            } catch (Throwable ignored) {
            }
        }
        for (Object unit : sorted) {
            long id = unitId(unit);
            if (!tokenCurrent(id, token)) continue;
            Object movement = movementMethod(unit).invoke(unit);
            if (movement == null || "AIR".equals(enumName(movement))) continue;
            Object layer = layerFor.invoke(pathEngine, movement);
            SmartPathGrid grid = requestGrids.get(layer);
            if (grid == null) {
                grid = cachedGrid(layer, scale, tick);
                if (grid != null) requestGrids.put(layer, grid);
            }
            if (grid == null || !grid.valid()) continue;
            result.add(new UnitRequest(unit, id, token,
                    host.number(host.findFieldValue(unit, "eq")),
                    host.number(host.findFieldValue(unit, "er")),
                    Math.max(1.0f, host.number(host.findFieldValue(unit, "cl"))),
                    targetX, targetY, targetRadius, grid, terminal));
        }
        return result;
    }

    private SmartPathGrid cachedGrid(Object layer, float scale, int tick) throws Throwable {
        if (layer == null) return null;
        synchronized (gridCache) {
            CachedGrid cached = gridCache.get(layer);
            if (cached != null && tick - cached.tick >= 0
                    && tick - cached.tick <= GRID_CACHE_TICKS) return cached.grid;
        }
        int width = ((Number) host.findFieldValue(layer, "b")).intValue();
        int height = ((Number) host.findFieldValue(layer, "c")).intValue();
        Object terrain = host.findFieldValue(layer, "d");
        Object buildings = host.findFieldValue(layer, "e");
        Object objects = host.findFieldValue(layer, "f");
        if (!(terrain instanceof byte[]) || !(buildings instanceof byte[])
                || !(objects instanceof byte[])) return null;
        SmartPathGrid grid = new SmartPathGrid(width, height, scale,
                ((byte[]) terrain).clone(), ((byte[]) buildings).clone(),
                ((byte[]) objects).clone());
        if (!grid.valid()) return null;
        synchronized (gridCache) {
            gridCache.put(layer, new CachedGrid(tick, grid));
        }
        return grid;
    }

    private TerminalSpec terminalSpec(Object command) throws Throwable {
        Object order = host.findFieldValue(command, "j");
        String type = orderTypeName(order);
        if (!("attack".equals(type) || "build".equals(type)
                || "attackMove".equals(type))) return null;
        float x = host.number(host.findFieldValue(order, "e"));
        float y = host.number(host.findFieldValue(order, "f"));
        if ("attack".equals(type)) {
            Object target = host.findFieldValue(order, "h");
            if (target == null) return null;
            x = host.number(host.findFieldValue(target, "eq"));
            y = host.number(host.findFieldValue(target, "er"));
            return new TerminalSpec(type, x, y, target, null, 0);
        }
        if ("build".equals(type)) {
            Object buildType = host.findFieldValue(order, "b");
            int variant = ((Number) host.findFieldValue(order, "d")).intValue();
            if (buildType == null) return null;
            return new TerminalSpec(type, x, y, null, buildType, variant);
        }
        return new TerminalSpec(type, x, y, null, null, 0);
    }

    private String orderTypeName(Object order) throws Throwable {
        if (order == null) return null;
        for (String name : new String[]{"a"}) {
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

    private ArrayList<Object> selectedOwnUnits(Object engine) throws Throwable {
        Object me = host.findFieldValue(engine, "bp");
        Class<?> unitClass = loader.loadClass(host.target("game.units.bp"));
        Class<?> buildingClass = loader.loadClass(host.target("game.units.d.f"));
        Class<?> registry = loader.loadClass(host.target("gameFramework.ah"));
        Object all = host.findField(registry, "et").get(null);
        ArrayList<Object> result = new ArrayList<>();
        if (!(all instanceof Iterable)) return result;
        for (Object unit : (Iterable<?>) all) {
            if (unit != null && unitClass.isInstance(unit) && !buildingClass.isInstance(unit)
                    && host.boolField(unit, "cI") && host.findFieldValue(unit, "bZ") == me) {
                result.add(unit);
            }
        }
        return result;
    }

    boolean isUnitSelection(Object selected) {
        if (selected == null) return false;
        try {
            Object engine = host.findEngine(loader);
            Object me = host.findFieldValue(engine, "bp");
            Class<?> unitClass = loader.loadClass(host.target("game.units.bp"));
            Class<?> buildingClass = loader.loadClass(host.target("game.units.d.f"));
            return unitClass.isInstance(selected) && !buildingClass.isInstance(selected)
                    && host.findFieldValue(selected, "bZ") == me;
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean hasSelectedUnitSelection() {
        try {
            return !selectedOwnUnits(host.findEngine(loader)).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    String titleForSelection() {
        try {
            ArrayList<Object> selected = selectedOwnUnits(host.findEngine(loader));
            if (!selected.isEmpty() && allSmartEnabled(selected)) return "关闭智寻";
        } catch (Throwable ignored) {
        }
        return "智能寻路";
    }

    void toggleSelected() {
        try {
            ArrayList<Object> selected = selectedOwnUnits(host.findEngine(loader));
            if (selected.isEmpty()) return;
            boolean disable = allSmartEnabled(selected);
            synchronized (manualEnabled) {
                for (Object unit : selected) {
                    long id = unitId(unit);
                    if (disable) {
                        manualEnabled.remove(id);
                        manualDisabled.add(id);
                    } else {
                        manualDisabled.remove(id);
                        manualEnabled.add(id);
                    }
                    unitTokens.put(id, nextToken.incrementAndGet());
                }
            }
            if (runtimeAvailable()) ensureHooks();
            else disableHooks();
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to toggle smart path selection", t);
        }
    }

    void setSelected(boolean enable) {
        try {
            ArrayList<Object> selected = selectedOwnUnits(host.findEngine(loader));
            if (selected.isEmpty()) return;
            synchronized (manualEnabled) {
                for (Object unit : selected) {
                    if (unitSmartEnabled(unit) == enable) continue;
                    long id = unitId(unit);
                    if (enable) {
                        manualDisabled.remove(id);
                        manualEnabled.add(id);
                    } else {
                        manualEnabled.remove(id);
                        manualDisabled.add(id);
                    }
                    unitTokens.put(id, nextToken.incrementAndGet());
                }
            }
            if (runtimeAvailable()) ensureHooks();
            else disableHooks();
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to set smart path selection", t);
        }
    }

    private ArrayList<Object> smartEnabledUnits(List<Object> units) {
        ArrayList<Object> result = new ArrayList<>();
        for (Object unit : units) if (unitSmartEnabled(unit)) result.add(unit);
        return result;
    }

    private boolean allSmartEnabled(List<Object> units) {
        for (Object unit : units) if (!unitSmartEnabled(unit)) return false;
        return !units.isEmpty();
    }

    private boolean unitSmartEnabled(Object unit) {
        if (!showAction()) return enabled();
        long id = unitId(unit);
        synchronized (manualEnabled) {
            if (manualDisabled.contains(id)) return false;
            if (manualEnabled.contains(id)) return true;
        }
        return enabled();
    }

    private ArrayList<Object> ownCommandUnits(Object command) throws Throwable {
        Object engine = host.findEngine(loader);
        Object me = host.findFieldValue(engine, "bp");
        Class<?> unitClass = loader.loadClass(host.target("game.units.bp"));
        Class<?> buildingClass = loader.loadClass(host.target("game.units.d.f"));
        Object units = host.findFieldValue(command, "w");
        ArrayList<Object> result = new ArrayList<>();
        if (!(units instanceof Iterable)) return result;
        for (Object unit : (Iterable<?>) units) {
            if (unit != null && unitClass.isInstance(unit) && !buildingClass.isInstance(unit)
                    && host.findFieldValue(unit, "bZ") == me) result.add(unit);
        }
        return result;
    }

    private void removeCommandUnits(Object command, List<Object> units) throws Throwable {
        Object attached = host.findFieldValue(command, "w");
        if (!(attached instanceof java.util.Collection)) return;
        java.util.Collection<?> collection = (java.util.Collection<?>) attached;
        for (Object unit : units) collection.remove(unit);
    }

    private boolean unitStillOwned(Object unit) {
        try {
            Object engine = host.findEngine(loader);
            return unit != null && !host.boolField(unit, "bX")
                    && host.findFieldValue(unit, "bZ") == host.findFieldValue(engine, "bp");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Method movementMethod(Object unit) throws Throwable {
        Method method = host.findNoArgMethod(unit.getClass(), "g");
        if (method == null) throw new NoSuchMethodException("unit g()");
        return method;
    }

    private int currentTick(Object engine) {
        try {
            return ((Number) host.findFieldValue(engine, "bu")).intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long unitId(Object unit) {
        try {
            Object value = host.findFieldValue(unit, "ej");
            return value instanceof Number ? ((Number) value).longValue() : Long.MAX_VALUE;
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;
        }
    }

    private boolean tokenCurrent(long unitId, long token) {
        Long current = unitTokens.get(unitId);
        return current != null && current == token;
    }

    private void invalidateWorldState(boolean clearManualChoices) {
        nextToken.incrementAndGet();
        unitTokens.clear();
        pendingMove = null;
        ready.clear();
        synchronized (stagedRoutes) { stagedRoutes.clear(); }
        synchronized (gridCache) { gridCache.clear(); }
        synchronized (inspectedCommands) { inspectedCommands.clear(); }
        synchronized (generatedCommands) { generatedCommands.clear(); }
        if (clearManualChoices) {
            synchronized (manualEnabled) {
                manualEnabled.clear();
                manualDisabled.clear();
            }
        }
    }

    private String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : null;
    }

    private boolean enabled() {
        Boolean cached = enabledState;
        if (cached != null) return cached;
        boolean value = host.selectionActionEnabled(KEY_SMART_PATHING);
        enabledState = value;
        return value;
    }

    private boolean showAction() {
        Boolean cached = showActionState;
        if (cached != null) return cached;
        boolean value = host.selectionActionEnabled(KEY_SHOW_SMART_PATH_ACTION);
        showActionState = value;
        return value;
    }

    private boolean available() {
        return enabled() || showAction();
    }

    private boolean runtimeAvailable() {
        if (enabled()) return true;
        synchronized (manualEnabled) {
            return !manualEnabled.isEmpty();
        }
    }

    private int threshold() {
        Integer cached = thresholdState;
        if (cached != null) return cached;
        int value = host.preferenceInt(
                KEY_SMART_PATHING_THRESHOLD, DEFAULT_SMART_PATHING_THRESHOLD);
        value = Math.max(1, Math.min(200, value));
        thresholdState = value;
        return value;
    }

    private static final class CachedGrid {
        final int tick;
        final SmartPathGrid grid;

        CachedGrid(int tick, SmartPathGrid grid) {
            this.tick = tick;
            this.grid = grid;
        }
    }

    private static final class PendingMove {
        final long token;
        final int tick;
        final float targetX;
        final float targetY;
        final ArrayList<Object> units;

        PendingMove(long token, int tick, float targetX, float targetY,
                    ArrayList<Object> units) {
            this.token = token;
            this.tick = tick;
            this.targetX = targetX;
            this.targetY = targetY;
            this.units = new ArrayList<>(units);
        }
    }

    private static final class UnitRequest {
        final Object unit;
        final long unitId;
        final long token;
        final float startX;
        final float startY;
        final float radius;
        final float targetX;
        final float targetY;
        final float targetRadius;
        final SmartPathGrid grid;
        final TerminalSpec terminal;

        UnitRequest(Object unit, long unitId, long token,
                    float startX, float startY, float radius,
                    float targetX, float targetY, float targetRadius,
                    SmartPathGrid grid, TerminalSpec terminal) {
            this.unit = unit;
            this.unitId = unitId;
            this.token = token;
            this.startX = startX;
            this.startY = startY;
            this.radius = radius;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetRadius = targetRadius;
            this.grid = grid;
            this.terminal = terminal;
        }
    }

    private static final class PathResult {
        final UnitRequest request;
        final List<PointF> route;

        PathResult(UnitRequest request, List<PointF> route) {
            this.request = request;
            this.route = route;
        }
    }

    private static final class StagedRoute {
        final UnitRequest request;
        final List<PointF> route;
        Object barrier;
        int nextIndex = 1;

        StagedRoute(UnitRequest request, List<PointF> route, Object barrier) {
            this.request = request;
            this.route = new ArrayList<>(route);
            this.barrier = barrier;
        }
    }

    private static final class TerminalSpec {
        final String type;
        final float x;
        final float y;
        final Object target;
        final Object buildType;
        final int variant;

        TerminalSpec(String type, float x, float y, Object target,
                     Object buildType, int variant) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.target = target;
            this.buildType = buildType;
            this.variant = variant;
        }

        float pathX() {
            return x;
        }

        float pathY() {
            return y;
        }
    }
}
