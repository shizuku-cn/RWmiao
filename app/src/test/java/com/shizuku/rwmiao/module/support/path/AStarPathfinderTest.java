package com.shizuku.rwmiao.module.support.path;

import android.graphics.PointF;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AStarPathfinderTest {
    private final AStarPathfinder pathfinder = new AStarPathfinder();

    @Test
    public void directActionSkipsPathSearch() {
        SmartPathGrid grid = openGrid(12, 12);

        List<PointF> route = pathfinder.findActionApproach(
                grid, 1.5f, 1.5f, 10.5f, 10.5f, () -> false);

        assertTrue(route.isEmpty());
    }

    @Test
    public void actionSearchStopsAtFirstClearApproach() {
        SmartPathGrid grid = openGrid(12, 12, 0.25f);
        for (int y = 0; y < 10; y++) block(grid, 5, y);

        List<PointF> route = pathfinder.findActionApproach(
                grid, world(1, grid), world(1, grid),
                world(10, grid), world(1, grid), () -> false);
        List<PointF> fullRoute = pathfinder.find(
                grid, world(1, grid), world(1, grid),
                world(10, grid), world(1, grid), () -> false);

        assertFalse(route.isEmpty());
        assertTrue(route.size() < fullRoute.size());
    }

    @Test
    public void ordinaryMoveStillKeepsExactDestination() {
        SmartPathGrid grid = openGrid(12, 12);

        List<PointF> route = pathfinder.find(
                grid, 1.5f, 1.5f, 10.25f, 10.75f, () -> false);

        assertTrue(route.size() == 1);
    }

    @Test
    public void blockedActionCellCanStillBeApproachedDirectly() {
        SmartPathGrid grid = openGrid(8, 8);
        block(grid, 6, 4);

        List<PointF> route = pathfinder.findActionApproach(
                grid, 2.5f, 4.5f, 6.5f, 4.5f, () -> false);

        assertTrue(route.isEmpty());
    }

    @Test
    public void largeBuildingIsReachableFromEveryFace() {
        SmartPathGrid grid = openGrid(25, 25, 0.05f);
        blockSquare(grid, 10, 10, 14, 14);
        float target = world(12, grid);
        float radius = 55.0f;
        int[][] starts = {{2, 12}, {22, 12}, {12, 2}, {12, 22}};

        for (int[] start : starts) {
            float startX = world(start[0], grid);
            float startY = world(start[1], grid);
            assertTrue(grid.lineOfSightToTargetFootprint(
                    startX, startY, target, target, radius));
            assertTrue(pathfinder.findActionApproach(
                    grid, startX, startY, target, target, radius, () -> false).isEmpty());
        }
    }

    @Test
    public void centreOnlyRayDoesNotCrossLargeBuildingOccupancy() {
        SmartPathGrid grid = openGrid(25, 25, 0.05f);
        blockSquare(grid, 10, 10, 14, 14);
        float startX = world(2, grid);
        float y = world(12, grid);
        float targetX = world(12, grid);

        assertFalse(grid.lineOfSightToTarget(startX, y, targetX, y));
        assertTrue(grid.lineOfSightToTargetFootprint(startX, y, targetX, y, 55.0f));
    }

    @Test
    public void obstacleOutsideTargetFootprintStillRequiresRouting() {
        SmartPathGrid grid = openGrid(25, 25, 0.05f);
        blockSquare(grid, 10, 10, 14, 14);
        for (int y = 0; y <= 20; y++) block(grid, 7, y);

        List<PointF> route = pathfinder.findActionApproach(
                grid, world(2, grid), world(12, grid),
                world(12, grid), world(12, grid), 55.0f, () -> false);

        assertFalse(route.isEmpty());
    }

    private SmartPathGrid openGrid(int width, int height) {
        return openGrid(width, height, 1.0f);
    }

    private SmartPathGrid openGrid(int width, int height, float scale) {
        int size = width * height;
        return new SmartPathGrid(width, height, scale,
                new byte[size], new byte[size], new byte[size]);
    }

    private void block(SmartPathGrid grid, int x, int y) {
        int index = grid.height * x + y;
        grid.terrain[index] = -1;
    }

    private void blockSquare(SmartPathGrid grid, int minX, int minY, int maxX, int maxY) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) block(grid, x, y);
        }
    }

    private float world(int cell, SmartPathGrid grid) {
        return (cell + 0.5f) / grid.worldToGrid;
    }
}
