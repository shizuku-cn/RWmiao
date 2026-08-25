package com.shizuku.rwmiao.payload;

import java.util.ArrayList;
import java.util.function.Consumer;

public final class RWMiaoBridge {
    public static Runnable rangeToggle;
    public static Runnable lineToggle;
    public static Runnable reinforceOpen;
    public static Runnable segmentToggle;
    public static Runnable smartPathToggle;
    public static Consumer<Object> scriptsOpen;
    public static String rangeTitle = "绘制范围";
    public static String lineTitle = "指示索敌";
    public static String segmentTitle = "分段指令";
    public static String smartPathTitle = "关闭智寻";
    public static String scriptsTitle = "脚本管理";
    private RWMiaoBridge() {}
    public static ArrayList maybeAdd(ArrayList actions, int insertionIndex,
                                     boolean addRange, boolean addLine, boolean addReinforce,
                                     boolean addSegment, boolean addSmartPath, boolean addScripts,
                                     boolean addMotherRally) {
        if (actions == null) return actions;
        int index = insertionIndex < 0 || insertionIndex > actions.size()
                ? actions.size() : insertionIndex;
        if (addRange && !actions.contains(RWMiaoDrawAction.INST)) {
            actions.add(index, RWMiaoDrawAction.INST);
        }
        if (addLine && !actions.contains(RWMiaoLineAction.INST)) {
            actions.add(index, RWMiaoLineAction.INST);
        }
        if (addReinforce && !actions.contains(RWMiaoReinforceAction.INST)) {
            actions.add(index, RWMiaoReinforceAction.INST);
        }
        if (addSegment && !actions.contains(RWMiaoSegmentAction.INST)) {
            actions.add(index, RWMiaoSegmentAction.INST);
        }
        if (addSmartPath && !actions.contains(RWMiaoSmartPathAction.INST)) {
            actions.add(index, RWMiaoSmartPathAction.INST);
        }
        if (addScripts && !actions.contains(RWMiaoScriptsAction.INST)) {
            actions.add(index, RWMiaoScriptsAction.INST);
        }
        if (addMotherRally && !containsNativeRallyAction(actions)) {
            // Keep the native setRally input branch, while supplying the
            // missing producer-unit entry without requiring a predeclared
            // action in the unit definition.
            actions.add(index, RWMiaoMotherRallyAction.INST);
        }
        return actions;
    }

    private static boolean containsNativeRallyAction(ArrayList actions) {
        for (Object action : actions) {
            if (action instanceof com.corrodinggames.rts.game.units.a.o) return true;
        }
        return false;
    }
    public static void toggleRange() { if (rangeToggle != null) rangeToggle.run(); }
    public static void toggleLine() { if (lineToggle != null) lineToggle.run(); }
    public static void openReinforce() { if (reinforceOpen != null) reinforceOpen.run(); }
    public static void toggleSegment() { if (segmentToggle != null) segmentToggle.run(); }
    public static void toggleSmartPath() { if (smartPathToggle != null) smartPathToggle.run(); }
    public static void openScripts(Object unit) { if (scriptsOpen != null) scriptsOpen.accept(unit); }
    public static String rangeTitle() { return rangeTitle == null ? "绘制范围" : rangeTitle; }
    public static String lineTitle() { return lineTitle == null ? "指示索敌" : lineTitle; }
    public static String segmentTitle() { return segmentTitle == null ? "分段指令" : segmentTitle; }
    public static String smartPathTitle() {
        return smartPathTitle == null ? "关闭智寻" : smartPathTitle;
    }
    public static String scriptsTitle() { return scriptsTitle == null ? "脚本管理" : scriptsTitle; }
}
