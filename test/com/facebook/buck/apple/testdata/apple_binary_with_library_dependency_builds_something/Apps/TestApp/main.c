#include <CoreFoundation/CoreFoundation.h>

#ifdef BAD_TEST_LIBRARY
#include <BadTestLibrary/lib.h>
#else
#include <TestLibrary/lib.h>
#endif

int main(int argc, char *argv[]) {
  int a = answer();
  return CFStringGetLength(CFSTR("Hello World!"));
}
