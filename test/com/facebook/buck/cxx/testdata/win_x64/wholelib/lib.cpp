#include <stdio.h>

// When this lib is link_whole'd, then the object side effects will
// take effet and print "hello from lib"
struct Printer {
  Printer(const char* c) {
    printf(c);
  }
};

static Printer p("hello from lib");
