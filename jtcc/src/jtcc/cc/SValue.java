package jtcc.cc;

/** tcc SValue: an entry on the value stack during expression codegen. */
public final class SValue {
    public CType type;
    public int r;                 // register/VT_CONST/VT_LOCAL (+VT_LVAL)
    public int r2;                // second register (unused for now)
    // constant / jump / symbol payload
    public long c;                // integer constant (or offset)
    public double d;              // float constant
    public int jtrue, jfalse;     // forward jump lists
    public Sym sym;               // referenced symbol
    public int symId = -1;        // symbol table index (for VT_SYM codegen)

    public SValue(CType type) { this.type = type; }

    public boolean isConst() { return (r & 0x00ff) == Sym.VT_CONST && (r & 0x0200) == 0; }
    public boolean isLval() { return (r & Sym.VT_LVAL) != 0; }
}
