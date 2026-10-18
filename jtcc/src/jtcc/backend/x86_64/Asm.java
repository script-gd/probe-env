package jtcc.backend.x86_64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal x86-64 assembler for gdcc's minicoro file-scope __asm__ blocks (AT&T syntax).
 *  Supports the instruction/directive subset used by minicoro.h: movq (reg/mem forms),
 *  leaq rip-relative, jmp *reg / *mem, ret, labels, and .globl/.type/.hidden/.size/.text.
 *  This is the focused M4 subset; a full faithful port of i386-asm.c is future work. */
public final class Asm {

    private Asm() {}

    // register name -> number (x86-64 encoding)
    private static final Map<String, Integer> REGS = new HashMap<>();
    static {
        String[] names = {"rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15"};
        for (int i = 0; i < names.length; i++) REGS.put(names[i], i);
    }

    private static final class Fixup {
        int at;        // offset of the disp32 to patch
        String label;  // target label
        Fixup(int at, String label) { this.at = at; this.label = label; }
    }

    public static void assemble(String asm, GenState st) {
        Map<String, Integer> labels = new HashMap<>();
        List<Fixup> fixups = new ArrayList<>();
        List<String> pendingGlobl = new ArrayList<>(); // symbol names to export after assembly
        X64Enc enc = new X64Enc(st);

        String[] lines = asm.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // label definition (check before directives: `.Lfoo:` labels also start with '.')
            if (line.endsWith(":")) {
                String name = line.substring(0, line.length() - 1).trim();
                labels.put(name, st.text.size);
                continue;
            }
            // directives
            if (line.startsWith(".")) {
                if (line.startsWith(".globl") || line.startsWith(".global")) {
                    String name = line.substring(line.indexOf(' ') + 1).trim();
                    pendingGlobl.add(name);
                }
                // .text/.type/.hidden/.size: no emission needed for our purposes
                continue;
            }
            assembleInstr(line, enc, st, labels, fixups);
        }
        // apply rip-relative fixups: disp32 = label - (at + 4)
        for (Fixup f : fixups) {
            Integer target = labels.get(f.label);
            if (target == null) throw new IllegalStateException("asm: undefined label " + f.label);
            int disp = target - (f.at + 4);
            st.text.patchLe32(f.at, disp);
        }
        // export the .globl symbols as function symbols at their label offsets
        for (String name : pendingGlobl) {
            Integer off = labels.get(name);
            if (off == null) throw new IllegalStateException("asm: .globl without label " + name);
            jtcc.elf.ElfObj.SymDef sd = new jtcc.elf.ElfObj.SymDef(name, jtcc.elf.ElfObj.STB_GLOBAL << 4 | jtcc.elf.ElfObj.STT_FUNC);
            sd.shndx = st.obj.sectionIndex(st.text);
            sd.value = off;
            st.obj.addSymbol(sd);
        }
    }

    private static void assembleInstr(String line, X64Enc enc, GenState st,
                                      Map<String, Integer> labels, List<Fixup> fixups) {
        // split mnemonic and operands
        int sp = line.indexOf(' ');
        if (sp < 0) {
            if (line.equals("ret")) { st.text.add(0xc3); return; }
            throw new IllegalStateException("asm: unsupported instruction '" + line + "'");
        }
        String mn = line.substring(0, sp).trim();
        String ops = line.substring(sp + 1).trim();
        switch (mn) {
            case "movq": {
                String[] parts = splitOps(ops);
                String src = parts[0], dst = parts[1];
                Integer sreg = REGS.get(regName(src));
                Integer dreg = REGS.get(regName(dst));
                if (sreg != null && isMem(dst)) {
                    enc.movStore(8, sreg, parseMem(dst)[0], parseMem(dst)[1]);
                } else if (isMem(src) && dreg != null) {
                    enc.movLoad(8, dreg, parseMem(src)[0], parseMem(src)[1], false);
                } else if (sreg != null && dreg != null) {
                    enc.rex(true, sreg, 0, dreg);
                    st.text.add(0x89);
                    enc.modrmRR(sreg, dreg);
                } else {
                    throw new IllegalStateException("asm: unsupported movq form '" + line + "'");
                }
                return;
            }
            case "leaq": {
                // leaq label(%rip), %reg
                String[] parts = splitOps(ops);
                String src = parts[0];
                Integer dreg = REGS.get(regName(parts[1]));
                if (dreg == null) throw new IllegalStateException("asm: bad leaq dst '" + line + "'");
                String label = src.substring(0, src.indexOf('(')).trim();
                enc.rex(true, dreg, 0, 0);
                st.text.add(0x8d);
                st.text.add((0 << 6) | ((dreg & 7) << 3) | 5); // [rip+disp32]
                fixups.add(new Fixup(st.text.size, label));
                st.le32(0);
                return;
            }
            case "jmp": {
                String op = ops.trim();
                if (!op.startsWith("*")) throw new IllegalStateException("asm: unsupported jmp '" + line + "'");
                String t = op.substring(1).trim();
                Integer r = REGS.get(regName(t));
                if (r != null) {
                    // jmp *%reg : FF /4
                    enc.rex(false, 0, 0, r);
                    st.text.add(0xff);
                    enc.modrmRR(4, r);
                } else if (isMem(t)) {
                    // jmp *disp(%base) : FF /4 mem
                    int[] mem = parseMem(t);
                    enc.rex(false, 4, 0, mem[0]);
                    st.text.add(0xff);
                    enc.modrmMem(4, mem[0], mem[1]);
                } else {
                    throw new IllegalStateException("asm: bad jmp '" + line + "'");
                }
                return;
            }
            default:
                throw new IllegalStateException("asm: unsupported mnemonic '" + mn + "'");
        }
    }

    private static String[] splitOps(String ops) {
        int comma = ops.indexOf(',');
        return new String[]{ops.substring(0, comma).trim(), ops.substring(comma + 1).trim()};
    }

    private static boolean isMem(String op) { return op.contains("("); }

    private static String regName(String op) {
        String t = op.trim();
        return t.startsWith("%") ? t.substring(1) : t;
    }

    /** parse `disp(%base)` or `(%base)` -> [base, disp]. */
    private static int[] parseMem(String op) {
        int lp = op.indexOf('(');
        int rp = op.indexOf(')');
        String dispStr = op.substring(0, lp).trim();
        String baseStr = op.substring(lp + 1, rp).trim();
        if (baseStr.equals("%rip")) throw new IllegalStateException("rip-mem not via parseMem");
        Integer base = REGS.get(regName(baseStr));
        if (base == null) throw new IllegalStateException("asm: bad base '" + op + "'");
        int disp = dispStr.isEmpty() ? 0 : (int) Long.decode(dispStr).longValue();
        return new int[]{base, disp};
    }
}
