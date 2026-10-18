package jtcc.elf;

import java.util.ArrayList;
import java.util.List;

/** A growable output section with backpatch support and relocations (tcc Section). */
public final class Section {
    public static final int SHT_NULL = 0, SHT_PROGBITS = 1, SHT_SYMTAB = 2, SHT_STRTAB = 3,
            SHT_RELA = 4, SHT_NOBITS = 8;
    public static final int SHF_WRITE = 1, SHF_ALLOC = 2, SHF_EXECINSTR = 4;

    public final String name;
    public int shType;
    public int shFlags;
    public int shAddralign = 16;
    public int shEntsize;
    public Section link;      // e.g. rela -> symtab
    public Section target;    // section being relocated (rela target)

    private byte[] data = new byte[1024];
    public int size;

    public final List<Reloc> relocs = new ArrayList<>();

    public Section(String name, int type, int flags) {
        this.name = name; this.shType = type; this.shFlags = flags;
    }

    public int pos() { return size; }

    public int add(byte[] b, int off, int len) {
        ensure(size + len);
        System.arraycopy(b, off, data, size, len);
        size += len;
        return size - len;
    }

    public int add(byte[] b) { return add(b, 0, b.length); }

    public int add(int v) { ensure(size + 1); data[size] = (byte) v; return size++; }

    public int addLe16(int v) { add((byte) v); add((byte) (v >>> 8)); return size - 2; }
    public int addLe32(int v) { addLe16(v); addLe16(v >>> 16); return size - 4; }
    public long addLe64(long v) { addLe32((int) v); addLe32((int) (v >>> 32)); return size - 8L; }

    public void patchLe16(int at, int v) { data[at] = (byte) v; data[at + 1] = (byte) (v >>> 8); }
    public void patchLe32(int at, int v) {
        data[at] = (byte) v; data[at + 1] = (byte) (v >>> 8);
        data[at + 2] = (byte) (v >>> 16); data[at + 3] = (byte) (v >>> 24);
    }
    public void patchLe64(int at, long v) { patchLe32(at, (int) v); patchLe32(at + 4, (int) (v >>> 32)); }

    public int getU8(int at) { return data[at] & 0xff; }

    public byte[] bytes() {
        byte[] b = new byte[size];
        System.arraycopy(data, 0, b, 0, size);
        return b;
    }

    private void ensure(int cap) {
        if (cap <= data.length) return;
        int n = data.length * 2;
        while (n < cap) n *= 2;
        byte[] d = new byte[n];
        // copy only what the old buffer actually holds: `size` may exceed data.length when
        // the prologue reserves bytes by bumping size directly (backpatched later)
        System.arraycopy(data, 0, d, 0, Math.min(size, data.length));
        data = d;
    }

    /** Relocation entry (ELF Rela: r_offset, r_info(sym,type), r_addend). */
    public static final class Reloc {
        public final int offset;
        public final int symIndex;
        public final int type;
        public final long addend;
        public Reloc(int offset, int symIndex, int type, long addend) {
            this.offset = offset; this.symIndex = symIndex; this.type = type; this.addend = addend;
        }
    }
}
