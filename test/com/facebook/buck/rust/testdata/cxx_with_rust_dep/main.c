#include <stdio.h>

extern void helloer(void);

int main() {
    printf("Calling helloer\n");
    helloer();
    printf("Helloer called\n");
}