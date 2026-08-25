package com.shizuku.rwmiao.module.script;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;

/** Lua VM instruction limiter; the debug table itself is removed from script globals. */
final class BudgetDebugLib extends DebugLib {
    private int remaining = Integer.MAX_VALUE;
    void begin(int instructions) { remaining = instructions; }
    void end() { remaining = Integer.MAX_VALUE; }
    @Override public void onInstruction(int pc, Varargs v, int top) {
        if (--remaining < 0) throw new LuaError("Lua 指令配额已用完");
        super.onInstruction(pc, v, top);
    }
}
