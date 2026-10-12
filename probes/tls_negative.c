#include <stdio.h>

/* Deliberately unpatched: expose local-exec/TLV bugs even when a DSO links. */
static __thread int value;

int main(void) {
    printf("UNPATCHED_TLS_INITIAL::%x\n", value);
    if (value != 0) return 1;
    value = 42;
    return value == 42 ? 0 : 1;
}
