#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

/* Occupy executable TLS so an incorrectly linked local-exec DSO cannot get lucky. */
static __thread volatile long sentinel = 0x12345678;

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) return 2;
    int rounds = argc == 3 ? atoi(argv[2]) : 1;
    if (rounds < 1) return 2;
    printf("HOST_TLS %lx\n", sentinel);
    for (int i = 0; i < rounds; i++) {
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 3; }
    int (*probe)(void) = (int (*)(void))dlsym(library, "main");
    if (!probe) { fprintf(stderr, "%s\n", dlerror()); return 4; }
    int result = probe();
    if (sentinel != 0x12345678) return 5;
    if (dlclose(library)) return 6;
    if (result) return result;
    }
    printf("DLOPEN_ROUNDS_PASS::%d\n", rounds);
    return 0;
}
