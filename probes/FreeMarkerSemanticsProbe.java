import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FreeMarker no-desktop semantics probe.
 *
 * <p>This probe is intentionally self-contained (one Java file, no external test framework). It is compiled once
 * against the official {@code freemarker-gae-2.3.34.jar} and then executed against two jars:
 * <ul>
 *   <li>the official GAE jar on a full JDK (reference behavior), and</li>
 *   <li>the patched jar under {@code --limit-modules} that excludes {@code java.desktop} (candidate behavior).</li>
 * </ul>
 * The candidate must emit the same {@code FMSEM_*} lines as the reference for the "core" cases and must render an
 * exact expected template string.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --mode=core} (default): supported semantics, exact template assertion, and detection of whether
 *       {@code java.beans}/{@code java.desktop} is observable in this JVM.</li>
 *   <li>{@code --mode=wrapper}: demonstrates that {@code new Configuration(...)} is itself the blocker. A custom
 *       {@code ObjectWrapper} (including {@code SimpleObjectWrapper}, which extends {@code DefaultObjectWrapper}
 *       which extends {@code BeansWrapper}) cannot be installed before the constructor builds the default wrapper.</li>
 *   <li>{@code --mode=extended}: additionally exercises cases that are known to diverge from
 *       {@code java.beans.Introspector}: a custom {@code BeanInfo} and an ambiguous {@code getX()}/{@code isX()}
 *       selection. Requires {@code java.desktop}. Loaded reflectively so that core mode on a no-desktop JVM never
 *       links the {@code java.beans} dependent class.</li>
 * </ul>
 *
 * <p>Exit codes: 0 = assertions passed, 1 = template/assertion mismatch, 2 = Configuration could not be built.
 */
public final class FreeMarkerSemanticsProbe {

    // ------------------------------------------------------------------
    // Representative data classes used by the core and extended cases.
    // ------------------------------------------------------------------

    /** {@code getURL()} must decapitalize to the bean property {@code URL} (acronym rule). */
    public static final class UrlBean {
        public String getURL() { return "u"; }
    }

    /** Both {@code getFlag()} and {@code isFlag()} exist; the JavaBeans rule prefers {@code isFlag()}. */
    public static final class BoolConflict {
        public boolean getFlag() { return true; }
        public boolean isFlag() { return false; }
    }

    /** Indexed-only property: {@code getItem(int)}. */
    public static final class IndexedOnly {
        public String getItem(int i) { return "i" + i; }
    }

    /** Inherited interface default method that is a bean property reader. */
    public interface HasGreeting {
        default String getGreeting() { return "hi"; }
    }

    public static final class GreetImpl implements HasGreeting {}

    public record Pt(int x, int y) {}

    /** Overloaded methods with primitive, reference and two-argument signatures. */
    public static final class Over {
        public String m(int i) { return "i" + i; }
        public String m(String s) { return "s" + s; }
        public String m(int a, int b) { return "ii" + (a + b); }
    }

    /** Varargs method invocation. */
    public static final class Var {
        public String v(String... parts) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) { sb.append(p); }
            return "v" + sb;
        }
    }

    /** {@code getClass()} exposure plus a static method. */
    public static final class Classy {
        public String getURL() { return "u"; }
        public static String stat() { return "STAT"; }
    }

    private static final String CORE_TEMPLATE =
            "URL=${u.URL};bool=${bf.flag?c};idx=${ix.item[0]};def=${g.greeting};rec=${p.x},${p.y};"
            + "ov=${o.m(3)},${o.m(\"q\")},${o.m(1,2)};va=${v.v(\"a\",\"b\",\"c\")};"
            + "cls=${c.class.simpleName};st=${c.stat()}";

    private static final String CORE_EXPECTED =
            "URL=u;bool=false;idx=i0;def=hi;rec=1,2;ov=i3,sq,ii3;va=vabc;cls=Classy;st=STAT";

    private FreeMarkerSemanticsProbe() {}

    // ------------------------------------------------------------------
    // Entry point.
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String mode = "core";
        for (String a : args) {
            if (a.startsWith("--mode=")) {
                mode = a.substring("--mode=".length());
            } else {
                System.err.println("Unknown argument: " + a);
                System.exit(64);
            }
        }
        PrintStream out = System.out;
        int rc;
        switch (mode) {
            case "core": rc = runCore(out); break;
            case "wrapper": rc = runWrapper(out); break;
            case "extended": rc = runExtended(out); break;
            case "setting":
                try {
                    newConfiguration().setSetting("object_wrapper", "DefaultObjectWrapper(2.3.34, exposeFields=true)");
                    out.println("FMSEM_SETTING=PASS");
                    rc = 0;
                } catch (LinkageError error) {
                    out.println("FMSEM_SETTING=FAIL " + error);
                    rc = 2;
                }
                break;
            default:
                System.err.println("Unknown mode: " + mode);
                rc = 64;
        }
        System.exit(rc);
    }

    // ------------------------------------------------------------------
    // Modes.
    // ------------------------------------------------------------------

    private static int runCore(PrintStream out) throws Exception {
        printEnvironment(out);
        Configuration cfg;
        try {
            cfg = newConfiguration();
        } catch (Throwable t) {
            out.println("FMSEM_CONFIG=FAIL " + t.getClass().getName() + ": " + oneLine(t.getMessage()));
            out.println("FMSEM_RESULT=CONFIG_FAIL");
            return 2;
        }
        out.println("FMSEM_CONFIG=OK");

        Map<String, Object> root = coreRoot();
        String first = render(CORE_TEMPLATE, root, cfg);
        String second = render(CORE_TEMPLATE, root, cfg);
        out.println("FMSEM_TEMPLATE_OUTPUT_BEGIN");
        out.println(first);
        out.println("FMSEM_TEMPLATE_OUTPUT_END");
        out.println("FMSEM_TEMPLATE_MATCH=" + CORE_EXPECTED.equals(first));
        out.println("FMSEM_DETERMINISM=" + first.equals(second));

        // Individual observable cases (stable, comparable lines).
        out.println("FMSEM_CASE decapURL=" + render("${u.URL}", root, cfg));
        out.println("FMSEM_CASE booleanConflict=" + render("${bf.flag?c}", root, cfg));
        out.println("FMSEM_CASE booleanConflictGet=" + render("${bf.getFlag()?c}", root, cfg));
        out.println("FMSEM_CASE booleanConflictIs=" + render("${bf.isFlag()?c}", root, cfg));
        out.println("FMSEM_CASE indexed=" + render("${ix.item[1]}", root, cfg));
        out.println("FMSEM_CASE inheritedDefault=" + render("${g.greeting}", root, cfg));
        out.println("FMSEM_CASE recordProp=" + render("${p.x}/${p.y}", root, cfg));
        out.println("FMSEM_CASE recordCall=" + render("${p.x()}/${p.y()}", root, cfg));
        out.println("FMSEM_CASE overloads=" + render("${o.m(3)}|${o.m(\"q\")}|${o.m(1,2)}", root, cfg));
        out.println("FMSEM_CASE varargs=" + render("${v.v(\"a\",\"b\",\"c\")}", root, cfg));
        out.println("FMSEM_CASE classGetter=" + render("${c.class.simpleName}", root, cfg));
        out.println("FMSEM_CASE staticMethod=" + render("${c.stat()}", root, cfg));

        boolean ok = CORE_EXPECTED.equals(first) && first.equals(second);
        out.println("FMSEM_RESULT=" + (ok ? "PASS" : "FAIL"));
        return ok ? 0 : 1;
    }

    private static int runWrapper(PrintStream out) {
        printEnvironment(out);
        Configuration cfg;
        try {
            cfg = newConfiguration();
        } catch (Throwable t) {
            out.println("FMSEM_CONFIG=FAIL " + t.getClass().getName() + ": " + oneLine(t.getMessage()));
            out.println("FMSEM_RESULT=CONFIG_FAIL");
            return 2;
        }
        out.println("FMSEM_CONFIG=OK");
        // SimpleObjectWrapper extends DefaultObjectWrapper extends BeansWrapper: if BeansWrapper is usable, this is too.
        try {
            Object sw = new freemarker.template.SimpleObjectWrapper(Configuration.VERSION_2_3_34);
            cfg.setObjectWrapper((freemarker.template.ObjectWrapper) sw);
            out.println("FMSEM_SIMPLE_WRAPPER=OK");
        } catch (Throwable t) {
            out.println("FMSEM_SIMPLE_WRAPPER=FAIL " + t.getClass().getName() + ": " + oneLine(t.getMessage()));
        }
        out.println("FMSEM_NOTE=Configurable(Version) builds the default Beans-based wrapper during construction, "
                + "so a custom ObjectWrapper cannot be installed in time to avoid the java.beans dependency.");
        out.println("FMSEM_RESULT=PASS");
        return 0;
    }

    private static int runExtended(PrintStream out) throws Exception {
        int rc = runCore(out);
        if (rc != 0) {
            return rc;
        }
        // Loaded reflectively so core mode never links this java.beans-dependent class.
        try {
            Class<?> desktop = Class.forName("FreeMarkerSemanticsProbeDesktop");
            desktop.getMethod("run", PrintStream.class).invoke(null, out);
        } catch (Throwable t) {
            out.println("FMSEM_EXTENDED=UNAVAILABLE " + t.getClass().getName() + ": " + oneLine(t.getMessage()));
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Shared helpers (also used by FreeMarkerSemanticsProbeDesktop).
    // ------------------------------------------------------------------

    static Configuration newConfiguration() throws Exception {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_34);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setLocale(Locale.ROOT);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setObjectWrapper(
                new freemarker.template.DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_34).build());
        return cfg;
    }

    static String render(String template, Map<String, Object> root, Configuration cfg) throws Exception {
        try {
            Template t = new Template("probe", new StringReader(template), cfg);
            StringWriter w = new StringWriter();
            t.process(root, w);
            return w.toString();
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName() + ":" + oneLine(t.getMessage());
        }
    }

    static Map<String, Object> coreRoot() {
        Map<String, Object> root = new HashMap<>();
        root.put("u", new UrlBean());
        root.put("bf", new BoolConflict());
        root.put("ix", new IndexedOnly());
        root.put("g", new GreetImpl());
        root.put("p", new Pt(1, 2));
        root.put("o", new Over());
        root.put("v", new Var());
        root.put("c", new Classy());
        return root;
    }

    static String oneLine(String s) {
        if (s == null) {
            return "null";
        }
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        int cr = head.indexOf('\r');
        return cr >= 0 ? head.substring(0, cr) : head;
    }

    private static void printEnvironment(PrintStream out) {
        out.println("FMSEM_JAVA_BEANS=" + (classPresent("java.beans.Introspector") ? "present" : "absent"));
        out.println("FMSEM_JAVA_DESKTOP=" + (classPresent("javax.swing.JComponent") ? "present" : "absent"));
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

/**
 * java.beans-dependent extended cases, isolated in its own class so that the core probe can run on a JVM without
 * {@code java.desktop}. Contains a custom {@code BeanInfo} to demonstrate a documented divergence: the fork replaces
 * {@code java.beans.Introspector}, so custom {@code BeanInfo} contributions are not honored.
 */
final class FreeMarkerSemanticsProbeDesktop {
    private FreeMarkerSemanticsProbeDesktop() {}

    public static final class CustomBean {
        public String getReal() { return "real"; }
    }

    public static final class CustomBeanBeanInfo extends java.beans.SimpleBeanInfo {
        @Override
        public java.beans.PropertyDescriptor[] getPropertyDescriptors() {
            try {
                return new java.beans.PropertyDescriptor[] {
                        new java.beans.PropertyDescriptor("synthetic", CustomBean.class, "getReal", null)
                };
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void run(PrintStream out) throws Exception {
        Configuration cfg = FreeMarkerSemanticsProbe.newConfiguration();
        Map<String, Object> root = new HashMap<>();
        root.put("b", new CustomBean());
        out.println("FMSEM_EXT_CUSTOM_SYNTHETIC="
                + FreeMarkerSemanticsProbe.render("${b.synthetic}", root, cfg));
        out.println("FMSEM_EXT_CUSTOM_REAL="
                + FreeMarkerSemanticsProbe.render("${b.real}", root, cfg));
        out.println("FMSEM_EXT_NOTE=Custom BeanInfo is a documented divergence of the no-desktop fork.");
    }
}
