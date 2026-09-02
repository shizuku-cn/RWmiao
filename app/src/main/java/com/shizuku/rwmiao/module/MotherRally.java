package com.shizuku.rwmiao.module;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_MOTHER_RALLY;

import java.lang.reflect.Method;

final class MotherRally {
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private Class<?> producerInterface;
    private Class<?> productionActionClass;

    MotherRally(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        producerInterface = loader.loadClass(host.target("game.units.d.s"));
        productionActionClass = loader.loadClass(host.target("game.units.a.w"));
    }

    void refreshSettings() {
    }

    boolean enabled() {
        return host.selectionActionEnabled(KEY_MOTHER_RALLY);
    }

    boolean isApplicable(Object unit) {
        if (!enabled() || unit == null || producerInterface == null) return false;
        if (!producerInterface.isInstance(unit)) return false;
        if (!hasProductionAction(unit)) return false;
        return ensureRallyCapability(unit);
    }

    private boolean ensureRallyCapability(Object unit) {
        if (unit.getClass().getName().equals(host.target("game.units.custom.j"))) {
            try {
                Object definition = host.findFieldValue(unit, "x");
                if (definition == null) return false;
                java.lang.reflect.Field rallyFlag = host.findField(definition.getClass(), "dc");
                if (!rallyFlag.getBoolean(definition)) rallyFlag.setBoolean(definition, true);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    private boolean hasProductionAction(Object unit) {
        try {
            Method listMethod = host.findNoArgMethod(unit.getClass(), "N");
            if (listMethod == null) return false;
            Object value = listMethod.invoke(unit);
            if (!(value instanceof Iterable)) return false;
            for (Object action : (Iterable<?>) value) {
                if (action == null) continue;
                if (action.getClass().getName().equals(host.target("game.units.a.o"))) {
                    return true;
                }
                if (productionActionClass == null || !productionActionClass.isInstance(action)) {
                    continue;
                }
                Method producedType = host.findNoArgMethod(action.getClass(), "h");
                if (producedType != null && producedType.invoke(action) != null) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
