package com.shizuku.rwmiao.module;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_COMBAT_VIEW;

final class CombatView {
    private static final String TAG = "RWmiao";
    private static final float AREA_JOIN_DISTANCE = 520.0f;
    private static final float VISITED_MATCH_DISTANCE = 1_300.0f;
    private static final float AREA_PADDING = 64.0f;
    private static final float MIN_AREA_WIDTH = 160.0f;
    private static final float MIN_AREA_HEIGHT = 160.0f;
    private static final float MAX_EFFECTIVE_ZOOM = 4.6f;
    private static final float COMBAT_VIEW_VIEW_MARGIN = 2.0f;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private RuntimeAccess runtime;
    private final ArrayList<VisitedArea> visitedAreas = new ArrayList<>();

    CombatView(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        runtime = new RuntimeAccess();
        host.addGameResyncListener(this::resetAfterResync);
    }

    private synchronized void resetAfterResync() {
        resetCycle();
    }

    boolean enabled() {
        return host.preferenceBoolean(KEY_COMBAT_VIEW, false);
    }

    synchronized void refreshSettings() {
        if (!enabled()) resetCycle();
    }

    synchronized String titleForSelection() {
        return "交战视角";
    }

    synchronized void jumpToNextCombat() {
        if (!enabled() || runtime == null) return;
        try {
            Object engine = host.findEngine(loader);
            if (engine == null) return;

            CameraState current = runtime.readCamera(engine);
            ArrayList<CombatArea> areas = detectAreas(engine);
            if (areas.isEmpty()) {
                resetCycle();
                return;
            }

            for (CombatArea area : areas) {
                if (runtime.isVisible(engine, current, area)) rememberVisited(area);
            }

            CombatArea next = largestUnvisitedOutsideArea(engine, current, areas);
            if (next == null) {
                visitedAreas.clear();
                for (CombatArea area : areas) {
                    if (runtime.isVisible(engine, current, area)) rememberVisited(area);
                }
                next = largestUnvisitedOutsideArea(engine, current, areas);
            }

            if (next == null) return;
            rememberVisited(next);
            runtime.moveCameraTo(engine, next);
        } catch (Throwable t) {
            host.log(5, TAG, "Combat-view jump failed", t);
        }
    }

    private CombatArea largestUnvisitedOutsideArea(Object engine, CameraState current,
                                                    ArrayList<CombatArea> areas)
            throws IllegalAccessException {
        CombatArea best = null;
        for (CombatArea area : areas) {
            if (area.friendlyCount == 0 || runtime.isVisible(engine, current, area)
                    || isVisited(area)) continue;
            if (best == null || AREA_ORDER.compare(area, best) < 0) best = area;
        }
        return best;
    }

    private static final Comparator<CombatArea> AREA_ORDER = (left, right) -> {
        int count = Integer.compare(right.participants.size(), left.participants.size());
        if (count != 0) return count;
        int friendly = Integer.compare(right.friendlyCount, left.friendlyCount);
        if (friendly != 0) return friendly;
        return Float.compare(right.footprint(), left.footprint());
    };

    private boolean isVisited(CombatArea area) {
        for (VisitedArea visited : visitedAreas) {
            if (visited.matches(area)) return true;
        }
        return false;
    }

    private void rememberVisited(CombatArea area) {
        for (VisitedArea visited : visitedAreas) {
            if (visited.matches(area)) {
                visited.update(area);
                return;
            }
        }
        visitedAreas.add(new VisitedArea(area));
    }

    private void resetCycle() {
        visitedAreas.clear();
    }

    private ArrayList<CombatArea> detectAreas(Object engine) throws Throwable {
        Object player = runtime.player.get(engine);
        if (player == null) return new ArrayList<>();
        Object all = runtime.allUnits.invoke(null);
        if (!(all instanceof Iterable)) return new ArrayList<>();

        ArrayList<CombatEncounter> encounters = new ArrayList<>();
        for (Object source : (Iterable<?>) all) {
            if (source == null || !runtime.battleUnit.isInstance(source)
                    || !runtime.isLive(source)) continue;
            Object target = combatTarget(source);
            if (target == null || !runtime.isLive(target)
                    || !runtime.areEnemies(source, target)) continue;
            encounters.add(new CombatEncounter(source, target, player));
        }
        if (encounters.isEmpty()) return new ArrayList<>();

        ArrayList<CombatArea> areas = new ArrayList<>();
        for (CombatEncounter encounter : encounters) {
            CombatArea merged = new CombatArea(encounter);
            boolean joined;
            do {
                joined = false;
                Iterator<CombatArea> iterator = areas.iterator();
                while (iterator.hasNext()) {
                    CombatArea area = iterator.next();
                    if (!merged.closeTo(area)) continue;
                    merged.add(area);
                    iterator.remove();
                    joined = true;
                }
            } while (joined);
            areas.add(merged);
        }

        ArrayList<CombatArea> result = new ArrayList<>();
        for (CombatArea area : areas) {
            if (area.friendlyCount > 0 && area.participants.size() >= 2) result.add(area);
        }
        return result;
    }

    private Object combatTarget(Object source) throws IllegalAccessException {
        Object target = runtime.currentTarget.get(source);
        if (runtime.isLive(target) && runtime.areEnemies(source, target)) return target;

        Object orders = runtime.orders.get(source);
        if (orders == null || runtime.orderType == null || runtime.orderTarget == null) {
            return null;
        }
        int count = Math.max(0, Math.min(runtime.orderCount.getInt(source), Array.getLength(orders)));
        if (count == 0) return null;
        Object order = Array.get(orders, 0);
        if (order == null) return null;
        Object kind = runtime.orderType.get(order);
        if (kind == null || !"attack".equals(String.valueOf(kind))) return null;
        Object orderedTarget = runtime.orderTarget.get(order);
        return runtime.isLive(orderedTarget) && runtime.areEnemies(source, orderedTarget)
                ? orderedTarget : null;
    }

    private final class CombatEncounter {
        final CombatArea area;

        CombatEncounter(Object source, Object target, Object player) throws IllegalAccessException {
            area = new CombatArea();
            area.addUnit(source, player);
            area.addUnit(target, player);
        }
    }

    private final class CombatArea {
        final Set<Object> participants = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        final Set<Object> friendlyParticipants = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        final Set<Long> participantIds = new HashSet<>();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        int friendlyCount;

        CombatArea() {
        }

        CombatArea(CombatEncounter encounter) {
            add(encounter);
        }

        void add(CombatEncounter encounter) {
            add(encounter.area);
        }

        void add(CombatArea other) {
            for (Object unit : other.participants) {
                participants.add(unit);
            }
            friendlyParticipants.addAll(other.friendlyParticipants);
            participantIds.addAll(other.participantIds);
            minX = Math.min(minX, other.minX);
            minY = Math.min(minY, other.minY);
            maxX = Math.max(maxX, other.maxX);
            maxY = Math.max(maxY, other.maxY);
            friendlyCount = friendlyParticipants.size();
        }

        void addUnit(Object unit, Object player) throws IllegalAccessException {
            if (!participants.add(unit)) return;
            float x = runtime.unitX.getFloat(unit);
            float y = runtime.groundY(unit);
            float radius = runtime.radius(unit);
            minX = Math.min(minX, x - radius);
            minY = Math.min(minY, y - radius);
            maxX = Math.max(maxX, x + radius);
            maxY = Math.max(maxY, y + radius);
            if (runtime.unitOwner.get(unit) == player) {
                friendlyParticipants.add(unit);
                friendlyCount = friendlyParticipants.size();
            }
            if (runtime.unitId != null) {
                long id = runtime.unitId.getLong(unit);
                if (id != 0L) participantIds.add(id);
            }
        }

        boolean closeTo(CombatEncounter encounter) {
            return closeTo(encounter.area);
        }

        boolean closeTo(CombatArea other) {
            float dx = axisGap(minX, maxX, other.minX, other.maxX);
            float dy = axisGap(minY, maxY, other.minY, other.maxY);
            return (float) Math.sqrt((dx * dx) + (dy * dy)) <= AREA_JOIN_DISTANCE;
        }

        float footprint() {
            return Math.max(1.0f, maxX - minX) * Math.max(1.0f, maxY - minY);
        }
    }

    private static float axisGap(float minA, float maxA, float minB, float maxB) {
        if (maxA < minB) return minB - maxA;
        if (maxB < minA) return minA - maxB;
        return 0.0f;
    }

    private final class VisitedArea {
        final Set<Object> participants = Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        final Set<Long> participantIds = new HashSet<>();
        float centerX;
        float centerY;

        VisitedArea(CombatArea area) {
            update(area);
        }

        void update(CombatArea area) {
            participants.addAll(area.participants);
            participantIds.addAll(area.participantIds);
            centerX = (area.minX + area.maxX) * 0.5f;
            centerY = (area.minY + area.maxY) * 0.5f;
        }

        boolean matches(CombatArea area) {
            boolean overlap = false;
            for (Long id : area.participantIds) {
                if (participantIds.contains(id)) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                for (Object unit : area.participants) {
                    if (participants.contains(unit)) {
                        overlap = true;
                        break;
                    }
                }
            }
            float dx = centerX - ((area.minX + area.maxX) * 0.5f);
            float dy = centerY - ((area.minY + area.maxY) * 0.5f);
            float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
            return overlap && distance <= VISITED_MATCH_DISTANCE;
        }
    }

    private final class CameraState {
        final float left;
        final float top;
        final float zoom;

        CameraState(float left, float top, float zoom) {
            this.left = left;
            this.top = top;
            this.zoom = zoom;
        }
    }

    private final class RuntimeAccess {
        final Class<?> engineClass = loader.loadClass(host.target("gameFramework.k"));
        final Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
        final Class<?> battleUnit = loader.loadClass(host.target("game.units.bp"));
        final Class<?> waypointClass = loader.loadClass(host.target("game.units.en"));

        final Field player = host.findField(engineClass, "bp");
        final Field gameView = optionalField(engineClass, "an");
        final Field allUnitsMap = host.findField(engineClass, "bI");
        final Field cameraLeft = host.findField(engineClass, "cv");
        final Field cameraTop = host.findField(engineClass, "cw");
        final Field targetZoom = host.findField(engineClass, "cS");
        final Field currentZoom = host.findField(engineClass, "cU");
        final Field zoomBase = host.findField(engineClass, "cV");
        final Field screenWidth = host.findField(engineClass, "cC");
        final Field contentWidth = host.findField(engineClass, "cD");
        final Field screenHeight = host.findField(engineClass, "cE");
        final Field visibleWorldWidth = host.findField(engineClass, "cx");
        final Field visibleWorldHeight = host.findField(engineClass, "cy");
        final Field visibleContentWidth = host.findField(engineClass, "cB");
        final Field visibleHalfWidth = host.findField(engineClass, "cF");
        final Field visibleHalfHeight = host.findField(engineClass, "cG");
        final Field screenViewport = optionalField(engineClass, "cH");
        final Field visibleWorldViewport = optionalField(engineClass, "cI");
        final Field visibleWorldViewportFloat = optionalField(engineClass, "cJ");
        final Field zoomAroundPoint = optionalField(engineClass, "cW");
        final Field cameraVelocityX = optionalField(engineClass, "co");
        final Field cameraVelocityY = optionalField(engineClass, "cp");
        final Field unitDead = host.findField(unitClass, "bX");
        final Field unitOwner = host.findField(unitClass, "bZ");
        final Field unitAttached = host.findField(unitClass, "cP");
        final Field unitX = host.findField(unitClass, "eq");
        final Field unitY = host.findField(unitClass, "er");
        final Field unitGroundOffset = optionalField(unitClass, "es");
        final Field unitRadius = optionalField(unitClass, "cl");
        final Field unitId = optionalField(unitClass, "ej");
        final Field currentTarget = host.findField(battleUnit, "T");
        final Field orders = host.findField(battleUnit, "Q");
        final Field orderCount = host.findField(battleUnit, "O");
        final Field orderType = optionalField(waypointClass, "a");
        final Field orderTarget = optionalField(waypointClass, "h");
        final Method allUnits = host.findCompatibleMethod(unitClass, "bn");
        final Method mapWidth = optionalMethod(allMapClass(), "f");
        final Method mapHeight = optionalMethod(allMapClass(), "g");
        final Method setCamera = host.findCompatibleMethod(engineClass, "a",
                float.class, float.class);
        final Method clampCamera = host.findNoArgMethod(engineClass, "H");

        RuntimeAccess() throws Throwable {
            allUnits.setAccessible(true);
            if (setCamera == null || clampCamera == null) {
                throw new NoSuchMethodException("camera position contract");
            }
        }

        private Class<?> allMapClass() throws IllegalAccessException {
            return allUnitsMap.getType();
        }

        private Field optionalField(Class<?> type, String name) {
            try {
                return host.findField(type, name);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Method optionalMethod(Class<?> type, String name) {
            return host.findNoArgMethod(type, name);
        }

        boolean isLive(Object unit) throws IllegalAccessException {
            return unit != null && unitClass.isInstance(unit)
                    && !unitDead.getBoolean(unit) && unitAttached.get(unit) == null;
        }

        boolean areEnemies(Object left, Object right) throws IllegalAccessException {
            Object leftOwner = unitOwner.get(left);
            Object rightOwner = unitOwner.get(right);
            return leftOwner != null && rightOwner != null
                    && host.relation(leftOwner, rightOwner) == 1;
        }

        float groundY(Object unit) throws IllegalAccessException {
            return unitGroundOffset == null
                    ? unitY.getFloat(unit) : unitY.getFloat(unit) - unitGroundOffset.getFloat(unit);
        }

        float radius(Object unit) throws IllegalAccessException {
            if (unitRadius == null) return AREA_PADDING;
            return Math.max(AREA_PADDING, Math.abs(unitRadius.getFloat(unit)) + 24.0f);
        }

        CameraState readCamera(Object engine) throws IllegalAccessException {
            float zoom = currentZoom.getFloat(engine);
            if (zoom <= 0.0f) zoom = 1.0f;
            return new CameraState(cameraLeft.getFloat(engine), cameraTop.getFloat(engine), zoom);
        }

        boolean isVisible(Object engine, CameraState camera, CombatArea area)
                throws IllegalAccessException {
            float zoom = camera.zoom <= 0.0f ? 1.0f : camera.zoom;
            float width = visibleWorldWidth.getFloat(engine);
            float height = visibleWorldHeight.getFloat(engine);
            if (width <= 0.0f) width = screenWidth.getFloat(engine) / zoom;
            if (height <= 0.0f) height = screenHeight.getFloat(engine) / zoom;
            return area.minX >= camera.left && area.maxX <= camera.left + width
                    && area.minY >= camera.top && area.maxY <= camera.top + height;
        }

        void moveCameraTo(Object engine, CombatArea area) throws Throwable {
            float widthPx = screenWidth.getFloat(engine);
            float heightPx = screenHeight.getFloat(engine);
            if (widthPx <= 0.0f || heightPx <= 0.0f) return;

            float width = Math.max(MIN_AREA_WIDTH, area.maxX - area.minX);
            float height = Math.max(MIN_AREA_HEIGHT, area.maxY - area.minY);
            float fitZoom = Math.min(widthPx / width, heightPx / height);
            float boundedFitZoom = Math.min(MAX_EFFECTIVE_ZOOM, fitZoom);
            float minZoom = mapMinimumZoom(engine, widthPx, heightPx);
            float effectiveZoom = Math.max(minZoom,
                    boundedFitZoom / COMBAT_VIEW_VIEW_MARGIN);
            float worldWidth = widthPx / effectiveZoom;
            float worldHeight = heightPx / effectiveZoom;
            float centerX = (area.minX + area.maxX) * 0.5f;
            float centerY = (area.minY + area.maxY) * 0.5f;
            setCamera(engine, effectiveZoom, centerX - worldWidth * 0.5f,
                    centerY - worldHeight * 0.5f);
        }

        private float mapMinimumZoom(Object engine, float widthPx, float heightPx)
                throws IllegalAccessException {
            Object map = allUnitsMap.get(engine);
            if (map == null || mapWidth == null || mapHeight == null) return 0.01f;
            try {
                float mapW = ((Number) mapWidth.invoke(map)).floatValue();
                float mapH = ((Number) mapHeight.invoke(map)).floatValue();
                if (mapW > 0.0f && mapH > 0.0f) {
                    return Math.min(widthPx / mapW, heightPx / mapH);
                }
            } catch (Throwable ignored) {
            }
            return 0.01f;
        }

        private void setCamera(Object engine, float effectiveZoom, float left, float top)
                throws Throwable {
            float base = zoomBase.getFloat(engine);
            if (base <= 0.0f) base = 1.0f;
            currentZoom.setFloat(engine, effectiveZoom);
            targetZoom.setFloat(engine, effectiveZoom / base);
            if (zoomAroundPoint != null) zoomAroundPoint.setBoolean(engine, false);
            if (cameraVelocityX != null) cameraVelocityX.setFloat(engine, 0.0f);
            if (cameraVelocityY != null) cameraVelocityY.setFloat(engine, 0.0f);
            syncCameraGeometry(engine, effectiveZoom);
            setCamera.invoke(engine, left, top);
            clampCamera.invoke(engine);
            refreshGameView(engine);
        }

        private void syncCameraGeometry(Object engine, float effectiveZoom)
                throws Throwable {
            Method recalculateView = host.findNoArgMethod(engine.getClass(), "am");
            if (recalculateView != null) {
                recalculateView.invoke(engine);
                return;
            }

            updateVisibleDimensions(engine, effectiveZoom);
            float widthPx = Math.max(1.0f, screenWidth.getFloat(engine));
            float heightPx = Math.max(1.0f, screenHeight.getFloat(engine));
            if (screenViewport != null) {
                Object viewport = screenViewport.get(engine);
                Method set = host.findCompatibleMethod(viewport.getClass(), "set",
                        int.class, int.class, int.class, int.class);
                if (set != null) set.invoke(viewport, 0, 0, (int) widthPx, (int) heightPx);
            }
            if (visibleWorldViewport != null) {
                Object viewport = visibleWorldViewport.get(engine);
                Method set = host.findCompatibleMethod(viewport.getClass(), "set",
                        int.class, int.class, int.class, int.class);
                if (set != null) {
                    set.invoke(viewport, 0, 0,
                            (int) visibleWorldWidth.getFloat(engine) + 1,
                            (int) visibleWorldHeight.getFloat(engine) + 1);
                }
            }
            if (visibleWorldViewportFloat != null) {
                Object viewport = visibleWorldViewportFloat.get(engine);
                Method set = host.findCompatibleMethod(viewport.getClass(), "set",
                        float.class, float.class, float.class, float.class);
                if (set != null) {
                    set.invoke(viewport, 0.0f, 0.0f,
                            visibleWorldWidth.getFloat(engine) + 1.0f,
                            visibleWorldHeight.getFloat(engine) + 1.0f);
                }
            }
        }

        private void refreshGameView(Object engine) {
            if (gameView == null) return;
            final Object view;
            try {
                view = gameView.get(engine);
            } catch (Throwable t) {
                host.log(5, TAG, "Failed to read game view for camera refresh", t);
                return;
            }
            if (view == null) return;

            try {
                Method requestRender = host.findNoArgMethod(view.getClass(), "requestRender");
                if (requestRender != null) requestRender.invoke(view);
            } catch (Throwable t) {
                host.log(5, TAG, "Failed to request game-view render", t);
            }

            try {
                Method postInvalidate = host.findNoArgMethod(view.getClass(), "postInvalidate");
                if (postInvalidate != null) {
                    postInvalidate.invoke(view);
                    return;
                }
                Method invalidate = host.findNoArgMethod(view.getClass(), "invalidate");
                if (invalidate != null) invalidate.invoke(view);
            } catch (Throwable t) {
                host.log(5, TAG, "Failed to invalidate game view", t);
            }
        }

        private void updateVisibleDimensions(Object engine, float effectiveZoom)
                throws IllegalAccessException {
            float widthPx = Math.max(1.0f, screenWidth.getFloat(engine));
            float contentPx = Math.max(1.0f, contentWidth.getFloat(engine));
            float heightPx = Math.max(1.0f, screenHeight.getFloat(engine));
            visibleWorldWidth.setFloat(engine, widthPx / effectiveZoom);
            visibleWorldHeight.setFloat(engine, heightPx / effectiveZoom);
            visibleContentWidth.setFloat(engine, contentPx / effectiveZoom);
            visibleHalfWidth.setFloat(engine, visibleWorldWidth.getFloat(engine) * 0.5f);
            visibleHalfHeight.setFloat(engine, visibleWorldHeight.getFloat(engine) * 0.5f);
        }
    }
}
