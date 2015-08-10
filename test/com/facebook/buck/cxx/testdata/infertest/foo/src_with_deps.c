#include "foo/dep_one.h"
#include "foo/dep_two.h"

int main() {
    int a = 10;
    int* p = func_dep_one(10, &a);
    *p = 20;
    return *p + func_dep_two();
}
