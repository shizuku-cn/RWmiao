package com.shizuku.rwmiao.module;

import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/** Native economic-panel source selection hook. */
final class EconomicPanel {
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private Method setMode;
    private Class<?> mode;
    private XposedInterface.HookHandle hook;

    EconomicPanel(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        mode = loader.loadClass(host.target("gameFramework.g.g"));
        setMode = host.findCompatibleMethod(engine, "a", mode, int.class);
        if (setMode == null) throw new NoSuchMethodException("k.a(g,int)");
        refreshSettings();
    }

    synchronized void refreshSettings() {
        if (!host.economicPanelEnabled(loader)) {
            if (hook != null) hook.unhook();
            hook = null;
            return;
        }
        if (hook != null) return;
        hook = host.hookExecutable(setMode, chain -> {
            Object result = chain.proceed();
            host.applyEconomicPanelIfEnabled(loader, chain.getThisObject(), setMode, mode);
            return result;
        });
    }
}
