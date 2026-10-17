package jtcc.ctok;

/** A C-level token: pp token + keyword identity + decoded constant value. */
public final class CTok {
    public enum Kind {
        IDENT, KEYWORD,
        CINT, CUINT, CLONG, CULONG, CLLONG, CULLONG,   // integer constants
        CFLOAT, CDOUBLE, CLDOUBLE,                      // float constants
        CCHAR, LCHAR,                                   // char constants
        STR, LSTR,                                      // string literals (decoded)
        PUNCT, EOF
    }

    public Kind kind;
    public String text;          // keyword text / identifier text / punct
    public long i;               // integer/char value
    public double d;             // double value
    public float f;              // float value
    public byte[] str;           // decoded string bytes (with trailing NUL)
    public final int line;
    public final String file;

    public CTok(Kind kind, String text, int line, String file) {
        this.kind = kind; this.text = text; this.line = line; this.file = file;
    }

    public boolean is(String s) { return (kind == Kind.PUNCT || kind == Kind.KEYWORD) && text.equals(s); }
    public boolean isIdent() { return kind == Kind.IDENT; }

    @Override public String toString() {
        return kind == Kind.CINT || kind == Kind.CUINT || kind == Kind.CLONG || kind == Kind.CULONG
                || kind == Kind.CLLONG || kind == Kind.CULLONG ? kind + ":" + i
                : kind + ":" + text;
    }
}
