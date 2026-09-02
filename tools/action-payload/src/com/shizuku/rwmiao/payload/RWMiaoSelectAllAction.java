package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.p;
import com.corrodinggames.rts.game.units.ce;

public final class RWMiaoSelectAllAction extends p {
    public static RWMiaoSelectAllAction INST = new RWMiaoSelectAllAction();

    public RWMiaoSelectAllAction() {
        super("rwmiao_select_all");
    }

    public String a() { return "Select all own units"; }
    public String b() { return RWMiaoBridge.selectAllTitle(); }
    public boolean c(ce unit, boolean alternate) {
        RWMiaoBridge.selectAll();
        return true;
    }
}
