package com.shizuku.rwmiao.module;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;

final class GameSyncTracker {
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final AtomicLong completedGeneration = new AtomicLong();
    @SuppressWarnings("unused")
    private XposedInterface.HookHandle loadHook;

    GameSyncTracker(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> saver = loader.loadClass(host.target("gameFramework.aj"));
        Class<?> reader = loader.loadClass(host.target("gameFramework.j.j"));
        Method load = host.findCompatibleMethod(
                saver, "a", reader, boolean.class, boolean.class);
        if (load == null) {
            throw new NoSuchMethodException("gameFramework.aj.a(j,boolean,boolean)");
        }
        loadHook = host.hookExecutable(load, chain -> {
            boolean resync = Boolean.TRUE.equals(chain.getArg(1))
                    && Boolean.TRUE.equals(chain.getArg(2));
            Object result = chain.proceed();
            if (resync && !Boolean.FALSE.equals(result)) {
                completedGeneration.incrementAndGet();
                host.notifyGameResyncCompleted();
            }
            return result;
        });
    }

    long completedGeneration() {
        return completedGeneration.get();
    }
}
