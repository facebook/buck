#ifdef __cplusplus
extern "C" {
#endif

#include <stdio.h>
#include "_cgo_export.h"

void printSomethingFromGo(int a, int b)
{
    extern GoInt GoFunction(GoInt, GoInt);
    printf("From CPP: %d", (int)GoFunction(a, b));
    fflush(stdout);
}

#ifdef __cplusplus
} // extern "C"
#endif
