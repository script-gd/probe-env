package jtcc.backend.x86_64;

import jtcc.elf.ElfObj;
import jtcc.elf.Section;

/** x86-64 machine-code emission context: sections, symbols, relocations (tcc TCCState pieces). */
public final class GenState {
    public final ElfObj obj = new ElfObj();
    public final Section text;
    public final Section data;
    public final Section rodata;
    public final Section bss;

    /** current text-section write offset (tcc `ind`) */
    public int ind;

    public GenState() {
        text = obj.section(".text", Section.SHT_PROGBITS, Section.SHF_ALLOC | Section.SHF_EXECINSTR);
        data = obj.section(".data", Section.SHT_PROGBITS, Section.SHF_ALLOC | Section.SHF_WRITE);
        rodata = obj.section(".rodata", Section.SHT_PROGBITS, Section.SHF_ALLOC);
        bss = obj.section(".bss", Section.SHT_NOBITS, Section.SHF_ALLOC | Section.SHF_WRITE);
    }

    // ---- byte emission (tcc g/o/gen_le*) ----
    public void g(int c) {
        text.add((byte) c);
        ind = text.size;
    }
    public void o(int c) {
        while (c != 0) { g(c); c >>>= 8; }
    }
    public void le16(int v) { g(v); g(v >>> 8); }
    public void le32(int v) { le16(v); le16(v >>> 16); ind = text.size; }
    public void le64(long v) { le32((int) v); le32((int) (v >>> 32)); }

    /** add a relocation against the given symbol index. */
    public void reloca(Section s, int symIndex, int offset, int type, long addend) {
        s.relocs.add(new Section.Reloc(offset, symIndex, type, addend));
    }

    public int addFuncSymbol(String name, boolean global) {
        // reconcile a forward reference: a use-before-def created an undef entry; patch it in place
        Integer fwd = undefByName.get(name);
        if (fwd != null) {
            ElfObj.SymDef sd = obj.getSymbol(fwd);
            sd.info = (global ? ElfObj.STB_GLOBAL : ElfObj.STB_LOCAL) << 4 | ElfObj.STT_FUNC;
            sd.shndx = obj.sectionIndex(text);
            sd.value = ind;
            undefByName.remove(name);
            return fwd;
        }
        ElfObj.SymDef sd = new ElfObj.SymDef(name, (global ? ElfObj.STB_GLOBAL : ElfObj.STB_LOCAL) << 4 | ElfObj.STT_FUNC);
        sd.shndx = obj.sectionIndex(text);
        sd.value = ind;
        return obj.addSymbol(sd);
    }

    private final java.util.Map<String, Integer> undefByName = new java.util.HashMap<>();

    public int addUndefSymbol(String name) {
        Integer existing = undefByName.get(name);
        if (existing != null) return existing;
        int id = obj.addSymbol(new ElfObj.SymDef(name, ElfObj.STB_GLOBAL << 4 | ElfObj.STT_NOTYPE));
        undefByName.put(name, id);
        return id;
    }

    public int addDataSymbol(String name, Section s, long value, boolean global) {
        ElfObj.SymDef sd = new ElfObj.SymDef(name, (global ? ElfObj.STB_GLOBAL : ElfObj.STB_LOCAL) << 4 | ElfObj.STT_OBJECT);
        sd.shndx = obj.sectionIndex(s);
        sd.value = value;
        return obj.addSymbol(sd);
    }

    /** tentative definition (uninitialized non-static global): a COMMON symbol so duplicate
     *  definitions across TUs are merged by the linker (tcc -fcommon-style behavior). */
    public int addCommonSymbol(String name, long size, long align) {
        ElfObj.SymDef sd = new ElfObj.SymDef(name, ElfObj.STB_GLOBAL << 4 | ElfObj.STT_OBJECT);
        sd.shndx = ElfObj.SHN_COMMON;
        sd.value = align;   // st_value holds the alignment for COMMON
        sd.size = size;
        return obj.addSymbol(sd);
    }
}
