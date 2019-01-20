#include "some_lib/some_lib.h"
// TODO(steveo) the above path should work without "../"; that's likely a
// sign of something being broken w.r.t. include paths when building the PCH.

extern int func1(int);
extern int func2(int);
extern int func3(int);
