#include <stdio.h>
#include <stdlib.h>
#include "libtcc.h"

static void diagnostic(void *opaque, const char *message) {
    (void)opaque;
    fprintf(stderr, "libtcc: %s\n", message);
}

/* Exercise libtcc directly, without gdcc's Java registry or object-cache pipeline. */
int main(int argc, char **argv) {
    if (argc < 6) return 2;
    TCCState *s = tcc_new();
    if (!s) return 3;
    tcc_set_error_func(s, NULL, diagnostic);
    tcc_set_lib_path(s, argv[1]);
    int status = tcc_set_options(s, "-std=c11");
    if (!status) status = tcc_set_output_type(s, TCC_OUTPUT_DLL);
    if (!status) status = tcc_add_include_path(s, argv[2]);
    if (!status) status = tcc_add_include_path(s, argv[3]);
    for (int i = 5; !status && i < argc; i++) {
        fprintf(stderr, "TU %s\n", argv[i]);
        status = tcc_add_file(s, argv[i]);
    }
    if (!status) status = tcc_output_file(s, argv[4]);
    tcc_delete(s);
    return status ? 1 : 0;
}
