#include <CoreFoundation/CoreFoundation.h>
#include <TestLibrary/lib.h>

int main(int argc, char *argv[]) {
  int a = answer();
  return CFStringGetLength(CFSTR("Hello World!"));
}
