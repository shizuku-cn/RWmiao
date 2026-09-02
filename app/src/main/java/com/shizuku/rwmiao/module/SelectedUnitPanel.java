package com.shizuku.rwmiao.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.DEFAULT_SELECTION_PANEL_COLUMNS;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_SELECTION_PANEL_COLUMNS;
import static com.shizuku.rwmiao.config.SettingsContract.MAX_SELECTION_PANEL_COLUMNS;
import static com.shizuku.rwmiao.config.SettingsContract.MIN_SELECTION_PANEL_COLUMNS;

final class SelectedUnitPanel {
    private static final String TAG = "RWmiao";
    private final RWmiaoModule host;
    private final ClassLoader loader;

    private XposedInterface.HookHandle renderHook;
    private XposedInterface.HookHandle baseActionColumnsHook;
    private XposedInterface.HookHandle wrappedActionColumnsHook;
    private Field rendererEngineField;
    private Field enginePanelBoundsField;
    private Field panelWidthField;
    private volatile int requestedColumns = DEFAULT_SELECTION_PANEL_COLUMNS;

    SelectedUnitPanel(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        refreshSettings();
    }

    synchronized void refreshSettings() throws Throwable {
        int columns = configuredColumns();
        requestedColumns = columns;
        if (columns == DEFAULT_SELECTION_PANEL_COLUMNS) {
            unhookAll();
            return;
        }

        try {
            if (renderHook == null) hookRenderer();
            if (baseActionColumnsHook == null || wrappedActionColumnsHook == null) {
                hookActionColumnMethods();
            }
        } catch (Throwable t) {
            unhookAll();
            throw t;
        }
    }

    private int configuredColumns() {
        int columns = host.preferenceInt(
                KEY_SELECTION_PANEL_COLUMNS, DEFAULT_SELECTION_PANEL_COLUMNS);
        return Math.max(MIN_SELECTION_PANEL_COLUMNS,
                Math.min(MAX_SELECTION_PANEL_COLUMNS, columns));
    }

    private void hookRenderer() throws Throwable {
        Class<?> panelClass = loader.loadClass(host.target("gameFramework.f.a"));
        Method render = host.findCompatibleMethod(panelClass, "d", float.class);
        if (render == null || render.getReturnType() != int.class) {
            throw new NoSuchMethodException("selected-unit panel renderer");
        }
        rendererEngineField = host.findField(panelClass, "b");
        enginePanelBoundsField = host.findField(rendererEngineField.getType(), "bT");
        panelWidthField = host.findField(enginePanelBoundsField.getType(), "c");

        renderHook = host.hookExecutable(render, chain -> {
            Object panel = chain.getThisObject();
            Object engine = rendererEngineField.get(panel);
            if (engine == null) return chain.proceed();
            Object panelBounds = enginePanelBoundsField.get(engine);
            if (panelBounds == null) return chain.proceed();
            float originalWidth = panelWidthField.getFloat(panelBounds);
            if (!(originalWidth > 0.0f)) return chain.proceed();

            float widthScale = ((float) requestedColumns) / DEFAULT_SELECTION_PANEL_COLUMNS;
            panelWidthField.setFloat(panelBounds, originalWidth * widthScale);
            try {
                return chain.proceed();
            } finally {
                panelWidthField.setFloat(panelBounds, originalWidth);
            }
        });
    }

    private void hookActionColumnMethods() throws Throwable {
        Class<?> actionClass = loader.loadClass(host.target("game.units.a.s"));
        Method baseColumns = host.findCompatibleMethod(actionClass, "m");
        if (baseColumns == null || baseColumns.getReturnType() != int.class) {
            throw new NoSuchMethodException("base action column method");
        }
        baseActionColumnsHook = host.hookExecutable(baseColumns, chain -> {
            return requestedColumns;
        });

        Class<?> wrappedActionClass = loader.loadClass(host.target("game.units.a.g"));
        Method wrappedColumns = host.findCompatibleMethod(wrappedActionClass, "m");
        if (wrappedColumns == null || wrappedColumns.getReturnType() != int.class) {
            throw new NoSuchMethodException("wrapped action column method");
        }
        wrappedActionColumnsHook = host.hookExecutable(wrappedColumns, chain -> {
            return requestedColumns;
        });
    }

    private void unhookAll() {
        unhook(renderHook);
        unhook(baseActionColumnsHook);
        unhook(wrappedActionColumnsHook);
        renderHook = null;
        baseActionColumnsHook = null;
        wrappedActionColumnsHook = null;
        rendererEngineField = null;
        enginePanelBoundsField = null;
        panelWidthField = null;
    }

    private void unhook(XposedInterface.HookHandle handle) {
        if (handle == null) return;
        try {
            handle.unhook();
        } catch (Throwable ignored) {
        }
    }
}
