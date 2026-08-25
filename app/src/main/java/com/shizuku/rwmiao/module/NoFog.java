package com.shizuku.rwmiao.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_NO_FOG;

/** Local fog-grid setup hook. */
final class NoFog {
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private Method startGame;
    private XposedInterface.HookHandle hook;

    NoFog(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> gameClass = loader.loadClass(host.target("game.i"));
        startGame = gameClass.getDeclaredMethod("a", boolean.class, boolean.class, int.class);
        refreshSettings();
    }

    synchronized void refreshSettings() {
        boolean enabled = host.selectionActionEnabled(KEY_NO_FOG);
        if (!enabled) {
            if (hook != null) hook.unhook();
            hook = null;
            return;
        }
        if (hook != null) return;
        hook = host.hookExecutable(startGame, chain -> {
            Object game = chain.getThisObject();
            Object result = chain.proceed();
            try {
                if (host.isNoFogEnabled(game)) applyNoFogState(game);
            } catch (Throwable t) {
                host.log(6, "RWmiao", "Failed to apply local noFog setup", t);
            }
            return result;
        });
    }

    private void applyNoFogState(Object game) throws Throwable {
        Object map = host.findField(game.getClass(), "bI").get(game);
        if (map == null) return;
        host.findField(map.getClass(), "F").setBoolean(map, false);
        host.findField(map.getClass(), "G").setBoolean(map, false);

        Class<?> teamClass = loader.loadClass(host.target("game.p"));
        Method teams = teamClass.getDeclaredMethod("c");
        teams.setAccessible(true);
        Object value = teams.invoke(null);
        if (!(value instanceof Iterable)) return;
        Field qField = host.findField(teamClass, "Q");
        int width = host.findField(map.getClass(), "D").getInt(map);
        int height = host.findField(map.getClass(), "E").getInt(map);
        for (Object team : (Iterable<?>) value) {
            if (team == null) continue;
            Object fog = qField.get(team);
            if (!(fog instanceof byte[][])) continue;
            byte[][] grid = (byte[][]) fog;
            int rows = Math.min(width, grid.length);
            for (int x = 0; x < rows; x++) {
                if (grid[x] == null) continue;
                int columns = Math.min(height, grid[x].length);
                java.util.Arrays.fill(grid[x], 0, columns, (byte) 10);
            }
        }
    }
}
