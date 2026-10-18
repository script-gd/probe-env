package jtcc.backend.x86_64;

/** x86-64 instruction encoder (orex/modrm subset of tcc x86_64-gen.c). */
public final class X64Enc {
    // register numbers follow tcc: 0=eax 1=ecx 2=edx 6=esi 7=edi 8=r8 9=r9 10=r10 11=r11
    public static final int R_EAX = 0, R_ECX = 1, R_EDX = 2, R_ESI = 6, R_EDI = 7,
            R_R8 = 8, R_R9 = 9, R_R10 = 10, R_R11 = 11;
    public static final int R_RSP = 4, R_RBP = 5, R_R12 = 12, R_R13 = 13, R_R14 = 14, R_R15 = 15;

    private final GenState st;

    public X64Enc(GenState st) { this.st = st; }

    private static int rexBit(int r) { return r >= 8 ? 1 : 0; }

    /** REX prefix: W=64-bit operand, R=reg field ext, X=index ext, B=rm/base ext. */
    public void rex(boolean w, int r, int x, int b) {
        int v = 0x40 | (w ? 8 : 0) | (rexBit(r) << 2) | (rexBit(x) << 1) | rexBit(b);
        if (v != 0x40) st.g(v);
    }

    /** modrm byte + optional sib/displacement for [reg + disp] addressing. */
    public void modrmMem(int regField, int base, int disp) {
        if (base == R_RSP) {
            // needs SIB
            if (disp == 0) { st.g((0 << 6) | ((regField & 7) << 3) | 4); st.g(0x24); }
            else if (disp >= -128 && disp <= 127) { st.g((1 << 6) | ((regField & 7) << 3) | 4); st.g(0x24); st.g(disp); }
            else { st.g((2 << 6) | ((regField & 7) << 3) | 4); st.g(0x24); st.le32(disp); }
        } else if (disp == 0 && base != R_RBP && base != 13) {
            st.g((0 << 6) | ((regField & 7) << 3) | (base & 7));
        } else if (disp >= -128 && disp <= 127) {
            st.g((1 << 6) | ((regField & 7) << 3) | (base & 7));
            st.g(disp);
        } else {
            st.g((2 << 6) | ((regField & 7) << 3) | (base & 7));
            st.le32(disp);
        }
    }

    /** modrm for register-to-register. */
    public void modrmRR(int regField, int rm) {
        st.g(0xc0 | ((regField & 7) << 3) | (rm & 7));
    }

    // ---- common instructions ----

    /** mov r64, imm64 (or r32, imm32 when w=false). */
    public void movImm(int r, long v, boolean w) {
        rex(w, 0, 0, r);
        st.g(0xb8 + (r & 7));
        if (w) st.le64(v); else st.le32((int) v);
    }

    /** mov r64, imm32 sign-extended (shorter form). */
    public void movImm32SignExt(int r, int v) {
        rex(true, 0, 0, r);
        st.g(0xc7);
        modrmRR(0, r);
        st.le32(v);
    }

    /** mov [base+disp], r (size: 1/2/4/8). */
    public void movStore(int size, int src, int base, int disp) {
        switch (size) {
            case 1: rex(false, src, 0, base); st.g(0x88); modrmMem(src, base, disp); break;
            case 2: st.g(0x66); rex(false, src, 0, base); st.g(0x89); modrmMem(src, base, disp); break;
            case 4: rex(false, src, 0, base); st.g(0x89); modrmMem(src, base, disp); break;
            case 8: rex(true, src, 0, base); st.g(0x89); modrmMem(src, base, disp); break;
        }
    }

    /** mov r, [base+disp] (size: 4/8, zero/sign extended forms for 1/2). */
    public void movLoad(int size, int dst, int base, int disp, boolean unsignedType) {
        switch (size) {
            case 1:
                rex(false, dst, 0, base);
                st.g(0x0f); st.g(unsignedType ? 0xb6 : 0xbe);
                modrmMem(dst, base, disp);
                break;
            case 2:
                rex(false, dst, 0, base);
                st.g(0x0f); st.g(unsignedType ? 0xb7 : 0xbf);
                modrmMem(dst, base, disp);
                break;
            case 4: rex(false, dst, 0, base); st.g(0x8b); modrmMem(dst, base, disp); break;
            case 8: rex(true, dst, 0, base); st.g(0x8b); modrmMem(dst, base, disp); break;
        }
    }

    /** mov r, [rip+disp] with symbol reloc (R_X86_64_PC32, addend-4). */
    public void movLoadRipSym(int size, int dst, int symIndex) {
        movLoadRipSym(size, dst, symIndex, 0);
    }

    /** mov r, [rip+disp+off] with symbol reloc (addend = off-4). */
    public void movLoadRipSym(int size, int dst, int symIndex, int off) {
        rex(size == 8, dst, 0, 0);
        st.g(0x8b);
        st.g((0 << 6) | ((dst & 7) << 3) | 5); // [rip+disp32]
        st.reloca(st.text, symIndex, st.text.size, GenState_R.R_X86_64_PC32, off - 4);
        st.le32(0);
    }

    /** lea r, [base+disp]. */
    public void lea(int dst, int base, int disp) {
        rex(true, dst, 0, base);
        st.g(0x8d);
        modrmMem(dst, base, disp);
    }

    /** lea r, [rip+disp] with symbol reloc. */
    public void leaRipSym(int dst, int symIndex) {
        rex(true, dst, 0, 0);
        st.g(0x8d);
        st.g((0 << 6) | ((dst & 7) << 3) | 5);
        st.reloca(st.text, symIndex, st.text.size, GenState_R.R_X86_64_PC32, -4);
        st.le32(0);
    }

    /** mov r, [rip + sym@GOTPCREL]: load the symbol's 64-bit address from the GOT (PIC). */
    public void movGotPcRel(int dst, int symIndex) {
        rex(true, dst, 0, 0);
        st.g(0x8b);
        st.g((0 << 6) | ((dst & 7) << 3) | 5); // [rip+disp32]
        st.reloca(st.text, symIndex, st.text.size, GenState_R.R_X86_64_GOTPCREL, -4);
        st.le32(0);
    }

    public void push(int r) { if (r >= 8) st.g(0x41); st.g(0x50 + (r & 7)); }    public void pop(int r) { if (r >= 8) st.g(0x41); st.g(0x58 + (r & 7)); }

    /** binop r/m64, r64 (op opcode: add=1 or=3 adc=2 sbb=3? see table). */
    public void binopRM(int opcode, int src, int dst) {
        // opcode rm<-r forms: add 0x01, or 0x09, adc 0x11, sbb 0x19, and 0x21, sub 0x29, xor 0x31, cmp 0x39
        rex(true, src, 0, dst);
        st.g(opcode);
        modrmRR(src, dst);
    }

    /** binop r64, r/m64 (op from reg field versions: add 0x03, or 0x0b, and 0x23, sub 0x2b, xor 0x33, cmp 0x3b). */
    public void binopRR(int opcode, int dst, int src) {
        rex(true, dst, 0, src);
        st.g(opcode);
        modrmRR(dst, src);
    }

    public void imul(int dst, int src) {
        rex(true, dst, 0, src);
        st.g(0x0f); st.g(0xaf);
        modrmRR(dst, src);
    }

    /** unary grp on r64: not=2, neg=3. */
    public void unaryGrp(int sub, int r) {
        rex(true, 0, 0, r);
        st.g(0xf7);
        modrmRR(sub, r);
    }

    /** idiv r/m64 (signed). */
    public void idiv(int r) {
        rex(true, 0, 0, r);
        st.g(0xf7);
        modrmRR(7, r);
    }

    /** shift r64 by cl: left=4, right-arith=7, right-logical=5. */
    public void shiftCl(int sub, int r) {
        rex(true, 0, 0, r);
        st.g(0xd3);
        modrmRR(sub, r);
    }

    public void cqo() { rex(true, 0, 0, 0); st.g(0x99); }
    public void cdq() { rex(false, 0, 0, 0); st.g(0x99); }

    public void test(int r) {
        rex(true, r, 0, r);
        st.g(0x85);
        modrmRR(r, r);
    }

    public void callRel32(int symIndex) {
        st.g(0xe8);
        // PLT32 so the linker can route calls to undefined (extern) symbols through the PLT
        // when producing a shared object; resolves directly for locally-defined symbols.
        st.reloca(st.text, symIndex, st.text.size, GenState_R.R_X86_64_PLT32, -4);
        st.le32(0);
    }

    public void callReg(int r) {
        if (r >= 8) st.g(0x41);
        st.g(0xff);
        st.g(0xd0 + (r & 7)); // /2 call r/m
    }

    /** jmp rel32; returns offset of the disp32 for backpatch. */
    public int jmpRel32() {
        st.g(0xe9);
        int at = st.text.size;
        st.le32(0);
        return at;
    }

    /** jcc rel32 (0x0f 0x8? ); returns disp32 offset. cond codes: 4=Z 5=NZ 0xC=L 0xD=LE 0xE=G 0xF=GE 2=B 3=BE 6=A 7=AE */
    public int jccRel32(int cond) {
        st.g(0x0f);
        st.g(0x80 + cond);
        int at = st.text.size;
        st.le32(0);
        return at;
    }

    /** patch a rel32 disp at `at` to target `target`. */
    public void patchRel32(int at, int target) {
        st.text.patchLe32(at, target - (at + 4));
    }

    /** setcc /0: 4=Z 5=NZ 0xC=L 0xD=LE 0xE=G 0xF=GE 2=B 3=BE 6=A 7=AE */
    public void setcc(int cond, int r) {
        if (r >= 8) st.g(0x41);  // REX prefix comes FIRST (before the 0F escape)
        st.g(0x0f);
        st.g(0x90 + cond);
        st.g(0xc0 + (r & 7));
    }

    /** movzx r32, r8 (for setcc results). */
    public void movzxR32R8(int dst, int src) {
        rex(false, dst, 0, src);
        st.g(0x0f); st.g(0xb6);
        modrmRR(dst, src);
    }

    /** movslq r64, r/m32 (sign extend). */
    public void movslq(int dst, int src) {
        rex(true, dst, 0, src);
        st.g(0x63);
        modrmRR(dst, src);
    }

    public void leave() { st.g(0xc9); }
    public void ret() { st.g(0xc3); }

    // holder for reloc constants to avoid circular import in this file
    static final class GenState_R {
        static final int R_X86_64_PC32 = 2;
        static final int R_X86_64_PLT32 = 4;
        static final int R_X86_64_GOTPCREL = 9;
    }
}
