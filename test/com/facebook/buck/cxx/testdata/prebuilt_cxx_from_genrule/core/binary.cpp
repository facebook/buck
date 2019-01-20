#include <stdio.h>

extern int bar();
int main() {
    printf("%d\n", bar());
}
