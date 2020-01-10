#include <stdio.h>

#include <A/A.h>
#include <B/B.h>

int main(int argc, char *argv[]) {
  int a = get_value_from_a();
  int b = get_value_from_b();
  printf("a: %d, b: %d\n", a, b);

  return 0;
}
