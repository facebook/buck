#include "lib.h"

#ifdef TEST_USE_STATIC_FUNCTION

static int replacement_value() {
  return 1;
}

#endif

#ifdef TEST_IMPLEMENT_LOCATION_FUNCTION

int get_location() {
#ifdef TEST_USE_STATIC_FUNCTION
  return replacement_value();
#else
  return 42;
#endif
}

#endif

#ifdef TEST_IMPLEMENT_CONTACTS_FUNCTION

int get_contacts() {
#ifdef TEST_USE_STATIC_FUNCTION
  return replacement_value();
#else
  return 1337;
#endif
}

#endif
