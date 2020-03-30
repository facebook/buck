#include <stdio.h>

void a(void) {
  int a = 1337;
  printf("a: %d", a);
}

void b(void) {
  a();
  printf("b");
}
