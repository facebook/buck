#include <CoreFoundation/CoreFoundation.h>

int main(int argc, char *argv[]) {
  return CFStringGetLength(CFSTR("Hello World!"));
}
