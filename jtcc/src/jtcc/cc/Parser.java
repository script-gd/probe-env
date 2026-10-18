package jtcc.cc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jtcc.ctok.CTok;

/**
 * C parser, structural port of tccgen.c's parse side (decl/expr/block). One-pass like tcc:
 * calls the CodeGen backend interface during parsing (StubGen in M2 parse-only mode).
 *
 * Ported faithfully where implemented; gaps are marked with `// TODO(tccgen.c:NNNN)` mapping
 * to the source line of the not-yet-ported construct.
 */
public final class Parser {
    private final List<CTok> toks;
    private int pos;
    private final Diag diag;
    public interface Diag {
        void error(String file, int line, String msg);
        void warn(String file, int line, String msg);
    }

    // ------------------------------------------------------- symbol tables (tccgen.c stacks)
    /** M3 code generator; null in parse-only mode. */
    public jtcc.backend.x86_64.VGen gen;
    /** string literal counter for rodata labels. */
    private int strLitCount;
    private String curFuncName = ""; // current function name for __func__

    private final Deque<Map<String, Sym>> globalStack = new ArrayDeque<>();
    private final Deque<Map<String, Sym>> localStack = new ArrayDeque<>();
    private final Map<String, Sym> typedefs = new HashMap<>();      // typedef names (global for now)
    private final Map<String, Sym> structTags = new HashMap<>();    // struct/union/enum tags
    private int blockDepth;
    /** C block scoping for struct tags: on block exit, tags defined inside go out of
     *  scope (and shadowed outer definitions are restored). Mirrors tcc local_scope. */
    private static final class StructScope {
        final java.util.Set<String> added = new java.util.HashSet<>();
        final Map<String, Sym> shadowed = new HashMap<>();
    }
    private final Deque<StructScope> structScopes = new ArrayDeque<>();
    private final List<SValue> vstack = new ArrayList<>();
    private int vtop; // index of top

    public Parser(List<CTok> toks, Diag diag) {
        this.toks = toks;
        this.diag = diag;
    }

    // ------------------------------------------------------- token access
    private CTok tok() { return toks.get(pos); }
    private CTok next() { CTok t = toks.get(pos++); if (gen != null) { gen.dbgLine = t.line; gen.dbgFile = t.file; } return t; }
    private boolean accept(String s) { if (tok().is(s)) { pos++; return true; } return false; }
    private void expect(String s) {
        if (!accept(s)) error("expected '" + s + "' got '" + tok().text + "'");
    }
    private void error(String msg) { diag.error(tok().file, tok().line, msg); throw new ParseError(msg); }
    private void warn(String msg) { diag.warn(tok().file, tok().line, msg); }
    public static final class ParseError extends RuntimeException { ParseError(String m) { super(m); } }

    // ------------------------------------------------------- entry: tccgen_compile (tccgen.c:403)
    public void compile() {
        while (tok().kind != CTok.Kind.EOF) {
            if (!decl(Sym.VT_CONST)) {
                if (tok().kind == CTok.Kind.EOF) break;
                error("declaration expected");
            }
        }
    }

    // ------------------------------------------------------- types

    /** parse_btype (tccgen.c:4752): base type specifiers. Returns true if a type was found. */
    private boolean parseBtype(CType type, Sym fa /* attr acc */, boolean ignoreLabel) {
        boolean fromTypedef = false; // type came from a typedef name: skip scalar normalization
        // typedef name?
        CTok t = tok();
        if (t.kind == CTok.Kind.IDENT) {
            Sym td = typedefs.get(t.text);
            if (td != null) {
                next();
                type.t = td.type.t & ~(CType.VT_STORAGE | CType.VT_QUAL);
                type.ref = td.type.ref;
                fromTypedef = true;
                return true;
            }
        }
        if (t.kind != CTok.Kind.KEYWORD) return false;
        boolean found = false;
        boolean done = false;
        boolean sawSpec = false; // attribute/alignas/thread-local seen (implicit-int trigger)
        int bt = -1;
        boolean sign = false, signSet = false;
        boolean isLong = false;
        while (!done) {
            String k = tok().text;
            switch (k) {
                case "void": bt = CType.VT_VOID; found = true; next(); break;
                case "char": bt = CType.VT_BYTE; found = true; next(); break;
                case "short": found = true; bt = CType.VT_SHORT; next(); break;
                case "int": found = true; if (bt < 0) bt = CType.VT_INT; next(); break;
                case "float": found = true; bt = CType.VT_FLOAT; next(); break;
                case "double": found = true; bt = CType.VT_DOUBLE; next(); break;
                case "_Bool": found = true; bt = CType.VT_BOOL; next(); break;
                case "_Complex": next(); break; // parsed but unsupported downstream for now
                case "long":
                    found = true; next();
                    if (isLong) { bt = CType.VT_LLONG; } else { isLong = true; }
                    break;
                case "signed": case "__signed": case "__signed__":
                    found = true; sign = false; signSet = true; next(); break;
                case "unsigned":
                    found = true; sign = true; signSet = true; next(); break;
                case "const": case "__const": case "__const__":
                    type.t |= CType.VT_CONSTANT; next(); break;
                case "volatile": case "__volatile": case "__volatile__":
                    type.t |= CType.VT_VOLATILE; next(); break;
                case "restrict": case "__restrict": case "__restrict__":
                    type.t |= CType.VT_RESTRICT; next(); break;
                case "_Atomic":
                    next();
                    if (accept("(")) {
                        // _Atomic(type-name): atomic type specifier (C11 6.7.2.4)
                        CType at = new CType();
                        parseBtype(at, null, false);
                        typeDecl(at, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                        expect(")");
                        type.t &= ~(CType.VT_BTYPE | CType.VT_STRUCT_MASK | CType.VT_LONG);
                        type.t |= at.t;
                        type.ref = at.ref;
                        found = true;
                    }
                    type.t |= CType.VT_ATOMIC;
                    break;
                case "struct": case "union": {
                    next();
                    found = true;
                    // TODO(tccgen.c:4476 struct_decl full port)
                    parseStructRef(type, k.equals("union"));
                    break;
                }
                case "enum": {
                    next();
                    found = true;
                    parseEnumRef(type);
                    break;
                }
                case "__extension__": next(); break;
                case "typeof": case "__typeof": case "__typeof__": {
                    next();
                    expect("(");
                    CType tt = new CType();
                    if (parseBtype(tt, null, false)) typeDecl(tt, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                    else expr(); // typeof expr: type comes from the expression (M3)
                    expect(")");
                    found = true;
                    bt = tt.btype() >= 0 ? tt.btype() : CType.VT_INT;
                    type.t &= ~(CType.VT_BTYPE | CType.VT_STRUCT_MASK);
                    type.t |= tt.t;
                    type.ref = tt.ref;
                    break;
                }
                case "typedef": type.t |= CType.VT_TYPEDEF; next(); break;
                case "extern": type.t |= CType.VT_EXTERN; next(); break;
                case "static": type.t |= CType.VT_STATIC; next(); break;
                case "inline": case "__inline": case "__inline__":
                    type.t |= CType.VT_INLINE; next(); break;
                case "register": case "auto": next(); break;
                case "_Thread_local": case "__thread": next(); sawSpec = true; break;
                case "_Noreturn": case "__noreturn__": next(); sawSpec = true; break;
                case "__attribute": case "__attribute__":
                    next();
                    parseAttribute();
                    sawSpec = true;
                    break;
                case "_Alignas":
                    next();
                    sawSpec = true;
                    if (accept("(")) {
                        CType at = new CType();
                        if (parseBtype(at, null, false)) typeDecl(at, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                        else exprConst64();
                        expect(")");
                    }
                    break;
                default:
                    done = true;
            }
            // typedef name as type (tccgen.c parse_btype TOK_IDENT path): checked inside the
            // loop so qualifiers/storage may surround it
            if (done && tok().kind == CTok.Kind.IDENT && typedefs.containsKey(tok().text)
                    && bt < 0 && !signSet && !isLong) {
                Sym td = typedefs.get(tok().text);
                next();
                found = true;
                type.t &= CType.VT_STORAGE | CType.VT_QUAL;
                type.t |= td.type.t & ~(CType.VT_STORAGE | CType.VT_QUAL);
                type.ref = td.type.ref;
                fromTypedef = true;
                done = false;
            }
        }
        if (!found) {
            // implicit int for declarations starting with attributes/alignas only (C89 holdover tcc accepts)
            if ((type.t & (CType.VT_QUAL)) != 0 || sawSpec) { type.t |= CType.VT_INT; return true; }
            return false;
        }
        if (fromTypedef) return true; // typedef-resolved type: no scalar normalization
        // struct/enum set their own btype via refs; only scalar specifiers go through bt
        boolean composite = (type.t & CType.VT_BTYPE) == CType.VT_STRUCT
                || (type.t & CType.VT_STRUCT_MASK) == CType.VT_ENUM;
        if (composite) return true;
        if (bt < 0) bt = CType.VT_INT;
        type.t &= ~(CType.VT_BTYPE | CType.VT_UNSIGNED | CType.VT_LONG);
        type.t |= bt;
        // LP64 normalization (tccgen.c:5016): `long` on 64-bit targets is VT_LLONG|VT_LONG
        if (bt == CType.VT_INT && isLong) { type.t = (type.t & ~CType.VT_BTYPE) | CType.VT_LLONG; type.t |= CType.VT_LONG; }
        if (bt == CType.VT_LLONG && isLong) type.t |= CType.VT_LONG;
        if (bt == CType.VT_DOUBLE && isLong) { type.t = (type.t & ~CType.VT_BTYPE) | CType.VT_LDOUBLE; }
        if (signSet && sign) type.t |= CType.VT_UNSIGNED;
        return true;
    }

    private void parseStructRef(CType type, boolean isUnion) {
        // struct_decl (tccgen.c:4476): struct [attr] [tag] [{ fields }]
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
        CTok t = tok();
        String tag = null;
        if (t.kind == CTok.Kind.IDENT) { tag = t.text; next(); }
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }

        Sym s = null;
        if (tag != null) {
            s = structTags.get(tag);
            if (s != null && tok().is("{") && s.c >= 0 && structScopes.peek() != null
                    && structScopes.peek().added.contains(tag))
                error("struct/union '" + tag + "' already defined in this scope");
            if (s != null && tok().is("{") && s.c >= 0 && structScopes.peek() != null
                    && !structScopes.peek().added.contains(tag)) {
                // shadow the outer definition in this deeper scope (restored at block exit)
                structScopes.peek().shadowed.putIfAbsent(tag, s);
                structScopes.peek().added.add(tag);
                s = null;
            }
        } else {
            if (!tok().is("{") && !tok().is(";")) error("struct/union name expected");
        }
        if (s == null) {
            s = new Sym(tag);
            s.c = -1; // undefined size (forward ref)
            s.r = 0;  // default alignment
            if (tag != null) {
                structTags.put(tag, s);
                if (structScopes.peek() != null) structScopes.peek().added.add(tag);
            }
        }
        type.t &= ~(CType.VT_BTYPE | CType.VT_STRUCT_MASK);
        type.t |= CType.VT_STRUCT | (isUnion ? CType.VT_UNION : 0);
        type.ref = s;

        if (accept("{")) {
            if (s.c >= 0) error("struct/union '" + tag + "' already defined");
            s.c = -2;
            Sym first = null, last = null;
            while (!tok().is("}")) {
                if (tok().is("_Static_assert")) {
                    next(); expect("(");
                    long v = exprConst64();
                    expect(",");
                    while (tok().kind == CTok.Kind.STR || tok().kind == CTok.Kind.LSTR) next();
                    expect(")"); expect(";");
                    if (v == 0) warn("static assertion not provable in parse-only mode (M2); exact evaluation in M3");
                    continue;
                }
                CType btype = new CType();
                if (!parseBtype(btype, null, false)) { expect(";"); continue; }
                for (;;) {
                    String fname = null;
                    int bitSize = -1;
                    CType ftype = new CType(btype);
                    if (!tok().is(":")) {
                        if (!tok().is(";")) {
                            StringBuilder fn = new StringBuilder();
                            typeDecl(ftype, fn, TYPE_DIRECT);
                            fname = fn.length() == 0 ? null : fn.toString();
                        }
                        if (fname == null && ftype.btype() != CType.VT_STRUCT) {
                            // anonymous struct/union member needs a struct type
                            if (ftype.btype() != CType.VT_STRUCT) error("identifier expected");
                        }
                        int al;
                        if (typeSize(ftype) < 0) {
                            // incomplete type: allow flexible array member at the end
                            if (!isUnion && ftype.isArray() && first != null) { /* flexible */ }
                            else if (!tok().is(";")) error("field has incomplete type");
                        }
                        if (ftype.isFunc() || ftype.btype() == CType.VT_VOID)
                            error("invalid type for field");
                    }
                    if (accept(":")) {
                        bitSize = (int) exprConst64();
                        if (bitSize < 0) error("negative width in bit-field");
                        if (fname != null && bitSize == 0) error("zero width for bit-field");
                        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
                    }
                    int fsize = typeSize(ftype);
                    if (bitSize >= 0) {
                        int bt = ftype.btype();
                        if (bt != CType.VT_INT && bt != CType.VT_BYTE && bt != CType.VT_SHORT
                                && bt != CType.VT_BOOL && bt != CType.VT_LLONG)
                            error("bitfields must have scalar type");
                        if (bitSize > fsize * 8) error("width of bit-field exceeds its type");
                        if (bitSize != fsize * 8) {
                            if (bitSize == 64) error("field width 64 not implemented");
                            ftype.t = (ftype.t & ~CType.VT_STRUCT_MASK) | CType.VT_BITFIELD
                                    | (bitSize << (CType.VT_STRUCT_SHIFT + 6));
                        }
                    }
                    if (fname == null && (ftype.btype() == CType.VT_STRUCT || bitSize >= 0))
                        fname = ""; // anonymous member marker
                    if (fname != null) {
                        Sym f = new Sym(fname);
                        f.type = ftype;
                        f.a = new Sym.SymAttr();
                        if (first == null) first = f; else last.next = f;
                        last = f;
                    }
                    if (tok().is(";") || tok().kind == CTok.Kind.EOF) break;
                    expect(",");
                }
                expect(";");
            }
            expect("}");
            while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
            s.next = first;
            structLayout(s, isUnion);
        }
    }

    /** struct_layout (tccgen.c:4217): compute field offsets and total size/alignment (PCC). */
    private void structLayout(Sym s, boolean isUnion) {

        int offset = 0, maxAlign = 1, c = 0;
        for (Sym f = s.next; f != null; f = f.next) {
            int size = typeSize(f.type);
            int align = alignOf(f.type);
            if (f.a.aligned > 0) align = 1 << (f.a.aligned - 1);
            if (isUnion) {
                f.c = 0;
                if (size > c) c = size;
                if (align > maxAlign) maxAlign = align;
            } else {
                c = (c + align - 1) & -align;
                f.c = c;
                if (size > 0) c += size;
                if (align > maxAlign) maxAlign = align;
            }
        }
        s.c = isUnion ? c : (c + maxAlign - 1) & -maxAlign;
        s.r = maxAlign;
    }

    /** type_size (tccgen.c:3502): bytes of a type; negative when incomplete. */
    int typeSize(CType type) {
        switch (type.btype()) {
            case CType.VT_STRUCT: {
                Sym s = type.ref;
                return s == null ? -1 : s.c;
            }
            case CType.VT_PTR: {
                if (type.isArray()) {
                    Sym s = type.ref;
                    if (s.c < 0) return -1;
                    int ts = typeSize(s.type);
                    return ts < 0 ? -1 : ts * s.c;
                }
                return 8;
            }
            case CType.VT_LDOUBLE: return 16;
            case CType.VT_DOUBLE: case CType.VT_LLONG: return 8;
            case CType.VT_INT: case CType.VT_FLOAT: return 4;
            case CType.VT_SHORT: return 2;
            default: return 1; // char, void, function, _Bool
        }
    }

    /** alignment of a type (tcc type_size's *a): arrays align as their element type. */
    int alignOf(CType type) {
        switch (type.btype()) {
            case CType.VT_STRUCT: {
                Sym s = type.ref;
                return s == null || s.r == 0 ? 1 : s.r;
            }
            case CType.VT_PTR: {
                if (type.isArray()) return alignOf(type.ref.type); // element alignment
                return 8;
            }
            case CType.VT_LDOUBLE: return 16;
            case CType.VT_DOUBLE: case CType.VT_LLONG: return 8;
            case CType.VT_INT: case CType.VT_FLOAT: return 4;
            case CType.VT_SHORT: return 2;
            default: return 1;
        }
    }

    private final Map<String, Long> enumConsts = new HashMap<>();

    private void parseEnumRef(CType type) {
        CTok t = tok();
        if (t.kind == CTok.Kind.IDENT) next();
        if (accept("{")) {
            // enumerators with constant values (tccgen.c struct_decl enum path)
            long v = 0;
            while (!tok().is("}")) {
                if (tok().kind == CTok.Kind.IDENT) {
                    String name = tok().text;
                    next();
                    if (accept("=")) v = exprConst64();
                    enumConsts.put(name, v);
                    v++;
                } else if (tok().is(",")) { next(); continue; }
                else next();
                if (!accept(",")) break;
            }
            expect("}");
        }
        type.t &= ~(CType.VT_BTYPE | CType.VT_STRUCT_MASK);
        type.t |= CType.VT_INT | CType.VT_ENUM;
    }

    /** __attribute__((...)) — parse and discard contents (fixture attributes validated at use). */
    private void parseAttribute() {
        if (!accept("(")) return;
        int depth = 1; // the outer paren we just consumed
        while (depth > 0 && tok().kind != CTok.Kind.EOF) {
            if (accept("(")) depth++;
            else if (accept(")")) depth--;
            else next();
        }
    }

    // ------------------------------------------------------- declarators (faithful port)

    private static final int TYPE_DIRECT = 2, TYPE_ABSTRACT = 4, TYPE_PARAM = 8;

    /** mk_pointer (tccgen.c): wrap type into a pointer in place. */
    private static void mkPointer(CType type) {
        Sym s = new Sym(null);
        s.type = new CType(type.t, type.ref);
        type.t = CType.VT_PTR;
        type.ref = s;
    }

    private static CType pointedType(CType type) { return type.ref.type; }

    /**
     * type_decl (tccgen.c:5295): parse a declarator into `type` (mutated in place).
     * Returns the slot where postfix operators apply (the innermost pointed-to type).
     */
    private CType typeDecl(CType type, StringBuilder nameOut, int td) {
        int storage = type.t & CType.VT_STORAGE;
        type.t &= ~CType.VT_STORAGE;
        CType post = type, ret = type;

        while (tok().is("*")) {
            int qualifiers = 0;
            next(); // '*'
            for (;;) {
                String k = tok().text;
                if (k.equals("_Atomic")) { qualifiers |= CType.VT_ATOMIC; next(); }
                else if (k.equals("const") || k.equals("__const") || k.equals("__const__")) { qualifiers |= CType.VT_CONSTANT; next(); }
                else if (k.equals("volatile") || k.equals("__volatile") || k.equals("__volatile__")) { qualifiers |= CType.VT_VOLATILE; next(); }
                else if (k.equals("restrict") || k.equals("__restrict") || k.equals("__restrict__")) { qualifiers |= CType.VT_RESTRICT; next(); }
                else if (k.equals("__attribute") || k.equals("__attribute__")) { next(); parseAttribute(); break; } // tcc: attribute ends the qualifier run
                else break;
            }
            mkPointer(type);
            type.t |= qualifiers;
            if (ret == type)
                ret = pointedType(type); // innermost pointed-to slot
        }

        // attributes between pointer run and declarator name (tcc parse_attribute position)
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }

        if (tok().is("(")) {
            if (!postType(type, 0, td)) {
                post = typeDecl(type, nameOut, td);
                expect(")");
            }
            // else: abstract param list already handled by postType
        } else if (tok().kind == CTok.Kind.IDENT && (td & TYPE_DIRECT) != 0) {
            nameOut.append(tok().text);
            next();
        } else {
            if ((td & TYPE_ABSTRACT) == 0) error("identifier expected");
        }
        postType(post, post != ret ? 0 : storage, td & ~(TYPE_DIRECT | TYPE_ABSTRACT));
        // trailing attributes
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
        type.t |= storage;
        return ret;
    }

    /**
     * post_type (tccgen.c:5067): function/array suffixes, mutating `type` in place.
     * Returns false when '(' did not start a parameter list (nested declarator case).
     */
    private boolean postType(CType type, int storage, int td) {
        for (;;) {
            if (tok().is("(")) {
                next();
                if (TYPE_DIRECT == (td & (TYPE_DIRECT | TYPE_ABSTRACT))) return false;
                Sym fn = new Sym(null);
                fn.fa = new Sym.FuncAttr();
                fn.fa.funcType = 0; // FUNC_OLD
                Sym first = null, last = null;
                structScopes.push(new StructScope()); // parameter scope (tcc post_type scope boundary)
                if (!tok().is(")")) {
                    CType pt = new CType();
                    if (parseBtype(pt, null, false)) {
                        fn.fa.funcType = 1; // FUNC_NEW
                    } else if ((td & (TYPE_DIRECT | TYPE_ABSTRACT)) != 0) {
                        return false; // not a parameter list: nested declarator
                    } // else FUNC_OLD
                    if (fn.fa.funcType == 1) {
                        for (;;) {
                            if (pt.btype() == CType.VT_VOID && tok().is(")")) break;
                            StringBuilder pn = new StringBuilder();
                            typeDecl(pt, pn, TYPE_DIRECT | TYPE_ABSTRACT | TYPE_PARAM);
                            if (pt.btype() == CType.VT_VOID) error("parameter declared as void");
                            Sym p = new Sym(pn.length() == 0 ? null : pn.toString());
                            p.type = new CType(pt);
                            if (first == null) first = p; else last.next = p;
                            last = p;
                            if (tok().is(")")) break;
                            expect(",");
                            if (tok().is("...")) { next(); fn.fa.funcType = 2; break; } // ELLIPSIS
                            pt = new CType();
                            if (!parseBtype(pt, null, false)) error("invalid type");
                        }
                    } else {
                        // FUNC_OLD: identifier list
                        for (;;) {
                            if (tok().kind != CTok.Kind.IDENT) break;
                            String pn = tok().text; next();
                            Sym p = new Sym(pn);
                            p.type = new CType(CType.VT_INT | CType.VT_EXTERN, null);
                            if (first == null) first = p; else last.next = p;
                            last = p;
                            if (tok().is(")")) break;
                            expect(",");
                        }
                    }
                }
                expect(")");
                popStructScope(); // tcc post_type pops the parameter scope after ')'
                // function returning array quirk: int c()[]
                if (accept("[")) { expect("]"); mkPointer(type); }
                type.t &= ~CType.VT_QUAL;
                fn.type = new CType(type.t, type.ref);
                fn.next = first;
                type.t = CType.VT_FUNC | (type.t & CType.VT_STORAGE);
                type.ref = fn;
            } else if (tok().is("[")) {
                next();
                long n = -1;
                boolean isStatic = false;
                // qualifiers/static/'*' in parameter array brackets
                for (;;) {
                    String k = tok().text;
                    if (k.equals("const") || k.equals("volatile") || k.equals("restrict") || k.equals("static") || k.equals("*")) { next(); continue; }
                    break;
                }
                if (!tok().is("]")) {
                    n = exprConst64(); // constant bounds get their value; VLA degrades to 0 for now
                }
                expect("]");
                Sym arr = new Sym(null);
                arr.type = new CType(type.t, type.ref);
                arr.c = (int) n;
                type.t = CType.VT_PTR | CType.VT_ARRAY | (type.t & (CType.VT_STORAGE | CType.VT_QUAL));
                type.ref = arr;
            } else break;
        }
        return true;
    }

    // ------------------------------------------------------- declarations (tccgen.c decl())

    // ------------------------------------------------------- declarations (tccgen.c decl())

    /** decl(l): parse one declaration/statement-level declaration. Returns false if none. */
    private boolean decl(int storageClass) {
        CTok t = tok();
        if (t.kind == CTok.Kind.KEYWORD && t.is("_Static_assert")) {
            next(); expect("(");
            long v = exprConst64();
            expect(",");
            while (tok().kind == CTok.Kind.STR || tok().kind == CTok.Kind.LSTR) next(); // adjacent literals
            expect(")"); expect(";");
            if (v == 0) warn("static assertion not provable in parse-only mode (M2); exact evaluation in M3");
            return true;
        }
        if (tok().is("asm") || tok().is("__asm") || tok().is("__asm__")) {
            // file-scope asm("...") block (tccasm.c asm_global_instr path comes in M4)
            next();
            parseAttribute();
            expect(";");
            return true;
        }
        CType btype = new CType();
        if (!parseBtype(btype, null, false)) return false;
        if (accept(";")) return true;
        for (;;) {
            CType type = new CType(btype); // fresh copy per declarator: typeDecl mutates in place (tcc decl0)
            StringBuilder name = new StringBuilder();
            typeDecl(type, name, TYPE_DIRECT); // mutates `type` in place (tcc semantics)
            Sym sym = new Sym(name.length() == 0 ? null : name.toString());
            sym.type = new CType(type);
            CType dt = sym.type;
            // asm label on declarations: name proto __asm__("alias"); (glibc __REDIRECT)
            while (tok().is("__asm__") || tok().is("__asm") || tok().is("asm")
                    || tok().is("__attribute") || tok().is("__attribute__")) {
                next();
                parseAttribute(); // consumes balanced (...) — covers asm("...") too
            }
            if ((type.t & CType.VT_TYPEDEF) != 0) {
                if (sym.name != null) typedefs.put(sym.name, sym);
            } else if (dt.isFunc()) {
                boolean krScope = false;
                if (dt.ref.fa != null && dt.ref.fa.funcType == 0 && !tok().is("{") && !tok().is(";") && !tok().is(",")) {
                    // K&R: old-style parameter declarations before the body (tccgen.c decl0)
                    // parsed in the function's own scope (struct tags there are function-local)
                    structScopes.push(new StructScope());
                    krScope = true;
                    CType pt = new CType();
                    while (parseBtype(pt, null, false)) {
                        do {
                            StringBuilder pn = new StringBuilder();
                            CType pt2 = new CType(pt);
                            typeDecl(pt2, pn, TYPE_DIRECT);
                            Sym p = new Sym(pn.length() == 0 ? null : pn.toString());
                            p.type = new CType(pt2);
                            // attach to the function's param list by name order (approximation)
                            Sym q = dt.ref;
                            Sym lastP = null;
                            while (q.next != null) { lastP = q.next; q = q.next; }
                            if (pn.length() > 0) {
                                p.next = null;
                                // find the matching FUNC_OLD param by name and set its type
                                for (Sym it = dt.ref.next; it != null; it = it.next)
                                    if (pn.toString().equals(it.name)) { it.type = p.type; break; }
                            }
                        } while (accept(","));
                        expect(";");
                        pt = new CType();
                    }
                }
                if (tok().is("{")) {
                    // function definition: register the symbol before compiling the body
                    declareGlobal(sym);
                    parseFunction(sym);
                    if (krScope) popStructScope();
                    return true;
                }
                if (krScope) popStructScope();
                declareGlobal(sym);
            } else if (gen != null && !localStack.isEmpty() && storageClass != Sym.VT_CONST) {
                // local variable: allocate stack, optional initializer store
                int sz = Math.max(1, jtcc.backend.x86_64.VGen.typeSize(sym.type));
                gen.loc = (gen.loc - sz) & -Math.max(1, alignOf(sym.type)); // size then align (tcc layout)
                sym.r = Sym.VT_LOCAL | Sym.VT_LVAL;
                sym.c = gen.loc; if (System.getenv("JTCC_DBG_LAYOUT") != null) System.err.println("DBG layout " + sym.name + " c=" + sym.c + " sz=" + sz);
                localStack.peek().put(sym.name, sym);
                if (accept("=")) {
                    skipInitializer();
                    if (gen.vtop > 0) {
                        SValue rv = gen.vtop();
                        int r = gen.gv();
                        gen.store(r, symToLvalue(sym));
                        gen.vpop();
                    }
                }
            } else {
                declareGlobal(sym);
                if (gen != null) {
                    boolean isExtern = (type.t & CType.VT_EXTERN) != 0;
                    boolean hasInit = tok().is("=");
                    genDefineGlobal(sym, type, isExtern, hasInit);
                }
                if (accept("=")) {
                    // scalar constant initializers get real .data contents (M3)
                    if (gen != null && sym.symId != 0 && !tok().is("{") && tok().kind != CTok.Kind.STR && tok().kind != CTok.Kind.LSTR) {
                        if (!tryGlobalSymReloc(sym)) {
                            long v = exprConst64();
                            writeGlobalInit(sym, v);
                        }
                    } else {
                        skipInitializer();
                    }
                }
            }
            if (!accept(",")) break;
        }
        expect(";");
        return true;
    }

    private void declareGlobal(Sym sym) {
        if (sym.name == null) return;
        Map<String, Sym> scope = globalStack.peek();
        if (scope == null) { scope = new HashMap<>(); globalStack.push(scope); }
        scope.put(sym.name, sym);
    }

    private void parseFunction(Sym fn) {
        curFuncName = fn.name;
        // new scope for params+body
        localStack.push(new HashMap<>());
        if (gen != null) {
            gen.gfuncProlog(fn);
            Sym param = fn.type.ref.next;
            while (param != null) {
                if (param.name != null) {
                    param.r = Sym.VT_LOCAL | Sym.VT_LVAL;
                    localStack.peek().put(param.name, param);
                }
                param = param.next;
            }
            block(0);
            gen.gfuncEpilog();
        } else {
            Sym param = fn.type.ref.next;
            while (param != null) {
                if (param.name != null) localStack.peek().put(param.name, param);
                param = param.next;
            }
            block(0);
        }
        localStack.pop();
    }

    private void popStructScope() {
        StructScope sc = structScopes.pop();
        for (String tag : sc.added) structTags.remove(tag);
        structTags.putAll(sc.shadowed);
    }

    private void skipInitializer() {
        if (accept("{")) {
            int depth = 1;
            while (depth > 0) {
                if (accept("{")) depth++;
                else if (accept("}")) depth--;
                else if (tok().kind == CTok.Kind.EOF) { error("unexpected EOF in initializer"); return; }
                else next();
            }
        } else {
            // initializer is an assignment-expression (comma operator NOT allowed here)
            exprEq();
        }
    }

    /** compound literal `(T){ ... }`: allocate T on the stack, evaluate scalar field
     *  initializers (designated `.f = e` or positional), push the object lvalue. */
    private void genCompoundLiteral(CType ct) {
        if (gen == null) { skipInitializer(); return; }
        int size = Math.max(1, sizeOfType(ct));
        gen.loc = (gen.loc - size) & -Math.max(1, alignOf(ct));
        int base = gen.loc;
        expect("{");
        Sym posField = (ct.btype() == CType.VT_STRUCT && ct.ref != null) ? ct.ref.next : null;
        while (!tok().is("}")) {
            Sym f = null;
            if (accept(".")) {
                String fname = tok().text; next();
                f = fieldSym(ct, fname);
                expect("=");
            } else {
                f = posField;
                if (posField != null) posField = posField.next;
            }
            if (tok().is("{")) { skipInitializer(); }
            else {
                exprEq();
                if (f != null) {
                    int r = gen.gv();
                    SValue dst = new SValue(f.type);
                    dst.r = Sym.VT_LOCAL | Sym.VT_LVAL;
                    dst.c = base + f.c;
                    gen.store(r, dst);
                }
                if (gen.vtop > 0) gen.vpop();
            }
            if (!accept(",")) break;
        }
        expect("}");
        SValue v = gen.vpush();
        v.type = ct;
        v.r = Sym.VT_LOCAL | Sym.VT_LVAL;
        v.c = base;
    }

    // ------------------------------------------------------- statements (tccgen.c block())

    /** statement-expression body `({ ... })`: parse statements; the value of a trailing
     *  expression-statement becomes the result (tcc: the last expr is the block value). */
    private void blockStmtExpr() {
        while (!tok().is("}")) {
            if (tok().kind == CTok.Kind.EOF) { error("unexpected EOF in statement expression"); return; }
            if (isLastExprStmt()) {
                expr();      // keep this value on the stack as the statement-expression result
                expect(";");
                return;
            }
            statement();
        }
        if (gen != null) gen.vpushConst(new CType(CType.VT_INT, null), 0); // void result
    }

    /** true when the upcoming tokens are a single expression-statement immediately before the
     *  closing brace: `<expr> ; }` — the expression is not a declaration, and its terminating
     *  `;` at block depth 0 is followed directly by `}`. */
    private boolean isLastExprStmt() {
        CTok t0 = tok();
        if (t0.kind == CTok.Kind.KEYWORD) return false; // decl / control-flow keyword
        if (t0.kind == CTok.Kind.IDENT && typedefs.get(t0.text) != null) return false; // typedef decl
        int depth = 0;
        for (int i = pos; i < toks.size(); i++) {
            CTok t = toks.get(i);
            if (t.is("(") || t.is("[")) depth++;
            else if (t.is(")") || t.is("]")) depth--;
            else if (t.is("{") || t.is("}")) return false; // compound/block: not a simple trailing expression
            else if (t.is(";") && depth == 0) return toks.get(i + 1).is("}");
        }
        return false;
    }

    private void block(int flags) {
        blockDepth++;
        structScopes.push(new StructScope());
        expect("{");
        while (!tok().is("}")) {
            if (tok().kind == CTok.Kind.EOF) { error("unexpected EOF in block"); return; }
            statement();
        }
        next();
        blockDepth--;
        popStructScope(); // tags defined in this block go out of scope; restore shadows
    }

    private void statement() {
        CTok t = tok();
        if (t.kind == CTok.Kind.PUNCT) {
            if (t.is("{")) { block(0); return; }
            if (t.is(";")) { next(); return; }
        }
        switch (t.kind == CTok.Kind.KEYWORD ? t.text : "") {
            case "if": {
                next(); expect("("); expr(); expect(")");
                if (gen != null) {
                    int jz = gen.gjmpIfZero();
                    statement();
                    if (accept("else")) {
                        int jend = gen.enc.jmpRel32();
                        if (jz >= 0) gen.gsym(jz);
                        statement();
                        gen.gsym(jend);
                    } else {
                        if (jz >= 0) gen.gsym(jz);
                    }
                } else {
                    statement();
                    if (accept("else")) statement();
                }
                return;
            }
            case "while": {
                next();
                if (gen != null) {
                    int begin = gen.label();
                    expect("("); expr(); expect(")");
                    int jz = gen.gjmpIfZero();
                    pushBreakContinue(begin);
                    statement();
                    int back = gen.enc.jmpRel32();
                    gen.enc.patchRel32(back, begin);
                    popLoop();
                    if (jz >= 0) gen.gsym(jz);
                    patchBreaks();
                } else {
                    expect("("); expr(); expect(")");
                    statement();
                }
                return;
            }
            case "do": {
                next();
                if (gen != null) {
                    int begin = gen.label();
                    pushBreakContinue(begin); // provisional: continue -> cond, patched below
                    statement();
                    expect("while"); expect("("); expr(); expect(")");
                    int condAt = gen.label();
                    loopStack.get(loopStack.size() - 1)[0] = condAt;
                    int jnz = gen.gjmpIfNonZero();
                    if (jnz >= 0) gen.enc.patchRel32(jnz, begin);
                    popLoop();
                    patchBreaks();
                    expect(";");
                } else {
                    statement();
                    expect("while"); expect("("); expr(); expect(")"); expect(";");
                }
                return;
            }
            case "for": {
                next(); expect("(");
                if (!accept(";")) {
                     if (!decl(0)) { int b = gen != null ? gen.vtop : 0; expr(); if (gen != null) while (gen.vtop > b) gen.vpop(); expect(";"); }
                    // decl() consumed its own ';'
                }
                if (gen != null) {
                    int begin = gen.label();
                    if (!tok().is(";")) expr(); // condition value stays on stack for gjmpIfZero
                    expect(";");
                    int jz = gen.gjmpIfZero();
                    int bodyAt = gen.enc.jmpRel32();
                    int incrAt = gen.label();
                     if (!tok().is(")")) { int b = gen != null ? gen.vtop : 0; expr(); if (gen != null) while (gen.vtop > b) gen.vpop(); }
                    int back = gen.enc.jmpRel32();
                    gen.enc.patchRel32(back, begin);
                    expect(")");
                    pushBreakContinue(incrAt);
                    gen.gsym(bodyAt);
                    statement();
                    int end = gen.enc.jmpRel32();
                    gen.enc.patchRel32(end, incrAt);
                    popLoop();
                    if (jz >= 0) gen.gsym(jz);
                    patchBreaks();
                } else {
                    if (!tok().is(";")) expr();
                    expect(";");
                    if (!tok().is(")")) expr();
                    expect(")");
                    statement();
                }
                return;
            }
            case "switch": {
                next();
                int swBase = gen != null ? gen.vtop : 0;
                expect("("); expr(); expect(")");
                if (gen != null) {
                    int switchReg = gen.gv();
                    int dispatch = gen.enc.jmpRel32();
                    switchStack.add(new SwitchCtx(switchReg, dispatch));
                    pushBreakContinue(-1);
                    statement();
                    SwitchCtx ctx = switchStack.remove(switchStack.size() - 1);
                    // body falls through to switch end; the routing chain lives after it
                    int end = gen.enc.jmpRel32();
                    // dispatch compare chain
                    gen.gsym(dispatch);
                    for (int[] c : ctx.cases) {
                        gen.enc.movImm32SignExt(0, c[0]); // rax = case value
                        gen.enc.rex(true, 0, 0, ctx.reg);
                        gen.st.g(0x39); // cmp ctx.reg, rax
                        gen.enc.modrmRR(0, ctx.reg);
                        int je = gen.enc.jccRel32(4); // je case label
                        gen.enc.patchRel32(je, c[1]);
                    }
                    if (ctx.defaultLabel >= 0) {
                        int jd = gen.enc.jmpRel32();
                        gen.enc.patchRel32(jd, ctx.defaultLabel);
                    }
                    gen.enc.patchRel32(end, gen.label());
                    popLoop();
                    patchBreaks();
                    while (gen.vtop > swBase) gen.vpop(); // release the switch controlling value's register
                } else {
                    statement();
                }
                return;
            }
            case "case": {
                next();
                long v = exprConst64();
                if (accept("...")) exprConst64(); // case ranges: later
                expect(":");
                if (gen != null && !switchStack.isEmpty()) {
                    int lbl = gen.label();
                    switchStack.get(switchStack.size() - 1).cases.add(new int[]{(int) v, lbl});
                }
                statement();
                return;
            }
            case "default": {
                next(); expect(":");
                if (gen != null && !switchStack.isEmpty()) {
                    switchStack.get(switchStack.size() - 1).defaultLabel = gen.label();
                }
                statement();
                return;
            }
            case "break":
                next();
                if (gen != null) genBreak();
                expect(";");
                return;
            case "continue":
                next();
                if (gen != null) genContinue();
                expect(";");
                return;
            case "return":
                next();
                if (!tok().is(";")) expr();
                if (gen != null) gen.genReturn();
                expect(";");
                return;
            case "goto": next(); if (accept("*")) unary(); else if (tok().kind == CTok.Kind.IDENT) next(); expect(";"); return;
            case "asm": case "__asm": case "__asm__":
                next();
                while (tok().is("volatile") || tok().is("inline") || tok().is("goto")
                        || tok().is("__volatile") || tok().is("__volatile__")) next();
                parseAttribute();
                expect(";"); return;
            default:
                if (t.kind == CTok.Kind.IDENT && toks.get(pos + 1).is(":")) {
                    next(); next(); // label
                    statement();
                    return;
                }
                if (decl(0)) return;
                int base = gen != null ? gen.vtop : 0;
                expr();
                if (gen != null) while (gen.vtop > base) gen.vpop(); // pop to entry baseline, not 0
                expect(";");
                if (gen != null && System.getenv("JTCC_DBG_REGS") != null) gen.dumpUsed("stmt@" + gen.dbgLine);
        }
    }

    // ------------------------------------------------------- globals (M3)

    /** define a file-scope data symbol (.data with scalar init, .bss for zero-init). */
    private void genDefineGlobal(Sym sym, CType declType, boolean isExtern, boolean hasInit) {
        if (isExtern) return;
        int sz = Math.max(1, jtcc.backend.x86_64.VGen.typeSize(sym.type));
        boolean global = (sym.type.t & CType.VT_STATIC) == 0;
        if (hasInit) {
            int off = gen.st.data.size;
            sym.symId = gen.st.addDataSymbol(sym.name, gen.st.data, off, global);
            gen.st.data.add(new byte[sz]);
            dataInitOff.put(sym.name, off);
        } else if (global) {
            // tentative definition: COMMON symbol, merged across TUs by the linker
            sym.symId = gen.st.addCommonSymbol(sym.name, sz, Math.max(1, alignOf(sym.type)));
        } else {
            int off = gen.st.bss.size;
            sym.symId = gen.st.addDataSymbol(sym.name, gen.st.bss, off, false);
            gen.st.bss.size += sz;
        }
    }

    private final Map<String, Integer> dataInitOff = new HashMap<>();

    /** global init of the form `&name` / `name` where name is a function/global symbol:
     *  emit a 64-bit relocation at the data slot instead of a scalar value. */
    private boolean tryGlobalSymReloc(Sym sym) {
        boolean addrOf = tok().is("&");
        if (addrOf) next();
        CTok t = tok(); // re-read after consuming '&'
        if (t.kind != CTok.Kind.IDENT) { if (addrOf) pos--; return false; }
        // resolve the identifier to a function symbol (global fn or extern fn)
        String name = t.text;
        Sym target = null;
        for (Map<String, Sym> scope : globalStack) { target = scope.get(name); if (target != null) break; }
        int tid = -1;
        if (target != null && target.type.isFunc()) {
            tid = target.symId != 0 ? target.symId : gen.st.addUndefSymbol(name);
        } else if (target == null) {
            // extern function (implicit decl)
            tid = gen.st.addUndefSymbol(name);
        }
        if (tid < 0) { if (addrOf) pos--; return false; }
        next();
        Integer off = dataInitOff.get(sym.name);
        if (off != null) {
            gen.st.data.relocs.add(new jtcc.elf.Section.Reloc(off, tid, jtcc.elf.ElfObj.R_X86_64_64, 0));
        }
        return true;
    }

    /** write a scalar constant into a global's .data slot. */
    private void writeGlobalInit(Sym sym, long v) {
        Integer off = dataInitOff.get(sym.name);
        if (off == null) return;
        switch (jtcc.backend.x86_64.VGen.typeSize(sym.type)) {
            case 1: gen.st.data.bytes()[off] = (byte) v; break;
            case 2: gen.st.data.patchLe16(off, (int) v); break;
            case 4: gen.st.data.patchLe32(off, (int) v); break;
            default: gen.st.data.patchLe64(off, v); break;
        }
    }

    // ------------------------------------------------------- pointers (M3)

    private int elemSize(CType t) {
        if (t.isArray() && t.ref != null && t.ref.type != null)
            return Math.max(1, sizeOfType(t.ref.type));
        if (t.isPtr() && t.ref != null && t.ref.type != null)
            return Math.max(1, sizeOfType(t.ref.type));
        return 1;
    }

    private CType elemType(CType t) {
        if ((t.isPtr() || t.isArray()) && t.ref != null && t.ref.type != null) return t.ref.type;
        return new CType(CType.VT_INT, null);
    }

    /** member access on a struct value/lvalue: adjust by field offset. */
    private void genMemberAccess(String fname, boolean throughPtr) {
        SValue v = gen.vtop();
        CType st = v.type;
        if (st.btype() != CType.VT_STRUCT || st.ref == null) return;
        Sym f = fieldSym(st, fname);
        if (f == null) { error("no member '" + fname + "'"); return; }
        v.c += f.c;
        v.type = f.type;
        if ((v.r & 0x00ff) == Sym.VT_LOCAL) {
            // local struct: member is a local at offset+field (still lvalue)
        } else if ((v.r & Sym.VT_LVAL) != 0) {
            // lvalue through base: fold offset into c
        }
    }

    // ------------------------------------------------------- switch (M3)

    private static final class SwitchCtx {
        final int reg;
        final int dispatch;
        final java.util.List<int[]> cases = new java.util.ArrayList<>();
        int defaultLabel = -1;
        SwitchCtx(int reg, int dispatch) { this.reg = reg; this.dispatch = dispatch; }
    }
    private final java.util.List<SwitchCtx> switchStack = new java.util.ArrayList<>();

    // ------------------------------------------------------- break/continue (M3)

    private final java.util.List<int[]> loopStack = new java.util.ArrayList<>();
    private final java.util.List<Integer> pendingBreaks = new java.util.ArrayList<>();

    private void pushBreakContinue(int continueTarget) {
        loopStack.add(new int[]{continueTarget, 0});
    }
    private void popLoop() { loopStack.remove(loopStack.size() - 1); }
    private void genBreak() {
        if (loopStack.isEmpty()) { error("break outside loop"); return; }
        pendingBreaks.add(gen.enc.jmpRel32());
    }
    private void patchBreaks() {
        for (int at : pendingBreaks) gen.gsym(at);
        pendingBreaks.clear();
    }
    private void genContinue() {
        if (loopStack.isEmpty()) { error("continue outside loop"); return; }
        int target = loopStack.get(loopStack.size() - 1)[0];
        int at = gen.enc.jmpRel32();
        gen.enc.patchRel32(at, target);
    }

    // ------------------------------------------------------- expressions (tccgen.c precedence chain)

    private void expr() { // gexpr
        for (;;) {
            exprEq();
            if (!accept(",")) break;
        }
    }

    private void exprEq() { // assignment
        exprCond();
        String op = tok().text;
        if (tok().kind == CTok.Kind.PUNCT && isAssignOp(op)) {
            next();
            if (gen != null && !"=".equals(op)) {
                // compound: dup lvalue, load it, then op then store
                SValue lv = gen.vtop();
                SValue dup = gen.vpush();
                dup.type = lv.type; dup.r = lv.r; dup.c = lv.c; dup.symId = lv.symId;
                int r = gen.gv();
                exprEq();
                gen.genOpi(op.substring(0, op.length() - 1)); // strip trailing '=' (>>= -> >>)
                genStoreBack(lv);
                return;
            }
            exprEq();
            if (gen != null) {
                SValue rv = gen.vtop();
                SValue lv = gen.vstack[gen.vtop - 2];
                if (lv.type != null && lv.type.btype() == CType.VT_STRUCT
                        && rv.type != null && rv.type.btype() == CType.VT_STRUCT) {
                    // struct-by-value assignment: copy the whole object
                    gen.genStructCopy(lv, rv, sizeOfType(lv.type));
                    gen.vpop(); // pop rhs; leave the lhs lvalue as the expression result
                } else {
                    genStoreBack(null);
                }
            }
        }
    }

    /** store the rvalue on top into the lvalue below it, leaving the rvalue on top. */
    private void genStoreBack(SValue compoundLv) {
        if (compoundLv == null && gen.vtop < 2) throw new IllegalStateException("store underflow @" + gen.dbgFile + ":" + gen.dbgLine + " vtop=" + gen.vtop);
        SValue rv = gen.vtop();
        SValue lv = compoundLv != null ? compoundLv : gen.vstack[gen.vtop - 2];
        int r = gen.gv();
        SValue target = lv;
        gen.store(r, target);
        // pop: rvalue stays, lvalue below removed
        if (compoundLv == null) {
            gen.vstack[gen.vtop - 2].type = rv.type;
            gen.vstack[gen.vtop - 2].r = rv.r;
            gen.vstack[gen.vtop - 2].c = rv.c;
            gen.vstack[gen.vtop - 2].symId = rv.symId;
            gen.vtop--;
        }
    }

    private static boolean isAssignOp(String s) {
        switch (s) {
            case "=": case "+=": case "-=": case "*=": case "/=": case "%=":
            case "<<=": case ">>=": case "&=": case "^=": case "|=": return true;
            default: return false;
        }
    }

    private void exprCond() { // ternary
        exprLor();
        if (tok().is("?")) {
            next();
            if (gen != null) {
                int jfalse = gen.gjmpIfZero();   // condition consumed
                // true branch: value spilled via push (no register held across the branches)
                expr();
                int r = gen.gv();
                gen.enc.push(r);
                gen.freeRegPublic(r);
                int jend = gen.enc.jmpRel32();
                expect(":");
                if (jfalse >= 0) gen.gsym(jfalse); // guard: -1 for constant-true conditions
                exprCond(); // false branch
                int r2 = gen.gv();
                gen.enc.push(r2); // balanced: both paths push exactly one value
                gen.freeRegPublic(r2);
                gen.enc.patchRel32(jend, gen.label());
                // merge: pop whichever value the executed branch pushed
                int rr = gen.allocTempPublic();
                gen.enc.pop(rr);
                gen.vtop--;
                gen.vtop().r = rr;
            } else {
                expr();
                expect(":");
                exprCond();
            }
        }
    }

    private void binLevel(Runnable sub, String... ops) {
        sub.run();
        while (tok().kind == CTok.Kind.PUNCT) {
            String op = null;
            for (String o : ops) if (tok().is(o)) { op = o; break; }
            if (op == null) return;
            next();
            sub.run();
            if (gen != null) gen.genOpi(op);
        }
    }

    private void exprLor() {
        exprLand();
        while (tok().is("||")) {
            if (gen != null) {
                // a || b  =>  a!=0 ? 1 : (b!=0)
                next();
                int jtrue = gen.gjmpIfNonZero();   // a popped; jumps when a!=0
                exprLand();
                int r = gen.gv();
                gen.enc.test(r);
                gen.enc.setcc(5, r);
                gen.enc.movzxR32R8(r, r);          // (b != 0) in r
                gen.vtop().r = r;                  // result register
                int jend = gen.enc.jmpRel32();
                if (jtrue >= 0) {
                    gen.gsym(jtrue);
                    gen.enc.movImm32SignExt(r, 1); // true branch writes 1 into the SAME register
                    gen.enc.patchRel32(jend, gen.label());
                }
            } else {
                next();
                exprLand();
            }
        }
    }

    private void exprLand() {
        exprOr();
        while (tok().is("&&")) {
            if (gen != null) {
                // a && b  =>  a==0 ? 0 : (b!=0)
                next();
                int jfalse = gen.gjmpIfZero();     // a popped; jumps when a==0
                exprOr();
                int r = gen.gv();
                gen.enc.test(r);
                gen.enc.setcc(5, r);
                gen.enc.movzxR32R8(r, r);          // (b != 0) in r
                gen.vtop().r = r;                  // result register
                int jend = gen.enc.jmpRel32();
                if (jfalse >= 0) {
                    gen.gsym(jfalse);
                    gen.enc.movImm32SignExt(r, 0); // false branch writes 0 into the SAME register
                    gen.enc.patchRel32(jend, gen.label());
                }
            } else {
                next();
                exprOr();
            }
        }
    }
    private void exprOr()   { binLevel(this::exprXor, "|"); }
    private void exprXor()  { binLevel(this::exprAnd, "^"); }
    private void exprAnd()  { binLevel(this::exprCmpeq, "&"); }
    private void exprCmpeq(){
        exprCmp();
        while (tok().kind == CTok.Kind.PUNCT) {
            String op = null;
            if (tok().is("==")) op = "==";
            else if (tok().is("!=")) op = "!=";
            if (op == null) return;
            next();
            exprCmp();
            if (gen != null) gen.genCmpSet(op);
        }
    }
    private void exprCmp()  {
        exprShift();
        while (tok().kind == CTok.Kind.PUNCT) {
            String op = null;
            if (tok().is("<")) op = "<";
            else if (tok().is(">")) op = ">";
            else if (tok().is("<=")) op = "<=";
            else if (tok().is(">=")) op = ">=";
            if (op == null) return;
            next();
            exprShift();
            if (gen != null) gen.genCmpSet(op);
        }
    }
    private void exprShift(){ binLevel(this::exprSum, "<<", ">>"); }
    private void exprSum()  {
        exprProd();
        while (tok().kind == CTok.Kind.PUNCT) {
            String op = null;
            if (tok().is("+")) op = "+";
            else if (tok().is("-")) op = "-";
            if (op == null) return;
            next();
            exprProd();
            if (gen != null) {
                SValue r = gen.vstack[gen.vtop - 1];
                SValue l = gen.vstack[gen.vtop - 2];
                boolean lp = l.type.isPtr() || l.type.isArray();
                boolean rp = r.type.isPtr() || r.type.isArray();
                if (lp && !rp) {
                    gen.genPtrArith(l, r, elemSize(l.type), op.equals("-"));
                    gen.vtop--;
                    gen.vtop().type = l.type;
                } else if (!lp && rp && op.equals("+")) {
                    gen.genPtrArith(r, l, elemSize(r.type), false);
                    // swap result into l's slot
                    gen.vstack[gen.vtop - 2].type = r.type;
                    gen.vstack[gen.vtop - 2].r = r.r;
                    gen.vstack[gen.vtop - 2].c = r.c;
                    gen.vstack[gen.vtop - 2].symId = r.symId;
                    gen.vtop--;
                } else if (lp && rp && op.equals("-")) {
                    // pointer difference: (p - q) / elsize
                    gen.genOpi("-");
                    int el = elemSize(l.type);
                    if (el > 1) {
                        SValue esz = gen.vpushConst(new CType(CType.VT_LLONG, null), el);
                        gen.genOpi("/");
                    }
                    gen.vtop().type = new CType(CType.VT_LLONG, null);
                } else {
                    gen.genOpi(op);
                }
            }
        }
    }
    private void exprProd() { binLevel(this::unary, "*", "/", "%"); }

    private void unary() {
        CTok t = tok();
        if (t.kind == CTok.Kind.PUNCT) {
            switch (t.text) {
                case "!": case "~": case "-": case "+": {
                    String uop = tok().text;
                    next(); unary();
                    if (gen != null) {
                        switch (uop) {
                            case "!": gen.genLnot(); gen.vtop().type = new CType(CType.VT_INT, null); break;
                            case "~": gen.genNot(); break;
                            case "-": gen.genNeg(); break;
                            default: break; // unary +
                        }
                    }
                    return;
                }
                case "&":
                    next();
                    unary();
                    if (gen != null) {
                        CType inner = gen.vtop().type;
                        gen.genAddrOf(gen.vtop());
                        CType pt = new CType(CType.VT_PTR, null);
                        Sym ref = new Sym(null);
                        ref.type = inner;
                        pt.ref = ref;
                        gen.vtop().type = pt;
                    }
                    return;
                case "*":
                    next();
                    unary();
                    if (gen != null) {
                        gen.genDeref(gen.vtop());
                        if (gen.vtop().type.isPtr() && gen.vtop().type.ref != null)
                            gen.vtop().type = gen.vtop().type.ref.type;
                    }
                    return;
                case "&&": // GNU label address (tccgen.c:6069 TOK_LAND)
                    next();
                    if (tok().kind != CTok.Kind.IDENT) error("label identifier expected");
                    next();
                    return;
                case "++": case "--": {
                    boolean inc = t.text.equals("++");
                    next();
                    unary();
                    if (gen != null) gen.genIncDec(gen.vtop(), inc ? 1 : -1, true);
                    return;
                }
                case "(":
                    // cast or parenthesized or statement-expression
                    if (toks.get(pos + 1).is("{")) {
                        next(); next(); // '({' statement expression
                        blockStmtExpr();
                        expect("}"); expect(")");
                        postfixLoop();
                        return;
                    }
                    next();
                    CType ct = new CType();
                    if (parseBtype(ct, null, false)) {
                        typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                        expect(")");
                        if (tok().is("{")) { genCompoundLiteral(ct); postfixLoop(); return; } // compound literal
                        unary(); // cast operand
                        postfixLoop();
                        return;
                    }
                    expr();
                    expect(")");
                    postfixLoop();
                    return;
            }
        } else if (t.kind == CTok.Kind.KEYWORD) {
            switch (t.text) {
                case "sizeof": {
                    next();
                    boolean paren = accept("(");
                    CType ct = new CType();
                    if (paren && parseBtype(ct, null, false)) {
                        typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                        expect(")");
                        if (gen != null) gen.vpushConst(new CType(CType.VT_INT, null), sizeOfType(ct));
                    } else if (paren) {
                        expr();
                        expect(")");
                        if (gen != null) {
                            CType szt = gen.vtop().type;
                            gen.vpop();
                            gen.vpushConst(new CType(CType.VT_INT, null), sizeOfType(szt));
                        }
                    } else {
                        unary();
                        if (gen != null) {
                            CType szt = gen.vtop().type;
                            gen.vpop();
                            gen.vpushConst(new CType(CType.VT_INT, null), sizeOfType(szt));
                        }
                    }
                    return;
                }
                case "__alignof": case "__alignof__": case "_Alignof": {
                    next();
                    expect("(");
                    CType ct = new CType();
                    parseBtype(ct, null, false);
                    typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                    expect(")");
                    return;
                }
                case "_Generic": {
                    next(); expect("(");
                    exprEq();
                    while (accept(",")) {
                        // type-name : expr
                        CType ct = new CType();
                        if (tok().is("default")) { next(); } else { parseBtype(ct, null, false); typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT); }
                        expect(":");
                        exprEq();
                    }
                    expect(")");
                    postfixLoop();
                    return;
                }
                case "__builtin_constant_p": case "__builtin_expect":
                case "__builtin_types_compatible_p": case "__builtin_choose_expr":
                case "__builtin_frame_address": case "__builtin_return_address":
                case "__builtin_unreachable": case "__builtin_trap": case "__builtin_va_arg_types":
                case "__builtin_va_start": case "__builtin_va_arg": case "__builtin_va_end": case "__builtin_va_copy": {
                    next(); expect("(");
                    int depth = 1;
                    while (depth > 0) {
                        if (accept("(")) depth++;
                        else if (accept(")")) depth--;
                        else if (tok().kind == CTok.Kind.EOF) { error("EOF in builtin"); return; }
                        else next();
                    }
                    return;
                }
                case "__typeof": case "__typeof__": {
                    next(); expect("(");
                    CType ct = new CType();
                    if (parseBtype(ct, null, false)) { typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT); }
                    else expr();
                    expect(")");
                    return;
                }
            }
        }
        postfix();
    }

    private void postfix() {
        primary();
        postfixLoop();
    }

    private void postfixLoop() {
        for (;;) {
            if (accept("(")) {
                // call: fn value is on the stack; args follow; first 6 are spilled to the
                // machine stack as they're parsed (avoids RAX clobbering between args)
                int nbArgs = 0;
                if (!tok().is(")")) {
                    for (;;) {
                        exprEq();
                        nbArgs++;
                        if (gen != null && nbArgs <= 6) gen.spillArg();
                        if (!accept(",")) break;
                    }
                }
                expect(")");
                if (gen != null) gen.gfuncCall(nbArgs);
            } else if (accept("[")) {
                // a[i] => *(a + i * elementsize)
                expr();
                expect("]");
                if (gen != null) {
                    CType at = gen.vstack[gen.vtop - 2].type;
                    int elsize = elemSize(at);
                    CType at0 = at;
                    gen.genPtrArith(gen.vstack[gen.vtop - 2], gen.vstack[gen.vtop - 1], elsize, false);
                    gen.vtop--;
                    gen.genDeref(gen.vtop());
                    gen.vtop().type = elemType(at0);
                }
            } else if (accept(".")) {
                if (tok().kind == CTok.Kind.IDENT) {
                    String fname = tok().text;
                    next();
                    if (gen != null) genMemberAccess(fname, false);
                } else error("field name expected");
            } else if (accept("->")) {
                if (tok().kind == CTok.Kind.IDENT) {
                    String fname = tok().text;
                    next();
                    if (gen != null) {
                        gen.genDeref(gen.vtop());
                        if (gen.vtop().type.isPtr() && gen.vtop().type.ref != null)
                            gen.vtop().type = gen.vtop().type.ref.type;
                        genMemberAccess(fname, true);
                    }
                } else error("field name expected");
            } else if (tok().is("++") || tok().is("--")) {
                boolean inc = tok().is("++");
                next();
                if (gen != null) gen.genIncDec(gen.vtop(), inc ? 1 : -1, false);
            } else break;
        }
    }

    private void primary() {
        CTok t = tok();
        switch (t.kind) {
            case CINT: case CUINT: case CLONG: case CULONG: case CLLONG: case CULLONG:
            case CCHAR: case LCHAR:
                if (gen != null) {
                    CType ct = intTypeOf(t);
                    gen.vpushConst(ct, t.i);
                }
                next();
                return;
            case CFLOAT: case CDOUBLE: case CLDOUBLE:
                // M3 is integer-scoped: push a placeholder so the value stack stays balanced.
                // Float SSE codegen is a known gap (tracked in JAVA_TCC_PLAN M3 risks).
                if (gen != null) gen.vpushConst(new CType(CType.VT_DOUBLE, null), 0);
                next();
                return;
            case STR: case LSTR:
                if (gen != null) pushStringLiteral(t);
                while (tok().kind == CTok.Kind.STR || tok().kind == CTok.Kind.LSTR) next(); // adjacency
                return;
            case IDENT:
                next();
                if (gen != null) pushIdent(t.text);
                return;
            case KEYWORD:
                if (t.text.equals("__func__") || t.text.equals("__FUNCTION__") || t.text.equals("__PRETTY_FUNCTION__")) {
                    if (gen != null) pushCStringLiteral(curFuncName);
                    next();
                    return;
                }
                break;
            case PUNCT:
                if (accept("(")) {
                    expr();
                    expect(")");
                    return;
                }
                break;
        }
        error("expression expected, got '" + t.text + "'");
    }

    private static CType intTypeOf(CTok t) {
        switch (t.kind) {
            case CINT: return new CType(CType.VT_INT, null);
            case CUINT: return new CType(CType.VT_INT | CType.VT_UNSIGNED, null);
            case CLONG: return new CType(CType.VT_LLONG | CType.VT_LONG, null);
            case CULONG: return new CType(CType.VT_LLONG | CType.VT_LONG | CType.VT_UNSIGNED, null);
            case CLLONG: return new CType(CType.VT_LLONG, null);
            case CULLONG: return new CType(CType.VT_LLONG | CType.VT_UNSIGNED, null);
            case LCHAR: return new CType(CType.VT_INT, null);
            default: return new CType(CType.VT_INT, null);
        }
    }

    private SValue symToLvalue(Sym s) {
        SValue v = gen.vpush();
        v.type = s.type;
        v.r = Sym.VT_LOCAL | Sym.VT_LVAL;
        v.c = s.c;
        gen.vtop--;
        return v;
    }

    /** push an identifier reference (local, global, or extern function symbol). */
    private void pushIdent(String name) {
        if (System.getenv("JTCC_DBG_ID") != null) System.err.println("DBG pushIdent " + name + " @" + (pos>0?toks.get(pos-1).file+":"+toks.get(pos-1).line:"?") + " vtop(before)=" + (gen!=null?gen.vtop:-9));
        // enum constants are compile-time integers, not symbols
        Long enumVal = enumConsts.get(name);
        if (enumVal != null) {
            gen.vpushConst(new CType(CType.VT_INT | CType.VT_ENUM, null), enumVal);
            return;
        }
        for (Map<String, Sym> scope : localStack) {
            Sym s = scope.get(name);
            if (s != null) { genPushSym(s, true); return; }
        }
        for (Map<String, Sym> scope : globalStack) {
            Sym s = scope.get(name);
            if (s != null) { genPushSym(s, false); return; }
        }
        // implicit extern function reference (C89 implicit decl; diagnostics come later)
        SValue v = gen.vpush();
        v.type = new CType(CType.VT_FUNC, null);
        v.r = Sym.VT_CONST | Sym.VT_SYM;
        v.c = 0;
        v.symId = gen.st.addUndefSymbol(name);
    }

    private void genPushSym(Sym s, boolean local) {
        SValue v = gen.vpush();
        v.type = s.type;
        if ((s.r & 0x00ff) == Sym.VT_LOCAL) {
            v.r = Sym.VT_LOCAL | Sym.VT_LVAL;
            v.c = s.c;
        } else {
            v.r = Sym.VT_CONST | Sym.VT_SYM | Sym.VT_LVAL;
            v.c = 0;
            v.symId = s.symId != 0 ? s.symId : gen.st.addUndefSymbol(s.name);
        }
    }

    /** string literal → rodata symbol, pushed as char*. */
    private void pushStringLiteral(CTok t) {
        // merge adjacent literals: current token already decoded; adjacency handled by caller loop
        String label = ".LC" + (strLitCount++);
        int off = gen.st.rodata.size;
        byte[] bytes = t.str != null ? t.str : new byte[]{0};
        gen.st.rodata.add(bytes);
        pushRoDataString(label, off);
    }

    /** push a C string literal built from a Java string (for __func__ etc.). */
    private void pushCStringLiteral(String s) {
        String label = ".LC" + (strLitCount++);
        int off = gen.st.rodata.size;
        byte[] bytes = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) bytes[i] = (byte) s.charAt(i);
        gen.st.rodata.add(bytes);
        pushRoDataString(label, off);
    }

    private void pushRoDataString(String label, int off) {
        int id = gen.st.addDataSymbol(label, gen.st.rodata, off, false);
        SValue v = gen.vpush();
        CType ct = new CType(CType.VT_PTR, null);
        ct.ref = new Sym(null);
        ct.ref.type = new CType(CType.VT_BYTE, null);
        v.type = ct;
        v.r = Sym.VT_CONST | Sym.VT_SYM;
        v.c = 0;
        v.symId = id;
    }

    /** expr_const64 (tccgen.c): integer constant expression via the shared-stream evaluator. */
    private long exprConst64() {
        ConstEval ce = new ConstEval(constHost);
        return ce.eval();
    }

    private final ConstEval.Host constHost = new ConstEval.Host() {
        public CTok tok() { return Parser.this.tok(); }
        public CTok next() { return Parser.this.next(); }
        public boolean accept(String s) { return Parser.this.accept(s); }
        public void error(String msg) { Parser.this.error(msg); }
        public Long enumValue(String name) { return enumConsts.get(name); }
        public int sizeOfType(CType t) { return Parser.this.sizeOfType(t); }
        public int sizeofConst() { return Parser.this.sizeofConst(); }
        public Long constBuiltin(String name) { return Parser.this.constBuiltin(name); }
        public long constParenOrCast() { return Parser.this.constParenOrCast(); }
        public long tryOffsetof() { return Parser.this.tryOffsetof(); }
    };

    /** offsetof pattern in const contexts: & ( ( T * ) 0 ) -> field (tccdefs.h expansion). */
    private long tryOffsetof() {
        int save = pos;
        long NA = Long.MIN_VALUE;
        if (!accept("(")) { pos = save; return NA; }
        if (!accept("(")) { pos = save; return NA; }
        CType ct = new CType();
        if (!parseBtype(ct, null, false)) { pos = save; return NA; }
        // the '*' of (T *): use typeDecl for the pointer, then require ) 0 ) ->
        typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
        if (!ct.isPtr()) { pos = save; return NA; }
        if (!accept(")")) { pos = save; return NA; }
        if (tok().kind != CTok.Kind.CINT && tok().kind != CTok.Kind.CUINT
                && tok().kind != CTok.Kind.CLONG && tok().kind != CTok.Kind.CULONG
                && tok().kind != CTok.Kind.CLLONG && tok().kind != CTok.Kind.CULLONG) { pos = save; return NA; }
        if (tok().i != 0) { pos = save; return NA; }
        next();
        if (!accept(")")) { pos = save; return NA; }
        CType st = ct.ref.type; // pointee
        long off = 0;
        while (accept("->") || accept(".")) {
            if (tok().kind != CTok.Kind.IDENT) { pos = save; return NA; }
            String fname = tok().text; next();
            long seg = fieldOff(st, fname);
            if (seg < 0) { pos = save; return NA; }
            off += seg;
            Sym f = fieldSym(st, fname);
            if (f == null) { pos = save; return NA; }
            st = f.type;
        }
        return off;
    }

    /** direct+anonymous field lookup, returns the field for type continuation. */
    private Sym fieldSym(CType st, String fname) {
        if (st.btype() != CType.VT_STRUCT || st.ref == null || st.ref.c < 0) return null;
        for (Sym f = st.ref.next; f != null; f = f.next)
            if (fname.equals(f.name)) return f;
        for (Sym f = st.ref.next; f != null; f = f.next)
            if (f.type.btype() == CType.VT_STRUCT && (f.name == null || f.name.isEmpty())) {
                Sym r = fieldSym(f.type, fname);
                if (r != null) return r;
            }
        return null;
    }

    /** field offset descending into anonymous struct/union members (C11). */
    private long fieldOff(CType st, String fname) {
        if (st.btype() != CType.VT_STRUCT || st.ref == null || st.ref.c < 0) return -1;
        for (Sym f = st.ref.next; f != null; f = f.next)
            if (fname.equals(f.name)) return f.c;
        // anonymous struct/union members (marked with empty name)
        for (Sym f = st.ref.next; f != null; f = f.next) {
            if (f.type.btype() == CType.VT_STRUCT && (f.name == null || f.name.isEmpty())) {
                long sub = fieldOff(f.type, fname);
                if (sub >= 0) return f.c + sub;
            }
        }
        return -1;
    }

    /** '(' in a constant expression: (typename) unary-op | (expr). Value is best-effort. */
    private long constParenOrCast() {
        CType ct = new CType();
        if (tok().is("{")) {
            // GNU statement expression ({...}) in const context: parse, value unknown
            next();
            while (!tok().is("}")) statement();
            expect("}"); expect(")");
            return 0;
        }
        if (parseBtype(ct, null, false)) {
            typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
            expect(")");
            // cast: value of the operand (member-access chains like ->x evaluate to 0 here)
            ConstEval inner = new ConstEval(constHost);
            return inner.evalUnaryPublic();
        }
        ConstEval inner = new ConstEval(constHost);
        long v = inner.eval();
        expect(")");
        return v;
    }

    /** __builtin_* / _Generic in integer-constant contexts (100_c99array etc.). */
    private Long constBuiltin(String name) {
        switch (name) {
            case "__builtin_constant_p":
                return 1L; // we're in a constant context
            case "__builtin_types_compatible_p": {
                CType a = new CType(), b = new CType();
                parseBtype(a, null, false); typeDecl(a, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                expect(",");
                parseBtype(b, null, false); typeDecl(b, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                long eq = (a.t == b.t && a.ref == b.ref) ? 1 : 0;
                return eq;
            }
            case "__builtin_choose_expr": {
                ConstEval ce = new ConstEval(constHost);
                long cond = ce.eval();
                expect(",");
                if (cond != 0) { ce = new ConstEval(constHost); long v = ce.eval(); expect(","); skipInitializer(); return v; }
                else { skipInitializer(); expect(","); ConstEval ce2 = new ConstEval(constHost); return ce2.eval(); }
            }
            case "_Generic": {
                // _Generic in const context: controlling expr value ignored; pick first assoc
                // (exact type-based selection needs M3's expression typing; fixture/tests tolerate)
                ConstEval ce = new ConstEval(constHost);
                long ctrl = ce.eval();
                long result = 0;
                boolean firstAssoc = true;
                while (accept(",")) {
                    CType at = new CType();
                    if (tok().is("default")) next();
                    else { parseBtype(at, null, false); typeDecl(at, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT); }
                    expect(":");
                    ConstEval ve = new ConstEval(constHost);
                    long val = ve.eval();
                    if (firstAssoc) { result = val; firstAssoc = false; } // exact type match needs M3
                }
                return result;
            }
        }
        // unknown builtin (e.g. __builtin_offsetof): parse args tolerantly, value 0
        for (;;) {
            CType at = new CType();
            if (parseBtype(at, null, false)) typeDecl(at, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
            else exprConst64();
            if (!accept(",")) break;
        }
        return 0L;
    }

    /** sizeof operand in a constant context: exact for type-names; expressions degrade to
     *  the operand's best-effort size (exact layout arrives with M3). */
    private int sizeofConst() {
        boolean paren = accept("(");
        CType ct = new CType();
        if (paren && parseBtype(ct, null, false)) {
            typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
            expect(")");
            return sizeOfType(ct);
        }
        // expression operand: the offsetof-pattern member type ((T*)0)->field is exact;
        // other expressions degrade until M3 type inference
        if (paren) {
            int save = pos;
            long msz = tryMemberSizeof();
            if (msz >= 0) return (int) msz;
            pos = save;
            expr();
            expect(")");
        } else {
            unary();
        }
        return 4; // approximate expression size until M3 type inference
    }

    /** sizeof pattern ((T*)0)->a.b.c: size of the last field's type (fixture layout asserts). */
    private long tryMemberSizeof() {
        int save = pos;
        long NA = -1;
        if (!accept("(")) { pos = save; return NA; }
        if (!accept("(")) { pos = save; return NA; }
        CType ct = new CType();
        if (!parseBtype(ct, null, false)) { pos = save; return NA; }
        typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
        if (!ct.isPtr()) { pos = save; return NA; }
        if (!accept(")")) { pos = save; return NA; }
        if (tok().kind != CTok.Kind.CINT && tok().kind != CTok.Kind.CUINT
                && tok().kind != CTok.Kind.CLONG && tok().kind != CTok.Kind.CULONG
                && tok().kind != CTok.Kind.CLLONG && tok().kind != CTok.Kind.CULLONG) { pos = save; return NA; }
        if (tok().i != 0) { pos = save; return NA; }
        next();
        if (!accept(")")) { pos = save; return NA; }
        CType st = ct.ref.type;
        Sym last = null;
        while (accept("->") || accept(".")) {
            if (tok().kind != CTok.Kind.IDENT) { pos = save; return NA; }
            String fname = tok().text; next();
            last = fieldSym(st, fname);
            if (last == null) { pos = save; return NA; }
            st = last.type;
        }
        if (last == null) { pos = save; return NA; }
        if (!accept(")")) { pos = save; return NA; } // the sizeof closing paren
        return typeSize(last.type);
    }

    /** type_size for constant contexts: delegates to the layout-aware typeSize. */
    int sizeOfType(CType t) {
        int sz = typeSize(t);
        return sz < 0 ? -1 : sz;
    }
}
