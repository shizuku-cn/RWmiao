package com.shizuku.rwmiao.module;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SimulationLifecycleTest {
    @Test
    public void tickRollbackWithoutReloadStartsNewMatch() {
        SimulationLifecycle lifecycle = new SimulationLifecycle();
        assertEquals(SimulationLifecycle.Observation.ADVANCE, lifecycle.observe(400, 0));
        assertEquals(SimulationLifecycle.Observation.ADVANCE, lifecycle.observe(401, 0));
        assertEquals(SimulationLifecycle.Observation.NEW_MATCH, lifecycle.observe(0, 0));
    }

    @Test
    public void multiplayerReloadOwnsRollbackAndPreservesMatch() {
        SimulationLifecycle lifecycle = new SimulationLifecycle();
        lifecycle.observe(400, 0);
        assertEquals(SimulationLifecycle.Observation.RESYNC, lifecycle.observe(350, 1));
        assertEquals(SimulationLifecycle.Observation.ADVANCE, lifecycle.observe(351, 1));
    }

    @Test
    public void reloadIsObservedEvenWhenTickDoesNotMoveBackwards() {
        SimulationLifecycle lifecycle = new SimulationLifecycle();
        lifecycle.observe(400, 0);
        assertEquals(SimulationLifecycle.Observation.RESYNC, lifecycle.observe(400, 1));
        assertEquals(SimulationLifecycle.Observation.ADVANCE, lifecycle.observe(401, 1));
    }

    @Test
    public void consumedReloadDoesNotMaskLaterNewMatch() {
        SimulationLifecycle lifecycle = new SimulationLifecycle();
        lifecycle.observe(400, 0);
        lifecycle.observe(401, 1);
        assertEquals(SimulationLifecycle.Observation.NEW_MATCH, lifecycle.observe(0, 1));
    }

    @Test
    public void invalidAndDuplicateTicksDoNotChangeLifecycle() {
        SimulationLifecycle lifecycle = new SimulationLifecycle();
        assertEquals(SimulationLifecycle.Observation.INVALID, lifecycle.observe(-1, 0));
        assertEquals(SimulationLifecycle.Observation.ADVANCE, lifecycle.observe(10, 0));
        assertEquals(SimulationLifecycle.Observation.DUPLICATE, lifecycle.observe(10, 0));
    }
}
