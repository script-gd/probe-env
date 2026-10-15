/* Test harness only: embed Android-hosted libtcc in the actual editor app process. */
#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include "gdextension/gdextension_interface.h"

int android_compile_project(int argc, char **argv);
typedef GDExtensionBool (*Entry)(GDExtensionInterfaceGetProcAddress,
    GDExtensionClassLibraryPtr, GDExtensionInitialization *);

GDExtensionBool gdextension_entry(GDExtensionInterfaceGetProcAddress get_proc,
        GDExtensionClassLibraryPtr library, GDExtensionInitialization *init) {
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
