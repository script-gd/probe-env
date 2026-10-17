package jtcc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C11-ish preprocessor matching tcc 0.9.28 (+ gdcc u8 patch) behavior closely enough to
 * reproduce `tcc -E` token streams on the gdcc generated C project. Prosser hideset
 * macro expansion; #include with quoted/angle search; conditional evaluation;
 * #error/#pragma once/#line; __FILE__/__LINE__/__COUNTER__/__BASE_FILE__/__DATE__/__TIME__.
 */
public final class Preprocessor {
    static final class Macro {
        final List<String> params;     // parameter names; variadic name last when variadic
        final boolean variadic;
        final boolean functionLike;
        final List<Tok> body;
        Macro(List<String> params, boolean variadic, boolean functionLike, List<Tok> body) {
            this.params = params; this.variadic = variadic; this.functionLike = functionLike; this.body = body;
        }
    }

    private final Map<String, Macro> macros = new HashMap<>();
    private final List<String> includeDirs = new ArrayList<>();  // -I (quoted + angle)
    private final List<String> sysDirs = new ArrayList<>();      // -B include + system roots
    private final Set<String> pragmaOnce = new HashSet<>();
    private final Deque<FileState> files = new ArrayDeque<>();
    private final List<Tok> out = new ArrayList<>();
    private final Deque<CondFrame> cond = new ArrayDeque<>();
    private long ppCounter = 0;
    private String baseFile;
    private String error;

    private static final class FileState {
        final Lexer lexer;
        final String dir;   // directory for quoted include resolution ("" spelling preserved)
        FileState(Lexer lexer, String dir) { this.lexer = lexer; this.dir = dir; }
    }

    private static final class CondFrame {
        boolean active;      // this branch currently emitting
        boolean taken;       // some branch in this group was true
        boolean seenElse;
        final boolean parentActive;
        CondFrame(boolean active, boolean taken, boolean parentActive) {
            this.active = active; this.taken = taken; this.parentActive = parentActive;
        }
    }

    public Preprocessor(List<String> includeDirs, List<String> sysDirs) {
        this.includeDirs.addAll(includeDirs);
        this.sysDirs.addAll(sysDirs);
    }

    public String error() { return error; }
    public List<Tok> output() { return out; }

    /** Emit preprocessed text in `tcc -E -P` style: original whitespace preserved via ws
     *  flags, plus anti-merge spaces per tcc's pp_need_space rule; one logical line per
     *  source line. */
    public String outputText() {
        StringBuilder sb = new StringBuilder();
        Tok prev = null;
        for (int idx = 0; idx < out.size(); idx++) {
            Tok t = out.get(idx);
            if (breaks.contains(idx)) { sb.append('\n'); prev = null; }
            if (prev == null) { if (t.ws && sb.length() > 0) sb.append(' '); }
            else if (t.ws || needSpace(prev, t)) sb.append(' ');
            sb.append(t.text);
            prev = t;
        }
        if (prev != null) sb.append('\n');
        return sb.toString();
    }

    /** tccpp.c pp_need_space equivalent: prevent accidental token pasting in -E output. */
    private static boolean needSpace(Tok a, Tok b) {
        boolean wa = a.kind == Tok.Kind.IDENT || a.kind == Tok.Kind.PPNUM
                || a.kind == Tok.Kind.STRING || a.kind == Tok.Kind.CHARCONST;
        boolean wb = b.kind == Tok.Kind.IDENT || b.kind == Tok.Kind.PPNUM
                || b.kind == Tok.Kind.STRING || b.kind == Tok.Kind.CHARCONST;
        if (wa && wb) return true;
        char la = a.text.charAt(a.text.length() - 1);
        if ((la == 'e' || la == 'E') && a.kind == Tok.Kind.PPNUM && (b.isPunct("+") || b.isPunct("-"))) return true;
        if (a.isPunct("+") && (b.isPunct("++") || b.isPunct("+"))) return true;
        if (a.isPunct("-") && (b.isPunct("--") || b.isPunct("-"))) return true;
        return false;
    }

    private boolean active() {
        for (CondFrame f : cond) if (!f.active) return false;
        return true;
    }

    public void process(Path mainFile) {
        baseFile = mainFile.toString();
        // tcc command-line prelude (tcc_predefs), then the main file
        StringBuilder pre = new StringBuilder();
        pre.append("#define __TINYC__ 928\n");
        pre.append("#define __x86_64__ 1\n#define __x86_64 1\n#define __amd64__ 1\n");
        pre.append("#define __linux__ 1\n#define __linux 1\n#define __unix__ 1\n#define __unix 1\n");
        pre.append("#define __TCC_PP__ 1\n");
        pre.append("#define __SIZEOF_POINTER__ 8\n#define __SIZEOF_LONG__ 8\n");
        pre.append("#define __STDC__ 1\n#define __STDC_HOSTED__ 1\n#define __STDC_VERSION__ 201112L\n");
        pre.append("#include <tccdefs.h>\n");
        pre.append("#define __BASE_FILE__ \"").append(baseFile.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        pushFile(mainFile.toString(), mainFile.getParent() == null ? "" : mainFile.getParent().toString());
        files.push(new FileState(new Lexer(pre.toString(), "<command line>"), ""));
        run();
    }

    private void pushFile(String name, String dir) {
        String text;
        try {
            text = Files.readString(Path.of(name));
        } catch (IOException e) {
            fail("include file '" + name + "' not found");
            files.push(new FileState(new Lexer("", name), dir));
            return;
        }
        files.push(new FileState(new Lexer(text, name), dir));
    }

    private void fail(String msg) {
        if (error == null) error = msg;
        throw new PpError(msg);
    }

    static final class PpError extends RuntimeException { PpError(String m) { super(m); } }

    private Tok nextRaw() {
        for (;;) {
            FileState f = files.peek();
            if (f == null) return null;
            Tok t = f.lexer.next();
            if (t != null) return t;
            files.pop();
        }
    }

    private void run() {
        try {
            for (;;) {
                Tok t = pending.poll();
                boolean fromStream = false;
                if (t == null) {
                    t = nextRaw();
                    if (t == null) break;
                    fromStream = true;
                }
                if (t.kind == Tok.Kind.NL) {
                    if (!out.isEmpty()) breaks.add(out.size());
                    continue;
                }
                // directive check applies to both origins: pending holds directive tokens
                // only at expansion/lookahead boundaries pushed back by expandMacroTok
                if (t.bol && t.isPunct("#")) {
                    directive(t);
                    if (!out.isEmpty()) breaks.add(out.size());
                    continue;
                }
                if (fromStream && !active()) { skipToNextDirective(t); continue; }
                expandTok(t);
            }
        } catch (PpError e) {
            // recorded in error()
        }
    }

    /** Expansion-produced tokens awaiting rescanning (the unified tcc-style token stream). */
    private final ArrayDeque<Tok> pending = new ArrayDeque<>();

    /** Indices in `out` where a raw-stream line break occurred (tcc -E newline model:
     *  newlines come only from LINEFEED tokens not swallowed by macro arg collection). */
    private final java.util.Set<Integer> breaks = new java.util.HashSet<>();

    private Tok pull() {
        Tok t = pending.poll();
        lastPullFromStream = (t == null);
        if (t == null) t = nextRaw();
        return t;
    }
    private boolean lastPullFromStream;

    private void pushBack(List<Tok> toks) {
        for (int k = toks.size() - 1; k >= 0; k--) pending.addFirst(toks.get(k));
    }

    /** The first token of a macro expansion adopts the invocation site's leading-ws flag
     *  (tcc -E prints source whitespace ahead of the replayed token stream). */
    private static List<Tok> adoptInvokeWs(List<Tok> toks, Tok callSite) {
        if (!toks.isEmpty() && toks.get(0).ws != callSite.ws) {
            Tok f = toks.get(0);
            Tok nf = new Tok(f.kind, f.text, f.line, f.file, callSite.ws, f.bol);
            nf.hs = f.hs; nf.pasteOp = f.pasteOp;
            toks.set(0, nf);
        }
        return toks;
    }

    /** Expand one token of the main stream; macro substitution results are rescanned via pending. */
    private void expandTok(Tok t) {
        if (t.kind != Tok.Kind.IDENT) { out.add(t); return; }
        Macro m0 = macros.get(t.text);
        if (m0 != null) { expandMacroTok(t, m0); return; }
        switch (t.text) {
            case "__FILE__":
                out.add(t.withKindText(Tok.Kind.STRING, "\"" + t.file.replace("\\", "\\\\").replace("\"", "\\\"") + "\""));
                return;
            case "__LINE__":
                out.add(t.withKindText(Tok.Kind.PPNUM, Integer.toString(t.line)));
                return;
            case "__BASE_FILE__":
                out.add(t.withKindText(Tok.Kind.STRING, "\"" + baseFile.replace("\\", "\\\\").replace("\"", "\\\"") + "\""));
                return;
            case "__COUNTER__":
                out.add(t.withKindText(Tok.Kind.PPNUM, Long.toString(ppCounter++)));
                return;
            case "__DATE__":
                out.add(t.withKindText(Tok.Kind.STRING, "\"Jan  1 1970\""));
                return;
            case "__TIME__":
                out.add(t.withKindText(Tok.Kind.STRING, "\"00:00:00\""));
                return;
            default:
        }
        out.add(t); return;
    }

    private void expandMacroTok(Tok t, Macro m) {
        if (hidden(t, t.text)) { out.add(t); return; }
        if (!m.functionLike) {
            pushBack(adoptInvokeWs(pasteTokens(applyHideset(m.body, t.text, t, null), null, false), t));
            return;
        }
        // function-like: pull next token (skipping NLs) from pending or stream
        Tok open = pull();
        List<Tok> skippedNls = new ArrayList<>();
        while (open != null && open.kind == Tok.Kind.NL) { skippedNls.add(open); open = pull(); }
        if (open == null || !open.isPunct("(") || hidden(open, t.text)) {
            out.add(t);
            if (open != null) {
                pending.addFirst(open);
                // preserve skipped line breaks as markers (a directive token here is
                // handled by the uniform directive check in run())
                for (int k = skippedNls.size() - 1; k >= 0; k--) pending.addFirst(skippedNls.get(k));
            }
            return;
        }
        List<List<Tok>> args = collectArgsStream();
        // Prosser: substitution hideset = intersect(hs(name), hs(token after invocation)) ∪ {name}
        Tok after = pull();
        int afterNls = 0;
        while (after != null && after.kind == Tok.Kind.NL) { afterNls++; after = pull(); }
        Set<String> afterHs = after == null || after.hs == null ? Set.of() : after.hs;
        if (after != null) {
            pending.addFirst(after);
            // line breaks between invocation and the next token become output newlines
            for (int k = 0; k < afterNls; k++)
                pending.addFirst(new Tok(Tok.Kind.NL, "\n", after.line, after.file, true, true));
        }
        pushBack(substitute(m, args, t, afterHs, lastCloseParen != null ? lastCloseParen : t));
    }

    /** Collect macro args from pending/stream (calls may span lines; directives found at
     *  BOL inside the argument list are processed, per tcc's unified token stream). */
    private Tok lastCloseParen; // the ')' ending the most recent macro call

    private List<List<Tok>> collectArgsStream() {
        List<List<Tok>> args = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        int depth = 1;
        boolean sawAny = false;
        for (;;) {
            Tok t = pull();
            if (t == null) fail("unterminated macro call");
            if (lastPullFromStream && t.bol && t.isPunct("#")) { directive(t); continue; }
            if (!active()) continue; // tokens in dead conditional regions are dropped
            if (t.kind == Tok.Kind.NL) continue;
            if (t.isPunct("(")) { depth++; cur.add(t); sawAny = true; }
            else if (t.isPunct(")")) {
                depth--;
                if (depth == 0) { if (sawAny || !cur.isEmpty()) args.add(cur); lastCloseParen = t; break; }
                cur.add(t);
            } else if (t.isPunct(",") && depth == 1) { args.add(cur); cur = new ArrayList<>(); sawAny = true; }
            else { cur.add(t); sawAny = true; }
        }
        return args;
    }

    /** We saw a non-directive token while skipping: consume through the next NL. */
    private void skipToNextDirective(Tok first) {
        // tokens arrive via nextRaw in run(); simply consume until NL
        if (first.kind == Tok.Kind.NL) return;
        Tok t = first;
        while (t.kind != Tok.Kind.NL && t.kind != Tok.Kind.EOF) {
            t = nextRaw();
            if (t == null) return;
        }
    }

    // ---------------------------------------------------------------- directives

    private void directive(Tok hash) {
        List<Tok> line = readDirectiveLine();
        if (line.isEmpty()) return; // null directive
        Tok head = line.get(0);
        String name = head.kind == Tok.Kind.IDENT ? head.text
                : head.kind == Tok.Kind.PPNUM ? "#line-marker" : "";
        switch (name) {
            case "if": case "ifdef": case "ifndef": case "elif": case "else": case "endif":
                condDirective(name, line);
                return;
            default:
                if (!active()) return;
        }
        switch (name) {
            case "define": doDefine(line); break;
            case "undef": if (line.size() >= 2) macros.remove(line.get(1).text); break;
            case "include": case "include_next": doInclude(line); break;
            case "error": fail("#error " + joinText(line, 1)); break;
            case "warning": break;
            case "pragma": doPragma(line); break;
            case "line": case "#line-marker": doLine(line); break;
            case "ident": case "sccs": case "assert": case "unassert": break; // ignored by tcc
            case "": break;
            default:
                // tcc errors on unknown directives; be strict to surface gaps
                fail("unknown directive #" + name);
        }
    }

    private List<Tok> readDirectiveLine() {
        List<Tok> line = new ArrayList<>();
        Tok t;
        while ((t = nextRaw()) != null && t.kind != Tok.Kind.NL) line.add(t);
        return line;
    }

    private void condDirective(String name, List<Tok> line) {
        boolean parentActive = active();
        switch (name) {
            case "ifdef": case "ifndef": {
                boolean def = line.size() >= 2 && macros.containsKey(line.get(1).text);
                boolean v = name.equals("ifdef") == def;
                cond.push(new CondFrame(parentActive && v, v, parentActive));
                break;
            }
            case "if": {
                boolean v = parentActive && evalIf(line.subList(1, line.size()));
                cond.push(new CondFrame(parentActive && v, v, parentActive));
                break;
            }
            case "elif": {
                CondFrame f = cond.peek();
                if (f == null) { fail("#elif without #if"); return; }
                if (f.seenElse) { fail("#elif after #else"); return; }
                boolean v = f.parentActive && !f.taken && evalIf(line.subList(1, line.size()));
                f.active = f.parentActive && !f.taken && v;
                f.taken |= v;
                break;
            }
            case "else": {
                CondFrame f = cond.peek();
                if (f == null) { fail("#else without #if"); return; }
                f.seenElse = true;
                f.active = f.parentActive && !f.taken;
                f.taken = true;
                break;
            }
            case "endif": {
                if (cond.isEmpty()) { fail("#endif without #if"); return; }
                cond.pop();
                break;
            }
        }
    }

    private boolean evalIf(List<Tok> exprToks) {
        // tcc semantics (tccpp.c:1440-1480): scan left-to-right, intercept `defined`
        // (including when produced by macro substitution) without expanding its operand;
        // expand everything else inline.
        inPpExpr++;
        List<Tok> expanded;
        try {
            expanded = expandAll(new ArrayList<>(exprToks));
        } finally {
            inPpExpr--;
        }
        return Eval.eval(expanded);
    }

    private void doDefine(List<Tok> line) {
        if (line.size() < 2) { fail("bad #define"); return; }
        Tok nameTok = line.get(1);
        String name = nameTok.text; // position used for redefinition warnings
        int i = 2;
        List<String> params = new ArrayList<>();
        boolean variadic = false, functionLike = false;
        if (i < line.size() && line.get(i).isPunct("(") && !line.get(i).ws) {
            functionLike = true;
            i++;
            while (i < line.size() && !line.get(i).isPunct(")")) {
                Tok p = line.get(i);
                if (p.isPunct("...")) {
                    variadic = true;
                    params.add("__VA_ARGS__");
                } else if (p.kind == Tok.Kind.IDENT) {
                    if (i + 1 < line.size() && line.get(i + 1).isPunct("...")) {
                        variadic = true;
                        params.add(p.text); // GNU named variadic
                        i++;
                    } else {
                        params.add(p.text);
                    }
                }
                i++;
                if (i < line.size() && line.get(i).isPunct(",")) i++;
            }
            i++; // skip ')'
        }
        List<Tok> body = new ArrayList<>(line.subList(Math.min(i, line.size()), line.size()));
        if (!body.isEmpty()) {
            // tcc strips whitespace before the first body token (pp/15 vs pp/18)
            Tok f = body.get(0);
            if (f.ws) body.set(0, new Tok(f.kind, f.text, f.line, f.file, false, f.bol));
        }
        Macro prev = macros.get(name);
        if (prev != null) {
            boolean same = prev.functionLike == functionLike && prev.params.equals(params)
                    && prev.body.size() == body.size()
                    && java.util.stream.IntStream.range(0, body.size())
                        .allMatch(k -> prev.body.get(k).text.equals(body.get(k).text)
                                 && prev.body.get(k).kind == body.get(k).kind);
            if (!same)
                System.err.println(nameTok.file + ":" + nameTok.line + ": warning: " + name + " redefined");
        }
        macros.put(name, new Macro(params, variadic, functionLike, body));
    }

    private void doInclude(List<Tok> line) {
        List<Tok> rest = new ArrayList<>(line.subList(1, line.size()));
        String header = null;
        boolean quoted = false;
        if (!rest.isEmpty() && rest.get(0).kind == Tok.Kind.STRING) {
            String s = rest.get(0).text;
            header = unquote(s);
            quoted = true;
        } else if (!rest.isEmpty() && rest.get(0).isPunct("<")) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            while (i < rest.size() && !rest.get(i).isPunct(">")) { sb.append(rest.get(i).text); i++; }
            header = sb.toString();
        } else {
            List<Tok> expanded = expandAll(rest);
            if (!expanded.isEmpty() && expanded.get(0).kind == Tok.Kind.STRING) {
                header = unquote(expanded.get(0).text);
                quoted = true;
            } else if (!expanded.isEmpty() && expanded.get(0).isPunct("<")) {
                StringBuilder sb = new StringBuilder();
                int i = 1;
                while (i < expanded.size() && !expanded.get(i).isPunct(">")) { sb.append(expanded.get(i).text); i++; }
                header = sb.toString();
            }
        }
        if (header == null) { fail("bad #include line"); return; }
        String resolved = resolveInclude(header, quoted);
        if (resolved == null) { fail("include file '" + header + "' not found"); return; }
        String canon;
        try { canon = Path.of(resolved).toRealPath().toString(); }
        catch (IOException e) { canon = Path.of(resolved).toAbsolutePath().normalize().toString(); }
        if (pragmaOnce.contains(canon)) return; // #pragma once: skip re-inclusion entirely
        String name = Path.of(resolved).getFileName().toString();
        String dir = resolved.substring(0, resolved.length() - name.length());
        if (dir.endsWith("/") || dir.endsWith("\\")) dir = dir.substring(0, dir.length() - 1);
        pushFile(resolved, dir);
    }

    private String resolveInclude(String header, boolean quoted) {
        if (quoted) {
            FileState cur = files.peek();
            if (cur != null) {
                String p = cur.dir.isEmpty() ? header : cur.dir + "/" + header;
                if (Files.isRegularFile(Path.of(p))) return p;
            }
            for (String d : includeDirs) {
                String p = d + "/" + header;
                if (Files.isRegularFile(Path.of(p))) return p;
            }
        } else {
            for (String d : includeDirs) {
                String p = d + "/" + header;
                if (Files.isRegularFile(Path.of(p))) return p;
            }
        }
        for (String d : sysDirs) {
            String p = d + "/" + header;
            if (Files.isRegularFile(Path.of(p))) return p;
        }
        return null;
    }

    private static String unquote(String s) {
        int q = s.indexOf('"');
        int e = s.lastIndexOf('"');
        return e > q ? s.substring(q + 1, e) : s;
    }

    private void doPragma(List<Tok> line) {
        if (line.size() >= 2 && line.get(1).isIdent("once")) {
            FileState f = files.peek();
            if (f != null) {
                try { pragmaOnce.add(Path.of(f.lexer.fileName()).toRealPath().toString()); }
                catch (IOException e) { pragmaOnce.add(f.lexer.fileName()); }
            }
        }
        // all other pragmas ignored (tcc ignores unknown pragmas too)
    }

    private void doLine(List<Tok> line) {
        // #line N ["file"]  or  linemarker: # N "file" [flags]
        int idx = 1;
        if (line.size() <= idx) return;
        Tok n = line.get(idx);
        if (n.kind != Tok.Kind.PPNUM) return;
        int num;
        try { num = Integer.parseInt(n.text); } catch (NumberFormatException e) { return; }
        String fname = null;
        if (line.size() > idx + 1 && line.get(idx + 1).kind == Tok.Kind.STRING)
            fname = unquote(line.get(idx + 1).text);
        FileState f = files.peek();
        if (f != null) f.lexer.remapLine(num - 1, fname); // NL right after bumps to num
    }

    private static String joinText(List<Tok> toks, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toks.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(toks.get(i).text);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- expansion

    /** Expand one token from the main stream; pulls further raw tokens for macro args. */

    private static boolean hidden(Tok t, String name) {
        return t.hs != null && t.hs.contains(name);
    }

    /** Copy body tokens adding `name` to hidesets; the base hideset is `base` when given
     *  (function-like substitution: intersect rule), else the call-site token's own hideset
     *  (object-like). When callSite != null retarget file/line to the invocation site
     *  (tcc evaluates __FILE__/__LINE__ at the expansion point). */
    private static List<Tok> applyHideset(List<Tok> body, String name, Tok callSite, Set<String> base) {
        if (base == null) base = callSite != null && callSite.hs != null ? callSite.hs : Set.of();
        List<Tok> sub = new ArrayList<>(body.size());
        for (Tok b : body) {
            Tok c = new Tok(b.kind, b.text,
                    callSite != null ? callSite.line : b.line,
                    callSite != null ? callSite.file : b.file,
                    b.ws, b.bol);
            Set<String> s = new HashSet<>(base);
            if (b.hs != null) s.addAll(b.hs);
            s.add(name);
            c.hs = Set.copyOf(s);
            if (c.kind == Tok.Kind.PUNCT && c.text.equals("##")) c.pasteOp = true;
            sub.add(c);
        }
        return sub;
    }

    /** Expand all tokens in a list (used for directive lines and macro arg prescan). */
    List<Tok> expandAll(List<Tok> toks) {
        List<Tok> result = new ArrayList<>();
        expandListInto(new ArrayList<>(toks), result);
        return result;
    }

    private int inPpExpr; // >0 while expanding a #if line (defined interception active)

    /** Core expansion loop over a worklist; appends expanded tokens to result. */
    private void expandListInto(List<Tok> work, List<Tok> result) {
        int i = 0;
        while (i < work.size()) {
            Tok t = work.get(i);
            if (inPpExpr > 0 && t.isIdent("defined")) {
                // intercept defined X / defined(X) without expanding the operand
                int j = i + 1;
                boolean paren = j < work.size() && work.get(j).isPunct("(");
                if (paren) j++;
                String mname = j < work.size() ? work.get(j).text : "";
                if (paren && j + 1 < work.size() && work.get(j + 1).isPunct(")")) j++;
                Tok num = new Tok(Tok.Kind.PPNUM, macros.containsKey(mname) ? "1" : "0", t.line, t.file, t.ws, t.bol);
                for (int k = j; k >= i; k--) work.remove(k);
                work.add(i, num);
                continue; // rescan (the PPNUM falls through to result)
            }
            if (t.kind != Tok.Kind.IDENT) { result.add(t); i++; continue; }
            Macro m = macros.get(t.text);
            if (m == null) {
                // builtins apply only when not shadowed by a user macro (tcc: #define __LINE__ wins)
                boolean done = true;
                switch (t.text) {
                    case "__FILE__":
                        result.add(t.withKindText(Tok.Kind.STRING, "\"" + t.file.replace("\\", "\\\\").replace("\"", "\\\"") + "\""));
                        break;
                    case "__LINE__":
                        result.add(t.withKindText(Tok.Kind.PPNUM, Integer.toString(t.line)));
                        break;
                    case "__BASE_FILE__":
                        result.add(t.withKindText(Tok.Kind.STRING, "\"" + baseFile.replace("\\", "\\\\").replace("\"", "\\\"") + "\""));
                        break;
                    case "__COUNTER__":
                        result.add(t.withKindText(Tok.Kind.PPNUM, Long.toString(ppCounter++)));
                        break;
                    case "__DATE__":
                        result.add(t.withKindText(Tok.Kind.STRING, "\"Jan  1 1970\""));
                        break;
                    case "__TIME__":
                        result.add(t.withKindText(Tok.Kind.STRING, "\"00:00:00\""));
                        break;
                    case "_Pragma":
                        // consume _Pragma("...") as no-op (we ignore pragmas)
                        if (i + 3 < work.size() && work.get(i + 1).isPunct("(") && work.get(i + 3).isPunct(")")) {
                            i += 3; // past ')'; loop's i++ moves beyond
                            break;
                        }
                        result.add(t);
                        break;
                    default:
                        done = false;
                        break;
                }
                if (done) { i++; continue; }
                result.add(t); i++; continue;
            }
            if (!hidden(t, t.text)) {
                if (!m.functionLike) {
                    List<Tok> sub = adoptInvokeWs(pasteTokens(applyHideset(m.body, t.text, t, null), null, false), t);
                    work.remove(i);
                    work.addAll(i, sub);
                    continue; // rescan
                }
                int j = i + 1;
                if (j < work.size() && work.get(j).isPunct("(") && !hidden(work.get(j), t.text)) {
                    List<List<Tok>> args = new ArrayList<>();
                    int end = parseArgsInList(work, j, args);
                    java.util.Set<String> afterHs = end + 1 < work.size() && work.get(end + 1).hs != null
                            ? work.get(end + 1).hs : java.util.Set.of();
                    List<Tok> sub = substitute(m, args, t, afterHs, work.get(end));
                    for (int k = end; k >= i; k--) work.remove(k);
                    work.addAll(i, sub);
                    continue; // rescan
                }
            }
            result.add(t);
            i++;
        }
    }

    /** Parse balanced-paren args from a token list starting at '('. Returns index of ')'. */
    private static int parseArgsInList(List<Tok> work, int openIdx, List<List<Tok>> args) {
        int depth = 0;
        List<Tok> cur = new ArrayList<>();
        boolean sawAny = false;
        int i = openIdx;
        for (; i < work.size(); i++) {
            Tok t = work.get(i);
            if (t.isPunct("(")) { depth++; if (depth > 1) cur.add(t); sawAny = true; }
            else if (t.isPunct(")")) {
                depth--;
                if (depth == 0) {
                    if (sawAny || !cur.isEmpty()) args.add(cur);
                    return i;
                }
                cur.add(t);
            }
            else if (t.isPunct(",") && depth == 1) { args.add(cur); cur = new ArrayList<>(); sawAny = true; }
            else { cur.add(t); if (t.kind != Tok.Kind.NL) sawAny = true; }
        }
        throw new PpError("unterminated macro call");
    }



    private static final String PM = "\u0000PM"; // placemarker sentinel for empty macro args

    /** Macro body substitution with # stringize, ## paste, GNU ,##__VA_ARGS__. */
    private List<Tok> substitute(Macro m, List<List<Tok>> args, Tok callSite, java.util.Set<String> afterHs, Tok posTarget) {
        String name = callSite.text;
        // Prosser: function-like substitution hideset = intersect(hs(name), hs(after)) ∪ {name}
        java.util.Set<String> callHs = callSite.hs == null ? java.util.Set.of() : callSite.hs;
        java.util.Set<String> base0 = new HashSet<>(callHs);
        base0.retainAll(afterHs);
        base0.add(name);
        final java.util.Set<String> base = java.util.Set.copyOf(base0);
        // map params to args
        Map<String, List<Tok>> amap = new HashMap<>();
        int fixed = m.params.size() + (m.variadic ? -1 : 0);
        for (int p = 0; p < m.params.size(); p++) {
            String pname = m.params.get(p);
            if (m.variadic && p == m.params.size() - 1) {
                List<Tok> rest = new ArrayList<>();
                for (int a = fixed; a < args.size(); a++) {
                    if (a > fixed) rest.add(punct(",", args.get(a)));
                    rest.addAll(args.get(a));
                }
                amap.put(pname, rest);
            } else {
                amap.put(pname, p < args.size() ? args.get(p) : List.of());
            }
        }
        String variadicName = m.variadic ? m.params.get(m.params.size() - 1) : null;
        // which params are used raw (next to ## or after #)?
        List<Tok> body = m.body;
        Set<String> rawUse = new HashSet<>();
        for (int k = 0; k < body.size(); k++) {
            Tok b = body.get(k);
            if (b.kind == Tok.Kind.IDENT && amap.containsKey(b.text)) {
                boolean raw = (k > 0 && body.get(k - 1).isPunct("##"))
                        || (k + 1 < body.size() && body.get(k + 1).isPunct("##"))
                        || (k > 0 && body.get(k - 1).isPunct("#"));
                if (raw) rawUse.add(b.text);
            }
        }
        Map<String, List<Tok>> expanded = new HashMap<>();
        for (String p : amap.keySet()) {
            if (rawUse.contains(p)) {
                expanded.put(p, amap.get(p));
            } else {
                // tcc nested_list scope rule (tccpp.c:3229-3232): a macro's hideset
                // expires when its replay frame ends, so arg prescan runs with each
                // token's own hideset only; recursion control happens on the RESULT
                expanded.put(p, expandAll(new ArrayList<>(amap.get(p))));
            }
        }
        // substitute; empty args become placemarkers so ## behaves per C11
        List<Tok> res = new ArrayList<>();
        for (int k = 0; k < body.size(); k++) {
            Tok b = body.get(k);
            if (b.isPunct("#") && k + 1 < body.size() && body.get(k + 1).kind == Tok.Kind.IDENT
                    && amap.containsKey(body.get(k + 1).text)) {
                Tok st = new Tok(Tok.Kind.STRING, "\"" + stringize(amap.get(body.get(k + 1).text)) + "\"",
                        posTarget.line, posTarget.file, b.ws, b.bol);
                st.hs = base;
                res.add(st);
                k++;
                continue;
            }
            if (b.kind == Tok.Kind.IDENT && amap.containsKey(b.text)) {
                List<Tok> arg = expanded.get(b.text);
                if (arg.isEmpty()) res.add(new Tok(Tok.Kind.PUNCT, PM, b.line, b.file, b.ws, b.bol));
                else {
                    boolean first = true;
                    for (Tok a : arg) {
                        // Prosser: every token of the substitution result (args included)
                        // gets the substitution hideset; the first token takes the body
                        // parameter's leading-whitespace flag
                        Tok at = new Tok(a.kind, a.text, posTarget.line, posTarget.file,
                                first ? b.ws : a.ws, a.bol);
                        Set<String> s = new HashSet<>(a.hs == null ? Set.<String>of() : a.hs);
                        s.addAll(base);
                        at.hs = Set.copyOf(s);
                        res.add(at);
                        first = false;
                    }
                }
                continue;
            }
            // body token: retarget to the replay position and apply the substitution hideset
            Tok bt = new Tok(b.kind, b.text, posTarget.line, posTarget.file, b.ws, b.bol);
            bt.hs = base;
            bt.pasteOp = b.isPunct("##");
            res.add(bt);
        }
        return adoptInvokeWs(pasteTokens(res, base, m.variadic), callSite);
    }

    /** ## token pasting over a substituted body (placemarker-aware). tcc also pastes
     *  inside object-like macro bodies (e.g. `#define __LINE__ # ## #`). */
    private List<Tok> pasteTokens(List<Tok> res, java.util.Set<String> base, boolean variadicMacro) {
        List<Tok> pasted = new ArrayList<>();
        for (int k = 0; k < res.size(); k++) {
            Tok t = res.get(k);
            if (t.isPunct("##") && t.pasteOp && k + 1 < res.size()) {
                Tok nx = res.get(k + 1);
                Tok left = pasted.isEmpty() ? null : pasted.get(pasted.size() - 1);
                boolean leftPm = left != null && left.isPunct(PM);
                boolean rightPm = nx.isPunct(PM);
                // GNU: , ## __VA_ARGS__ with empty varargs drops the comma
                if (rightPm && nx.text.equals(PM) && left != null && left.isPunct(",")
                        && variadicMacro) {
                    pasted.remove(pasted.size() - 1);
                    k++;
                    continue;
                }
                if (leftPm && rightPm) { /* keep one PM */ k++; continue; }
                if (leftPm) { pasted.remove(pasted.size() - 1); pasted.add(nx); k++; continue; }
                if (rightPm) { k++; continue; } // A ## PM = A
                if (left == null) { k++; continue; } // leading ## (GNU): drop
                pasted.remove(pasted.size() - 1);
                List<Tok> fused = relex(left.text + nx.text, left);
                for (Tok ft : fused) ft.hs = base;
                pasted.addAll(fused);
                k++;
                continue;
            }
            pasted.add(t);
        }
        List<Tok> noPm = new ArrayList<>(pasted.size());
        for (Tok t : pasted) if (!t.isPunct(PM)) noPm.add(t);
        return noPm;
    }

    private static Tok punct(String s, List<Tok> like) {
        Tok ref = like.isEmpty() ? null : like.get(0);
        return new Tok(Tok.Kind.PUNCT, s, ref == null ? 0 : ref.line, ref == null ? "" : ref.file, false, false);
    }

    private static List<Tok> relex(String text, Tok like) {
        Lexer lx = new Lexer(text, like.file);
        List<Tok> toks = new ArrayList<>();
        Tok t;
        while ((t = lx.next()) != null) {
            if (t.kind == Tok.Kind.NL) continue;
            toks.add(new Tok(t.kind, t.text, like.line, like.file, like.ws, like.bol));
        }
        if (toks.isEmpty()) toks.add(new Tok(Tok.Kind.PUNCT, text, like.line, like.file, like.ws, like.bol));
        return toks;
    }

    private static String stringize(List<Tok> arg) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Tok t : arg) {
            if (!first && t.ws) sb.append(' ');
            String txt = t.text;
            if (t.kind == Tok.Kind.STRING || t.kind == Tok.Kind.CHARCONST) {
                txt = txt.replace("\\", "\\\\").replace("\"", "\\\"");
            }
            sb.append(txt);
            first = false;
        }
        return sb.toString();
    }
}
