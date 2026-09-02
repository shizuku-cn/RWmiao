package com.shizuku.rwmiao.module;

public final class SimulationLifecycle {
    public enum Observation {
        INVALID,
        DUPLICATE,
        ADVANCE,
        RESYNC,
        NEW_MATCH
    }

    private int lastTick = Integer.MIN_VALUE;
    private long observedResyncGeneration;

    public Observation observe(int tick, long resyncGeneration) {
        if (tick < 0) return Observation.INVALID;
        if (lastTick == Integer.MIN_VALUE) {
            lastTick = tick;
            observedResyncGeneration = resyncGeneration;
            return Observation.ADVANCE;
        }

        int previousTick = lastTick;
        boolean resynced = resyncGeneration != observedResyncGeneration;
        lastTick = tick;
        observedResyncGeneration = resyncGeneration;

        if (resynced) return Observation.RESYNC;
        if (tick == previousTick) return Observation.DUPLICATE;
        return tick < previousTick ? Observation.NEW_MATCH : Observation.ADVANCE;
    }

    public void reset(long resyncGeneration) {
        lastTick = Integer.MIN_VALUE;
        observedResyncGeneration = resyncGeneration;
    }
}
