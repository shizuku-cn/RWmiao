package com.corrodinggames.rts.game.units.a;

import com.corrodinggames.rts.game.units.ce;
import com.corrodinggames.rts.game.units.el;

public abstract class p extends s {
    public p(String id) { super("c__cut_" + id); }
    public final int b(ce unit, boolean preview) { return -1; }
    public final int c() { return 0; }
    public final u d() { return enumValue(u.values(), "infoOnly"); }
    public final t e() { return enumValue(t.values(), "infoOnly"); }
    public final boolean f() { return false; }
    public final el h() { return null; }
    public final String i() { return b(); }
    public final boolean k() { return false; }
    public final boolean I() { return false; }
    public final float l() { return 1.0f; }
    public final boolean q() { return true; }

    private static <E extends Enum<E>> E enumValue(E[] values, String wanted) {
        for (E value : values) if (wanted.equals(value.name())) return value;
        return values.length == 0 ? null : values[0];
    }
}
