#include <dlfcn.h>
#include <stdio.h>

/* Occupy executable TLS so an incorrectly linked local-exec DSO cannot get lucky. */
static __thread volatile long sentinel = 0x12345678;

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    printf("HOST_TLS %lx\n", sentinel);
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 3; }
    int (*probe)(void) = (int (*)(void))dlsym(library, "main");
    if (!probe) { fprintf(stderr, "%s\n", dlerror()); return 4; }
    int result = probe();
    if (sentinel != 0x12345678) return 5;
    dlclose(library);
    return result;
}
