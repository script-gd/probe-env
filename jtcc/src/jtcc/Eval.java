package jtcc;

import java.util.ArrayList;
import java.util.List;

/** #if expression evaluation on an expanded token list (identifiers already mean 0). */
public final class Eval {
    private final List<Tok> toks;
    private int i;

    private Eval(List<Tok> toks) { this.toks = toks; }

    public static boolean eval(List<Tok> expanded) {
        List<Tok> t = new ArrayList<>(expanded.size());
        for (Tok tok : expanded) {
            if (tok.kind == Tok.Kind.IDENT) {
                // remaining identifiers are 0; `true`/`false` arrive via macros already
                t.add(tok.withKindText(Tok.Kind.PPNUM, "0"));
            } else if (tok.kind == Tok.Kind.CHARCONST) {
                t.add(tok.withKindText(Tok.Kind.PPNUM, Long.toString(charValue(tok.text))));
            } else {
                t.add(tok);
            }
        }
        Eval e = new Eval(t);
        long v = e.ternary();
        return v != 0;
    }

    private Tok peek() { return i < toks.size() ? toks.get(i) : null; }
    private Tok take() { return i < toks.size() ? toks.get(i++) : null; }
    private boolean at(String p) { Tok t = peek(); return t != null && t.isPunct(p); }

    private long ternary() {
        long c = or();
        if (at("?")) {
            take();
            long a = ternary();
            if (at(":")) take();
            long b = ternary();
            return c != 0 ? a : b;
        }
        return c;
    }
    private long or()  { long v = and(); while (at("||")) { take(); long b = and(); v = (v != 0 || b != 0) ? 1 : 0; } return v; }
    private long and() { long v = bitor(); while (at("&&")) { take(); long b = bitor(); v = (v != 0 && b != 0) ? 1 : 0; } return v; }
    private long bitor() { long v = bitxor(); while (at("|")) { take(); v |= bitxor(); } return v; }
    private long bitxor() { long v = bitand(); while (at("^")) { take(); v ^= bitand(); } return v; }
    private long bitand() { long v = eq(); while (at("&")) { take(); v &= eq(); } return v; }
    private long eq() {
        long v = rel();
        for (;;) {
            if (at("==")) { take(); v = v == rel() ? 1 : 0; }
            else if (at("!=")) { take(); v = v != rel() ? 1 : 0; }
            else return v;
        }
    }
    private long rel() {
        long v = shift();
        for (;;) {
            if (at("<=")) { take(); v = v <= shift() ? 1 : 0; }
            else if (at(">=")) { take(); v = v >= shift() ? 1 : 0; }
            else if (at("<")) { take(); v = v < shift() ? 1 : 0; }
            else if (at(">")) { take(); v = v > shift() ? 1 : 0; }
            else return v;
        }
    }
    private long shift() {
        long v = add();
        for (;;) {
            if (at("<<")) { take(); v <<= add(); }
            else if (at(">>")) { take(); v >>= add(); }
            else return v;
        }
    }
    private long add() {
        long v = mul();
        for (;;) {
            if (at("+")) { take(); v += mul(); }
            else if (at("-")) { take(); v -= mul(); }
            else return v;
        }
    }
    private long mul() {
        long v = unary();
        for (;;) {
            if (at("*")) { take(); v *= unary(); }
            else if (at("/")) { take(); long b = unary(); v = b == 0 ? 0 : v / b; }
            else if (at("%")) { take(); long b = unary(); v = b == 0 ? 0 : v % b; }
            else return v;
        }
    }
    private long unary() {
        if (at("!")) { take(); return unary() == 0 ? 1 : 0; }
        if (at("~")) { take(); return ~unary(); }
        if (at("-")) { take(); return -unary(); }
        if (at("+")) { take(); return unary(); }
        return primary();
    }
    private long primary() {
        Tok t = take();
        if (t == null) return 0;
        if (t.isPunct("(")) {
            long v = ternary();
            if (at(")")) take();
            return v;
        }
        if (t.kind == Tok.Kind.PPNUM) return numValue(t.text);
        return 0;
    }

    static long numValue(String s) {
        int end = s.length();
        while (end > 0 && "uUlL".indexOf(s.charAt(end - 1)) >= 0) end--;
        String n = s.substring(0, end);
        try {
            if (n.startsWith("0x") || n.startsWith("0X")) return Long.parseUnsignedLong(n.substring(2), 16);
            if (n.length() > 1 && n.charAt(0) == '0') return Long.parseUnsignedLong(n, 8);
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            return 0; // floats and malformed constants are 0 in #if
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
