package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.p;
import com.corrodinggames.rts.game.units.ce;

public final class RWMiaoMutePanelAction extends p {
    public static RWMiaoMutePanelAction INST = new RWMiaoMutePanelAction();

    public RWMiaoMutePanelAction() {
        super("rwmiao_mute_panel");
    }

    public String a() { return "Open host mute panel"; }
    public String b() { return RWMiaoBridge.mutePanelTitle(); }
    public boolean c(ce unit, boolean alternate) {
        RWMiaoBridge.openMutePanel();
        return true;
    }
}
