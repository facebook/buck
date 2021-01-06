#include "A.h"
#include <B/B.h>

int get_value_from_a(void) {
  return get_value_from_b() + 15;
}
