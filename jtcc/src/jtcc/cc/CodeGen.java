package jtcc.cc;

/**
 * The backend contract of tcc's generic codegen (tccgen.c) → target backend (<arch>-gen.c),
 * per tcc.h:1625-1653. A Java port implements this once per target (x86_64 first).
 * All methods are no-ops in the parse-only phase (M2) via StubGen.
 */
public interface CodeGen {
    // raw emission
    void o(int b);                       // emit bytes into the text section
    void genLe16(int v);
    void genLe32(int v);
    void genLe64(long v);
    void genExpr32(long v);              // emit 32-bit expression value
    void genFillNops(int n);
    void gsym(int t);                    // patch jump target to current position
    void gsymAddr(int t, int a);

    // loads/stores per value stack
    void load(int r, SValue sv);
    void store(int r, SValue sv);

    // function ABI
    int gfuncSret(CType vt, CType vr, CType retType, int align, int regsize);
    void gfuncCall(int nbArgs);
    void gfuncProlog(Sym sym);
    void gfuncEpilog();
    void gfuncReturn(Sym funcSym);       // arm64-only in tcc; kept for symmetry

    // control flow
    int gjmp(int t);
    void gjmpAddr(int a);
    int gjmpCond(int op, int t);
    int gjmpAppend(int n, int t);
    void ggoto();

    // operators
    void genOpi(int op);                 // integer/generic op
    void genOpl(int op);                 // long-long op
    void genOpf(int op);                 // float op
    void genCvtFtoi(int t);
    void genCvtItof(int t);
    void genCvtFtof(int t);
    void genCvtSxtw();
    void genCvtCsti();

    // VLA
    void genVlaSpSave(int addr);
    void genVlaSpRestore(int addr);
    void genVlaAlloc(CType type, int align);
    void genStructCopy(int size);        // struct assignment helper

    // varargs (arm64 lowers via genVaStart/genVaArg; x86_64 uses builtins + libtcc1)
    void genVaStart();
    void genVaArg();
}
