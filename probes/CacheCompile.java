import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Independent reproduction of TinyCcCompiler/TinyCcObjCache, not the gdcc application. */
public class CacheCompile {
    private static final List<String> diagnostics = new ArrayList<>();
    private static boolean callbackFailed;
    private static int upcalls;

    public static void diagnostic(MemorySegment opaque, MemorySegment message) {
        try {
            diagnostics.add(message.reinterpret(Long.MAX_VALUE).getString(0));
            upcalls++;
        } catch (Throwable failure) {
            callbackFailed = true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void checkCallback() {
        require(!callbackFailed, "diagnostic upcall failed");
    }

    private static void check(int status) {
        checkCallback();
        if (status != 0) throw new CompileFailure("libtcc status=" + status);
    }

    private static final class CompileFailure extends RuntimeException {
        CompileFailure(String message) { super(message); }
    }

    private static MessageDigest sha256() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    // Same length-prefixed sections and v2 key document as gdcc's object cache.
    private static void section(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static void section(MessageDigest digest, String text) {
        section(digest, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String treeHash(Path directory) throws Exception {
        MessageDigest digest = sha256();
        try (var walk = Files.walk(directory)) {
            for (Path file : walk.sorted(Comparator.comparing(p -> directory.relativize(p).toString())).toList()) {
                if (Files.isSymbolicLink(file) && Files.isDirectory(file))
                    throw new IOException("directory symlink in include tree: " + file);
                if (!Files.isRegularFile(file)) continue;
                section(digest, directory.relativize(file).toString().replace('\\', '/'));
                section(digest, Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String key(Path tu, List<Path> includes, List<String> trees,
                              String version, String target) throws Exception {
        StringBuilder document = new StringBuilder("format=gdcc-tcc-obj-v2\nfixed-inputs=-std=c11|obj\n");
        document.append("target=").append(target).append('\n');
        for (int i = 0; i < includes.size(); i++) {
            document.append("include[").append(i).append("]=").append(includes.get(i)).append('\n');
            document.append("include-tree-sha256[").append(i).append("]=").append(trees.get(i)).append('\n');
        }
        document.append("tu-path=").append(tu).append('\n');
        MessageDigest digest = sha256();
        section(digest, document.toString());
        section(digest, version);
        section(digest, Files.readAllBytes(tu));
        try (var files = Files.list(tu.getParent())) {
            var headers = files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".h"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            section(digest, "tu-headers-count=" + headers.size() + "\n");
            for (Path header : headers) {
                section(digest, header.getFileName().toString());
                section(digest, Files.readAllBytes(header));
            }
        }
        return HexFormat.of().formatHex(digest.digest(), 0, 16);
    }

    private static final class Native implements AutoCloseable {
        final Arena arena = Arena.ofConfined();
        final Path root;
        final MethodHandle create, delete, errors, libPath, options, outputType, include, addFile, outputFile;
        int objects, links, deleted;

        Native(Path root, String suffix) {
            this.root = root;
            var library = SymbolLookup.libraryLookup(root.resolve("libtcc." + suffix), arena);
            create = bind(library, "tcc_new", FunctionDescriptor.of(ADDRESS));
            delete = bind(library, "tcc_delete", FunctionDescriptor.ofVoid(ADDRESS));
            errors = bind(library, "tcc_set_error_func", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            libPath = bind(library, "tcc_set_lib_path", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            options = bind(library, "tcc_set_options", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            outputType = bind(library, "tcc_set_output_type", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            include = bind(library, "tcc_add_include_path", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            addFile = bind(library, "tcc_add_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            outputFile = bind(library, "tcc_output_file", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }

        private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), descriptor);
        }

        @Override public void close() { arena.close(); }

        final class State implements AutoCloseable {
            final Arena strings = Arena.ofConfined();
            final MemorySegment state;

            State(int type, List<Path> includes) throws Throwable {
                state = (MemorySegment) create.invokeExact();
                if (state.equals(MemorySegment.NULL)) {
                    strings.close();
                    throw new CompileFailure("tcc_new failed");
                }
                if (type == 3) objects++; else links++;
                try {
                    var callback = MethodHandles.lookup().findStatic(CacheCompile.class, "diagnostic",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
                    var stub = Linker.nativeLinker().upcallStub(callback,
                            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), strings);
                    errors.invokeExact(state, MemorySegment.NULL, stub);
                    checkCallback();
                    libPath.invokeExact(state, strings.allocateFrom(root.toString()));
                    checkCallback();
                    check((int) options.invokeExact(state, strings.allocateFrom("-std=c11")));
                    check((int) outputType.invokeExact(state, type));
                    for (Path directory : includes)
                        check((int) include.invokeExact(state, strings.allocateFrom(directory.toString())));
                } catch (Throwable failure) {
                    close();
                    throw failure;
                }
            }

            int add(Path file) throws Throwable {
                int status = (int) addFile.invokeExact(state, strings.allocateFrom(file.toString()));
                checkCallback(); // A broken upcall must never trigger cache self-healing.
                return status;
            }

            void output(Path file) throws Throwable {
                Files.deleteIfExists(file);
                check((int) outputFile.invokeExact(state, strings.allocateFrom(file.toString())));
                require(Files.isRegularFile(file), "output missing: " + file);
            }

            @Override public void close() throws Exception {
                try {
                    delete.invokeExact(state);
                    deleted++;
                } catch (Throwable failure) {
                    throw new Exception(failure);
                } finally {
                    strings.close();
                }
            }
        }
    }

    private record ObjectInput(Path file, String hitKey) {}
    private record Result(boolean success, int hits, int misses, int objects, int links) {}

    private static final class Suite {
        final Native nativeApi;
        final List<Path> files, includes;
        final Path cache, outputs;
        final String version, target, suffix;

        Suite(Native nativeApi, List<Path> files, List<Path> includes, Path work, String target,
              String suffix, String version) throws IOException {
            this.nativeApi = nativeApi;
            this.files = files;
            this.includes = includes;
            this.target = target;
            this.suffix = suffix;
            this.version = version;
            cache = Files.createDirectories(work.resolve("tcc-obj"));
            outputs = Files.createDirectories(work.resolve("outputs"));
        }

        List<String> keys() throws Exception {
            var trees = new ArrayList<String>();
            for (Path directory : includes) trees.add(treeHash(directory));
            var keys = new ArrayList<String>();
            for (Path file : files) keys.add(key(file, includes, trees, version, target));
            return keys;
        }

        Path entry(String key) { return cache.resolve(key + ".o"); }

        Path compile(int index, String key) throws Throwable {
            Path temp = Files.createTempFile(cache, key + ".tmp-", ".o");
            try {
                try (var state = nativeApi.new State(3, includes)) {
                    System.out.println("CACHE_OBJ_TU::" + files.get(index));
                    check(state.add(files.get(index)));
                    state.output(temp);
                }
                byte[] bytes = Files.readAllBytes(temp);
                ByteBuffer elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                int machine = target.endsWith("AARCH64") ? 183 : 62;
                require(bytes.length >= 64 && elf.getInt(0) == 0x464c457f
                        && bytes[4] == 2 && bytes[5] == 1 && elf.getShort(16) == 1
                        && Short.toUnsignedInt(elf.getShort(18)) == machine, "invalid emitted ELF object");
                Files.move(temp, entry(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("CACHE_ELF_OBJECT_PASS::" + key + ":machine=" + machine);
                return entry(key);
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        Result round(String label) throws Throwable {
            nativeApi.objects = nativeApi.links = nativeApi.deleted = 0;
            diagnostics.clear();
            callbackFailed = false;
            int hits = 0, misses = 0;
            boolean success = false;
            var keys = keys();
            var objects = new ArrayList<ObjectInput>();
            try {
                for (int i = 0; i < files.size(); i++) {
                    String key = keys.get(i);
                    if (Files.isRegularFile(entry(key))) {
                        hits++;
                        objects.add(new ObjectInput(entry(key), key));
                    } else {
                        misses++;
                        objects.add(new ObjectInput(compile(i, key), null));
                    }
                }
                for (int attempt = 0; ; attempt++) {
                    int rejected = -1;
                    int diagnosticMark = diagnostics.size();
                    try (var state = nativeApi.new State(4, List.of())) {
                        for (int i = 0; i < objects.size(); i++) {
                            int status = state.add(objects.get(i).file());
                            if (status != 0 && attempt == 0 && objects.get(i).hitKey() != null) {
                                rejected = i;
                                break;
                            }
                            check(status);
                        }
                        if (rejected < 0) {
                            state.output(outputs.resolve(label + "." + suffix));
                            success = true;
                            break;
                        }
                    }
                    require(diagnostics.subList(diagnosticMark, diagnostics.size()).stream()
                            .anyMatch(d -> d.contains("invalid object file")), "corrupt object rejection not diagnosed");
                    diagnostics.subList(diagnosticMark, diagnostics.size()).clear();
                    String key = objects.get(rejected).hitKey();
                    Files.delete(entry(key));
                    hits--;
                    misses++;
                    objects.set(rejected, new ObjectInput(compile(rejected, key), null));
                    System.out.println("CACHE_SELF_HEAL::" + label + ":" + rejected);
                }
            } catch (CompileFailure expectedFailure) {
                System.out.println("CACHE_REJECTED::" + label + ":" + expectedFailure.getMessage());
                for (String diagnostic : diagnostics) System.out.println("CACHE_DIAGNOSTIC::" + diagnostic);
            } finally {
                require(nativeApi.deleted == nativeApi.objects + nativeApi.links, "compiler state leaked");
                try (var entries = Files.list(cache)) {
                    require(entries.noneMatch(p -> p.getFileName().toString().contains(".tmp-")), "unpublished temp remains");
                }
            }
            System.out.printf("CACHE_ROUND::%s:success=%s:hits=%d:misses=%d:obj_states=%d:link_states=%d:deleted=%d:diagnostics=%d%n",
                    label, success, hits, misses, nativeApi.objects, nativeApi.links, nativeApi.deleted, diagnostics.size());
            return new Result(success, hits, misses, nativeApi.objects, nativeApi.links);
        }

        void expect(String label, boolean success, int hits, int misses, int objects, int links) throws Throwable {
            require(round(label).equals(new Result(success, hits, misses, objects, links)), "unexpected round: " + label);
            if (success) require(diagnostics.isEmpty(), "rejected link diagnostics escaped self-heal");
            else require(!diagnostics.isEmpty(), "failure must invoke the diagnostic upcall");
        }

        void corrupt(int index) throws Exception {
            Path object = entry(keys().get(index));
            byte[] bytes = Files.readAllBytes(object);
            bytes[5] = 2; // ELF reader must reject a big-endian cached object.
            Files.write(object, bytes);
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path copy = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(copy);
                else Files.copy(path, copy);
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        require(args.length == 5, "usage: libtcc-root original-fixture empty-work-directory generation-source generation-header");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path fixture = Path.of(args[1]).toAbsolutePath().normalize();
        Path work = Path.of(args[2]).toAbsolutePath().normalize();
        String suffix = System.getProperty("os.name").startsWith("Mac") ? "dylib" : "so";
        String arch = System.getProperty("os.arch");
        require(List.of("aarch64", "arm64", "amd64", "x86_64").contains(arch), "unsupported probe architecture");
        String target = (suffix.equals("dylib") ? "MACOS_" : "LINUX_")
                + (arch.equals("aarch64") || arch.equals("arm64") ? "AARCH64" : "X86_64");
        Path input = Files.createDirectories(work.resolve("fixture"));
        Files.copy(fixture.resolve("entry.c"), input.resolve("entry.c"));
        try (var entries = Files.list(fixture)) {
            for (Path header : entries.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".h")).toList())
                Files.copy(header, input.resolve(header.getFileName()));
        }
        copyTree(fixture.resolve("include"), input.resolve("include"));
        List<Path> includes = List.of(input.resolve("include/gdcc"), input.resolve("include/godot"));
        List<Path> files = List.of(input.resolve("entry.c"), input.resolve("include/godot/godot_binding.c"),
                input.resolve("include/gdcc/minicoro.c"), input.resolve("include/gdcc/gdcc_coroutine.c"),
                input.resolve("include/gdcc/gdcc_hrx.c"));
        // Standalone bundle VERSION: identify the actual libtcc rather than a fabricated shipped bundle.
        String version = "probeBundleFormat=1\nplatform=" + target + "\nlibtccSha256="
                + HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(root.resolve("libtcc." + suffix)))) + "\n";
        try (var nativeApi = new Native(root, suffix)) {
            Suite suite = new Suite(nativeApi, files, includes, work, target, suffix, version);
            suite.expect("cold", true, 0, 5, 5, 1);
            suite.expect("warm", true, 5, 0, 0, 1);
            String original = Files.readString(files.getFirst());
            String oldKey = suite.keys().getFirst();
            Files.writeString(files.getFirst(), original + "\n/* one TU changed */\n");
            suite.expect("changed", true, 4, 1, 1, 1);
            require(Files.isRegularFile(suite.entry(oldKey)), "content-addressed old entry was overwritten");
            Files.writeString(input.resolve("adjacent_probe.h"), "/* adjacent header changes first TU key */\n");
            suite.expect("adjacent", true, 4, 1, 1, 1);
            Files.writeString(includes.getFirst().resolve("include_probe.h"), "/* whole include tree changes every key */\n");
            suite.expect("include", true, 0, 5, 5, 1);

            var hashes = List.of(treeHash(includes.get(0)), treeHash(includes.get(1)));
            String key = suite.keys().getFirst();
            require(!key.equals(key(files.getFirst(), includes, hashes, version + "changed", target)), "VERSION not keyed");
            require(!key.equals(key(files.getFirst(), includes, hashes, version, target + "changed")), "target not keyed");
            require(!key.equals(key(files.getFirst(), includes.reversed(), hashes.reversed(), version, target)), "include order not keyed");
            System.out.println("CACHE_KEY_INPUTS_PASS");

            suite.corrupt(0);
            suite.expect("repaired", true, 4, 1, 1, 2);
            suite.corrupt(0);
            suite.corrupt(1);
            suite.expect("bounded-rejection", false, 4, 1, 1, 2);
            suite.expect("recovered", true, 4, 1, 1, 2);
            String valid = Files.readString(files.getFirst());
            Files.writeString(files.getFirst(), "invalid C for diagnostic upcall probe !!!\n");
            try {
                suite.expect("invalid-c", false, 0, 1, 1, 0);
            } finally {
                Files.writeString(files.getFirst(), valid);
            }
            suite.expect("after-error", true, 5, 0, 0, 1);
            require(upcalls > 0, "diagnostic upcalls never ran");
            System.out.println("CACHE_DIAGNOSTIC_UPCALL_PASS::" + upcalls);

            // Reload images reuse the exact cached runtime/binding objects from the fixture rounds.
            Path generation = input.resolve("reload_generation.c");
            Files.copy(Path.of(args[4]), input.resolve("reload_api.h"));
            String generationSource = Files.readString(Path.of(args[3]));
            Suite reload = new Suite(nativeApi, List.of(generation, files.get(4), files.get(1)),
                    includes, work, target, suffix, version);
            for (int number = 1; number <= 2; number++) {
                Files.writeString(generation, "#define PROBE_GENERATION " + number + "\n" + generationSource);
                reload.expect("reload-gen" + number, true, 2, 1, 1, 1);
            }
            Files.writeString(generation, "#define PROBE_GENERATION 2\n#define PROBE_NO_REBIND 1\n" + generationSource);
            reload.expect("reload-no-rebind", true, 2, 1, 1, 1);
            System.out.println("CACHE_SUITE_PASS");
        }
    }
}
