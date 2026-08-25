package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.p;
import com.corrodinggames.rts.game.units.ce;

/** A no-selection action that toggles the module's map gesture mode. */
public final class RWMiaoFreeSelectionAction extends p {
    public static RWMiaoFreeSelectionAction INST = new RWMiaoFreeSelectionAction();

    public RWMiaoFreeSelectionAction() {
        super("rwmiao_free_selection");
    }

    public String a() { return "Toggle free-form unit selection"; }
    public String b() { return RWMiaoBridge.freeSelectionTitle(); }
    public boolean c(ce unit, boolean alternate) {
        RWMiaoBridge.toggleFreeSelection();
        return true;
    }
}
