package jtcc;

import java.util.ArrayList;
import java.util.List;

/**
 * C preprocessing-phase lexer, pull-based: line splicing, comment removal, pp-tokenization.
 * Escape sequences inside literals are kept verbatim (matching `tcc -E` output).
 * Supports the gdcc u8"..." patch: an identifier u8 immediately followed by '"'
 * lexes as one STRING token including the prefix. Supports #line remapping.
 */
public final class Lexer {
    private final String src;
    private final int[] lineAdd; // per-spliced-line count of spliced-away physical newlines
    private int pos;
    private int physLine = 1;    // line in the spliced text
    private int bias;            // #line adjustment
    private String logicalName;
    private boolean atBol = true;
    private boolean pendingWs = false;

    public Lexer(String src, String fileName) {
        Spliced sp = splice(src);
        this.src = sp.text;
        this.lineAdd = sp.add;
        this.logicalName = fileName;
    }

    private int reported() { return physLine + (physLine < lineAdd.length ? lineAdd[physLine] : 0) + bias; }

    private static final class Spliced { String text; int[] add; }

    /** Translation phase 2: delete backslash-newline, but remember how many physical
     *  newlines disappeared before each remaining line so line numbers stay physical. */
    private static Spliced splice(String s) {
        Spliced r = new Spliced();
        if (s.indexOf('\\') < 0) { r.text = s; r.add = new int[0]; return r; }
        StringBuilder b = new StringBuilder(s.length());
        List<Integer> add = new ArrayList<>();
        add.add(0); // line 0 unused
        int removed = 0, line = 1;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == '\n') { removed++; i += 2; continue; }
                if (d == '\r' && i + 2 < n && s.charAt(i + 2) == '\n') { removed++; i += 3; continue; }
            }
            b.append(c);
            if (c == '\n') { line++; while (add.size() <= line) add.add(0); add.set(line, removed); }
            i++;
        }
        r.text = b.toString();
        r.add = new int[add.size() + 2];
        for (int k = 0; k < add.size(); k++) r.add[k] = add.get(k);
        r.add[add.size()] = removed; r.add[add.size() + 1] = removed;
        return r;
    }

    /** Implements #line: the next line will carry line `logical` and file `name`. */
    public void remapLine(int logical, String name) {
        bias = (logical - 1) - reported();
        if (name != null) this.logicalName = name;
    }

    public String fileName() { return logicalName; }

    /** Next token, or null at end of input. */
    public Tok next() {
        boolean ws = pendingWs;
        pendingWs = false;
        for (;;) {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == 0x0b || c == 0x0c) { pos++; ws = true; }
                else break;
            }
            if (pos + 1 < src.length() && src.charAt(pos) == '/' && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                ws = true;
            } else if (pos + 1 < src.length() && src.charAt(pos) == '/' && src.charAt(pos + 1) == '*') {
                pos += 2;
                ws = true;
                while (pos < src.length() && !(pos + 1 < src.length() && src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    if (src.charAt(pos) == '\n') physLine++;
                    pos++;
                }
                if (pos < src.length()) pos += 2;
            } else break;
        }
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        if (c == '\n') {
            pos++;
            physLine++;
            boolean wasB = atBol;
            atBol = true;
            pendingWs = false; // ws means "whitespace since previous token on the same line"
            return new Tok(Tok.Kind.NL, "\n", reported(), logicalName, ws, wasB);
        }
        boolean bol = atBol;
        atBol = false;
        Tok t = readToken(reported(), ws, bol);
        return t;
    }

    private Tok readToken(int line, boolean ws, boolean bol) {
        char c = src.charAt(pos);
        if (c == '"' || c == '\'') return readLiteral("", line, ws, bol);
        if (isIdentStart(c)) {
            int start = pos;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
            String word = src.substring(start, pos);
            if (pos < src.length()) {
                char q = src.charAt(pos);
                if (q == '"' && (word.equals("u8") || word.equals("u") || word.equals("U") || word.equals("L")))
                    return readLiteral(word, line, ws, bol);
                if (q == '\'' && (word.equals("u") || word.equals("U") || word.equals("L")))
                    return readLiteral(word, line, ws, bol);
            }
            return new Tok(Tok.Kind.IDENT, word, line, logicalName, ws, bol);
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
            int start = pos;
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (Character.isLetterOrDigit(d) || d == '.' || d == '_') { pos++; continue; }
                if ((d == '+' || d == '-') && pos > start) {
                    char p = src.charAt(pos - 1);
                    if (p == 'e' || p == 'E' || p == 'p' || p == 'P') { pos++; continue; }
                }
                break;
            }
            return new Tok(Tok.Kind.PPNUM, src.substring(start, pos), line, logicalName, ws, bol);
        }
        return readPunct(line, ws, bol);
    }

    private Tok readLiteral(String prefix, int line, boolean ws, boolean bol) {
        char quote = src.charAt(pos);
        StringBuilder b = new StringBuilder(prefix);
        b.append(quote);
        pos++;
        while (pos < src.length()) {
            char d = src.charAt(pos);
            b.append(d);
            pos++;
            if (d == '\\' && pos < src.length()) {
                char e = src.charAt(pos);
                b.append(e);
                if (e == '\n') physLine++;
                pos++;
                continue;
            }
            if (d == quote) break;
            if (d == '\n') { physLine++; break; } // unterminated
        }
        return new Tok(quote == '"' ? Tok.Kind.STRING : Tok.Kind.CHARCONST, b.toString(), line, logicalName, ws, bol);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c >= 0x80;
    }
    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c >= 0x80;
    }

    private static final String[] PUNCTS = {
        "%:%:", "...", "<<=", ">>=", "##", "<:", ":>", "<%", "%>", "%:",
        "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "++", "--", "->",
        "+=", "-=", "*=", "/=", "%=", "&=", "^=", "|=",
        "[", "]", "(", ")", "{", "}", ".", "&", "*", "+", "-", "~", "!",
        "/", "%", "<", ">", "^", "|", "?", ":", ";", "=", ",", "#"
    };

    private Tok readPunct(int line, boolean ws, boolean bol) {
        for (String p : PUNCTS) {
            if (src.startsWith(p, pos)) {
                pos += p.length();
                return new Tok(Tok.Kind.PUNCT, p, line, logicalName, ws, bol);
            }
        }
        char c = src.charAt(pos);
        pos++;
        return new Tok(Tok.Kind.PUNCT, Character.toString(c), line, logicalName, ws, bol);
    }
}
