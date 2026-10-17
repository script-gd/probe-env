package jtcc;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI:
 *   jtcc pp  <file.c> -I<dir>... --sys=<dir>...        preprocess, dump tokens
 *   jtcc lex <file.i>                                  lex preprocessed text (strips linemarkers), dump tokens
 *   jtcc cmp <a.tok> <b.tok>                           compare two token dumps
 */
public final class Main {
    public static void main(String[] argv) throws IOException {
        if (argv.length < 2) { usage(); return; }
        String mode = argv[0];
        switch (mode) {
            case "pp": case "pptext": {
                Path file = Path.of(argv[1]);
                List<String> inc = new ArrayList<>();
                List<String> sys = new ArrayList<>();
                for (int i = 2; i < argv.length; i++) {
                    String a = argv[i];
                    if (a.startsWith("-I")) inc.add(a.substring(2));
                    else if (a.startsWith("--sys=")) sys.add(a.substring(6));
                }
                Preprocessor pp = new Preprocessor(inc, sys);
                pp.process(file);
                if (pp.error() != null) {
                    System.err.println("jtcc: error: " + pp.error());
                    System.exit(2);
                }
                if (mode.equals("pptext")) {
                    System.out.print(pp.outputText());
                } else {
                    PrintStream o = System.out;
                    for (Tok t : pp.output()) o.println(t.dump());
                }
                break;
            }
            case "lex": {
                String text = Files.readString(Path.of(argv[1]));
                String noMarkers = stripLineMarkers(text);
                Lexer lx = new Lexer(noMarkers, argv[1]);
                PrintStream o = System.out;
                Tok t;
                while ((t = lx.next()) != null) {
                    if (t.kind == Tok.Kind.NL) continue;
                    o.println(t.dump());
                }
                break;
            }
            case "cmp": {
                List<String> a = Files.readAllLines(Path.of(argv[1]));
                List<String> b = Files.readAllLines(Path.of(argv[2]));
                int n = Math.min(a.size(), b.size());
                for (int i = 0; i < n; i++) {
                    if (!a.get(i).equals(b.get(i))) {
                        System.err.println("first difference at token " + i + ":");
                        for (int k = Math.max(0, i - 4); k < Math.min(n, i + 4); k++)
                            System.err.println("  " + k + "\tA " + a.get(k) + "\tB " + b.get(k));
                        System.exit(1);
                    }
                }
                if (a.size() != b.size()) {
                    System.err.println("length differs: A=" + a.size() + " B=" + b.size());
                    System.exit(1);
                }
                System.out.println("IDENTICAL " + a.size() + " tokens");
                break;
            }
            default: usage();
        }
    }

    private static String stripLineMarkers(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String l = line.stripLeading();
            if (l.startsWith("#") && l.length() > 1) {
                int k = 1;
                while (k < l.length() && l.charAt(k) == ' ') k++;
                if (k < l.length() && Character.isDigit(l.charAt(k))) continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void usage() {
        System.err.println("usage: jtcc pp <file.c> -I<dir>... --sys=<dir>... | lex <file.i> | cmp <a> <b>");
    }
}
