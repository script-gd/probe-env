package jtcc.elf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal ELF64 ET_REL (.o) writer for x86-64 (tccelf.c elf_output_obj subset).
 *  Two-pass: content (names/sections/symbols) is finalized first, then laid out and written. */
public final class ElfObj {
    public static final class SymDef {
        public String name;
        public long value;
        public long size;
        public int info;        // (bind<<4)|type
        public int shndx;       // section index, 0=undef, 0xfff1=ABS, 0xfff2=COMMON
        int nameOff;
        public SymDef(String name, int info) { this.name = name; this.info = info; }
    }

    public static final int STB_LOCAL = 0, STB_GLOBAL = 1, STB_WEAK = 2;
    public static final int STT_NOTYPE = 0, STT_OBJECT = 1, STT_FUNC = 2, STT_SECTION = 3, STT_FILE = 4;
    public static final int SHN_UNDEF = 0, SHN_ABS = 0xfff1, SHN_COMMON = 0xfff2;

    public static final int R_X86_64_NONE = 0, R_X86_64_64 = 1, R_X86_64_PC32 = 2, R_X86_64_GOT32 = 3,
            R_X86_64_PLT32 = 4, R_X86_64_32 = 10, R_X86_64_32S = 11, R_X86_64_PC64 = 24;

    private final List<Section> sections = new ArrayList<>();
    private final List<SymDef> symbols = new ArrayList<>();
    private final Section strtab = new Section(".strtab", Section.SHT_STRTAB, 0);
    private final Section shstrtab = new Section(".shstrtab", Section.SHT_STRTAB, 0);

    public ElfObj() {
        sections.add(new Section("", Section.SHT_NULL, 0));
        symbols.add(new SymDef("", 0)); // null symbol
    }

    public Section section(String name, int type, int flags) {
        Section s = new Section(name, type, flags);
        sections.add(s);
        return s;
    }

    public int addSymbol(SymDef def) {
        symbols.add(def);
        return symbols.size() - 1;
    }

    /** true when the symbol is local (static) — safe for direct RIP-relative PC32 access in PIC.
     *  Global/undefined symbols are potentially preemptible and need GOT-relative access. */
    public boolean symbolIsLocal(int id) {
        if (id <= 0 || id >= symbols.size()) return false;
        SymDef sd = symbols.get(id);
        return (sd.info >> 4) == STB_LOCAL && sd.shndx != SHN_UNDEF;
    }

    public int sectionIndex(Section s) { return sections.indexOf(s); }

    public void write(Path out) throws IOException {
        // ---- pass 1: finalize all content ----
        // ELF requires local symbols first: partition [null, locals..., globals...] and
        // remap symbol indices in relocations accordingly.
        List<SymDef> ordered = new ArrayList<>();
        ordered.add(symbols.get(0));
        int[] remap = new int[symbols.size()];
        for (int i = 1; i < symbols.size(); i++) if ((symbols.get(i).info >> 4) == STB_LOCAL) {
            remap[i] = ordered.size();
            ordered.add(symbols.get(i));
        }
        for (int i = 1; i < symbols.size(); i++) if ((symbols.get(i).info >> 4) != STB_LOCAL) {
            remap[i] = ordered.size();
            ordered.add(symbols.get(i));
        }

        strtab.size = 0; strtab.add((byte) 0);
        for (SymDef sd : ordered) sd.nameOff = strtab.add(nameBytes(sd.name));

        Section symtab = new Section(".symtab", Section.SHT_SYMTAB, 0);
        symtab.shAddralign = 8; symtab.shEntsize = 24;
        for (SymDef sd : ordered) {
            symtab.addLe32(sd.nameOff);
            symtab.add((byte) sd.info);
            symtab.add((byte) 0);
            symtab.addLe16(sd.shndx);
            symtab.addLe64(sd.value);
            symtab.addLe64(sd.size);
        }

        List<Section> all = new ArrayList<>();
        all.add(sections.get(0));
        for (int i = 1; i < sections.size(); i++) all.add(sections.get(i));
        all.add(strtab);
        all.add(symtab);
        List<Section> relaSecs = new ArrayList<>();
        for (int i = 1; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.relocs.isEmpty()) continue;
            Section r = new Section(".rela" + s.name, Section.SHT_RELA, 0);
            r.shEntsize = 24; r.shAddralign = 8; r.target = s;
            r.link = symtab;
            for (Section.Reloc rel : s.relocs) {
                r.addLe64(rel.offset & 0xffffffffL);
                r.addLe64(((long) remap[rel.symIndex] << 32) | (rel.type & 0xffffffffL));
                r.addLe64(rel.addend);
            }
            relaSecs.add(r);
        }
        all.addAll(relaSecs);
        all.add(shstrtab);

        shstrtab.size = 0; shstrtab.add((byte) 0);
        int[] nameOff = new int[all.size()];
        for (int i = 0; i < all.size(); i++) nameOff[i] = shstrtab.add(nameBytes(all.get(i).name));

        // ---- pass 2: layout ----
        int total = all.size();
        long cur = 64 + (long) total * 64;
        int[] offsets = new int[total];
        for (int i = 1; i < total; i++) {
            Section s = all.get(i);
            cur = align16(cur);
            offsets[i] = (int) cur;
            if (s.shType != Section.SHT_NOBITS) cur += s.size;
        }

        // ---- write ----
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        byte[] eh = new byte[64];
        eh[0] = 0x7f; eh[1] = 'E'; eh[2] = 'L'; eh[3] = 'F';
        eh[4] = 2; eh[5] = 1; eh[6] = 1;
        put16(eh, 16, 1); put16(eh, 18, 62);
        put32(eh, 20, 1);
        put64(eh, 40, 64);
        put16(eh, 52, 64); put16(eh, 58, 64); put16(eh, 60, total); put16(eh, 62, total - 1);
        file.write(eh, 0, 64);

        int strtabIdx = 1 + (sections.size() - 1);
        int symtabIdx = strtabIdx + 1;
        for (int i = 0; i < total; i++) {
            Section s = all.get(i);
            byte[] b = new byte[64];
            if (i > 0) {
                int link = 0, info = 0;
                if (s == symtab) { link = strtabIdx; info = firstNonLocal(ordered); }
                else if (s.target != null) { link = symtabIdx; info = sections.indexOf(s.target); }
                put32(b, 0, nameOff[i]);
                put32(b, 4, s.shType);
                put64(b, 8, s.shFlags);
                put64(b, 24, offsets[i]);
                put64(b, 32, s.size);
                put32(b, 40, link);
                put32(b, 44, info);
                put64(b, 48, s.shAddralign);
                put64(b, 56, s.shEntsize);
            }
            file.write(b, 0, 64);
        }

        long pos = 64 + (long) total * 64;
        for (int i = 1; i < total; i++) {
            Section s = all.get(i);
            long to = align16(pos);
            for (long k = pos; k < to; k++) file.write(0);
            pos = to;
            if (s.shType != Section.SHT_NOBITS) {
                file.write(s.bytes(), 0, s.size);
                pos += s.size;
            }
        }
        Files.write(out, file.toByteArray());
    }

    private int firstNonLocal(List<SymDef> ordered) {
        int i = 1;
        for (; i < ordered.size(); i++) if ((ordered.get(i).info >> 4) != STB_LOCAL) break;
        return i;
    }

    private static byte[] nameBytes(String s) {
        byte[] b = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    private static long align16(long v) { return (v + 15) & -16; }
    private static void put16(byte[] b, int off, int v) { b[off] = (byte) v; b[off + 1] = (byte) (v >>> 8); }
    private static void put32(byte[] b, int off, int v) { put16(b, off, v); put16(b, off + 2, v >>> 16); }
    private static void put64(byte[] b, int off, long v) { put32(b, off, (int) v); put32(b, off + 4, (int) (v >>> 32)); }
}
