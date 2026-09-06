package com.shizuku.rwmiao.module.script;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GameSnapshotThreatTest {
    @Test
    public void stationaryFarThreatDoesNotRequireRetreat() {
        UnitSnapshot own = unit(1, 0, 0, 0, 0, 0, 120, false);
        UnitSnapshot threat = unit(2, 800, 0, 0, 0, 0, 180, false);
        Map<String, Object> result = snapshot(own, threat).threatAssessment(
                own, Collections.singletonList(threat), 180, 30, 30, 3000, 1200);
        assertFalse(Boolean.TRUE.equals(result.get("retreat_required")));
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>)((java.util.List<?>)result.get("threats")).get(0)).get("moving")));
    }

    @Test
    public void movingThreatEnteringRangeRequiresRetreat() {
        UnitSnapshot own = unit(1, 0, 0, 0, 0, 0, 120, false);
        UnitSnapshot threat = unit(2, 400, 0, -2, 0, 180, 180, true);
        Map<String, Object> result = snapshot(own, threat).threatAssessment(
                own, Collections.singletonList(threat), 180, 30, 30, 3000, 1200);
        assertTrue(Boolean.TRUE.equals(result.get("retreat_required")));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>)((java.util.List<?>)result.get("threats")).get(0)).get("approaching")));
    }

    @Test
    public void trajectoryUsesCurrentVelocityAndAcceleration() {
        UnitSnapshot own = unit(1, 0, 0, 1, 0, 0, 120, true);
        Map<String, Object> result = snapshot(own).predictTrajectory(own, 30, 10);
        Map<?, ?> last = (Map<?, ?>)((java.util.List<?>)result.get("points")).get(2);
        assertTrue(((Number)last.get("x")).floatValue() > 20f);
    }

    private static GameSnapshot snapshot(UnitSnapshot... units) {
        return new GameSnapshot(1, false, 0, Arrays.asList(units));
    }

    private static UnitSnapshot unit(long id, float x, float y, float vx, float vy,
                                     float heading, float range, boolean moving) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("velocity_x", vx);
        extras.put("velocity_y", vy);
        extras.put("real_speed", (float)Math.hypot(vx, vy));
        extras.put("moving", moving);
        extras.put("throttle", moving ? 1f : 0f);
        extras.put("acceleration", 0.1f);
        extras.put("turn_speed", 20f);
        return new UnitSnapshot(id, "test", "test", id == 1 ? 0 : 1, "team", id == 1 ? 0 : 1,
                x, y, 0, heading, 12, 100, 100, 0, 0, 1,
                false, false, false, false, true, false, false, false, "LAND",
                range, 1, 0, 0, null, null, null, null, null, null, 2,
                Collections.emptyList(), Collections.emptyList(), extras, null);
    }
}
