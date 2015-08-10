#include "foo/chain_dep_one.h"

int main() {
    int *p = func_do_something();
    *p += 1;
    return *p;
}
