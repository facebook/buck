#include <unistd.h>

void func() {
  write(1, "a\n", 2);
}
