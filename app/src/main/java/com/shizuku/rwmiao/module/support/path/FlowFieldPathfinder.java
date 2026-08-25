package com.shizuku.rwmiao.module.support.path;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Shared reverse-flow search with destination slots and deterministic lanes. */
public final class FlowFieldPathfinder {
    private static final int RESERVATION_PENALTY = 8;
    private static final int SMOOTHING_LOOK_AHEAD = 48;
    private static final int[] DX = {-1, 1, 0, 0, -1, -1, 1, 1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    public List<List<PointF>> findAll(SmartPathGrid grid, List<Input> inputs,
                                      float targetX, float targetY, boolean spread,
                                      BooleanSupplier cancelled) {
        if (grid == null || !grid.valid() || inputs.isEmpty()) return Collections.emptyList();
        int tx = clamp((int) (targetX * grid.worldToGrid), 0, grid.width - 1);
        int ty = clamp((int) (targetY * grid.worldToGrid), 0, grid.height - 1);
        ArrayList<Integer> goals = goals(grid, tx, ty, spread ? inputs.size() : 1);
        if (goals.isEmpty()) return Collections.emptyList();
        int[] distance = distanceField(grid, goals, inputs, cancelled);
        if (distance == null) return Collections.emptyList();
        int[] assigned = assignGoals(grid, inputs, goals);
        int[] reservations = new int[grid.width * grid.height];
        boolean avoidCongestion = inputs.size() > 1;
        ArrayList<List<PointF>> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (cancelled.getAsBoolean()) return Collections.emptyList();
            List<PointF> route = trace(grid, inputs.get(i), assigned[i], distance,
                    reservations, i, spread, avoidCongestion, targetX, targetY);
            applyLaneOffset(grid, inputs.get(i), route, i, inputs.size());
            result.add(route);
        }
        return result;
    }

    private int[] distanceField(SmartPathGrid grid, List<Integer> goals, List<Input> inputs,
                                BooleanSupplier cancelled) {
        int[] distance = new int[grid.width * grid.height];
        Arrays.fill(distance, Integer.MAX_VALUE);
        IntHeap open = new IntHeap(Math.max(32, goals.size() * 2));
        for (int goal : goals) {
            distance[goal] = 0;
            open.add(goal, 0);
        }
        boolean[] wanted = new boolean[distance.length];
        int remaining = 0;
        for (Input input : inputs) {
            int sx = clamp((int) (input.x * grid.worldToGrid), 0, grid.width - 1);
            int sy = clamp((int) (input.y * grid.worldToGrid), 0, grid.height - 1);
            int start = index(grid, sx, sy);
            if (!wanted[start]) {
                wanted[start] = true;
                remaining++;
            }
        }
        int expanded = 0;
        while (!open.isEmpty()) {
            if ((expanded++ & 255) == 0 && cancelled.getAsBoolean()) return null;
            int nodeIndex = open.pollIndex();
            int nodeCost = open.lastCost;
            if (nodeCost != distance[nodeIndex]) continue;
            if (wanted[nodeIndex]) {
                wanted[nodeIndex] = false;
                if (--remaining == 0) break;
            }
            int x = nodeIndex % grid.width;
            int y = nodeIndex / grid.width;
            for (int d = 0; d < DX.length; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                if (!canStep(grid, x, y, nx, ny)) continue;
                boolean diagonal = DX[d] != 0 && DY[d] != 0;
                int next = index(grid, nx, ny);
                int value = nodeCost + (diagonal ? 14 : 10);
                if (value >= distance[next]) continue;
                distance[next] = value;
                open.add(next, value);
            }
        }
        return distance;
    }

    /** Keep neighbouring units in collision-radius-aware lanes around corners. */
    private void applyLaneOffset(SmartPathGrid grid, Input input, List<PointF> route,
                                 int unitIndex, int unitCount) {
        if (route.size() < 2 || unitCount < 2) return;
        int laneCount = Math.min(7, unitCount);
        float lane = unitIndex % laneCount - (laneCount - 1) * 0.5f;
        if (Math.abs(lane) < 0.01f) return;
        float cellWorld = 1.0f / grid.worldToGrid;
        float laneWidth = Math.max(cellWorld * 0.22f,
                Math.min(input.radius * 0.55f, cellWorld * 0.72f));
        float offset = lane * laneWidth;
        float previousX = input.x;
        float previousY = input.y;
        for (int i = 0; i < route.size() - 1; i++) {
            PointF point = route.get(i);
            PointF next = route.get(i + 1);
            float dx = next.x - previousX;
            float dy = next.y - previousY;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length <= 0.001f) continue;
            float candidateX = point.x + (-dy / length) * offset;
            float candidateY = point.y + (dx / length) * offset;
            int gx = (int) (candidateX * grid.worldToGrid);
            int gy = (int) (candidateY * grid.worldToGrid);
            int clearance = Math.max(0,
                    Math.min(2, (int) Math.ceil(input.radius * grid.worldToGrid * 0.55f)));
            if (hasClearance(grid, gx, gy, clearance)) {
                route.set(i, new PointF(candidateX, candidateY));
            }
            previousX = point.x;
            previousY = point.y;
        }
    }

    private List<PointF> trace(SmartPathGrid grid, Input input, int assignedGoal,
                               int[] distance, int[] reservations, int lane, boolean spread,
                               boolean avoidCongestion,
                               float exactX, float exactY) {
        int sx = clamp((int) (input.x * grid.worldToGrid), 0, grid.width - 1);
        int sy = clamp((int) (input.y * grid.worldToGrid), 0, grid.height - 1);
        int current = index(grid, sx, sy);
        if (distance[current] == Integer.MAX_VALUE) return Collections.emptyList();
        ArrayList<Integer> raw = new ArrayList<>();
        raw.add(current);
        for (int steps = 0; steps < grid.width * grid.height && distance[current] > 0; steps++) {
            int x = current % grid.width;
            int y = current / grid.width;
            int best = -1;
            int bestScore = Integer.MAX_VALUE;
            int bestTie = Integer.MAX_VALUE;
            for (int offset = 0; offset < DX.length; offset++) {
                int direction = (offset + lane * 3) & 7;
                int nx = x + DX[direction];
                int ny = y + DY[direction];
                if (!canStep(grid, x, y, nx, ny)) continue;
                int next = index(grid, nx, ny);
                int value = distance[next];
                if (value >= distance[current]) continue;
                int score = value + reservations[next] * RESERVATION_PENALTY;
                int tie = Math.abs((direction * 2) - (lane & 7));
                if (score < bestScore || score == bestScore && tie < bestTie) {
                    bestScore = score;
                    bestTie = tie;
                    best = next;
                }
            }
            if (best < 0 || best == current) break;
            current = best;
            raw.add(current);
        }
        if (spread && current != assignedGoal
                && grid.lineOfSight(current % grid.width, current / grid.width,
                assignedGoal % grid.width, assignedGoal / grid.width)) {
            raw.add(assignedGoal);
        }
        if (avoidCongestion) reserve(grid, raw, reservations, input.radius);
        ArrayList<Integer> smooth = smooth(grid, raw,
                avoidCongestion ? SMOOTHING_LOOK_AHEAD : Integer.MAX_VALUE);
        ArrayList<PointF> route = new ArrayList<>();
        int exactGoal = index(grid,
                clamp((int) (exactX * grid.worldToGrid), 0, grid.width - 1),
                clamp((int) (exactY * grid.worldToGrid), 0, grid.height - 1));
        for (int i = 1; i < smooth.size(); i++) {
            int node = smooth.get(i);
            float x = ((node % grid.width) + 0.5f) / grid.worldToGrid;
            float y = ((node / grid.width) + 0.5f) / grid.worldToGrid;
            if (i == smooth.size() - 1 && (!spread || node == exactGoal)) {
                x = exactX;
                y = exactY;
            }
            route.add(new PointF(x, y));
        }
        if (spread && route.isEmpty()) {
            route.add(worldPoint(grid, assignedGoal));
        }
        return route;
    }

    private ArrayList<Integer> goals(SmartPathGrid grid, int tx, int ty, int count) {
        ArrayList<Integer> result = new ArrayList<>();
        int limit = Math.max(grid.width, grid.height);
        for (int radius = 0; radius <= limit; radius++) {
            ArrayList<Integer> ring = new ArrayList<>();
            for (int y = ty - radius; y <= ty + radius; y++) {
                for (int x = tx - radius; x <= tx + radius; x++) {
                    if (Math.max(Math.abs(x - tx), Math.abs(y - ty)) != radius
                            || !grid.passable(x, y)) continue;
                    ring.add(index(grid, x, y));
                }
            }
            ring.sort(Comparator.comparingInt(node -> {
                int dx = node % grid.width - tx;
                int dy = node / grid.width - ty;
                return dx * dx + dy * dy;
            }));
            for (int node : ring) {
                result.add(node);
                if (result.size() >= count) return result;
            }
        }
        return result;
    }

    private int[] assignGoals(SmartPathGrid grid, List<Input> inputs, List<Integer> goals) {
        int[] assigned = new int[inputs.size()];
        boolean[] used = new boolean[goals.size()];
        for (int i = 0; i < inputs.size(); i++) {
            Input input = inputs.get(i);
            int best = 0;
            float bestDistance = Float.MAX_VALUE;
            for (int g = 0; g < goals.size(); g++) {
                if (used[g]) continue;
                int goal = goals.get(g);
                float dx = input.x * grid.worldToGrid - goal % grid.width;
                float dy = input.y * grid.worldToGrid - goal / grid.width;
                float value = dx * dx + dy * dy;
                if (value < bestDistance) {
                    bestDistance = value;
                    best = g;
                }
            }
            used[best] = true;
            assigned[i] = goals.get(best);
        }
        return assigned;
    }

    private ArrayList<Integer> smooth(SmartPathGrid grid, List<Integer> raw, int lookAhead) {
        ArrayList<Integer> result = new ArrayList<>();
        if (raw.isEmpty()) return result;
        int anchor = 0;
        result.add(raw.get(0));
        while (anchor < raw.size() - 1) {
            int next = Math.min(raw.size() - 1, anchor + lookAhead);
            int from = raw.get(anchor);
            while (next > anchor + 1) {
                int candidate = raw.get(next);
                if (grid.lineOfSight(from % grid.width, from / grid.width,
                        candidate % grid.width, candidate / grid.width)) break;
                next--;
            }
            result.add(raw.get(next));
            anchor = next;
        }
        return result;
    }

    /**
     * Reserve a narrow corridor after each route. Later units prefer another valid descent,
     * which avoids reconverging at the exact same corner without running another search.
     */
    private void reserve(SmartPathGrid grid, List<Integer> raw, int[] reservations,
                         float radius) {
        int halo = Math.max(1,
                Math.min(2, (int) Math.ceil(radius * grid.worldToGrid * 0.65f)));
        for (int position = 0; position < raw.size(); position += 2) {
            int node = raw.get(position);
            int x = node % grid.width;
            int y = node / grid.width;
            for (int oy = -halo; oy <= halo; oy++) {
                for (int ox = -halo; ox <= halo; ox++) {
                    int nx = x + ox;
                    int ny = y + oy;
                    if (!grid.passable(nx, ny)) continue;
                    if (ox != 0 && oy != 0 && Math.abs(ox) + Math.abs(oy) > halo) continue;
                    int weight = ox == 0 && oy == 0 ? 2 : 1;
                    reservations[index(grid, nx, ny)] += weight;
                }
            }
        }
    }

    private boolean hasClearance(SmartPathGrid grid, int x, int y, int radius) {
        for (int oy = -radius; oy <= radius; oy++) {
            for (int ox = -radius; ox <= radius; ox++) {
                if (!grid.passable(x + ox, y + oy)) return false;
            }
        }
        return true;
    }

    private boolean canStep(SmartPathGrid grid, int x, int y, int nx, int ny) {
        if (!grid.passable(nx, ny)) return false;
        return nx == x || ny == y
                || grid.passable(nx, y) && grid.passable(x, ny);
    }

    private PointF worldPoint(SmartPathGrid grid, int index) {
        return new PointF(((index % grid.width) + 0.5f) / grid.worldToGrid,
                ((index / grid.width) + 0.5f) / grid.worldToGrid);
    }

    private int index(SmartPathGrid grid, int x, int y) {
        return y * grid.width + x;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Input {
        public final float x;
        public final float y;
        public final float radius;

        public Input(float x, float y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    /** Primitive heap avoids allocating one object for every relaxed grid edge. */
    private static final class IntHeap {
        int[] indexes;
        int[] costs;
        int size;
        int lastCost;

        IntHeap(int capacity) {
            indexes = new int[capacity];
            costs = new int[capacity];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void add(int index, int cost) {
            if (size == indexes.length) {
                indexes = Arrays.copyOf(indexes, size * 2);
                costs = Arrays.copyOf(costs, size * 2);
            }
            int child = size++;
            while (child > 0) {
                int parent = (child - 1) >>> 1;
                if (costs[parent] <= cost) break;
                indexes[child] = indexes[parent];
                costs[child] = costs[parent];
                child = parent;
            }
            indexes[child] = index;
            costs[child] = cost;
        }

        int pollIndex() {
            int result = indexes[0];
            lastCost = costs[0];
            int lastIndex = indexes[--size];
            int lastValue = costs[size];
            int parent = 0;
            while (true) {
                int left = parent * 2 + 1;
                if (left >= size) break;
                int right = left + 1;
                int child = right < size && costs[right] < costs[left] ? right : left;
                if (costs[child] >= lastValue) break;
                indexes[parent] = indexes[child];
                costs[parent] = costs[child];
                parent = child;
            }
            if (size > 0) {
                indexes[parent] = lastIndex;
                costs[parent] = lastValue;
            }
            return result;
        }
    }
}
