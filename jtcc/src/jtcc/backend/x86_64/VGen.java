package jtcc.backend.x86_64;

import jtcc.cc.CType;
import jtcc.cc.SValue;
import jtcc.cc.Sym;

/**
 * Value-stack code generator for x86-64 (tccgen.c gv/gen_op discipline + x86_64-gen.c backend,
 * M3 integer/pointer scope; float/SSE arrives in a later pass).
 */
public final class VGen {
    public final GenState st;
    public final X64Enc enc;

    // value stack
    public final SValue[] vstack = new SValue[64];
    public int vtop;

    // register allocator: temp pool in tcc-ish order (eax kept for results/idiv)
    private static final int[] TEMP_POOL = {
        X64Enc.R_ECX, X64Enc.R_EDX, X64Enc.R_ESI, X64Enc.R_EDI,
        X64Enc.R_R8, X64Enc.R_R9, X64Enc.R_R10, X64Enc.R_R11
    };
    private final boolean[] used = new boolean[16];

    // frame state
    public int loc;                 // frame offset (tcc loc, negative)
    public int funcSubSpOffset;     // backpatch offset for sub rsp
    private static final int FUNC_PROLOG_SIZE = 11; // push rbp(1) + mov rbp,rsp(3) + sub rsp,imm32(3+4)

    public VGen(GenState st) {
        this.st = st;
        this.enc = new X64Enc(st);
        for (int i = 0; i < vstack.length; i++) vstack[i] = new SValue(new CType());
    }

    // ---------------------------------------------------------- register allocation

    private int allocTemp() {
        for (int r : TEMP_POOL) if (!used[r]) { used[r] = true; return r; }
        throw new IllegalStateException("out of registers");
    }

    private void freeReg(int r) {
        if (r >= 0 && r < 16 && r != X64Enc.R_EAX) used[r] = false;
    }

    /** public wrapper for parser-side temp release after manual conversions. */
    public void freeRegPublic(int r) { freeReg(r); }

    /** debug: dump currently-used temp registers. */
    public void dumpUsed(String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) if (used[i]) sb.append(i).append(',');
        if (sb.length() > 0) System.err.println("DBG used@" + tag + ": " + sb);
    }

    private void freeValue(SValue v) {
        if ((v.r & 0x00ff) < Sym.VT_CONST) freeReg(v.r & 0x00ff);
    }

    // ---------------------------------------------------------- value stack

    public SValue vpush() {
        SValue v = vstack[vtop++];
        // reset stale fields from previous uses of this shared slot
        v.r = 0; v.r2 = 0; v.c = 0; v.d = 0; v.jtrue = 0; v.jfalse = 0; v.sym = null; v.symId = -1;
        return v;
    }

    public void vpop() {
        freeValue(vstack[--vtop]);
    }

    public SValue vtop() { return vstack[vtop - 1]; }

    public SValue vpushConst(CType type, long val) {
        SValue v = vpush();
        v.type = type;
        v.r = Sym.VT_CONST;
        v.c = val;
        return v;
    }

    /** force vtop into a register (tcc gv): lvalues are materialized to their value. */
    public int gv() {
        SValue v = vtop();
        if ((v.r & Sym.VT_LVAL) != 0) {
            // lvalue: load the pointed value into a register
            int r = (v.r & 0x00ff) < Sym.VT_CONST ? v.r & 0x00ff : -1;
            if (r < 0) r = allocTemp();
            load(r, v);
            v.r = r;
            return r;
        }
        if ((v.r & 0x00ff) < Sym.VT_CONST) return v.r & 0x00ff; // already in reg
        int r = allocTemp();
        load(r, v);
        v.r = r;
        return r;
    }

    /** load a value into register r (tcc load). */
    public void load(int r, SValue sv) {
        boolean w = is64(sv.type);
        int sz = typeSize(sv.type);
        if ((sv.r & 0x00ff) == Sym.VT_CONST) {
            if ((sv.r & Sym.VT_SYM) != 0) {
                // symbol address
                enc.leaRipSym(r, sv.symId);
                if (sv.c != 0) {
                    enc.rex(true, 0, 0, r);
                    st.g(0x81); enc.modrmRR(0, r); st.le32((int) sv.c); // add r, imm32
                }
            } else if (sv.c == 0) {
                enc.binopRR(0x33, r, r); // xor r, r
            } else if (sv.c >= Integer.MIN_VALUE && sv.c <= Integer.MAX_VALUE) {
                enc.movImm32SignExt(r, (int) sv.c);
            } else {
                enc.movImm(r, sv.c, true);
            }
        } else if ((sv.r & 0x00ff) == Sym.VT_LOCAL) {
            // local on stack
            if ((sv.r & Sym.VT_LVAL) != 0 && sv.symId < 0) {
                enc.movLoad(sz, r, X64Enc.R_RBP, (int) sv.c, sv.type.isUnsigned());
            } else {
                enc.lea(r, X64Enc.R_RBP, (int) sv.c);
            }
        } else if ((sv.r & Sym.VT_SYM) != 0 && (sv.r & Sym.VT_LVAL) != 0) {
            enc.movLoadRipSym(sz, r, sv.symId);
            if (sv.c != 0) { /* offset addend: fold into reloc addend instead (M3 simple path) */ }
        } else if ((sv.r & Sym.VT_LVAL) != 0 && (sv.r & 0x00ff) < Sym.VT_CONST) {
            int base = sv.r & 0x00ff;
            enc.movLoad(sz, r, base, (int) sv.c, sv.type.isUnsigned());
            if (base != r) freeReg(base);
        }
    }

    /** store register r into lvalue sv (tcc store). */
    public void store(int r, SValue sv) {
        int sz = typeSize(sv.type);
        if ((sv.r & 0x00ff) == Sym.VT_LOCAL) {
            enc.movStore(sz, r, X64Enc.R_RBP, (int) sv.c);
        } else if ((sv.r & Sym.VT_SYM) != 0 && (sv.r & Sym.VT_LVAL) != 0) {
            // store to global: mov [rip+sym], r
            enc.rex(sz == 8, r, 0, 0);
            st.g(0x89);
            st.g((0 << 6) | ((r & 7) << 3) | 5);
            st.reloca(st.text, sv.symId, st.text.size, jtcc.elf.ElfObj.R_X86_64_PC32, -4);
            st.le32(0);
        } else if ((sv.r & Sym.VT_LVAL) != 0 && (sv.r & 0x00ff) < Sym.VT_CONST) {
            int base = sv.r & 0x00ff;
            enc.movStore(sz, r, base, (int) sv.c);
            if (base != r) freeReg(base);
        }
    }

    private static boolean is64(CType t) {
        int b = t.btype();
        return b == CType.VT_PTR || b == CType.VT_FUNC || b == CType.VT_LLONG;
    }

    public static int typeSize(CType t) {
        switch (t.btype()) {
            case CType.VT_BOOL: case CType.VT_BYTE: return 1;
            case CType.VT_SHORT: return 2;
            case CType.VT_INT: case CType.VT_FLOAT: return 4;
            default: return 8;
        }
    }

    // ---------------------------------------------------------- function frames

    public int funcSymId;

    public void gfuncProlog(Sym fn) {
        loc = 0;
        st.ind = st.text.size;
        funcSymId = st.addFuncSymbol(fn.name, (fn.type.t & CType.VT_STATIC) == 0);
        fn.symId = funcSymId;
        funcSubSpOffset = st.text.size + FUNC_PROLOG_SIZE - 4;
        st.ind = st.text.size + FUNC_PROLOG_SIZE;
        st.text.size = st.ind; // reserve prolog bytes, backpatched at epilog
        // assign parameters: SysV int/ptr args rdi,rsi,rdx,rcx,r8,r9 then stack
        int[] argRegs = {X64Enc.R_EDI, X64Enc.R_ESI, X64Enc.R_EDX, X64Enc.R_ECX, X64Enc.R_R8, X64Enc.R_R9};
        Sym p = fn.type.ref.next;
        int i = 0, stackOff = 16;
        while (p != null) {
            int sz = typeSize(p.type);
            if (i < 6) {
                loc -= sz;
                loc = (loc - (sz - 1)) & -sz;
                p.c = loc;
                enc.movStore(sz, argRegs[i], X64Enc.R_RBP, loc);
            } else {
                p.c = stackOff;
                stackOff += 8;
            }
            i++;
            p = p.next;
        }
        loc = (loc - 15) & -16;
    }

    public void gfuncEpilog() {
        enc.leave();
        enc.ret();
        // backpatch prolog
        int v = (-loc + 15) & -16;
        int saved = st.text.size;
        st.text.size = funcSubSpOffset - (FUNC_PROLOG_SIZE - 4);
        st.text.add(0x55);                    // push %rbp
        st.text.add(0x48); st.text.add(0x89); st.text.add(0xe5); // mov %rsp,%rbp
        st.text.add(0x48); st.text.add(0x81); st.text.add(0xec); // sub rsp, v
        st.text.addLe32(v);
        st.text.size = saved;
        st.ind = saved;
    }

    /** return vtop in rax and emit epilog via a jump to the shared epilog label. */
    public int returnLabel = -1;

    public void genReturn() {
        if (vtop > 0) {
            int r = gv();
            if (r != X64Enc.R_EAX) {
                enc.rex(true, r, 0, X64Enc.R_EAX);
                st.g(0x89);
                enc.modrmRR(r, X64Enc.R_EAX);
                freeReg(r);
            }
            vpop();
        }
        int at = enc.jmpRel32();
        // chain return jumps
        enc.patchRel32(at, returnLabelPos());
    }

    private int returnLabelPos() {
        if (returnLabel < 0) returnLabel = st.text.size; // provisional; epilog patches forward refs
        return returnLabel;
    }

    // ---------------------------------------------------------- arithmetic (gen_opi)

    private static final int[] OP_RM = {
        0x01, 0x09, 0x11, 0x19, 0x21, 0x29, 0x31, 0x39
    };

    /** binary op on the top two stack values; result replaces them. */
    public void genOpi(String op) {
        SValue b = vstack[--vtop];
        SValue a = vstack[vtop - 1];
        // constant folding
        if (a.isConst() && b.isConst()) {
            a.c = fold(op, a.c, b.c, a.type.isUnsigned());
            return;
        }
        int ra = gvIn(a), rb = gvIn(b);
        boolean w = is64(a.type) || is64(b.type);
        switch (op) {
            case "+": enc.rex(w, rb, 0, ra); st.g(0x01); enc.modrmRR(rb, ra); break;
            case "-": enc.rex(w, rb, 0, ra); st.g(0x29); enc.modrmRR(rb, ra); break;
            case "&": enc.rex(w, rb, 0, ra); st.g(0x21); enc.modrmRR(rb, ra); break;
            case "|": enc.rex(w, rb, 0, ra); st.g(0x09); enc.modrmRR(rb, ra); break;
            case "^": enc.rex(w, rb, 0, ra); st.g(0x31); enc.modrmRR(rb, ra); break;
            case "*": enc.imul(ra, rb); break;
            case "<<": case ">>": {
                // count must be in cl; value stays in its own register
                if (rb != X64Enc.R_ECX) {
                    enc.rex(w, rb, 0, X64Enc.R_ECX);
                    st.g(0x89);
                    enc.modrmRR(rb, X64Enc.R_ECX);
                    freeReg(rb);
                    used[X64Enc.R_ECX] = true;
                    rb = X64Enc.R_ECX;
                }
                enc.shiftCl(op.equals("<<") ? 4 : (a.type.isUnsigned() ? 5 : 7), ra);
                break;
            }
            case "/": case "%": {
                // idiv: dividend in rax, quotient->rax, remainder->rdx
                if (ra != X64Enc.R_EAX) { enc.rex(w, ra, 0, X64Enc.R_EAX); st.g(0x89); enc.modrmRR(ra, X64Enc.R_EAX); freeReg(ra); }
                used[X64Enc.R_EDX] = true;
                if (w) enc.cqo(); else enc.cdq();
                enc.idiv(rb);
                freeReg(rb);
                if (op.equals("%")) {
                    enc.rex(w, X64Enc.R_EDX, 0, X64Enc.R_EAX); st.g(0x89); enc.modrmRR(X64Enc.R_EDX, X64Enc.R_EAX);
                }
                ra = X64Enc.R_EAX;
                used[X64Enc.R_EDX] = false;
                break;
            }
            default: throw new IllegalStateException("genOpi " + op);
        }
        freeReg(rb);
        a.r = ra;
        // result type: keep a.type for now (M3 int-centric)
    }

    private static long fold(String op, long a, long b, boolean u) {
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "&": return a & b;
            case "|": return a | b;
            case "^": return a ^ b;
            case "<<": return a << (b & 63);
            case ">>": return u ? a >>> (b & 63) : a >> (b & 63);
            case "/": return b == 0 ? 0 : (u ? Long.divideUnsigned(a, b) : a / b);
            case "%": return b == 0 ? 0 : (u ? Long.remainderUnsigned(a, b) : a % b);
            default: throw new IllegalStateException(op);
        }
    }

    private int gvIn(SValue v) {
        int r = (v.r & 0x00ff) < Sym.VT_CONST ? v.r & 0x00ff : -1;
        if (r >= 0) return r;
        int r2 = allocTemp();
        load(r2, v); // no stack juggling: the holder slot would alias the popped value
        return r2;
    }

    /** unary - on vtop. */
    public void genNeg() {
        if (vtop().isConst()) { vtop().c = -vtop().c; return; }
        int r = gv();
        enc.unaryGrp(3, r);
    }

    /** ~ on vtop. */
    public void genNot() {
        if (vtop().isConst()) { vtop().c = ~vtop().c; return; }
        int r = gv();
        enc.unaryGrp(2, r);
    }

    /** ! on vtop. */
    public void genLnot() {
        if (vtop().isConst()) { vtop().c = vtop().c == 0 ? 1 : 0; return; }
        int r = gv();
        enc.test(r);
        enc.setcc(4, r);
        enc.movzxR32R8(r, r);
    }

    // ---------------------------------------------------------- comparisons & jumps

    /** compare vtop-1 with vtop; leave condition flags (gen compare). */
    private int genCmpValues() {
        SValue b = vstack[--vtop];
        SValue a = vstack[vtop - 1];
        if (a.isConst() && b.isConst()) { vtop--; vpushConst(new CType(CType.VT_INT, null), 0); return -1; }
        int ra = gvIn(a), rb = gvIn(b);
        boolean w = is64(a.type) || is64(b.type);
        enc.rex(w, rb, 0, ra);
        st.g(0x39);
        enc.modrmRR(rb, ra);
        freeReg(rb);
        freeReg(ra);
        return 0;
    }

    /** setcc-based comparison to a value (for ==,!=,<,<=,>,>= used as rvalue). */
    public void genCmpSet(String op) {
        if (vstack[vtop - 2].isConst() && vstack[vtop - 1].isConst()) {
            SValue b = vstack[--vtop], a = vstack[vtop - 1];
            a.c = cmpFold(op, a.c, b.c, a.type.isUnsigned());
            a.type = new CType(CType.VT_INT, null);
            return;
        }
        genCmpValues();
        int r = allocTemp();
        enc.setcc(condOf(op), r);
        enc.movzxR32R8(r, r);
        SValue v = vtop();
        v.type = new CType(CType.VT_INT, null);
        v.r = r;
    }

    private static long cmpFold(String op, long a, long b, boolean u) {
        switch (op) {
            case "==": return a == b ? 1L : 0L;
            case "!=": return a != b ? 1L : 0L;
            case "<": return u ? (Long.compareUnsigned(a, b) < 0 ? 1L : 0L) : (a < b ? 1L : 0L);
            case ">": return u ? (Long.compareUnsigned(a, b) > 0 ? 1L : 0L) : (a > b ? 1L : 0L);
            case "<=": return u ? (Long.compareUnsigned(a, b) <= 0 ? 1L : 0L) : (a <= b ? 1L : 0L);
            case ">=": return u ? (Long.compareUnsigned(a, b) >= 0 ? 1L : 0L) : (a >= b ? 1L : 0L);
            default: throw new IllegalStateException(op);
        }
    }

    private static int condOf(String op) {
        switch (op) {
            case "==": return 4;   // Z/E
            case "!=": return 5;   // NZ/NE
            case "<": return 0xC;  // L/NGE
            case ">": return 0xF;  // G/NLE
            case "<=": return 0xE; // LE/NG
            case ">=": return 0xD; // GE/NL
            default: throw new IllegalStateException(op);
        }
    }

    /** conditional jump if vtop is ZERO (tcc gtst with inv). Returns backpatch offset. */
    public int gjmpIfZero() {
        SValue v = vtop();
        if (v.isConst()) {
            vpop();
            if (v.c == 0) return enc.jmpRel32();
            return -1;
        }
        int r = gv();
        enc.test(r);
        freeReg(r);
        vpop();
        return enc.jccRel32(4); // JZ
    }

    /** conditional jump if vtop is NONZERO. */
    public int gjmpIfNonZero() {
        SValue v = vtop();
        if (v.isConst()) {
            vpop();
            if (v.c != 0) return enc.jmpRel32();
            return -1;
        }
        int r = gv();
        enc.test(r);
        freeReg(r);
        vpop();
        return enc.jccRel32(5); // JNZ
    }

    /** comparison as a jump-if-false (for if/while conditions). */
    public int gjmpCondFalse(String op) {
        SValue b = vstack[vtop - 1], a = vstack[vtop - 2];
        if (a.isConst() && b.isConst()) {
            vtop -= 2;
            if (cmpFold(op, a.c, b.c, a.type.isUnsigned()) == 0) return enc.jmpRel32();
            return -1;
        }
        genCmpValues();
        // jump on INVERTED condition
        return enc.jccRel32(invCondOf(op));
    }

    private static int invCondOf(String op) {
        switch (op) {
            case "==": return 5;   // NZ
            case "!=": return 4;   // Z
            case "<": return 0xD;  // GE
            case ">=": return 0xC; // L
            case ">": return 0xE;  // LE
            case "<=": return 0xF; // G
            default: throw new IllegalStateException(op);
        }
    }

    public void gsym(int at) { enc.patchRel32(at, st.text.size); }

    public int label() { return st.text.size; }

    /** unary &: turn an lvalue into its address (pointer value). */
    public void genAddrOf(SValue lv) {
        if ((lv.r & 0x00ff) == Sym.VT_LOCAL) {
            int r = allocTemp();
            enc.lea(r, X64Enc.R_RBP, (int) lv.c);
            lv.r = r;
        } else if ((lv.r & Sym.VT_LVAL) != 0 && (lv.r & 0x00ff) < Sym.VT_CONST) {
            // lvalue through base register: address = base + offset
            int base = lv.r & 0x00ff;
            if (lv.c != 0) {
                enc.rex(true, 0, 0, base);
                st.g(0x81); enc.modrmRR(0, base); st.le32((int) lv.c);
            }
            lv.r = base;
        } else if ((lv.r & Sym.VT_SYM) != 0) {
            // global: symbol address (+ addend)
            int r = allocTemp();
            enc.leaRipSym(r, lv.symId);
            if (lv.c != 0) {
                enc.rex(true, 0, 0, r);
                st.g(0x81); enc.modrmRR(0, r); st.le32((int) lv.c);
            }
            lv.r = r;
        }
        lv.r &= ~Sym.VT_LVAL;
        // type: pointer to lv.type — caller sets it
    }

    /** unary *: turn a pointer value into the pointed-to lvalue. */
    public void genDeref(SValue v) {
        int r = (v.r & 0x00ff) < Sym.VT_CONST ? v.r & 0x00ff : -1;
        if (r < 0) {
            // pointer currently not in a register: load it first
            r = allocTemp();
            load(r, v);
        }
        v.r = r | Sym.VT_LVAL;
        v.c = 0;
    }

    /** pointer/integer arithmetic with element-size scaling: p + n*elsize or n*elsize + p or p - n*elsize. */
    public void genPtrArith(SValue ptr, SValue n, int elsize, boolean sub) {
        // scale n by elsize if needed
        if (elsize != 1) {
            int rn = (n.r & 0x00ff) < Sym.VT_CONST ? n.r & 0x00ff : -1;
            if (rn < 0) { rn = allocTemp(); load(rn, n); }
            enc.rex(true, rn, 0, 0);
            st.g(0x69); enc.modrmRR(rn, rn); st.le32(elsize); // imul rn, rn, elsize
            n.r = rn;
        }
        int rp = (ptr.r & 0x00ff) < Sym.VT_CONST ? ptr.r & 0x00ff : -1;
        if (rp < 0) { rp = allocTemp(); load(rp, ptr); }
        int rn2 = (n.r & 0x00ff) < Sym.VT_CONST ? n.r & 0x00ff : -1;
        if (rn2 < 0) { rn2 = allocTemp(); load(rn2, n); }
        enc.rex(true, rn2, 0, rp);
        st.g(sub ? 0x29 : 0x01); // sub/add rp, rn2
        enc.modrmRR(rn2, rp);
        freeReg(rn2);
        ptr.r = rp;
    }

    /** x++ / x-- / ++x / --x on an lvalue (tcc gen_inc/dec pattern, M3 integer scope). */
    public void genIncDec(SValue lv, int delta, boolean prefix) {
        int r = (lv.r & 0x00ff) < Sym.VT_CONST ? lv.r & 0x00ff : -1;
        if (r < 0) r = allocTemp();
        load(r, lv);
        boolean w = is64(lv.type);
        if (prefix) {
            enc.rex(w, 0, 0, r);
            st.g(0x81); enc.modrmRR(0, r); st.le32(delta);
            store(r, lv);
            lv.r = r;
        } else {
            // postfix: old value is the result; compute new into a second temp
            int r2 = allocTemp();
            enc.rex(w, r, 0, r2);
            st.g(0x89);
            enc.modrmRR(r, r2); // mov r2, r
            enc.rex(w, 0, 0, r);
            st.g(0x81); enc.modrmRR(0, r); st.le32(delta);
            store(r, lv);
            freeReg(r);
            lv.r = r2;
        }
    }

    // ---------------------------------------------------------- calls (SysV AMD64)

    private static final int[] ARG_REGS = {
        X64Enc.R_EDI, X64Enc.R_ESI, X64Enc.R_EDX, X64Enc.R_ECX, X64Enc.R_R8, X64Enc.R_R9
    };

    /** call fn with nbArgs args already on the value stack [fn, a1..aN]. Result in rax. */
    public void gfuncCall(int nbArgs) {
        SValue fn = vstack[vtop - nbArgs - 1];
        // save all used temps (caller-saved incl. the arg registers)
        java.util.List<Integer> saved = new java.util.ArrayList<>();
        for (int r : TEMP_POOL) if (used[r]) { enc.push(r); saved.add(r); }
        // evaluate args left-to-right and push onto the machine stack
        java.util.List<Integer> argTemps = new java.util.ArrayList<>();
        int regArgs = Math.min(nbArgs, 6);
        for (int i = 0; i < regArgs; i++) {
            SValue arg = vstack[vtop - nbArgs + i];
            int r = (arg.r & 0x00ff) < Sym.VT_CONST ? arg.r & 0x00ff : -1;
            if (r < 0) { r = allocTemp(); load(r, arg); }
            argTemps.add(r);
            enc.push(r);
        }
        // SysV: args 7+ go on the stack, arg7 at [rsp] — push them in reverse order
        for (int i = nbArgs - 1; i >= 6; i--) {
            SValue arg = vstack[vtop - nbArgs + i];
            int r = (arg.r & 0x00ff) < Sym.VT_CONST ? arg.r & 0x00ff : -1;
            boolean temp = false;
            if (r < 0) { r = allocTemp(); load(r, arg); temp = true; }
            enc.push(r);
            if (temp) freeReg(r);
        }
        // pop into SysV arg registers in reverse order
        for (int i = regArgs - 1; i >= 0; i--) enc.pop(ARG_REGS[i]);
        // args consumed by the call: free their temp registers
        for (int r : argTemps) freeReg(r);
        // SysV: AL = number of SSE registers used for variadic calls (0 for integer-only)
        enc.binopRR(0x33, X64Enc.R_EAX, X64Enc.R_EAX); // xor eax, eax
        // 16-byte stack alignment at the call: register args were popped into argRegs,
        // net pending change at the call = saved temps + stack-passed args (7+).
        int pushes = saved.size() + Math.max(0, nbArgs - 6);
        if ((pushes & 1) != 0) {
            st.g(0x50); // push rax as 8-byte pad
        }
        // call
        if ((fn.r & Sym.VT_SYM) != 0) {
            enc.callRel32(fn.symId);
        } else {
            int fr = (fn.r & 0x00ff) < Sym.VT_CONST ? fn.r & 0x00ff : -1;
            if (fr < 0) { fr = allocTemp(); load(fr, fn); }
            enc.callReg(fr);
        }
        int undo = ((pushes & 1) != 0 ? 8 : 0) + Math.max(0, nbArgs - 6) * 8;
        if (undo > 0) {
            enc.rex(true, 0, 0, X64Enc.R_RSP);
            st.g(0x81); enc.modrmRR(0, X64Enc.R_RSP); st.le32(undo); // add rsp, undo
        }
        // restore temps
        for (int i = saved.size() - 1; i >= 0; i--) enc.pop(saved.get(i));
        // pop fn+args from the value stack, push result in rax
        for (int i = 0; i < nbArgs + 1; i++) vtop--;
        SValue res = vpush();
        res.type = new CType(CType.VT_INT, null); // M3: int returns for now
        res.r = X64Enc.R_EAX;
    }
}
