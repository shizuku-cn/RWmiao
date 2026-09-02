package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.p;
import com.corrodinggames.rts.game.units.ce;

public final class RWMiaoReinforceAction extends p {
    public static RWMiaoReinforceAction INST = new RWMiaoReinforceAction();
    public RWMiaoReinforceAction() { super("rwmiao_reinforce"); }
    public String a() { return "Automatic reinforcement list"; }
    public String b() { return "自动补兵"; }
    public boolean c(ce unit, boolean alternate) { RWMiaoBridge.openReinforce(); return true; }
}
