#include <virtual/path.h>
#include <virtual/exported-path.h>

#ifndef BINARY_HEADER_H
#  error "Binary header not included"
#endif

#ifndef LIBRARY_PUBLIC_HEADER_H
#  error "Library public header not included"
#endif

#ifdef LIBRARY_HEADER_H
#  error "Library private header included"
#endif

int main(int argc, char *argv[]) {
  return f();
}
