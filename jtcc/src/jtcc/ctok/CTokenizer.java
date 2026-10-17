package jtcc.ctok;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jtcc.Tok;

/**
 * pp-token stream → C-token stream: keyword recognition (tcctok.h keyword set),
 * number decoding (faithful port of tccpp.c parse_number, LP64), string/char decoding
 * (parse_string + parse_escape_string, including the gdcc u8 patch semantics).
 */
public final class CTokenizer {
    public static final Map<String, String> KEYWORDS = new HashMap<>();
    static {
        String[] kw = {
            "if","else","while","for","do","continue","break","return","goto","switch","case","default",
            "asm","__asm","__asm__","extern","static","unsigned","_Atomic","const","__const","__const__",
            "volatile","__volatile","__volatile__","register","signed","__signed","__signed__","auto",
            "inline","__inline","__inline__","restrict","__restrict","__restrict__","__extension__",
            "_Thread_local","__thread","_Generic","_Static_assert","void","char","int","float","double",
            "_Bool","_Complex","short","long","struct","union","typedef","enum","sizeof",
            "__attribute","__attribute__","__alignof","__alignof__","_Alignof","_Alignas","__typeof","__typeof__",
            "__builtin_constant_p","__builtin_frame_address","__builtin_return_address","__builtin_expect",
            "__builtin_va_start","__builtin_va_arg","__builtin_va_end","__builtin_va_copy","__builtin_va_arg_types",
            "__builtin_types_compatible_p","__builtin_choose_expr","__builtin_unreachable","__builtin_trap",
            "__noreturn__","__aligned__","__packed__","__label__","__FUNCTION__","__PRETTY_FUNCTION__","__func__",
        };
        for (String k : kw) KEYWORDS.put(k, k);
    }

    private CTokenizer() {}

    /** Convert pp tokens (already macro-expanded, directives consumed) into C tokens. */
    public static List<CTok> convert(List<Tok> ppToks, Diag diag) {
        List<CTok> out = new ArrayList<>(ppToks.size());
        for (Tok t : ppToks) {
            switch (t.kind) {
                case IDENT: {
                    String kw = KEYWORDS.get(t.text);
                    out.add(new CTok(kw != null ? CTok.Kind.KEYWORD : CTok.Kind.IDENT, t.text, t.line, t.file));
                    break;
                }
                case PUNCT:
                    out.add(new CTok(CTok.Kind.PUNCT, t.text, t.line, t.file));
                    break;
                case PPNUM:
                    out.add(parseNumber(t, diag));
                    break;
                case STRING:
                    out.add(parseStringToken(t, diag));
                    break;
                case CHARCONST:
                    out.add(parseCharToken(t, diag));
                    break;
                default: break;
            }
        }
        CTok eof = new CTok(CTok.Kind.EOF, "", ppToks.isEmpty() ? 0 : ppToks.get(ppToks.size() - 1).line, "");
        out.add(eof);
        return out;
    }

    public interface Diag {
        void error(String file, int line, String msg);
        void warn(String file, int line, String msg);
    }

    // ---------------------------------------------------------------- numbers

    /** tccpp.c parse_number, LP64 (LONG_SIZE=8). Float suffixes F/L map to CFLOAT/CLDOUBLE. */
    static CTok parseNumber(Tok t, Diag dg) {
        String s = t.text;
        int i = 0;
        int base = 10;
        boolean isFloat = false;
        StringBuilder intDigits = new StringBuilder();
        char ch = s.charAt(i);
        int start = i;
        char t0 = ch;
        if (i + 1 < s.length()) { i++; ch = s.charAt(i); }
        intDigits.append(t0);
        if (t0 == '0' && (ch == 'x' || ch == 'X' || ch == 'b' || ch == 'B')) {
            intDigits.setLength(0);
            base = (ch == 'x' || ch == 'X') ? 16 : 2;
            if (i + 1 < s.length()) { i++; ch = s.charAt(i); } else ch = 0;
        }
        while (true) {
            int d = digitVal(ch);
            if (d < 0 || d >= base) break;
            intDigits.append(ch);
            i++;
            ch = i < s.length() ? s.charAt(i) : 0;
        }
        boolean hexFloatSep = ch == '.' || (base == 10 && (ch == 'e' || ch == 'E'))
                || ((base == 16 || base == 2) && (ch == 'p' || ch == 'P'));
        if (hexFloatSep) {
            // float: delegate decimal to Java, translate hex to Java's hex-float syntax
            String rest = s.substring(start);
            int e = 0;
            while (e < rest.length()) {
                char c = rest.charAt(e);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '+' || c == '-') { e++; continue; }
                break;
            }
            String mant = rest.substring(0, e);
            char suffix = e < rest.length() ? rest.charAt(e) : 0;
            CTok ct = new CTok(CTok.Kind.CDOUBLE, t.text, t.line, t.file);
            try {
                double dv;
                if (base == 10) {
                    // strip f/F/l/L suffix from mantissa part
                    String m = mant;
                    dv = Double.parseDouble(m);
                } else {
                    dv = hexFloat(mant, base);
                }
                if (suffix == 'f' || suffix == 'F') { ct.kind = CTok.Kind.CFLOAT; ct.f = (float) dv; }
                else if (suffix == 'l' || suffix == 'L') { ct.kind = CTok.Kind.CLDOUBLE; ct.d = dv; }
                else ct.d = dv;
            } catch (NumberFormatException ex) {
                dg.error(t.file, t.line, "invalid number");
            }
            return ct;
        }
        // integer path
        if (base == 10 && intDigits.length() > 0 && intDigits.charAt(0) == '0') {
            base = 8;
            intDigits.deleteCharAt(0);
        }
        long n = 0;
        boolean ov = false;
        for (int k = 0; k < intDigits.length(); k++) {
            int d = digitVal(intDigits.charAt(k));
            if (d < 0 || d >= base) dg.error(t.file, t.line, "invalid digit");
            long n1 = n;
            n = n * base + d;
            if (Long.compareUnsigned(n1, 0x1000000000000000L) >= 0 && n / base != n1) ov = true;
        }
        int lcount = 0, ucount = 0;
        while (i < s.length()) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c == 'L') { lcount++; i++; }
            else if (c == 'U') { ucount++; i++; }
            else break;
        }
        if (ucount == 0 && base == 10) {
            if (lcount == 0) {
                if (n >= 0x80000000L) lcount = 1;      // LONG_SIZE==8 branch
            }
            if (n < 0) { ov = true; ucount = 1; }      // n >= 0x8000... (unsigned wrap)
        } else {
            if (lcount == 0) {
                if (n >= 0x100000000L) lcount = 1;
                else if (n >= 0x80000000L) ucount = 1;
            }
            if (n < 0) ucount = 1;
        }
        if (ov) dg.warn(t.file, t.line, "integer constant overflow");
        CTok.Kind kind = lcount == 0 ? CTok.Kind.CINT : lcount == 1 ? CTok.Kind.CLONG : CTok.Kind.CLLONG;
        if (ucount > 0) kind = CTok.Kind.values()[kind.ordinal() + 1];
        CTok ct = new CTok(kind, t.text, t.line, t.file);
        ct.i = n;
        return ct;
    }

    private static int digitVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Hex/binary float: normalize to Java's hex-float syntax and delegate. */
    private static double hexFloat(String mant, int base) {
        String m = mant;
        if (base == 16) {
            if (!m.startsWith("0x") && !m.startsWith("0X")) m = "0x" + m;
            return Double.parseDouble(m);
        }
        // binary float 0b1.01p3: expand to hex manually (rare; tcc extension)
        int p = Math.max(m.indexOf('p'), m.indexOf('P'));
        String mantDigits = p >= 0 ? m.substring(0, p) : m;
        int exp = p >= 0 ? Integer.parseInt(m.substring(p + 1)) : 0;
        int dot = mantDigits.indexOf('.');
        String intPart = dot < 0 ? mantDigits : mantDigits.substring(0, dot);
        String fracPart = dot < 0 ? "" : mantDigits.substring(dot + 1);
        long iv = intPart.isEmpty() ? 0 : Long.parseLong(intPart, 2);
        double fv = 0;
        for (int k = 0; k < fracPart.length(); k++)
            if (fracPart.charAt(k) == '1') fv += Math.pow(0.5, k + 1);
        return (iv + fv) * Math.pow(2.0, exp);
    }

    // ---------------------------------------------------------------- strings/chars

    /** parse_string for string literals: decode escapes into bytes (UTF-8 for UCN and raw
     *  non-ASCII), strip prefix (u8/u/U/L handled), append trailing NUL (two for L""). */
    static CTok parseStringToken(Tok t, Diag dg) {
        String raw = t.text;
        boolean isLong = raw.startsWith("L");
        int q = raw.indexOf('"');
        String body = raw.substring(q + 1, raw.length() - 1);
        byte[] decoded = decodeEscapes(body, isLong, t, dg);
        CTok ct = new CTok(isLong ? CTok.Kind.LSTR : CTok.Kind.STR, t.text, t.line, t.file);
        if (isLong) {
            // 32-bit wchar units
            int units = decoded.length; // bytes already wchar-flattened for our scope
            byte[] withNul = new byte[units + 4];
            System.arraycopy(decoded, 0, withNul, 0, units);
            ct.str = withNul;
        } else {
            byte[] withNul = new byte[decoded.length + 1];
            System.arraycopy(decoded, 0, withNul, 0, decoded.length);
            ct.str = withNul;
        }
        return ct;
    }

    /** parse_string for char constants: decode then fold to integer (multi-char allowed). */
    static CTok parseCharToken(Tok t, Diag dg) {
        String raw = t.text;
        boolean isLong = raw.startsWith("L") || raw.startsWith("u") || raw.startsWith("U");
        int q = raw.indexOf('\'');
        String body = raw.substring(q + 1, raw.length() - 1);
        byte[] decoded = decodeEscapes(body, isLong, t, dg);
        if (decoded.length < 1) dg.error(t.file, t.line, "empty character constant");
        if (decoded.length > 1) dg.warn(t.file, t.line, "multi-character character constant");
        long c = 0;
        for (byte b : decoded) c = (c << 8) | (b & 0xffL);
        CTok ct = new CTok(isLong ? CTok.Kind.LCHAR : CTok.Kind.CCHAR, t.text, t.line, t.file);
        ct.i = c;
        return ct;
    }

    /** parse_escape_string port: octal/hex/UCN/named escapes; UCN → UTF-8 when !isLong. */
    static byte[] decodeEscapes(String s, boolean isLong, Tok t, Diag dg) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\') {
                if (c >= 0x80) {
                    // raw UTF-8 bytes pass through unchanged (tcc reads bytes)
                    byte[] b = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.write(b, 0, b.length);
                } else {
                    out.write((byte) c);
                }
                i++;
                continue;
            }
            i++;
            if (i >= n) break;
            char e = s.charAt(i);
            switch (e) {
                case '0': case '1': case '2': case '3': case '4': case '5': case '6': case '7': {
                    int v = e - '0';
                    i++;
                    for (int k = 0; k < 2 && i < n; k++) {
                        char d = s.charAt(i);
                        if (d < '0' || d > '7') break;
                        v = v * 8 + (d - '0');
                        i++;
                    }
                    writeChar(out, v, isLong);
                    break;
                }
                case 'x': {
                    i++;
                    long v = 0;
                    while (i < n) {
                        int d = digitVal(s.charAt(i));
                        if (d < 0 || d >= 16) break;
                        v = v * 16 + d;
                        i++;
                    }
                    writeChar(out, (int) v, isLong);
                    break;
                }
                case 'u': case 'U': {
                    int digits = e == 'u' ? 4 : 8;
                    i++;
                    long v = 0;
                    for (int k = 0; k < digits; k++) {
                        if (i >= n) { dg.error(t.file, t.line, "more hex digits in universal-character-name"); break; }
                        int d = digitVal(s.charAt(i));
                        if (d < 0 || d >= 16) { dg.error(t.file, t.line, "more hex digits in universal-character-name"); break; }
                        v = v * 16 + d;
                        i++;
                    }
                    if (isLong) writeChar(out, (int) v, true);
                    else {
                        byte[] b = new String(Character.toChars((int) v)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.write(b, 0, b.length);
                    }
                    break;
                }
                case 'a': writeChar(out, 7, isLong); i++; break;
                case 'b': writeChar(out, 8, isLong); i++; break;
                case 't': writeChar(out, 9, isLong); i++; break;
                case 'n': writeChar(out, 10, isLong); i++; break;
                case 'v': writeChar(out, 11, isLong); i++; break;
                case 'f': writeChar(out, 12, isLong); i++; break;
                case 'r': writeChar(out, 13, isLong); i++; break;
                case 'e': writeChar(out, 27, isLong); i++; break;
                default: writeChar(out, e, isLong); i++; break; // \' \" \\ \? etc.
            }
        }
        return out.toByteArray();
    }

    private static void writeChar(java.io.ByteArrayOutputStream out, int v, boolean isLong) {
        if (isLong) {
            out.write(v & 0xff); out.write((v >>> 8) & 0xff); out.write((v >>> 16) & 0xff); out.write((v >>> 24) & 0xff);
        } else {
            if (v <= 0x7f) out.write(v);
            else {
                byte[] b = new String(Character.toChars(v)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
    }
}
