package gdcc.probe;

import java.io.FileOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class Java25TccProbe {
    private static final String ROOT = "/sdcard/probe-env";
    private static final String STATUS = ROOT + "/java25.status";

    static {
        System.loadLibrary("gdcc_java25_bridge");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String fixture = ROOT + "/fixture";
                    String original = new String(readFile(new File(fixture, "entry.c")),
                            StandardCharsets.UTF_8);
                    String changed = original.replace("u8\"value=%s\"", "u8\"java25=%s\"");
                    if (changed.equals(original)) {
                        throw new IllegalStateException("generated C entry literal was not found");
                    }
                    writeFile(new File(fixture, "entry-java25.c"), changed.getBytes(StandardCharsets.UTF_8));
                    int result = compile(ROOT + "/bundle", fixture,
                            ROOT + "/bin/libprobe-v2.so", fixture + "/entry-java25.c");
                    writeStatus(result == 0 ? "PASS\n" : "FAIL::libtcc returned " + result + "\n");
                } catch (Throwable error) {
                    writeStatus("FAIL::" + error + "\n");
                }
            }
        }, "java25-lib tcc-probe").start();
    }

    private static native int compile(String bundle, String fixture, String output, String entry);

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content);
        }
    }

    private static void writeStatus(String value) {
        try (FileOutputStream output = new FileOutputStream(STATUS)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
