package com.corrodinggames.rts.game.units.a;
import com.corrodinggames.rts.game.units.ce;
import com.corrodinggames.rts.game.units.el;
import com.corrodinggames.rts.gameFramework.ao;
public abstract class s implements Comparable {
 public s(String id){}
 public abstract String a(); public abstract int b(ce u, boolean z); public abstract String b();
 public abstract int c(); public abstract u d(); public abstract t e(); public abstract boolean f(); public abstract el h();
 public boolean c(ce u, boolean z){return false;} public String i(){return b();} public boolean k(){return true;}
 public boolean I(){return false;} public boolean q(){return false;} public float l(){return 1f;} public ao Q(){return null;}
 public int compareTo(Object o){return 0;}
}