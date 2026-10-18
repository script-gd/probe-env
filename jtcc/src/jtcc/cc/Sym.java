package jtcc.cc;

/** tcc Sym: symbol table entry. v = token id (we key by name String), r = register/storage
 *  class, c = offset/scope/label meaning depends on r. */
public final class Sym {
    // r values (storage classes, tcc convention)
    public static final int VT_CONST = 0x0030;      // constant / not in register
    public static final int VT_LOCAL = 0x0031;      // local on stack
    public static final int VT_LVAL = 0x0100;       // lvalue flag (ored into r)
    public static final int VT_SYM = 0x0200;        // symbol reference
    public static final int VT_CMP = 0x0400;        // comparison result
    public static final int VT_JMP = 0x0800;        // jump result
    public static final int VT_VALMASK = 0x00ff;    // VT_CONST/VT_LOCAL/register mask (without lval)

    public final String name;      // null for anonymous
    public CType type = new CType();
    public int symId;              // symbol table index for globals/functions (0 = unassigned)
    public int r;                  // register or VT_CONST/VT_LOCAL (+VT_LVAL)
    public int c;                  // offset (locals), field offset, enum value idx, label id...
    public long v;                 // constant value / enum value
    public FuncAttr fa;            // function attributes
    public SymAttr a = new SymAttr();
    public Sym next;               // param/field chain (tcc: next)
    public Sym prev;               // scope stack link (tcc: prev in stack, fields use it too)
    public Sym cleanupFunc;

    public Sym(String name) { this.name = name; }

    public static final class SymAttr {
        public int aligned;        // log2+1, 0 = unspecified
        public boolean packed, weak, dllexport, dllimport, nodecorate, addrtaken;
        public int visibility;     // 0=default 1=hidden 2=internal 3=protected
    }

    public static final class FuncAttr {
        public int funcCall;       // calling convention
        public int funcType;       // 0=OLD 1=NEW 2=ELLIPSIS
        public boolean noreturn, ctor, dtor, alwinl;
        public int args;
    }
}
