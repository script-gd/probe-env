package jtcc;

import java.util.ArrayList;
import java.util.List;

/**
 * #if expression evaluation with C11 (u)intmax_t semantics: values track signedness
 * (unsigned suffix, or hex/octal/binary overflow into uintmax_t per tcc's pp rules).
 */
public final class Eval {
    private static final class NV {
        final long v; final boolean u;
        NV(long v, boolean u) { this.v = v; this.u = u; }
    }
    private static NV S(long v) { return new NV(v, false); }

    private final List<Tok> toks;
    private int i;

    private Eval(List<Tok> toks) { this.toks = toks; }

    public static boolean eval(List<Tok> expanded) {
        List<Tok> t = new ArrayList<>(expanded.size());
        for (Tok tok : expanded) {
            if (tok.kind == Tok.Kind.IDENT) {
                t.add(tok.withKindText(Tok.Kind.PPNUM, "0"));
            } else if (tok.kind == Tok.Kind.CHARCONST) {
                t.add(tok.withKindText(Tok.Kind.PPNUM, Long.toString(charValue(tok.text))));
            } else {
                t.add(tok);
            }
        }
        Eval e = new Eval(t);
        return e.ternary().v != 0;
    }

    private Tok peek() { return i < toks.size() ? toks.get(i) : null; }
    private Tok take() { return i < toks.size() ? toks.get(i++) : null; }
    private boolean at(String p) { Tok t = peek(); return t != null && t.isPunct(p); }

    private NV ternary() {
        NV c = or();
        if (at("?")) {
            take();
            NV a = ternary();
            if (at(":")) take();
            NV b = ternary();
            boolean u = a.u || b.u;
            return new NV(c.v != 0 ? a.v : b.v, u);
        }
        return c;
    }
    private NV or()  { NV v = and(); while (at("||")) { take(); NV b = and(); v = S((v.v != 0 || b.v != 0) ? 1 : 0); } return v; }
    private NV and() { NV v = bitor(); while (at("&&")) { take(); NV b = bitor(); v = S((v.v != 0 && b.v != 0) ? 1 : 0); } return v; }
    private NV bitor() { NV v = bitxor(); while (at("|")) { take(); NV b = bitxor(); v = new NV(v.v | b.v, v.u || b.u); } return v; }
    private NV bitxor() { NV v = bitand(); while (at("^")) { take(); NV b = bitand(); v = new NV(v.v ^ b.v, v.u || b.u); } return v; }
    private NV bitand() { NV v = eq(); while (at("&")) { take(); NV b = eq(); v = new NV(v.v & b.v, v.u || b.u); } return v; }
    private NV eq() {
        NV v = rel();
        for (;;) {
            if (at("==")) { take(); NV b = rel(); v = S(v.v == b.v ? 1 : 0); }
            else if (at("!=")) { take(); NV b = rel(); v = S(v.v != b.v ? 1 : 0); }
            else return v;
        }
    }
    private NV rel() {
        NV v = shift();
        for (;;) {
            String op = null;
            if (at("<=")) { take(); op = "<="; } else if (at(">=")) { take(); op = ">="; }
            else if (at("<")) { take(); op = "<"; } else if (at(">")) { take(); op = ">"; }
            else return v;
            NV b = shift();
            boolean u = v.u || b.u;
            boolean r;
            switch (op) {
                case "<":  r = u ? Long.compareUnsigned(v.v, b.v) < 0 : v.v < b.v; break;
                case ">":  r = u ? Long.compareUnsigned(v.v, b.v) > 0 : v.v > b.v; break;
                case "<=": r = u ? Long.compareUnsigned(v.v, b.v) <= 0 : v.v <= b.v; break;
                default:   r = u ? Long.compareUnsigned(v.v, b.v) >= 0 : v.v >= b.v; break;
            }
            v = S(r ? 1 : 0);
        }
    }
    private NV shift() {
        NV v = add();
        for (;;) {
            if (at("<<")) { take(); v = new NV(v.v << (add().v & 63), v.u); }
            else if (at(">>")) { take(); NV b = add(); v = new NV(v.u ? v.v >>> (b.v & 63) : v.v >> (b.v & 63), v.u); }
            else return v;
        }
    }
    private NV add() {
        NV v = mul();
        for (;;) {
            if (at("+")) { take(); NV b = mul(); v = new NV(v.v + b.v, v.u || b.u); }
            else if (at("-")) { take(); NV b = mul(); v = new NV(v.v - b.v, v.u || b.u); }
            else return v;
        }
    }
    private NV mul() {
        NV v = unary();
        for (;;) {
            if (at("*")) { take(); NV b = unary(); v = new NV(v.v * b.v, v.u || b.u); }
            else if (at("/")) { take(); NV b = unary();
                v = new NV(b.v == 0 ? 0 : (v.u || b.u ? Long.divideUnsigned(v.v, b.v) : v.v / b.v), v.u || b.u); }
            else if (at("%")) { take(); NV b = unary();
                v = new NV(b.v == 0 ? 0 : (v.u || b.u ? Long.remainderUnsigned(v.v, b.v) : v.v % b.v), v.u || b.u); }
            else return v;
        }
    }
    private NV unary() {
        if (at("!")) { take(); return S(unary().v == 0 ? 1 : 0); }
        if (at("~")) { take(); NV a = unary(); return new NV(~a.v, a.u); }
        if (at("-")) { take(); NV a = unary(); return new NV(-a.v, a.u); }
        if (at("+")) { take(); return unary(); }
        return primary();
    }
    private NV primary() {
        Tok t = take();
        if (t == null) return S(0);
        if (t.isPunct("(")) {
            NV v = ternary();
            if (at(")")) take();
            return v;
        }
        if (t.kind == Tok.Kind.PPNUM) return numValue(t.text);
        return S(0);
    }

    /** pp-number value with (u)intmax_t typing: U suffix or non-decimal overflow → unsigned. */
    static NV numValue(String s) {
        boolean u = false;
        int end = s.length();
        while (end > 0 && "uUlL".indexOf(s.charAt(end - 1)) >= 0) {
            if (s.charAt(end - 1) == 'u' || s.charAt(end - 1) == 'U') u = true;
            end--;
        }
        String n = s.substring(0, end);
        try {
            if (n.startsWith("0x") || n.startsWith("0X")) {
                long v = Long.parseUnsignedLong(n.substring(2), 16);
                return new NV(v, u || v < 0); // hex overflow beyond int64 wraps unsigned
            }
            if (n.length() > 1 && n.charAt(0) == '0') {
                long v = Long.parseUnsignedLong(n, 8);
                return new NV(v, u || v < 0);
            }
            try {
                return new NV(Long.parseLong(n), u);
            } catch (NumberFormatException e) {
                return new NV(Long.parseUnsignedLong(n), true); // decimal overflow: uintmax_t
            }
        } catch (NumberFormatException e) {
            return S(0); // floats and malformed constants are 0 in #if
        }
    }

    static long charValue(String s) {
        int q = s.indexOf('\'');
        if (q < 0 || q + 1 >= s.length()) return 0;
        long v = 0;
        int k = q + 1;
        while (k < s.length() && s.charAt(k) != '\'') {
            long c;
            if (s.charAt(k) == '\\' && k + 1 < s.length()) {
                char e = s.charAt(++k);
                switch (e) {
                    case 'n': c = 10; break;
                    case 't': c = 9; break;
                    case 'r': c = 13; break;
                    case '0': case '1': case '2': case '3': case '4': case '5': case '6': case '7':
                        int o = 0, d = 0;
                        while (k < s.length() && d < 3 && s.charAt(k) >= '0' && s.charAt(k) <= '7') {
                            o = o * 8 + (s.charAt(k) - '0'); k++; d++;
                        }
                        k--;
                        c = o;
                        break;
                    case 'x':
                        int h = 0; k++;
                        while (k < s.length() && Character.digit(s.charAt(k), 16) >= 0) {
                            h = h * 16 + Character.digit(s.charAt(k), 16); k++;
                        }
                        k--;
                        c = h;
                        break;
                    default: c = e;
                }
                k++;
            } else {
                c = s.charAt(k++);
            }
            v = (v << 8) | (c & 0xff); // multi-char constants, tcc-style
        }
        return v;
    }
}
