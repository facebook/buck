#include <stdlib.h>

int* func_dep_one(int flag, int* input) {
    if (flag > 0) {
        return input;
    }
    return NULL;
}
