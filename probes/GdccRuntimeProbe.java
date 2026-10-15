import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.TinyCcCompiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Uses the shipped gdcc backend, not a replacement FFM implementation. */
public final class GdccRuntimeProbe {
    public static void main(String[] args) throws Exception {
        var project = Path.of(args[0]).toAbsolutePath();
        var destination = Path.of(args[1]).toAbsolutePath();
        var includes = List.of(project.resolve("include/gdcc"), project.resolve("include/godot"));
        var sources = List.of(
                project.resolve("entry.c"),
                project.resolve("include/godot/godot_binding.c"),
                project.resolve("include/gdcc/minicoro.c"),
                project.resolve("include/gdcc/gdcc_coroutine.c"),
                project.resolve("include/gdcc/gdcc_hrx.c"));
        var target = TargetPlatform.getNativePlatform();
        var compiler = new TinyCcCompiler();
        for (int round = 0; round < 2; round++) {
            var result = compiler.compile(project, includes, sources, "probe",
                    COptimizationLevel.DEBUG, target);
            System.out.println(result.buildLog());
            if (!result.success() || result.artifacts().size() != 1) {
                throw new AssertionError("Full gdcc project compilation failed");
            }
            String expected = round == 0 ? "0 hits, 5 misses" : "5 hits, 0 misses";
            if (!result.buildLog().contains(expected)) {
                throw new AssertionError("Expected isolated cache round: " + expected);
            }
            Files.createDirectories(destination.getParent());
            Files.copy(result.artifacts().getFirst(), destination, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("JRE_GDCC_BACKEND_PASS::round=" + round + "::tu=" + sources.size());
        }
    }
}
