package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.s;
import com.corrodinggames.rts.game.units.a.t;
import com.corrodinggames.rts.game.units.a.u;
import com.corrodinggames.rts.game.units.ce;
import com.corrodinggames.rts.game.units.el;

/** Fallback rally entry for producer units that do not declare the native action. */
public final class RWMiaoMotherRallyAction extends s {
    public static final RWMiaoMotherRallyAction INST = new RWMiaoMotherRallyAction();

    private RWMiaoMotherRallyAction() {
        super("c__cut_rwmiao_mother_rally");
    }

    public String a() { return "Set rally point"; }
    public int b(ce unit, boolean preview) { return -1; }
    public String b() { return "设置集结点"; }
    public int c() { return 0; }
    public u d() { return enumValue(u.values(), "setRally"); }
    public t e() { return enumValue(t.values(), "rally"); }
    public boolean f() { return false; }
    public el h() { return null; }
    public String i() { return b(); }
    public boolean k() { return false; }
    public boolean I() { return false; }
    public boolean q() { return true; }
    public boolean g() { return true; }
    public float l() { return 0.5f; }

    private static <E extends Enum<E>> E enumValue(E[] values, String wanted) {
        for (E value : values) {
            if (wanted.equals(value.name())) return value;
        }
        return values.length == 0 ? null : values[0];
    }
}
