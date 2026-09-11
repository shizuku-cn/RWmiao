package com.shizuku.rwmiao.module;

import java.lang.reflect.Method;
import java.util.ArrayList;
import io.github.libxposed.api.XposedInterface;

final class FactoryOptimization {
    private static final String TAG = "RWmiao";
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private Method produce;
    private XposedInterface.HookHandle hook;

    FactoryOptimization(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> renderer = loader.loadClass(host.target("gameFramework.f.i"));
        Class<?> command = loader.loadClass(host.target("gameFramework.e"));
        Class<?> action = loader.loadClass(host.target("game.units.a.s"));
        produce = host.findCompatibleMethod(renderer, "a", command, action);
        refreshSettings();
    }

    synchronized void refreshSettings() {
        if (!host.factoryOptimizationEnabled(loader)) {
            if (hook != null) hook.unhook();
            hook = null;
            return;
        }
        if (hook != null) return;
        hook = host.hookExecutable(produce, chain -> {
            try {
                if (optimizeFactoryCommand(loader, chain.getArg(0), chain.getArg(1))) {
                    return null;
                }
            } catch (Throwable t) {
                host.log(5, TAG, "Factory optimization skipped after reflection mismatch", t);
            }
            return chain.proceed();
        });
    }

private boolean optimizeFactoryCommand(ClassLoader loader, Object command, Object action)
        throws Throwable {
    if (command == null || action == null) return false;
    Object actionType = host.findField(action.getClass(), "j").get(action);
    if (actionType == null) return false;

    Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
    Class<?> battleUnitClass = loader.loadClass(host.target("game.units.bp"));
    Class<?> factoryClass = loader.loadClass(host.target("game.units.d.s"));
    Class<?> allUnitsClass = loader.loadClass(host.target("gameFramework.ah"));
    Class<?> rendererClass = loader.loadClass(host.target("gameFramework.f.i"));
    Method visible = host.findCompatibleMethod(rendererClass, "e", unitClass);
    visible.setAccessible(true);
    Object allUnits = host.findField(allUnitsClass, "et").get(null);
    if (!(allUnits instanceof Iterable)) return false;

    ArrayList<Object> factories = new ArrayList<>();
    for (Object candidate : (Iterable<?>) allUnits) {
        if (candidate == null || !battleUnitClass.isInstance(candidate)
                || !factoryClass.isInstance(candidate)
                || !host.boolField(candidate, "cI")
                || !Boolean.TRUE.equals(visible.invoke(null, candidate))) {
            continue;
        }
        Method actionForUnit = host.findCompatibleMethod(candidate.getClass(), "a",
                actionType.getClass());
        if (actionForUnit == null) continue;
        Object producedAction = actionForUnit.invoke(candidate, actionType);
        if (producedAction == null) continue;
        Method canProduce = host.findCompatibleMethod(producedAction.getClass(), "b",
                battleUnitClass);
        if (canProduce != null && Boolean.TRUE.equals(canProduce.invoke(producedAction, candidate))) {
            factories.add(candidate);
        }
    }
    if (factories.size() < 2) return false;

    Method addFactory = host.findCompatibleMethod(command.getClass(), "a", battleUnitClass);
    if (addFactory == null) return false;
    int[] queued = new int[factories.size()];
    int[] assigned = new int[factories.size()];
    for (int i = 0; i < factories.size(); i++) {
        Method queueCount = host.findNoArgMethod(factories.get(i).getClass(), "cW");
        queued[i] = queueCount == null ? Integer.MAX_VALUE : (int) host.number(queueCount.invoke(factories.get(i)));
    }
    for (int order = 0; order < factories.size(); order++) {
        int selected = 0;
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < factories.size(); i++) {
            int value = queued[i] + assigned[i];
            if (value < lowest) {
                lowest = value;
                selected = i;
            }
        }
        addFactory.invoke(command, factories.get(selected));
        assigned[selected]++;
    }
    return true;
}

}
