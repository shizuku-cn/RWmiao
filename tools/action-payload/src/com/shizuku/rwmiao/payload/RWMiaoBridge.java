package com.shizuku.rwmiao.payload;

import java.util.ArrayList;
import java.util.function.Consumer;

public final class RWMiaoBridge {
    public static Runnable rangeToggle;
    public static Runnable lineToggle;
    public static Runnable reinforceOpen;
    public static Runnable segmentToggle;
    public static Runnable smartPathToggle;
    public static Runnable freeSelectionToggle;
    public static Consumer<Object> freeBuildToggle;
    public static Runnable selectAllToggle;
    public static Runnable combatViewOpen;
    public static Consumer<Object> scriptsOpen;
    public static String rangeTitle = "绘制范围";
    public static String lineTitle = "指示索敌";
    public static String segmentTitle = "分段指令";
    public static String smartPathTitle = "关闭智寻";
    public static String scriptsTitle = "脚本管理";
    public static String freeSelectionTitle = "自由框选";
    public static String freeBuildTitle = "自由建造";
    public static String selectAllTitle = "一键全选";
    public static String combatViewTitle = "交战视角";
    public static String mutePanelTitle = "禁言面板";
    private RWMiaoBridge() {}
    public static ArrayList maybeAdd(ArrayList actions, int insertionIndex,
                                     boolean addRange, boolean addLine, boolean addReinforce,
                                     boolean addSegment, boolean addSmartPath, boolean addScripts,
                                     boolean addMotherRally, boolean addFreeSelection,
                                     boolean addFreeBuild, boolean addSelectAll, boolean addMutePanel) {
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
            actions.add(index, RWMiaoMotherRallyAction.INST);
        }
        if (addFreeSelection && !actions.contains(RWMiaoFreeSelectionAction.INST)) {
            actions.add(index, RWMiaoFreeSelectionAction.INST);
        }
        if (addFreeBuild && !actions.contains(RWMiaoFreeBuildAction.INST)) {
            actions.add(index, RWMiaoFreeBuildAction.INST);
        }
        if (addSelectAll && !actions.contains(RWMiaoSelectAllAction.INST)) {
            actions.add(index, RWMiaoSelectAllAction.INST);
        }
        if (addMutePanel && !actions.contains(RWMiaoMutePanelAction.INST)) {
            actions.add(index, RWMiaoMutePanelAction.INST);
        }
        return actions;
    }

    public static ArrayList maybeAddCombatView(ArrayList actions, int insertionIndex,
                                                boolean addCombatView) {
        if (actions == null) return actions;
        int index = insertionIndex < 0 || insertionIndex > actions.size()
                ? actions.size() : insertionIndex;
        if (addCombatView && !actions.contains(RWMiaoCombatViewAction.INST)) {
            actions.add(index, RWMiaoCombatViewAction.INST);
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
    public static void toggleFreeSelection() {
        if (freeSelectionToggle != null) freeSelectionToggle.run();
    }
    public static void openScripts(Object unit) { if (scriptsOpen != null) scriptsOpen.accept(unit); }
    public static String rangeTitle() { return rangeTitle == null ? "绘制范围" : rangeTitle; }
    public static String lineTitle() { return lineTitle == null ? "指示索敌" : lineTitle; }
    public static String segmentTitle() { return segmentTitle == null ? "分段指令" : segmentTitle; }
    public static String smartPathTitle() {
        return smartPathTitle == null ? "关闭智寻" : smartPathTitle;
    }
    public static String scriptsTitle() { return scriptsTitle == null ? "脚本管理" : scriptsTitle; }
    public static String freeSelectionTitle() {
        return freeSelectionTitle == null ? "自由框选" : freeSelectionTitle;
    }
    public static void toggleFreeBuild(Object unit) {
        if (freeBuildToggle != null) freeBuildToggle.accept(unit);
    }
    public static String freeBuildTitle() {
        return freeBuildTitle == null ? "自由建造" : freeBuildTitle;
    }
    public static void selectAll() {
        if (selectAllToggle != null) selectAllToggle.run();
    }
    public static String selectAllTitle() {
        return selectAllTitle == null ? "一键全选" : selectAllTitle;
    }
    public static void openCombatView() {
        if (combatViewOpen != null) combatViewOpen.run();
    }
    public static String combatViewTitle() {
        return combatViewTitle == null ? "交战视角" : combatViewTitle;
    }
    public static void openMutePanel() {
        if (mutePanelOpen != null) mutePanelOpen.run();
    }
    public static Runnable mutePanelOpen;
    public static String mutePanelTitle() {
        return mutePanelTitle == null ? "禁言面板" : mutePanelTitle;
    }
}
