package com.shizuku.rwmiao.module.selectall;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_SELECT_ALL;

public final class SelectAll {
    private static final String TAG = "RWmiao";

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private RuntimeAccess runtime;

    public SelectAll(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    public void install() throws Throwable {
        runtime = new RuntimeAccess();
    }

    public boolean enabled() {
        return host.selectionActionEnabled(KEY_SELECT_ALL);
    }

    public void selectAll() {
        if (!enabled() || runtime == null) return;
        try {
            Object engine = host.findEngine(loader);
            Object panel = runtime.ui.get(engine);
            Object player = runtime.player.get(engine);
            runtime.clearSelection.invoke(panel);

            Object all = runtime.allUnits.invoke(null);
            if (!(all instanceof Iterable)) return;
            for (Object unit : (Iterable<?>) all) {
                if (!runtime.battleUnit.isInstance(unit)
                        || runtime.dead.getBoolean(unit)
                        || runtime.owner.get(unit) != player
                        || runtime.attached.get(unit) != null) {
                    continue;
                }
                runtime.addSelection.invoke(panel, unit);
            }
        } catch (Throwable t) {
            host.log(6, TAG, "Native select-all failed", t);
        }
    }

    final class RuntimeAccess {
        final Class<?> uiClass = loader.loadClass(host.target("gameFramework.f.i"));
        final Class<?> unit = loader.loadClass(host.target("game.units.ce"));
        final Class<?> battleUnit = loader.loadClass(host.target("game.units.bp"));
        final Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        final Field ui = host.findField(engine, "bP");
        final Field player = host.findField(engine, "bp");
        final Field dead = host.findField(unit, "bX");
        final Field owner = host.findField(unit, "bZ");
        final Field attached = host.findField(unit, "cP");
        final Method allUnits = host.findCompatibleMethod(unit, "bn");
        final Method clearSelection = host.findNoArgMethod(uiClass, "h");
        final Method addSelection = host.findCompatibleMethod(uiClass, "b", unit);

        RuntimeAccess() throws Throwable {
            allUnits.setAccessible(true);
            if (clearSelection == null || addSelection == null) {
                throw new NoSuchMethodException("native select-all contract");
            }
        }
    }
}
