#include <unistd.h>

void func() {
  write(1, "b\n", 2);
}
