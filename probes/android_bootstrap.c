/* Test harness only: embed Android-hosted libtcc in the actual editor app process. */
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include "gdextension/gdextension_interface.h"

int android_compile_project(int argc, char **argv);
typedef GDExtensionBool (*Entry)(GDExtensionInterfaceGetProcAddress,
    GDExtensionClassLibraryPtr, GDExtensionInitialization *);

static int helper_probe(const char *name, int rounds) {
    char source[256], target[256], buffer[16384];
    snprintf(source, sizeof(source), "/sdcard/probe-env/bin/lib%s.so", name);
    snprintf(target, sizeof(target), "/data/data/org.godotengine.editor.v4/cache/lib%s.so", name);
    FILE *input = fopen(source, "rb");
    FILE *output = fopen(target, "wb");
    if (!input || !output) return 1;
    size_t count;
    int failed = 0;
    while ((count = fread(buffer, 1, sizeof(buffer), input))) {
        if (fwrite(buffer, 1, count, output) != count) { failed = 1; break; }
    }
    failed |= ferror(input);
    fclose(input);
    failed |= fclose(output) != 0;
    if (failed) return 1;
    for (int i = 0; i < rounds; i++) {
        void *image = dlopen(target, RTLD_NOW | RTLD_LOCAL);
        if (!image) {
            __android_log_print(ANDROID_LOG_ERROR, "godot", "HELPER_DLOPEN_FAIL::%s", dlerror());
            return 1;
        }
        int (*run)(void) = (int (*)(void))dlsym(image, "main");
        if (!run || run()) return 1;
        if (dlclose(image)) return 1;
    }
    __android_log_print(ANDROID_LOG_INFO, "godot", "ANDROID_HELPER_PASS::%s::%d", name, rounds);
    return 0;
}

GDExtensionBool gdextension_entry(GDExtensionInterfaceGetProcAddress get_proc,
        GDExtensionClassLibraryPtr library, GDExtensionInitialization *init) {
    if (helper_probe("coroutine", 32) || helper_probe("hrx", 1)) return 0;
    char *args[] = {"compile", "/sdcard/probe-env/bundle", "/sdcard/probe-env/fixture",
        "/data/data/org.godotengine.editor.v4/cache/libdevice_generated.so"};
    if (android_compile_project(4, args)) {
        __android_log_print(ANDROID_LOG_ERROR, "godot", "ANDROID_DEVICE_COMPILE_FAIL");
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, "godot", "ANDROID_DEVICE_COMPILE_PASS");
    void *handle = dlopen(args[3], RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR, "godot", "ANDROID_DEVICE_DLOPEN_FAIL::%s", dlerror());
        return 0;
    }
    Entry entry = (Entry)dlsym(handle, "gdextension_entry");
    if (!entry) return 0;
    /* Keep the image loaded: Godot retains its callbacks for the entire process. */
    return entry(get_proc, library, init);
}
