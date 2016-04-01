#include <stdlib.h>
#include "foo/used_header.h"

int main(int argc, char ** argv) {
    int a = 10;
    int* s = func_used(10, &a);
    *s = 42;
}
