package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.p;
import com.corrodinggames.rts.game.units.ce;

/** A local UI action. Clicking it only opens the module panel; production
 * orders are still created through the game's normal command queue. */
public final class RWMiaoReinforceAction extends p {
    public static RWMiaoReinforceAction INST = new RWMiaoReinforceAction();
    public RWMiaoReinforceAction() { super("rwmiao_reinforce"); }
    public String a() { return "Automatic reinforcement list"; }
    public String b() { return "自动补兵"; }
    public boolean c(ce unit, boolean alternate) { RWMiaoBridge.openReinforce(); return true; }
}
