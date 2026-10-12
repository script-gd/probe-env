import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A standalone reproduction of gdcc's FFM downcalls, not its platform/bundle registry. */
public class Compile {
    private static boolean callbackFailed;

    public static void diagnostic(MemorySegment opaque, MemorySegment message) {
        try {
            System.err.println("libtcc FFM: " + message.reinterpret(Long.MAX_VALUE).getString(0));
        } catch (Throwable failure) {
            callbackFailed = true;
        }
    }

    private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), descriptor);
    }

    private static void check(int status) {
        if (status != 0 || callbackFailed) throw new IllegalStateException("libtcc status=" + status);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 5) throw new IllegalArgumentException("root include1 include2 output C-files...");
        String suffix = System.getProperty("os.name").startsWith("Mac") ? "dylib" : "so";
        System.out.println("FFM_HOST::" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        try (Arena libraryArena = Arena.ofConfined(); Arena round = Arena.ofConfined()) {
            SymbolLookup library = SymbolLookup.libraryLookup(Path.of(args[0], "libtcc." + suffix), libraryArena);
            MethodHandle create = bind(library, "tcc_new", FunctionDescriptor.of(ADDRESS));
            MethodHandle delete = bind(library, "tcc_delete", FunctionDescriptor.ofVoid(ADDRESS));
            MethodHandle errors = bind(library, "tcc_set_error_func", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            MethodHandle libPath = bind(library, "tcc_set_lib_path", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            MethodHandle options = bind(library, "tcc_set_options", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle outputType = bind(library, "tcc_set_output_type", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle include = bind(library, "tcc_add_include_path", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle addFile = bind(library, "tcc_add_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle outputFile = bind(library, "tcc_output_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MemorySegment state = (MemorySegment) create.invokeExact();
            if (state.equals(MemorySegment.NULL)) throw new IllegalStateException("tcc_new failed");
            try {
                MethodHandle callback = MethodHandles.lookup().findStatic(Compile.class, "diagnostic",
                        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
                MemorySegment stub = Linker.nativeLinker().upcallStub(callback,
                        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), round);
                errors.invokeExact(state, MemorySegment.NULL, stub);
                libPath.invokeExact(state, round.allocateFrom(args[0]));
                check((int) options.invokeExact(state, round.allocateFrom("-std=c11")));
                check((int) outputType.invokeExact(state, 4));
                check((int) include.invokeExact(state, round.allocateFrom(args[1])));
                check((int) include.invokeExact(state, round.allocateFrom(args[2])));
                for (int i = 4; i < args.length; i++) {
                    System.out.println("FFM_TU::" + args[i]);
                    check((int) addFile.invokeExact(state, round.allocateFrom(args[i])));
                }
                Files.deleteIfExists(Path.of(args[3]));
                check((int) outputFile.invokeExact(state, round.allocateFrom(args[3])));
                if (!Files.isRegularFile(Path.of(args[3]))) throw new IllegalStateException("output missing");
                System.out.println("FFM_COMPILE_PASS");
            } finally {
                delete.invokeExact(state);
            }
        }
    }
}
