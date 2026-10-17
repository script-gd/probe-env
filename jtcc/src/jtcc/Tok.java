package jtcc;

import java.util.Set;

/** A preprocessing token. Text keeps the exact source spelling (no escape decoding). */
public final class Tok {
    public enum Kind { IDENT, PPNUM, STRING, CHARCONST, PUNCT, NL, EOF }

    public final Kind kind;
    public final String text;
    public final int line;      // logical line (after #line remapping)
    public final String file;   // logical file (after #line remapping)
    public final boolean ws;    // whitespace/comment/newline preceded this token
    public final boolean bol;   // first token on a logical line
    /** Hideset: macro names that must not be re-expanded at this token. */
    Set<String> hs;

    public Tok(Kind kind, String text, int line, String file, boolean ws, boolean bol) {
        this.kind = kind;
        this.text = text;
        this.line = line;
        this.file = file;
        this.ws = ws;
        this.bol = bol;
    }

    public Tok withKindText(Kind k, String t) {
        Tok r = new Tok(k, t, line, file, ws, bol);
        r.hs = hs;
        return r;
    }

    public boolean isIdent(String s) { return kind == Kind.IDENT && text.equals(s); }
    public boolean isPunct(String s) { return kind == Kind.PUNCT && text.equals(s); }

    public String dump() {
        String esc = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
        return kind + ":" + esc;
    }

    @Override public String toString() { return dump(); }
}
