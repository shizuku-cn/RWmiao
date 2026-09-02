package com.shizuku.rwmiao.module.freeselection;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FreeSelectionGeometryTest {
    @Test
    public void supportsClockwiseCounterClockwiseAndBoundaryPoints() {
        float[] squareX = {0, 100, 100, 0};
        float[] squareY = {0, 0, 100, 100};
        assertTrue(FreeSelectionGeometry.contains(squareX, squareY, 4, 50, 50));
        assertTrue(FreeSelectionGeometry.contains(
                new float[]{0, 0, 100, 100},
                new float[]{100, 0, 0, 100}, 4, 50, 50));
        assertTrue(FreeSelectionGeometry.contains(squareX, squareY, 4, 0, 50));
        assertFalse(FreeSelectionGeometry.contains(squareX, squareY, 4, 120, 50));
    }

    @Test
    public void supportsConcaveAndSelfIntersectingPolygons() {
        float[] concaveX = {0, 100, 100, 40, 40, 0};
        float[] concaveY = {0, 0, 40, 40, 100, 100};
        assertTrue(FreeSelectionGeometry.contains(concaveX, concaveY, 6, 20, 20));
        assertFalse(FreeSelectionGeometry.contains(concaveX, concaveY, 6, 80, 80));
        assertTrue(FreeSelectionGeometry.contains(concaveX, concaveY, 6, 40, 70));

        float[] bowTieX = {0, 100, 0, 100};
        float[] bowTieY = {0, 100, 100, 0};
        assertTrue(FreeSelectionGeometry.contains(bowTieX, bowTieY, 4, 25, 75));
        assertTrue(FreeSelectionGeometry.contains(bowTieX, bowTieY, 4, 50, 50));
    }

    @Test
    public void detectsClosedTracksWithRepeatedFinalPoint() {
        float[] repeatedX = {0, 100, 100, 0, 0};
        float[] repeatedY = {0, 0, 100, 100, 0};
        assertTrue(FreeSelectionGeometry.contains(repeatedX, repeatedY, 5, 25, 25));
        assertTrue(FreeSelectionGeometry.isClosed(
                repeatedX, repeatedY, 5, 1, 4, 300, 9000));
        assertFalse(FreeSelectionGeometry.isClosed(
                repeatedX, repeatedY, 4, 1, 4, 300, 9000));
    }

    @Test
    public void remainsStableAcrossCameraAndZoomConversion() {
        float[] worldX = {100, 150, 150, 100};
        float[] worldY = {50, 50, 100, 100};
        float[] transformedX = new float[4];
        float[] transformedY = new float[4];
        for (int i = 0; i < 4; i++) {
            float screenX = (worldX[i] - 100) * 2;
            float screenY = (worldY[i] - 50) * 2;
            transformedX[i] = screenX / 2 + 100;
            transformedY[i] = screenY / 2 + 50;
        }
        assertTrue(FreeSelectionGeometry.contains(
                transformedX, transformedY, 4, 125, 75));
    }
}
