#include <stdio.h>
#include <stdlib.h>
#include "libtcc.h"
#ifdef __ANDROID__
#include <android/log.h>
#endif

static void diagnostic(void *opaque, const char *message) {
    (void)opaque;
    fprintf(stderr, "libtcc: %s\n", message);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "godot", "libtcc: %s", message);
#endif
}

int android_compile_project(int argc, char **argv) {
    if (argc != 4 && argc != 5) return 2;
    const char *units[] = {"entry.c", "include/godot/godot_binding.c",
        "include/gdcc/minicoro.c", "include/gdcc/gdcc_coroutine.c", "include/gdcc/gdcc_hrx.c"};
    TCCState *s = tcc_new();
    if (!s) return 3;
    tcc_set_error_func(s, NULL, diagnostic);
    tcc_set_lib_path(s, argv[1]);
    char options[8192];
    snprintf(options, sizeof(options),
        "-std=c11 -D__ANDROID_API__=28 -Wl,-section-alignment=0x4000,-soname=libprobe.so "
        "-I%s/include/gdcc -I%s/include/godot -isystem %s/include -L%s/lib",
        argv[2], argv[2], argv[1], argv[1]);
    int status = tcc_set_options(s, options);
    if (!status) status = tcc_set_output_type(s, TCC_OUTPUT_DLL);
    for (unsigned i = 0; !status && i < sizeof(units) / sizeof(units[0]); i++) {
        char path[4096];
        if (i == 0 && argc == 5) snprintf(path, sizeof(path), "%s", argv[4]);
        else snprintf(path, sizeof(path), "%s/%s", argv[2], units[i]);
        fprintf(stderr, "TU %s\n", path);
        status = tcc_add_file(s, path);
    }
    if (!status) status = tcc_add_library(s, "m");
    if (!status) status = tcc_output_file(s, argv[3]);
    tcc_delete(s);
    if (!status) puts("ANDROID_LIBTCC_PROJECT_PASS");
    return status ? 1 : 0;
}

#ifndef ANDROID_BOOTSTRAP
int main(int argc, char **argv) {
    return android_compile_project(argc, argv);
}
#endif
