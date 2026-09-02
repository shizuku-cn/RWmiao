package com.shizuku.rwmiao.app;

import java.util.concurrent.CopyOnWriteArrayList;

public final class ModuleStatusBus {
    public interface Listener {
        void onModuleStatusChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private ModuleStatusBus() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void dispatch() {
        for (Listener listener : LISTENERS) {
            listener.onModuleStatusChanged();
        }
    }
}
