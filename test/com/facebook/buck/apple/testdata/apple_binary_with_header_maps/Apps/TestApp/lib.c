#include <virtual/path.h>
#include <virtual/exported-path.h>

#ifdef BINARY_HEADER_H
#  error "Binary header included"
#endif

#ifndef LIBRARY_PUBLIC_HEADER_H
#  error "Library public header not included"
#endif

#ifndef LIBRARY_HEADER_H
#  error "Library private header not included"
#endif

int f() {
  return 0;
}
