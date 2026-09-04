package com.shizuku.rwmiao.module;

import android.graphics.PointF;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_FACTORY_EXIT_THROUGH;

final class FactoryExitThrough {
    private static final String TAG = "RWmiao";

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private Method completeUnit;
    private Class<?> queueItemClass;
    private Class<?> factoryClass;
    private Class<?> unitClass;
    private Class<?> buildingClass;
    private Class<?> teamClass;
    private Class<?> commandClass;
    private Class<?> commandQueueClass;
    private Method createCommand;
    private Method setMoveTarget;
    private Method addUnit;
    private XposedInterface.HookHandle hook;

    FactoryExitThrough(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> queueClass = loader.loadClass(host.target("game.units.d.r"));
        queueItemClass = loader.loadClass(host.target("game.units.d.q"));
        factoryClass = loader.loadClass(host.target("game.units.d.s"));
        unitClass = loader.loadClass(host.target("game.units.bp"));
        buildingClass = loader.loadClass(host.target("game.units.d.f"));
        teamClass = loader.loadClass(host.target("game.p"));
        commandClass = loader.loadClass(host.target("gameFramework.e"));
        commandQueueClass = loader.loadClass(host.target("gameFramework.c"));

        completeUnit = host.findCompatibleMethod(
                queueClass, "a", queueItemClass, float.class, boolean.class, float.class);
        createCommand = exactMethod(commandQueueClass, "a", teamClass);
        setMoveTarget = exactMethod(commandClass, "a", float.class, float.class);
        addUnit = exactMethod(commandClass, "a", unitClass);
        if (completeUnit == null || createCommand == null
                || setMoveTarget == null || addUnit == null) {
            throw new NoSuchMethodException("factory completion or native move command");
        }
        refreshSettings();
    }

    synchronized void refreshSettings() {
        MotherRally motherRally = host.motherRallyFeature();
        boolean motherRallyEnabled = motherRally != null && motherRally.enabled();
        if (!enabled() && !motherRallyEnabled) {
            unhook();
            return;
        }
        if (hook != null) return;
        hook = host.hookExecutable(completeUnit, chain -> {
            Object result = chain.proceed();
            try {
                dispatchToRally(chain.getThisObject(), result);
            } catch (Throwable t) {
                host.log(5, TAG, "Factory rally move skipped", t);
            }
            return result;
        });
    }

    private void dispatchToRally(Object productionQueue, Object result) throws Throwable {
        if (productionQueue == null || result == null) return;
        Object producer = host.findFieldValue(productionQueue, "a");
        if (producer == null || !factoryClass.isInstance(producer)) return;
        if (!enabled()) {
            MotherRally motherRally = host.motherRallyFeature();
            if (motherRally == null || !motherRally.isApplicable(producer)) return;
        }
        Object rallyValue = host.findFieldValue(productionQueue, "b");
        if (!(rallyValue instanceof PointF)) return;
        PointF rally = (PointF) rallyValue;
        if (!Float.isFinite(rally.x) || !Float.isFinite(rally.y)) return;

        Object engine = host.findEngine(loader);
        Object localTeam = host.findField(engine.getClass(), "bp").get(engine);
        Object producerTeam = host.findFieldValue(producer, "bZ");
        if (localTeam == null || producerTeam != localTeam) return;

        if (!unitClass.isInstance(result) || buildingClass.isInstance(result)) return;
        Object command = createCommand.invoke(host.findField(engine.getClass(), "cc").get(engine),
                localTeam);
        if (command == null) return;
        setBoolean(command, "e", false);
        setBoolean(command, "h", true);
        setMoveTarget.invoke(command, rally.x, rally.y);
        addUnit.invoke(command, result);
    }

    private boolean enabled() {
        return host.selectionActionEnabled(KEY_FACTORY_EXIT_THROUGH);
    }

    private void setBoolean(Object object, String name, boolean value) throws Throwable {
        java.lang.reflect.Field field = host.findField(object.getClass(), name);
        if (field.getType() == boolean.class) field.setBoolean(object, value);
    }

    private Method exactMethod(Class<?> type, String name, Class<?>... parameters) {
        return host.findExactCompatibleMethod(type, name, parameters);
    }

    private void unhook() {
        XposedInterface.HookHandle current = hook;
        hook = null;
        if (current != null) {
            try {
                current.unhook();
            } catch (Throwable t) {
                host.log(5, TAG, "Failed to unhook factory rally move", t);
            }
        }
    }
}
