#include "B.h"
#include <C/C.h>

int get_value_from_b(void) {
  return get_value_from_c() + 10;
}
