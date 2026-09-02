package com.shizuku.rwmiao.module;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

final class ViewAll {
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private Method canSee;
    private Class<?> unitClass;
    private XposedInterface.HookHandle hook;
    private final Map<Class<?>, Method> deadMethods = new WeakHashMap<>();

    ViewAll(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> renderer = loader.loadClass(host.target("gameFramework.f.i"));
        unitClass = loader.loadClass(host.target("game.units.ce"));
        canSee = renderer.getDeclaredMethod("e", unitClass);
        refreshSettings();
    }

    synchronized void refreshSettings() {
        if (!host.viewAllEnabled(loader)) {
            if (hook != null) hook.unhook();
            hook = null;
            return;
        }
        if (hook != null) return;
        hook = host.hookExecutable(canSee, chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result)) return result;
            Object selected = chain.getArg(0);
            Method dead = deadMethod(selected == null ? unitClass : selected.getClass());
            if (selected != null && dead != null && !Boolean.TRUE.equals(dead.invoke(selected))) return true;
            return result;
        });
    }

    private synchronized Method deadMethod(Class<?> type) {
        Method cached = deadMethods.get(type);
        if (cached != null) return cached;
        Method resolved = host.findNoArgMethod(type, "cz");
        if (resolved != null) deadMethods.put(type, resolved);
        return resolved;
    }
}
