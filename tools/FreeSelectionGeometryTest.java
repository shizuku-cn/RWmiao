import com.shizuku.rwmiao.module.freeselection.FreeSelectionGeometry;

/** Small dependency-free offline regression suite for free-selection geometry. */
public final class FreeSelectionGeometryTest {
    public static void main(String[] args) {
        float[] squareX = {0, 100, 100, 0};
        float[] squareY = {0, 0, 100, 100};
        check(FreeSelectionGeometry.contains(squareX, squareY, 4, 50, 50),
                "counter-clockwise square");
        check(FreeSelectionGeometry.contains(new float[]{0, 0, 100, 100},
                        new float[]{100, 0, 0, 100}, 4, 50, 50),
                "clockwise square");
        check(FreeSelectionGeometry.contains(squareX, squareY, 4, 0, 50),
                "boundary point");
        check(!FreeSelectionGeometry.contains(squareX, squareY, 4, 120, 50),
                "square outside");

        float[] concaveX = {0, 100, 100, 40, 40, 0};
        float[] concaveY = {0, 0, 40, 40, 100, 100};
        check(FreeSelectionGeometry.contains(concaveX, concaveY, 6, 20, 20),
                "concave inside");
        check(!FreeSelectionGeometry.contains(concaveX, concaveY, 6, 80, 80),
                "concave notch outside");
        check(FreeSelectionGeometry.contains(concaveX, concaveY, 6, 40, 70),
                "concave boundary");

        float[] bowTieX = {0, 100, 0, 100};
        float[] bowTieY = {0, 100, 100, 0};
        check(FreeSelectionGeometry.contains(bowTieX, bowTieY, 4, 25, 75),
                "self-intersecting even-odd lobe");
        check(FreeSelectionGeometry.contains(bowTieX, bowTieY, 4, 50, 50),
                "self-intersection boundary");

        float[] repeatedX = {0, 100, 100, 0, 0};
        float[] repeatedY = {0, 0, 100, 100, 0};
        check(FreeSelectionGeometry.contains(repeatedX, repeatedY, 5, 25, 25),
                "repeated closing point");
        check(FreeSelectionGeometry.isClosed(repeatedX, repeatedY, 5, 1, 4, 300, 9000),
                "closed repeated-point track");
        check(!FreeSelectionGeometry.isClosed(repeatedX, repeatedY, 4, 1, 4, 300, 9000),
                "unclosed track");

        // screen -> world: world = screen / scale + camera.
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
        check(FreeSelectionGeometry.contains(transformedX, transformedY, 4, 125, 75),
                "camera and zoom conversion");

        System.out.println("FreeSelectionGeometryTest: PASS");
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
