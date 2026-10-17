package jtcc.cc;

/** tcc CType: type tag + reference to the Sym carrying pointed/element/struct details. */
public final class CType implements Cloneable {
    // VT_BTYPE
    public static final int VT_VOID = 0, VT_BYTE = 1, VT_SHORT = 2, VT_INT = 3, VT_LLONG = 4,
            VT_PTR = 5, VT_FUNC = 6, VT_STRUCT = 7, VT_FLOAT = 8, VT_DOUBLE = 9, VT_LDOUBLE = 10,
            VT_BOOL = 11, VT_BTYPE = 0x000f;
    public static final int VT_UNSIGNED = 0x0010, VT_ARRAY = 0x0040,
            VT_CONSTANT = 0x0100, VT_VOLATILE = 0x0200, VT_RESTRICT = 0x0400, VT_ATOMIC = VT_VOLATILE,
            VT_VLA = 0x0800, VT_LONG = 0x1000;
    // storage
    public static final int VT_EXTERN = 0x00000080, VT_STATIC = 0x00000100, VT_TYPEDEF = 0x00000200,
            VT_INLINE = 0x00000400, VT_STORAGE = VT_EXTERN | VT_STATIC | VT_TYPEDEF | VT_INLINE;
    public static final int VT_QUAL = VT_CONSTANT | VT_VOLATILE | VT_RESTRICT;
    public static final int VT_STRUCT_SHIFT = 20;
    public static final int VT_BITFIELD = 1 << (VT_STRUCT_SHIFT + 12);
    public static final int VT_UNION = (1 << VT_STRUCT_SHIFT) | VT_STRUCT;
    public static final int VT_ENUM = 2 << VT_STRUCT_SHIFT;
    public static final int VT_ENUM_VAL = 3 << VT_STRUCT_SHIFT;
    public static final int VT_STRUCT_MASK = ((1 << 12) - 1) << VT_STRUCT_SHIFT | VT_BITFIELD;
    public static final int VT_TYPE = ~(VT_STORAGE | VT_STRUCT_MASK);

    public int t;
    public Sym ref;

    public CType() { t = VT_INT; }
    public CType(int t, Sym ref) { this.t = t; this.ref = ref; }
    public CType(CType o) { this.t = o.t; this.ref = o.ref; }

    public int btype() { return t & VT_BTYPE; }
    public boolean isUnsigned() { return (t & VT_UNSIGNED) != 0; }
    public boolean isPtr() { return btype() == VT_PTR; }
    public boolean isArray() { return (t & VT_ARRAY) != 0; }
    public boolean isFunc() { return btype() == VT_FUNC; }
    public boolean isFloat() { int b = btype(); return b == VT_FLOAT || b == VT_DOUBLE || b == VT_LDOUBLE; }
    public boolean isInteger() { int b = btype(); return b <= VT_BOOL && b != VT_VOID || btype() == VT_PTR; }
    public boolean isEnum() { return (t & VT_STRUCT_MASK) == VT_ENUM; }
    public boolean isUnion() { return (t & (VT_STRUCT_MASK | VT_BTYPE)) == VT_UNION; }
    public boolean isVla() { return (t & VT_VLA) != 0; }

    public static int bitPos(int t) { return (t >> VT_STRUCT_SHIFT) & 0x3f; }
    public static int bitSize(int t) { return (t >> (VT_STRUCT_SHIFT + 6)) & 0x3f; }
}
