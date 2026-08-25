package com.shizuku.rwmiao.app;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local invalidation bus for the standalone module activity. */
public final class ThemeStateBus {
    public interface Listener {
        void onThemeChanged(int themeMode, boolean dynamicColor);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private ThemeStateBus() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void dispatch(int themeMode, boolean dynamicColor) {
        for (Listener listener : LISTENERS) {
            listener.onThemeChanged(themeMode, dynamicColor);
        }
    }
}
