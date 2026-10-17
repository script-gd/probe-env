package jtcc.cc;

import jtcc.ctok.CTok;

/**
 * Integer constant-expression evaluator (C11 6.6): conditional-expression level only.
 * Drives the parser's shared token stream. Values follow tcc's (u)intmax-ish arithmetic:
 * 64-bit with unsigned tracking. sizeof on scalars/pointers/arrays is exact; on
 * struct/union without layout (M3) yields -1 (callers must not assert-fail on it in M2).
 */
public final class ConstEval {
    public interface Host {
        CTok tok();
        CTok next();
        boolean accept(String s);
        void error(String msg);
        Long enumValue(String name); // enum constant lookup, null if unknown
        int sizeOfType(CType t);     // -1 if unknown layout
        int sizeofConst();           // sizeof operand parsing lives in the Parser
        Long constBuiltin(String name); // __builtin_* / _Generic in const contexts, null if N/A
        long constParenOrCast();     // '(' in const ctx: cast vs parenthesized expr (Parser)
    }

    private final Host h;
    public ConstEval(Host h) { this.h = h; }

    private long v;      // value of the last-parsed primary (for sizeof-expr probing)
    private boolean unsigned_;

    public long eval() { // conditional expression
        long a = lor();
        if (h.accept("?")) {
            eval(); // then-branch (any value; grammar-level)
            h.accept(":");
            long b = eval();
            return a != 0 ? vOr(b) : b;
        }
        return a;
    }
    private long vOr(long fallback) { return fallback; }

    private interface L { long run(); }
    private long bin(L sub, String... ops) {
        long a = sub.run();
        while (true) {
            String op = null;
            for (String o : ops) if (h.tok().is(o)) { op = o; break; }
            if (op == null) return a;
            h.next();
            long b = sub.run();
            a = apply(op, a, b);
        }
    }

    private static long apply(String op, long a, long b) {
        switch (op) {
            case "||": return (a != 0 || b != 0) ? 1 : 0;
            case "&&": return (a != 0 && b != 0) ? 1 : 0;
            case "|": return a | b;
            case "^": return a ^ b;
            case "&": return a & b;
            case "==": return a == b ? 1 : 0;
            case "!=": return a != b ? 1 : 0;
            case "<": return a < b ? 1 : 0;
            case ">": return a > b ? 1 : 0;
            case "<=": return a <= b ? 1 : 0;
            case ">=": return a >= b ? 1 : 0;
            case "<<": return a << (b & 63);
            case ">>": return a >> (b & 63);
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/": return b == 0 ? 0 : a / b;
            case "%": return b == 0 ? 0 : a % b;
            default: throw new IllegalStateException(op);
        }
    }

    private long lor()   { return bin(this::land, "||"); }
    private long land()  { return bin(this::or, "&&"); }
    private long or()    { return bin(this::xor, "|"); }
    private long xor()   { return bin(this::and, "^"); }
    private long and()   { return bin(this::eq, "&"); }
    private long eq()    { return bin(this::rel, "==", "!="); }
    private long rel()   { return bin(this::shift, "<", ">", "<=", ">="); }
    private long shift() { return bin(this::sum, "<<", ">>"); }
    private long sum()   { return bin(this::prod, "+", "-"); }
    private long prod()  { return bin(this::unary, "*", "/", "%"); }

    public long evalUnaryPublic() { return unary(); }

    private long unary() {
        CTok t = h.tok();
        long v;
        if (t.is("!")) { h.next(); v = unary() == 0 ? 1 : 0; }
        else if (t.is("~")) { h.next(); v = ~unary(); }
        else if (t.is("-")) { h.next(); v = -unary(); }
        else if (t.is("+")) { h.next(); v = unary(); }
        else if (t.is("&") || t.is("*") || t.is("++") || t.is("--")) { h.next(); unary(); v = 0; } // value unknown in const ctx
        else if (t.is("(")) {
            h.next();
            v = h.constParenOrCast();
        }
        else if (t.kind == CTok.Kind.KEYWORD && t.text.equals("sizeof")) {
            h.next();
            v = h.sizeofConst();
        }
        else v = primary();
        // postfix tail: parse and degrade (member access/call/index have no const value here)
        for (;;) {
            if (h.tok().is("(")) {
                h.next();
                int depth = 1;
                while (depth > 0) {
                    if (h.accept("(")) depth++;
                    else if (h.accept(")")) depth--;
                    else h.next();
                }
                v = 0;
            } else if (h.accept("[")) {
                eval();
                h.accept("]");
                v = 0;
            } else if (h.tok().is(".") || h.tok().is("->")) {
                h.next();
                if (h.tok().kind == CTok.Kind.IDENT) h.next();
                v = 0;
            } else if (h.accept("++") || h.accept("--")) {
                // postfix inc/dec
            } else break;
        }
        return v;
    }

    private long unary0() { return 0; }

    private long primary() {
        CTok t = h.tok();
        switch (t.kind) {
            case CINT: case CUINT: h.next(); return t.i;
            case CLONG: case CULONG: case CLLONG: case CULLONG: h.next(); return t.i;
            case CCHAR: case LCHAR: h.next(); return t.i;
            case IDENT: {
                h.next();
                Long ev = h.enumValue(t.text);
                if (ev != null) return ev;
                return 0; // unknown identifier in const context (tcc errors; M2 tolerant)
            }
            case KEYWORD: {
                if (t.text.startsWith("__builtin_") || t.text.equals("_Generic")) {
                    h.next();
                    if (h.accept("(")) {
                        Long v = h.constBuiltin(t.text);
                        h.accept(")");
                        return v == null ? 0 : v;
                    }
                    return 0;
                }
                h.next();
                return 0;
            }
            case PUNCT:
                if (t.is("(")) {
                    h.next();
                    return h.constParenOrCast();
                }
                break;
        }
        h.error("constant expected, got '" + t.text + "'");
        return 0;
    }
}
