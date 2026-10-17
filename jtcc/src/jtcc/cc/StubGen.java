package jtcc.cc;

/** No-op backend for the parse-only phase (M2): accepts everything, records nothing. */
public final class StubGen implements CodeGen {
    @Override public void o(int b) {}
    @Override public void genLe16(int v) {}
    @Override public void genLe32(int v) {}
    @Override public void genLe64(long v) {}
    @Override public void genExpr32(long v) {}
    @Override public void genFillNops(int n) {}
    @Override public void gsym(int t) {}
    @Override public void gsymAddr(int t, int a) {}
    @Override public void load(int r, SValue sv) {}
    @Override public void store(int r, SValue sv) {}
    @Override public int gfuncSret(CType vt, CType vr, CType retType, int align, int regsize) { return 0; }
    @Override public void gfuncCall(int nbArgs) {}
    @Override public void gfuncProlog(Sym sym) {}
    @Override public void gfuncEpilog() {}
    @Override public void gfuncReturn(Sym funcSym) {}
    @Override public int gjmp(int t) { return 0; }
    @Override public void gjmpAddr(int a) {}
    @Override public int gjmpCond(int op, int t) { return 0; }
    @Override public int gjmpAppend(int n, int t) { return 0; }
    @Override public void ggoto() {}
    @Override public void genOpi(int op) {}
    @Override public void genOpl(int op) {}
    @Override public void genOpf(int op) {}
    @Override public void genCvtFtoi(int t) {}
    @Override public void genCvtItof(int t) {}
    @Override public void genCvtFtof(int t) {}
    @Override public void genCvtSxtw() {}
    @Override public void genCvtCsti() {}
    @Override public void genVlaSpSave(int addr) {}
    @Override public void genVlaSpRestore(int addr) {}
    @Override public void genVlaAlloc(CType type, int align) {}
    @Override public void genStructCopy(int size) {}
    @Override public void genVaStart() {}
    @Override public void genVaArg() {}
}
