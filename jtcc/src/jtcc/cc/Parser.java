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
    private final CodeGen gen;

    public interface Diag {
        void error(String file, int line, String msg);
        void warn(String file, int line, String msg);
    }

    // ------------------------------------------------------- symbol tables (tccgen.c stacks)
    private final Deque<Map<String, Sym>> globalStack = new ArrayDeque<>();
    private final Deque<Map<String, Sym>> localStack = new ArrayDeque<>();
    private final Map<String, Sym> typedefs = new HashMap<>();      // typedef names (global for now)
    private final Map<String, Sym> structTags = new HashMap<>();    // struct/union/enum tags
    private final List<SValue> vstack = new ArrayList<>();
    private int vtop; // index of top

    public Parser(List<CTok> toks, Diag diag, CodeGen gen) {
        this.toks = toks;
        this.diag = diag;
        this.gen = gen;
    }

    // ------------------------------------------------------- token access
    private CTok tok() { return toks.get(pos); }
    private CTok next() { return toks.get(pos++); }
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
        // typedef name?
        CTok t = tok();
        if (t.kind == CTok.Kind.IDENT) {
            Sym td = typedefs.get(t.text);
            if (td != null) {
                next();
                type.t = td.type.t;
                type.ref = td.type.ref;
                return true;
            }
        }
        if (t.kind != CTok.Kind.KEYWORD) return false;
        boolean found = false;
        boolean done = false;
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
                    type.t |= CType.VT_ATOMIC; next(); break;
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
                case "typedef": type.t |= CType.VT_TYPEDEF; next(); break;
                case "extern": type.t |= CType.VT_EXTERN; next(); break;
                case "static": type.t |= CType.VT_STATIC; next(); break;
                case "inline": case "__inline": case "__inline__":
                    type.t |= CType.VT_INLINE; next(); break;
                case "register": case "auto": next(); break;
                case "_Thread_local": case "__thread": next(); break; // TODO TLS storage flag
                case "_Noreturn": case "__noreturn__": next(); break;
                case "__attribute": case "__attribute__":
                    next();
                    parseAttribute();
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
                done = false;
            }
        }
        if (!found) return false;
        // struct/enum set their own btype via refs; only scalar specifiers go through bt
        boolean composite = (type.t & CType.VT_BTYPE) == CType.VT_STRUCT
                || (type.t & CType.VT_STRUCT_MASK) == CType.VT_ENUM;
        if (composite) return true;
        if (bt < 0) bt = CType.VT_INT;
        type.t &= ~(CType.VT_BTYPE | CType.VT_UNSIGNED | CType.VT_LONG);
        type.t |= bt;
        if (bt == CType.VT_INT && isLong) type.t |= CType.VT_LONG;
        if (bt == CType.VT_DOUBLE && isLong) { type.t = (type.t & ~CType.VT_BTYPE) | CType.VT_LDOUBLE; }
        if (signSet && sign) type.t |= CType.VT_UNSIGNED;
        return true;
    }

    private void parseStructRef(CType type, boolean isUnion) {
        // struct [__attribute__((...))] [name] [{ fields }]
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
        CTok t = tok();
        String tag = null;
        if (t.kind == CTok.Kind.IDENT) { tag = t.text; next(); }
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
        if (accept("{")) {
            // TODO(tccgen.c:4476): full struct_decl with fields
            int depth = 1;
            while (depth > 0 && tok().kind != CTok.Kind.EOF) {
                if (accept("{")) depth++;
                else if (accept("}")) depth--;
                else next();
            }
        }
        while (tok().is("__attribute") || tok().is("__attribute__")) { next(); parseAttribute(); }
        Sym ref = structTags.get(tag);
        if (ref == null) { ref = new Sym(tag); structTags.put(tag == null ? "" : tag, ref); }
        type.t &= ~(CType.VT_BTYPE | CType.VT_STRUCT_MASK);
        type.t |= CType.VT_STRUCT | (isUnion ? CType.VT_UNION : 0);
        type.ref = ref;
    }

    private void parseEnumRef(CType type) {
        CTok t = tok();
        if (t.kind == CTok.Kind.IDENT) next();
        if (accept("{")) {
            int depth = 1;
            while (depth > 0 && tok().kind != CTok.Kind.EOF) {
                if (accept("{")) depth++;
                else if (accept("}")) depth--;
                else next();
            }
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
            for (;;) {
                next();
                String k = tok().text;
                if (k.equals("_Atomic")) qualifiers |= CType.VT_ATOMIC;
                else if (k.equals("const") || k.equals("__const") || k.equals("__const__")) qualifiers |= CType.VT_CONSTANT;
                else if (k.equals("volatile") || k.equals("__volatile") || k.equals("__volatile__")) qualifiers |= CType.VT_VOLATILE;
                else if (k.equals("restrict") || k.equals("__restrict") || k.equals("__restrict__")) qualifiers |= CType.VT_RESTRICT;
                else if (k.equals("__attribute") || k.equals("__attribute__")) parseAttribute();
                else break;
            }
            mkPointer(type);
            type.t |= qualifiers;
            if (ret == type)
                ret = pointedType(type); // innermost pointed-to slot
        }

        if (tok().is("(")) {
            // parameter type list for abstract declarators ('int ()'), or nested declarator
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
                    n = exprConst64(); // VLA path TODO(tccgen.c:5205 token replay)
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
            if (tok().kind == CTok.Kind.STR) next();
            expect(")"); expect(";");
            if (v == 0) error("static assertion failed");
            return true;
        }
        CType type = new CType();
        if (!parseBtype(type, null, false)) return false;
        if (accept(";")) return true;
        for (;;) {
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
                if (tok().is("{")) {
                    // function definition
                    parseFunction(sym);
                    return true;
                }
                declareGlobal(sym);
            } else {
                declareGlobal(sym);
                if (accept("=")) {
                    // initializer — parse and discard for now (M3 does codegen)
                    skipInitializer();
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
        // new scope for params+body
        localStack.push(new HashMap<>());
        Sym param = fn.type.ref.next;
        while (param != null) {
            if (param.name != null) localStack.peek().put(param.name, param);
            param = param.next;
        }
        block(0);
        localStack.pop();
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
            expr();
        }
    }

    // ------------------------------------------------------- statements (tccgen.c block())

    private void block(int flags) {
        expect("{");
        while (!tok().is("}")) {
            if (tok().kind == CTok.Kind.EOF) { error("unexpected EOF in block"); return; }
            statement();
        }
        next();
    }

    private void statement() {
        CTok t = tok();
        if (t.kind == CTok.Kind.PUNCT) {
            if (t.is("{")) { block(0); return; }
            if (t.is(";")) { next(); return; }
        }
        switch (t.kind == CTok.Kind.KEYWORD ? t.text : "") {
            case "if":
                next(); expect("("); expr(); expect(")");
                statement();
                if (accept("else")) statement();
                return;
            case "while":
                next(); expect("("); expr(); expect(")");
                statement();
                return;
            case "do":
                next(); statement();
                expect("while"); expect("("); expr(); expect(")"); expect(";");
                return;
            case "for":
                next(); expect("(");
                if (!accept(";")) {
                    if (!decl(0)) { expr(); expect(";"); }
                    // decl() consumed its own ';'
                }
                if (!tok().is(";")) expr();
                expect(";");
                if (!tok().is(")")) expr();
                expect(")");
                statement();
                return;
            case "switch": next(); expect("("); expr(); expect(")"); statement(); return;
            case "case": next(); exprConst64(); if (accept("...")) exprConst64(); expect(":"); statement(); return;
            case "default": next(); expect(":"); statement(); return;
            case "break": next(); expect(";"); return;
            case "continue": next(); expect(";"); return;
            case "return":
                next();
                if (!tok().is(";")) expr();
                expect(";");
                return;
            case "goto": next(); if (tok().kind == CTok.Kind.IDENT) next(); expect(";"); return;
            case "asm": case "__asm": case "__asm__":
                next(); parseAttribute(); if (tok().is("(")) { parseAttribute(); } expect(";"); return;
            default:
                if (t.kind == CTok.Kind.IDENT && toks.get(pos + 1).is(":")) {
                    next(); next(); // label
                    statement();
                    return;
                }
                if (decl(0)) return;
                expr();
                expect(";");
        }
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
            exprEq();
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
        if (accept("?")) {
            expr();
            expect(":");
            exprCond();
        }
    }

    private void binLevel(Runnable sub, String... ops) {
        sub.run();
        while (tok().kind == CTok.Kind.PUNCT) {
            boolean hit = false;
            for (String op : ops) if (tok().is(op)) { hit = true; break; }
            if (!hit) break;
            next();
            sub.run();
        }
    }

    private void exprLor()  { binLevel(this::exprLand, "||"); }
    private void exprLand() { binLevel(this::exprOr, "&&"); }
    private void exprOr()   { binLevel(this::exprXor, "|"); }
    private void exprXor()  { binLevel(this::exprAnd, "^"); }
    private void exprAnd()  { binLevel(this::exprCmpeq, "&"); }
    private void exprCmpeq(){ binLevel(this::exprCmp, "==", "!="); }
    private void exprCmp()  { binLevel(this::exprShift, "<", ">", "<=", ">="); }
    private void exprShift(){ binLevel(this::exprSum, "<<", ">>"); }
    private void exprSum()  { binLevel(this::exprProd, "+", "-"); }
    private void exprProd() { binLevel(this::unary, "*", "/", "%"); }

    private void unary() {
        CTok t = tok();
        if (t.kind == CTok.Kind.PUNCT) {
            switch (t.text) {
                case "!": case "~": case "-": case "+": case "&": case "*":
                    next(); unary(); return;
                case "++": case "--":
                    next(); unary(); return;
                case "(":
                    // cast or parenthesized or statement-expression
                    if (toks.get(pos + 1).is("{")) {
                        next(); next(); // '({' statement expression
                        while (!tok().is("}")) statement();
                        expect("}"); expect(")");
                        postfixLoop();
                        return;
                    }
                    next();
                    CType ct = new CType();
                    if (parseBtype(ct, null, false)) {
                        typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT);
                        expect(")");
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
                    } else {
                        unary();
                        if (paren) { /* it was an expression in parens already consumed */ }
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
                    expr();
                    while (accept(",")) {
                        // type-name : expr
                        CType ct = new CType();
                        if (tok().is("default")) { next(); } else { parseBtype(ct, null, false); typeDecl(ct, new StringBuilder(), TYPE_DIRECT | TYPE_ABSTRACT); }
                        expect(":");
                        exprEq();
                    }
                    expect(")");
                    return;
                }
                case "__builtin_constant_p": case "__builtin_expect":
                case "__builtin_types_compatible_p": case "__builtin_choose_expr":
                case "__builtin_frame_address": case "__builtin_return_address":
                case "__builtin_unreachable": case "__builtin_trap": {
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
                // call
                if (!tok().is(")")) {
                    for (;;) {
                        exprEq();
                        if (!accept(",")) break;
                    }
                }
                expect(")");
            } else if (accept("[")) {
                expr();
                expect("]");
            } else if (accept(".")) {
                if (tok().kind == CTok.Kind.IDENT) next(); else error("field name expected");
            } else if (accept("->")) {
                if (tok().kind == CTok.Kind.IDENT) next(); else error("field name expected");
            } else if (accept("++") || accept("--")) {
                // postfix inc/dec
            } else break;
        }
    }

    private void primary() {
        CTok t = tok();
        switch (t.kind) {
            case CINT: case CUINT: case CLONG: case CULONG: case CLLONG: case CULLONG:
            case CFLOAT: case CDOUBLE: case CLDOUBLE: case CCHAR: case LCHAR:
                next();
                return;
            case STR: case LSTR:
                while (tok().kind == CTok.Kind.STR || tok().kind == CTok.Kind.LSTR) next(); // adjacency
                return;
            case IDENT:
                next();
                return;
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

    /** expr_const64 (tccgen.c:153 area): parse a constant expression, return its value. */
    private long exprConst64() {
        // minimal constant folder for now: parse and evaluate a pure-constant expression
        // (needed for case labels, array sizes, static asserts, enum values)
        ConstEval ce = new ConstEval();
        exprCond(); // parse; TODO: hook with real evaluation once SValue codegen lands (M3)
        return ce.value;
    }

    private final class ConstEval {
        long value;
    }
}
