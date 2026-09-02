package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.p;
import com.corrodinggames.rts.game.units.ce;

public final class RWMiaoCombatViewAction extends p {
    public static RWMiaoCombatViewAction INST = new RWMiaoCombatViewAction();

    public RWMiaoCombatViewAction() {
        super("rwmiao_combat_view");
    }

    public String a() { return "Jump to next combat area"; }
    public String b() { return RWMiaoBridge.combatViewTitle(); }
    public boolean c(ce unit, boolean alternate) {
        RWMiaoBridge.openCombatView();
        return true;
    }
}
