package com.shizuku.rwmiao.module.support;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

public final class GameFrameDispatcher {
    public interface Callback {
        void afterFrame(Object renderer, float delta) throws Throwable;
    }

    public static final class Registration {
        private final GameFrameDispatcher owner;
        private final Method method;
        private final Callback callback;
        private volatile boolean closed;

        private Registration(GameFrameDispatcher owner, Method method, Callback callback) {
            this.owner = owner;
            this.method = method;
            this.callback = callback;
        }

        public void close() {
            if (closed) return;
            closed = true;
            owner.unregister(method, callback);
        }
    }

    private static final class Entry {
        final Method method;
        volatile Callback[] callbacks = new Callback[0];
        XposedInterface.HookHandle hook;

        Entry(Method method) {
            this.method = method;
        }
    }

    private final RWmiaoModule host;
    private final Map<Method, Entry> entries = new ConcurrentHashMap<>();

    public GameFrameDispatcher(RWmiaoModule host) {
        this.host = host;
    }

    public Registration register(Method method, Callback callback) {
        if (method == null || callback == null) {
            throw new IllegalArgumentException("frame method and callback are required");
        }
        Entry entry = entries.computeIfAbsent(method, Entry::new);
        synchronized (entry) {
            if (entry.hook == null) {
                entry.hook = host.hookExecutable(method, chain -> {
                    Object result = chain.proceed();
                    Object renderer = chain.getThisObject();
                    Object rawDelta = chain.getArg(0);
                    float delta = rawDelta instanceof Number
                            ? ((Number) rawDelta).floatValue() : 0.0f;
                    Callback[] callbacks = entry.callbacks;
                    for (Callback registeredCallback : callbacks) {
                        registeredCallback.afterFrame(renderer, delta);
                    }
                    return result;
                });
            }
            Callback[] current = entry.callbacks;
            for (Callback registered : current) {
                if (registered == callback) return new Registration(this, method, callback);
            }
            Callback[] updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = callback;
            entry.callbacks = updated;
        }
        return new Registration(this, method, callback);
    }

    private void unregister(Method method, Callback callback) {
        Entry entry = entries.get(method);
        if (entry == null) return;
        synchronized (entry) {
            Callback[] current = entry.callbacks;
            int index = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == callback) {
                    index = i;
                    break;
                }
            }
            if (index < 0) return;
            Callback[] updated = new Callback[current.length - 1];
            System.arraycopy(current, 0, updated, 0, index);
            System.arraycopy(current, index + 1, updated, index,
                    current.length - index - 1);
            entry.callbacks = updated;
            if (updated.length != 0) return;
            entries.remove(method, entry);
            XposedInterface.HookHandle hook = entry.hook;
            entry.hook = null;
            if (hook != null) {
                try { hook.unhook(); } catch (Throwable ignored) { }
            }
        }
    }
}
