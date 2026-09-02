package com.shizuku.rwmiao.module.support.path;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class AStarPathfinder {
    private static final int[] DX = {-1, 1, 0, 0, -1, -1, 1, 1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int MAX_EXPANDED = 120000;

    public List<PointF> find(SmartPathGrid grid, float startWorldX, float startWorldY,
                             float targetWorldX, float targetWorldY) {
        return find(grid, startWorldX, startWorldY, targetWorldX, targetWorldY, () -> false);
    }

    public List<PointF> find(SmartPathGrid grid, float startWorldX, float startWorldY,
                             float targetWorldX, float targetWorldY, BooleanSupplier cancelled) {
        return find(grid, startWorldX, startWorldY, targetWorldX, targetWorldY,
                false, 0.0f, cancelled);
    }

    public List<PointF> findActionApproach(SmartPathGrid grid,
                                           float startWorldX, float startWorldY,
                                           float targetWorldX, float targetWorldY,
                                           BooleanSupplier cancelled) {
        return find(grid, startWorldX, startWorldY, targetWorldX, targetWorldY,
                true, 0.0f, cancelled);
    }

    public List<PointF> findActionApproach(SmartPathGrid grid,
                                           float startWorldX, float startWorldY,
                                           float targetWorldX, float targetWorldY,
                                           float targetRadiusWorld,
                                           BooleanSupplier cancelled) {
        return find(grid, startWorldX, startWorldY, targetWorldX, targetWorldY,
                true, Math.max(0.0f, targetRadiusWorld), cancelled);
    }

    private List<PointF> find(SmartPathGrid grid, float startWorldX, float startWorldY,
                              float targetWorldX, float targetWorldY,
                              boolean stopOnClearApproach, float targetRadiusWorld,
                              BooleanSupplier cancelled) {
        if (grid == null || !grid.valid()) return Collections.emptyList();
        int sx = clamp((int) (startWorldX * grid.worldToGrid), 0, grid.width - 1);
        int sy = clamp((int) (startWorldY * grid.worldToGrid), 0, grid.height - 1);
        int requestedX = clamp((int) (targetWorldX * grid.worldToGrid), 0, grid.width - 1);
        int requestedY = clamp((int) (targetWorldY * grid.worldToGrid), 0, grid.height - 1);
        if (stopOnClearApproach
                && clearActionApproach(grid, sx, sy, requestedX, requestedY,
                targetRadiusWorld)) {
            return Collections.emptyList();
        }
        int target = nearestPassable(grid, requestedX, requestedY);
        if (target < 0) return Collections.emptyList();
        int tx = target % grid.width;
        int ty = target / grid.width;
        if (sx == tx && sy == ty) {
            if (requestedX == tx && requestedY == ty
                    && (Math.abs(startWorldX - targetWorldX) > 0.5f
                    || Math.abs(startWorldY - targetWorldY) > 0.5f)) {
                return Collections.singletonList(new PointF(targetWorldX, targetWorldY));
            }
            return Collections.emptyList();
        }
        if (grid.lineOfSight(sx, sy, tx, ty)) {
            return Collections.singletonList(requestedX == tx && requestedY == ty
                    ? new PointF(targetWorldX, targetWorldY) : worldPoint(grid, target));
        }

        int size = grid.width * grid.height;
        int[] cost = new int[size];
        int[] parent = new int[size];
        boolean[] closed = new boolean[size];
        Arrays.fill(cost, Integer.MAX_VALUE);
        Arrays.fill(parent, -2);
        int start = index(grid, sx, sy);
        cost[start] = 0;
        parent[start] = -1;
        IntHeap open = new IntHeap(64);
        open.add(start, 0, heuristic(sx, sy, tx, ty));
        int bestReachable = start;
        int bestTargetDistance = squared(sx - requestedX, sy - requestedY);
        int bestPathCost = Integer.MAX_VALUE;

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < Math.min(size, MAX_EXPANDED)) {
            if ((expanded & 127) == 0 && cancelled.getAsBoolean()) {
                return Collections.emptyList();
            }
            int nodeIndex = open.pollIndex();
            int nodeCost = open.lastG;
            if (closed[nodeIndex] || nodeCost != cost[nodeIndex]) continue;
            int nodeX = nodeIndex % grid.width;
            int nodeY = nodeIndex / grid.width;
            int targetDistance = squared(nodeX - requestedX, nodeY - requestedY);
            if (targetDistance < bestTargetDistance
                    || targetDistance == bestTargetDistance && nodeCost < bestPathCost) {
                bestReachable = nodeIndex;
                bestTargetDistance = targetDistance;
                bestPathCost = nodeCost;
            }
            if (stopOnClearApproach && nodeIndex != start
                    && clearActionApproach(grid, nodeX, nodeY, requestedX, requestedY,
                    targetRadiusWorld)) {
                return toWorldPath(grid, smooth(grid, reconstruct(parent, nodeIndex)),
                        false, targetWorldX, targetWorldY);
            }
            if (nodeIndex == target) {
                return toWorldPath(grid, smooth(grid, reconstruct(parent, target)),
                        requestedX == tx && requestedY == ty, targetWorldX, targetWorldY);
            }
            closed[nodeIndex] = true;
            int x = nodeX;
            int y = nodeY;
            for (int direction = 0; direction < DX.length; direction++) {
                int nx = x + DX[direction];
                int ny = y + DY[direction];
                if (!grid.passable(nx, ny)) continue;
                boolean diagonal = DX[direction] != 0 && DY[direction] != 0;
                if (diagonal && (!grid.passable(nx, y) || !grid.passable(x, ny))) continue;
                int next = index(grid, nx, ny);
                if (closed[next]) continue;
                int nextCost = nodeCost + (diagonal ? 14 : 10);
                if (nextCost >= cost[next]) continue;
                cost[next] = nextCost;
                parent[next] = nodeIndex;
                open.add(next, nextCost, nextCost + heuristic(nx, ny, tx, ty));
            }
        }
        if (bestReachable != start) {
            return toWorldPath(grid, smooth(grid, reconstruct(parent, bestReachable)),
                    false, targetWorldX, targetWorldY);
        }
        return Collections.emptyList();
    }

    private boolean clearActionApproach(SmartPathGrid grid,
                                        int x, int y, int targetX, int targetY,
                                        float targetRadiusWorld) {
        return grid.lineOfSightToTargetFootprint(x, y, targetX, targetY,
                targetRadiusWorld * grid.worldToGrid);
    }

    private int nearestPassable(SmartPathGrid grid, int targetX, int targetY) {
        if (grid.passable(targetX, targetY)) return index(grid, targetX, targetY);
        int limit = Math.max(grid.width, grid.height);
        for (int radius = 1; radius <= limit; radius++) {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int x = targetX - radius; x <= targetX + radius; x++) {
                for (int y : new int[]{targetY - radius, targetY + radius}) {
                    if (!grid.passable(x, y)) continue;
                    int distance = squared(x - targetX, y - targetY);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = index(grid, x, y);
                    }
                }
            }
            for (int y = targetY - radius + 1; y < targetY + radius; y++) {
                for (int x : new int[]{targetX - radius, targetX + radius}) {
                    if (!grid.passable(x, y)) continue;
                    int distance = squared(x - targetX, y - targetY);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = index(grid, x, y);
                    }
                }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    private ArrayList<Integer> reconstruct(int[] parent, int target) {
        ArrayList<Integer> result = new ArrayList<>();
        int cursor = target;
        while (cursor >= 0) {
            result.add(cursor);
            cursor = parent[cursor];
        }
        Collections.reverse(result);
        return result;
    }

    private ArrayList<Integer> smooth(SmartPathGrid grid, List<Integer> raw) {
        ArrayList<Integer> result = new ArrayList<>();
        if (raw.isEmpty()) return result;
        int anchor = 0;
        result.add(raw.get(0));
        while (anchor < raw.size() - 1) {
            int next = raw.size() - 1;
            int from = raw.get(anchor);
            int fx = from % grid.width;
            int fy = from / grid.width;
            while (next > anchor + 1) {
                int candidate = raw.get(next);
                if (grid.lineOfSight(fx, fy,
                        candidate % grid.width, candidate / grid.width)) break;
                next--;
            }
            result.add(raw.get(next));
            anchor = next;
        }
        return result;
    }

    private List<PointF> toWorldPath(SmartPathGrid grid, List<Integer> nodes,
                                     boolean exactTarget, float targetX, float targetY) {
        ArrayList<PointF> result = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            int node = nodes.get(i);
            float x = ((node % grid.width) + 0.5f) / grid.worldToGrid;
            float y = ((node / grid.width) + 0.5f) / grid.worldToGrid;
            if (exactTarget && i == nodes.size() - 1) {
                x = targetX;
                y = targetY;
            }
            result.add(new PointF(x, y));
        }
        return result;
    }

    private int heuristic(int x, int y, int targetX, int targetY) {
        int dx = Math.abs(targetX - x);
        int dy = Math.abs(targetY - y);
        return 10 * (dx + dy) - 6 * Math.min(dx, dy);
    }

    private int index(SmartPathGrid grid, int x, int y) {
        return (y * grid.width) + x;
    }

    private int squared(int x, int y) {
        return (x * x) + (y * y);
    }

    private PointF worldPoint(SmartPathGrid grid, int node) {
        return new PointF(((node % grid.width) + 0.5f) / grid.worldToGrid,
                ((node / grid.width) + 0.5f) / grid.worldToGrid);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class IntHeap {
        int[] indexes;
        int[] costs;
        int[] priorities;
        int size;
        int lastG;

        IntHeap(int capacity) {
            indexes = new int[capacity];
            costs = new int[capacity];
            priorities = new int[capacity];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void add(int index, int cost, int priority) {
            if (size == indexes.length) {
                int next = size * 2;
                indexes = Arrays.copyOf(indexes, next);
                costs = Arrays.copyOf(costs, next);
                priorities = Arrays.copyOf(priorities, next);
            }
            int child = size++;
            while (child > 0) {
                int parent = (child - 1) >>> 1;
                if (!less(priority, cost, priorities[parent], costs[parent])) break;
                indexes[child] = indexes[parent];
                costs[child] = costs[parent];
                priorities[child] = priorities[parent];
                child = parent;
            }
            indexes[child] = index;
            costs[child] = cost;
            priorities[child] = priority;
        }

        int pollIndex() {
            int result = indexes[0];
            lastG = costs[0];
            int last = --size;
            int lastIndex = indexes[last];
            int lastCost = costs[last];
            int lastPriority = priorities[last];
            int parent = 0;
            while (true) {
                int left = parent * 2 + 1;
                if (left >= size) break;
                int right = left + 1;
                int child = right < size
                        && less(priorities[right], costs[right],
                        priorities[left], costs[left]) ? right : left;
                if (!less(priorities[child], costs[child], lastPriority, lastCost)) break;
                indexes[parent] = indexes[child];
                costs[parent] = costs[child];
                priorities[parent] = priorities[child];
                parent = child;
            }
            if (size > 0) {
                indexes[parent] = lastIndex;
                costs[parent] = lastCost;
                priorities[parent] = lastPriority;
            }
            return result;
        }

        private static boolean less(int f0, int g0, int f1, int g1) {
            return f0 < f1 || f0 == f1 && g0 < g1;
        }
    }
}
